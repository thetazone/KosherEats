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
package storage

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/koshereats/backend/internal/config"
)

type Client struct {
	cfg       *config.Config
	s3        *s3.Client
	presigner *s3.PresignClient
	enabled   bool
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
	c.s3 = s3.NewFromConfig(awsCfg)
	c.presigner = s3.NewPresignClient(c.s3)
	c.enabled = true
	return c
}

// Presign issues a short-lived PUT URL for a given upload kind + content type.
// Keys are namespaced by user and kind so courier documents don't collide
// with restaurant images.
//
//   kind examples: "courier/license", "courier/insurance", "courier/profile"
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

// publicURLFor returns the URL the uploaded object will be available at.
// If S3_PUBLIC_URL is set (e.g. CloudFront) we use that; otherwise we build
// a direct S3 URL.
func (c *Client) publicURLFor(key string) string {
	if c.cfg.S3PublicURL != "" {
		return strings.TrimRight(c.cfg.S3PublicURL, "/") + "/" + key
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
	default:
		return ""
	}
}

func randHex(bytes int) string {
	b := make([]byte, bytes)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
