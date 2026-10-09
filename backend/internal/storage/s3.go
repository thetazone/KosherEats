// Package storage handles file uploads for the KosherEats platform —
// courier documents (drivers license, insurance, vehicle registration,
// profile photo), restaurant cover images, and menu item photos.
//
// We use S3 presigned PUT URLs so the iOS clients upload directly to S3
// without the file ever passing through our Go server. Saves bandwidth,
// avoids memory pressure, and matches the pattern used by Uber, DoorDash,
// Instacart, etc.
//
// Dev stub mode: when S3_BUCKET is unset, we return a harmless data URL
// the client can "upload" to as a no-op. Onboarding still progresses.
//
// Courier identity documents (license / ID, insurance, registration) go to a
// separate PRIVATE bucket. The upload response's public_url for those kinds is
// a short-lived presigned GET (so the app can preview what it just uploaded),
// the database stores only an opaque "private://<key>" reference, and every
// read path turns that back into a fresh short-lived URL. Nothing about these
// documents is reachable without a signature.
package storage

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/koshereats/backend/internal/config"
)

// PrivateRefPrefix marks a stored value that points at the private bucket.
// It is not a URL anyone can open; DocumentURL signs it on the way out.
const PrivateRefPrefix = "private://"

const (
	// How long the upload response's preview link stays valid.
	privatePreviewTTL = time.Hour
	// How long a link handed out on read (profile, admin review) stays valid.
	privateReadTTL = 15 * time.Minute
)

var (
	// ErrPrivateStorageUnavailable: a private upload kind was requested but
	// the private bucket is not configured. Fail closed rather than fall back
	// to the public bucket.
	ErrPrivateStorageUnavailable = errors.New("private document storage is not configured")
	// ErrInvalidDocumentRef: the submitted value is not one of this user's
	// own private document uploads.
	ErrInvalidDocumentRef = errors.New("invalid document reference")
)

// privateKinds are the upload kinds that must never be publicly readable.
// courier/profile stays public on purpose: customers see the courier's photo.
var privateKinds = map[string]string{
	"courier/license":      "license",
	"courier/insurance":    "insurance",
	"courier/registration": "registration",
}

// IsPrivateKind reports whether uploads of this kind go to the private bucket.
func IsPrivateKind(kind string) bool {
	_, ok := privateKinds[kind]
	return ok
}

var docFileRe = regexp.MustCompile(`^[0-9a-f]{16}(\.(jpg|png|heic|webp))?$`)

type Client struct {
	cfg       *config.Config
	s3        *s3.Client
	presigner *s3.PresignClient
	enabled   bool

	privPresigner *s3.PresignClient
	privEnabled   bool
}

type PresignResult struct {
	UploadURL string `json:"upload_url"`
	PublicURL string `json:"public_url"`
	Key       string `json:"key"`
	ExpiresIn int    `json:"expires_in"` // seconds
}

func New(cfg *config.Config) *Client {
	c := &Client{cfg: cfg}
	if cfg.S3Bucket == "" {
		log.Println("[storage] S3_BUCKET not set — running in dev stub mode")
		return c
	}

	awsCfg, err := awsconfig.LoadDefaultConfig(context.Background(),
		awsconfig.WithRegion(cfg.S3Region))
	if err != nil {
		log.Printf("[storage] failed to load AWS config: %v — running in dev stub mode", err)
		return c
	}
	// When S3_ENDPOINT (or Fly's AWS_ENDPOINT_URL_S3) is set we're talking to
	// an S3-compatible service like Tigris/R2, not AWS itself. Force path-style
	// addressing because not every provider supports virtual-host buckets, and
	// it sidesteps DNS/SSL issues with bucket names that contain dots.
	c.s3 = s3.NewFromConfig(awsCfg, func(o *s3.Options) {
		if cfg.S3Endpoint != "" {
			o.BaseEndpoint = aws.String(cfg.S3Endpoint)
			o.UsePathStyle = true
		}
	})
	c.presigner = s3.NewPresignClient(c.s3)
	c.enabled = true

	if cfg.PrivateS3Bucket != "" && cfg.PrivateS3AccessKeyID != "" && cfg.PrivateS3SecretAccessKey != "" {
		privCfg, err := awsconfig.LoadDefaultConfig(context.Background(),
			awsconfig.WithRegion(cfg.S3Region),
			awsconfig.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
				cfg.PrivateS3AccessKeyID, cfg.PrivateS3SecretAccessKey, "")))
		if err != nil {
			log.Printf("[storage] private bucket config failed: %v — courier document uploads disabled", err)
			return c
		}
		privS3 := s3.NewFromConfig(privCfg, func(o *s3.Options) {
			if cfg.S3Endpoint != "" {
				o.BaseEndpoint = aws.String(cfg.S3Endpoint)
				o.UsePathStyle = true
			}
		})
		c.privPresigner = s3.NewPresignClient(privS3)
		c.privEnabled = true
	} else {
		log.Println("[storage] PRIVATE_BUCKET_NAME / PRIVATE_AWS_* not set — courier document uploads will be refused")
	}
	return c
}

// Presign issues a short-lived PUT URL for a given upload kind + content type.
// Keys are namespaced by user and kind so courier documents don't collide
// with restaurant images.
//
//	kind examples: "courier/license", "courier/insurance", "courier/profile"
func (c *Client) Presign(ctx context.Context, userID, kind, contentType string) (*PresignResult, error) {
	key := buildKey(userID, kind, contentType)

	if !c.enabled {
		// Dev stub: return a harmless data URL. The iOS app can detect this
		// prefix and skip the actual HTTP PUT while still saving a URL string.
		return &PresignResult{
			UploadURL: "stub://" + key,
			PublicURL: "stub://" + key,
			Key:       key,
			ExpiresIn: 900,
		}, nil
	}

	if IsPrivateKind(kind) {
		return c.presignPrivate(ctx, key, contentType)
	}

	expires := 15 * time.Minute
	req, err := c.presigner.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket:      aws.String(c.cfg.S3Bucket),
		Key:         aws.String(key),
		ContentType: aws.String(contentType),
	}, s3.WithPresignExpires(expires))
	if err != nil {
		return nil, err
	}

	return &PresignResult{
		UploadURL: req.URL,
		PublicURL: c.publicURLFor(key),
		Key:       key,
		ExpiresIn: int(expires.Seconds()),
	}, nil
}

// presignPrivate issues the PUT for a private document plus a short-lived GET
// the app can show as a preview. The app sends that GET URL back when it
// submits the documents; NormalizeDocumentRef reduces it to the bare key.
func (c *Client) presignPrivate(ctx context.Context, key, contentType string) (*PresignResult, error) {
	if !c.privEnabled {
		return nil, ErrPrivateStorageUnavailable
	}
	expires := 15 * time.Minute
	put, err := c.privPresigner.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket:      aws.String(c.cfg.PrivateS3Bucket),
		Key:         aws.String(key),
		ContentType: aws.String(contentType),
	}, s3.WithPresignExpires(expires))
	if err != nil {
		return nil, err
	}
	get, err := c.privPresigner.PresignGetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(c.cfg.PrivateS3Bucket),
		Key:    aws.String(key),
	}, s3.WithPresignExpires(privatePreviewTTL))
	if err != nil {
		return nil, err
	}
	return &PresignResult{
		UploadURL: put.URL,
		PublicURL: get.URL,
		Key:       key,
		ExpiresIn: int(expires.Seconds()),
	}, nil
}

// NormalizeDocumentRef turns what a courier app submits for a document field
// into the value we store. It accepts the presigned GET URL from this user's
// own private upload (or a "private://" ref echoed back from a profile read)
// and returns "private://<key>". Anything else — another user's upload, a
// public-bucket URL, an arbitrary external link — is ErrInvalidDocumentRef, so
// a document can never be stored somewhere public or pointed at someone
// else's file. Empty stays empty. In dev stub mode values pass through.
func (c *Client) NormalizeDocumentRef(userID, raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", nil
	}
	if !c.enabled {
		return raw, nil
	}
	var key string
	if strings.HasPrefix(raw, PrivateRefPrefix) {
		key = strings.TrimPrefix(raw, PrivateRefPrefix)
	} else {
		k, ok := c.privateKeyFromURL(raw)
		if !ok {
			return "", ErrInvalidDocumentRef
		}
		key = k
	}
	if !validDocumentKey(userID, key) {
		return "", ErrInvalidDocumentRef
	}
	return PrivateRefPrefix + key, nil
}

// privateKeyFromURL extracts the object key from a URL on the private bucket,
// path-style (endpoint/bucket/key, what our client signs) or virtual-host
// (bucket.endpoint/key). Query strings (signatures) are ignored.
func (c *Client) privateKeyFromURL(raw string) (string, bool) {
	if c.cfg.PrivateS3Bucket == "" || c.cfg.S3Endpoint == "" {
		return "", false
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" {
		return "", false
	}
	ep, err := url.Parse(c.cfg.S3Endpoint)
	if err != nil || ep.Host == "" {
		return "", false
	}
	bucket := c.cfg.PrivateS3Bucket
	switch strings.ToLower(u.Host) {
	case strings.ToLower(ep.Host):
		prefix := "/" + bucket + "/"
		if !strings.HasPrefix(u.Path, prefix) {
			return "", false
		}
		return strings.TrimPrefix(u.Path, prefix), true
	case strings.ToLower(bucket + "." + ep.Host):
		return strings.TrimPrefix(u.Path, "/"), true
	}
	return "", false
}

// validDocumentKey: uploads/courier/<license|insurance|registration>/<userID>/<16 hex>[.ext]
// — exactly what buildKey produces for this user's private kinds.
func validDocumentKey(userID, key string) bool {
	parts := strings.Split(key, "/")
	if len(parts) != 5 || parts[0] != "uploads" || parts[1] != "courier" || parts[3] != userID || userID == "" {
		return false
	}
	switch parts[2] {
	case "license", "insurance", "registration":
	default:
		return false
	}
	return docFileRe.MatchString(parts[4])
}

// DocumentURL is the read-side counterpart: a stored "private://<key>" becomes
// a presigned GET valid for privateReadTTL. Any other value (empty, legacy,
// dev stub) is returned unchanged. If the private bucket isn't configured or
// signing fails, the document is withheld ("") rather than exposed.
func (c *Client) DocumentURL(ctx context.Context, stored string) string {
	if !strings.HasPrefix(stored, PrivateRefPrefix) {
		return stored
	}
	if !c.privEnabled {
		return ""
	}
	key := strings.TrimPrefix(stored, PrivateRefPrefix)
	req, err := c.privPresigner.PresignGetObject(ctx, &s3.GetObjectInput{
		Bucket: aws.String(c.cfg.PrivateS3Bucket),
		Key:    aws.String(key),
	}, s3.WithPresignExpires(privateReadTTL))
	if err != nil {
		log.Printf("[storage] sign private document failed: %v", err)
		return ""
	}
	return req.URL
}

// publicURLFor returns the URL the uploaded object will be available at.
//   - If S3_PUBLIC_URL is set (e.g. CloudFront), use that as the prefix.
//   - Else if S3_ENDPOINT is set (Tigris / R2), build a path-style URL against it.
//   - Else default to AWS virtual-host style.
func (c *Client) publicURLFor(key string) string {
	if c.cfg.S3PublicURL != "" {
		return strings.TrimRight(c.cfg.S3PublicURL, "/") + "/" + key
	}
	if c.cfg.S3Endpoint != "" {
		return fmt.Sprintf("%s/%s/%s", strings.TrimRight(c.cfg.S3Endpoint, "/"), c.cfg.S3Bucket, key)
	}
	return fmt.Sprintf("https://%s.s3.%s.amazonaws.com/%s", c.cfg.S3Bucket, c.cfg.S3Region, key)
}

func buildKey(userID, kind, contentType string) string {
	ext := extFromContentType(contentType)
	random := randHex(8)
	safeKind := strings.ReplaceAll(kind, "..", "")
	return fmt.Sprintf("uploads/%s/%s/%s%s", safeKind, userID, random, ext)
}

func extFromContentType(ct string) string {
	switch strings.ToLower(ct) {
	case "image/jpeg", "image/jpg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/heic":
		return ".heic"
	case "image/webp":
		return ".webp"
	default:
		return ""
	}
}

func randHex(bytes int) string {
	b := make([]byte, bytes)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
