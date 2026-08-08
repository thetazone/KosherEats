// FCM HTTP v1 client for Android push notifications.
//
// Auth flow:
//  1. Parse service account JSON at startup → RSA private key + client email.
//  2. On first send (and whenever the cached token is within 5m of expiry):
//     mint a short-lived RS256 JWT, POST it to Google's OAuth2 token endpoint,
//     receive an access_token good for ~1 hour.
//  3. Use the access_token as Bearer auth on
//     POST /v1/projects/{project_id}/messages:send
//
// When no service account is configured we run in stub mode and just log the
// push — same pattern as apns.go so local dev works without Firebase set up.
package notify

import (
	"bytes"
	"context"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/koshereats/backend/internal/config"
)

// fcmScope is the only OAuth2 scope needed to call the messaging API.
const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

// serviceAccount is the subset of a Google service account JSON we care about.
type serviceAccount struct {
	ClientEmail string `json:"client_email"`
	PrivateKey  string `json:"private_key"`
	TokenURI    string `json:"token_uri"`
	ProjectID   string `json:"project_id"`
}

type FCM struct {
	cfg       *config.Config
	client    *http.Client
	sa        *serviceAccount
	privKey   *rsa.PrivateKey
	enabled   bool
	projectID string
	tokenMu   sync.Mutex
	cachedTok string
	cachedExp time.Time
}

func NewFCM(cfg *config.Config) *FCM {
	f := &FCM{
		cfg:    cfg,
		client: &http.Client{Timeout: 10 * time.Second},
	}

	if cfg.FCMServiceAccountJSON == "" {
		log.Println("[fcm] FCM_SERVICE_ACCOUNT_JSON not set — running in dev stub mode (android pushes logged, not sent)")
		return f
	}

	var sa serviceAccount
	if err := json.Unmarshal([]byte(cfg.FCMServiceAccountJSON), &sa); err != nil {
		log.Printf("[fcm] failed to parse service account JSON: %v — running in dev stub mode", err)
		return f
	}
	if sa.ClientEmail == "" || sa.PrivateKey == "" {
		log.Println("[fcm] service account JSON missing client_email or private_key — running in dev stub mode")
		return f
	}

	key, err := parsePEMPrivateKey(sa.PrivateKey)
	if err != nil {
		log.Printf("[fcm] failed to parse private_key: %v — running in dev stub mode", err)
		return f
	}

	// Project id comes from the service account file by default; let env
	// override so you can point staging backends at a dev Firebase project.
	projectID := cfg.FCMProjectID
	if projectID == "" {
		projectID = sa.ProjectID
	}
	if projectID == "" {
		log.Println("[fcm] no project_id in service account or FCM_PROJECT_ID env — running in dev stub mode")
		return f
	}

	f.sa = &sa
	f.privKey = key
	f.projectID = projectID
	f.enabled = true
	log.Printf("[fcm] ready — project=%s", projectID)
	return f
}

// Send fires a push to one Android device. Matches the APNs.Send signature
// so notifier.go can route transparently.
func (f *FCM) Send(ctx context.Context, deviceToken string, app App, payload Payload) {
	if !f.enabled {
		log.Printf("[fcm stub] app=%s token=%s…%s title=%q body=%q",
			app, safePrefix(deviceToken), safeSuffix(deviceToken), payload.Title, payload.Body)
		return
	}

	accessToken, err := f.ensureAccessToken(ctx)
	if err != nil {
		log.Printf("[fcm] token error: %v", err)
		return
	}

	body, err := buildFCMBody(deviceToken, payload)
	if err != nil {
		log.Printf("[fcm] body error: %v", err)
		return
	}

	u := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", f.projectID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(body))
	if err != nil {
		log.Printf("[fcm] request error: %v", err)
		return
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json")

	resp, err := f.client.Do(req)
	if err != nil {
		log.Printf("[fcm] send error: %v", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		respBody, _ := io.ReadAll(resp.Body)
		log.Printf("[fcm] non-2xx status=%d body=%s token=%s…",
			resp.StatusCode, truncate(string(respBody), 200), safePrefix(deviceToken))
	}
}

// SendMulti fans a payload out to many tokens concurrently.
func (f *FCM) SendMulti(ctx context.Context, tokens []string, app App, payload Payload) {
	var wg sync.WaitGroup
	for _, t := range tokens {
		t := t
		wg.Add(1)
		go func() {
			defer wg.Done()
			f.Send(ctx, t, app, payload)
		}()
	}
	wg.Wait()
}

// ensureAccessToken returns a valid OAuth2 access token, minting a new one
// when the cached token is absent or within 5 minutes of expiry.
func (f *FCM) ensureAccessToken(ctx context.Context) (string, error) {
	f.tokenMu.Lock()
	defer f.tokenMu.Unlock()

	if f.cachedTok != "" && time.Until(f.cachedExp) > 5*time.Minute {
		return f.cachedTok, nil
	}

	// Build the assertion JWT.
	now := time.Now()
	assertion := jwt.NewWithClaims(jwt.SigningMethodRS256, jwt.MapClaims{
		"iss":   f.sa.ClientEmail,
		"scope": fcmScope,
		"aud":   f.sa.TokenURI,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	signed, err := assertion.SignedString(f.privKey)
	if err != nil {
		return "", fmt.Errorf("sign assertion: %w", err)
	}

	// Exchange the assertion for an access token.
	form := url.Values{}
	form.Set("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
	form.Set("assertion", signed)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.sa.TokenURI, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := f.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("token exchange %d: %s", resp.StatusCode, string(body))
	}

	var tokResp struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tokResp); err != nil {
		return "", err
	}
	if tokResp.AccessToken == "" {
		return "", fmt.Errorf("empty access_token in response")
	}

	f.cachedTok = tokResp.AccessToken
	f.cachedExp = now.Add(time.Duration(tokResp.ExpiresIn) * time.Second)
	return f.cachedTok, nil
}

// buildFCMBody shapes a Payload into the FCM HTTP v1 message format. All
// data values are stringified per the spec.
func buildFCMBody(deviceToken string, p Payload) ([]byte, error) {
	message := map[string]any{
		"token": deviceToken,
		"notification": map[string]string{
			"title": p.Title,
			"body":  p.Body,
		},
	}
	if len(p.Data) > 0 {
		// FCM requires data values to be strings — which they already are.
		message["data"] = p.Data
	}
	// Android-specific options: default priority is normal which can delay
	// delivery. Bump to high for user-facing alerts (order updates, new
	// delivery available, etc).
	message["android"] = map[string]any{
		"priority": "HIGH",
	}
	return json.Marshal(map[string]any{"message": message})
}

func parsePEMPrivateKey(pemData string) (*rsa.PrivateKey, error) {
	// env-var-encoded service accounts often ship with literal \n; normalize.
	pemData = strings.ReplaceAll(pemData, "\\n", "\n")
	block, _ := pem.Decode([]byte(pemData))
	if block == nil {
		return nil, fmt.Errorf("failed to decode PEM block")
	}
	// Google service accounts use PKCS8.
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		// Fall back to PKCS1 just in case.
		rsaKey, err2 := x509.ParsePKCS1PrivateKey(block.Bytes)
		if err2 != nil {
			return nil, fmt.Errorf("PKCS8: %w; PKCS1: %v", err, err2)
		}
		return rsaKey, nil
	}
	rsaKey, ok := key.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("not an RSA private key")
	}
	return rsaKey, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
