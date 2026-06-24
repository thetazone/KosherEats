// Package notify sends push notifications to users via APNs.
//
// The client uses APNs HTTP/2 + token-based authentication (the modern path)
// so we don't need to manage certificates. Auth is a short-lived ES256 JWT
// signed with the .p8 key downloaded from the Apple Developer portal.
//
// If no APNs credentials are configured (dev/local), Send is a no-op that
// just logs the push. This keeps the local loop simple — you can run the
// app and exercise order transitions without a real APNs account.
package notify

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/koshereats/backend/internal/config"
)

// App identifies which KosherEats app a token belongs to. The bundle id
// topic we send to APNs depends on this.
type App string

const (
	AppConsumer App = "consumer"
	AppSeller   App = "seller"
	AppCourier  App = "courier"
)

type APNs struct {
	cfg       *config.Config
	client    *http.Client
	privKey   *ecdsa.PrivateKey
	endpoint  string
	enabled   bool
	tokenMu   sync.Mutex
	cachedJWT string
	jwtIssued time.Time
}

type Payload struct {
	Title string            `json:"title"`
	Body  string            `json:"body"`
	Data  map[string]string `json:"data,omitempty"`
	Sound string            `json:"-"` // defaults to "default"
}

func New(cfg *config.Config) *APNs {
	a := &APNs{
		cfg:    cfg,
		client: &http.Client{Timeout: 10 * time.Second},
	}

	if cfg.APNsKeyID == "" || cfg.APNsTeamID == "" || cfg.APNsP8Key == "" {
		log.Println("[apns] credentials not configured — running in dev stub mode (pushes will be logged, not sent)")
		return a
	}

	key, err := parseP8(cfg.APNsP8Key)
	if err != nil {
		log.Printf("[apns] failed to parse APNS_P8_KEY: %v — running in dev stub mode", err)
		return a
	}
	a.privKey = key
	a.enabled = true
	if cfg.APNsProduction {
		a.endpoint = "https://api.push.apple.com"
	} else {
		a.endpoint = "https://api.sandbox.push.apple.com"
	}
	return a
}

// Send fires a push to one device. Errors are logged but not returned; the
// caller should never let notification failures block an order transition.
func (a *APNs) Send(ctx context.Context, deviceToken string, app App, payload Payload) {
	if !a.enabled {
		log.Printf("[apns stub] app=%s token=%s…%s title=%q body=%q",
			app, safePrefix(deviceToken), safeSuffix(deviceToken), payload.Title, payload.Body)
		return
	}

	jwtToken, err := a.ensureJWT()
	if err != nil {
		log.Printf("[apns] jwt error: %v", err)
		return
	}

	body, err := buildAPSBody(payload)
	if err != nil {
		log.Printf("[apns] body error: %v", err)
		return
	}

	url := fmt.Sprintf("%s/3/device/%s", a.endpoint, deviceToken)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		log.Printf("[apns] request error: %v", err)
		return
	}
	req.Header.Set("authorization", "bearer "+jwtToken)
	req.Header.Set("apns-topic", a.topicFor(app))
	req.Header.Set("apns-push-type", "alert")
	req.Header.Set("content-type", "application/json")

	resp, err := a.client.Do(req)
	if err != nil {
		log.Printf("[apns] send error: %v", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		// APNs returns a JSON body like {"reason":"BadDeviceToken"} on errors —
		// log it so we can tell apart a sandbox/prod token mismatch
		// (BadDeviceToken), a bad topic (DeviceTokenNotForTopic / BadTopic), an
		// expired token (Unregistered), or an auth-key problem (403
		// InvalidProviderToken / ExpiredProviderToken).
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		log.Printf("[apns] non-2xx status=%d reason=%s topic=%s for token=%s…",
			resp.StatusCode, strings.TrimSpace(string(body)), a.topicFor(app), safePrefix(deviceToken))
	}
}

// SendMulti fans out a single payload to many device tokens concurrently.
// Useful for "broadcast to all online couriers" on a new delivery.
func (a *APNs) SendMulti(ctx context.Context, tokens []string, app App, payload Payload) {
	var wg sync.WaitGroup
	for _, t := range tokens {
		t := t
		wg.Add(1)
		go func() {
			defer wg.Done()
			a.Send(ctx, t, app, payload)
		}()
	}
	wg.Wait()
}

// topicFor returns the APNs topic (bundle id) for a given app.
func (a *APNs) topicFor(app App) string {
	return a.cfg.APNsBundlePfx + "." + string(app)
}

// ensureJWT returns a valid provider JWT, minting a new one if the cached
// one is older than 50 minutes (Apple requires rotation within 60m).
func (a *APNs) ensureJWT() (string, error) {
	a.tokenMu.Lock()
	defer a.tokenMu.Unlock()

	if a.cachedJWT != "" && time.Since(a.jwtIssued) < 50*time.Minute {
		return a.cachedJWT, nil
	}

	token := jwt.NewWithClaims(jwt.SigningMethodES256, jwt.MapClaims{
		"iss": a.cfg.APNsTeamID,
		"iat": time.Now().Unix(),
	})
	token.Header["kid"] = a.cfg.APNsKeyID

	signed, err := token.SignedString(a.privKey)
	if err != nil {
		return "", err
	}
	a.cachedJWT = signed
	a.jwtIssued = time.Now()
	return signed, nil
}

func buildAPSBody(p Payload) ([]byte, error) {
	sound := p.Sound
	if sound == "" {
		sound = "default"
	}
	doc := map[string]any{
		"aps": map[string]any{
			"alert": map[string]string{
				"title": p.Title,
				"body":  p.Body,
			},
			"sound": sound,
		},
	}
	for k, v := range p.Data {
		doc[k] = v
	}
	return json.Marshal(doc)
}

func parseP8(pemData string) (*ecdsa.PrivateKey, error) {
	// Accept either raw PEM or a string with literal \n from env vars.
	pemData = strings.ReplaceAll(pemData, "\\n", "\n")
	block, _ := pem.Decode([]byte(pemData))
	if block == nil {
		return nil, fmt.Errorf("failed to decode PEM block")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	ec, ok := key.(*ecdsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("not an ECDSA private key")
	}
	return ec, nil
}

func safePrefix(s string) string {
	if len(s) < 6 {
		return s
	}
	return s[:6]
}
func safeSuffix(s string) string {
	if len(s) < 6 {
		return ""
	}
	return s[len(s)-4:]
}
