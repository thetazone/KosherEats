// Package background wraps the Checkr API for courier background checks.
//
// Real flow (prod):
//   1. Courier submits documents → we create a Checkr candidate
//   2. We invite them to complete the check → Checkr emails them a link
//   3. Courier completes identity verification on Checkr's hosted flow
//   4. Checkr runs the report asynchronously (hours to days)
//   5. Checkr POSTs a webhook to /webhooks/checkr when the report is ready
//   6. We flip courier_profiles.onboarding_status = 'approved' or 'rejected'
//
// Dev stub mode: when no CHECKR_API_KEY is configured we skip the real API
// and auto-approve the courier after a short delay. Keeps the onboarding
// flow end-to-end testable locally without a Checkr sandbox account.
package background

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/config"
)

// checkrBaseURL is the Checkr REST API root. Constant in prod; overridable
// in tests by swapping it at package level if we ever add integration tests.
const checkrBaseURL = "https://api.checkr.com/v1"

// checkrHTTPClient is a shared client with a sane timeout so a hung Checkr
// request can never stall the courier onboarding HTTP response — InitiateCheck
// is called inline from UpdateCourierDocuments.
var checkrHTTPClient = &http.Client{Timeout: 10 * time.Second}

type Checkr struct {
	cfg     *config.Config
	db      *pgxpool.Pool
	enabled bool
}

func New(cfg *config.Config, db *pgxpool.Pool) *Checkr {
	c := &Checkr{cfg: cfg, db: db}
	if cfg.CheckrAPIKey != "" {
		c.enabled = true
	} else {
		slog.Info("[checkr] CHECKR_API_KEY not set — running in dev stub mode (auto-approve after 2s)")
	}
	return c
}

// InitiateCheck kicks off a background check for a courier. In prod this
// creates a candidate + invitation via the Checkr REST API. In dev stub
// mode it schedules a goroutine that auto-approves the courier after a
// short delay so the UX flow still works end-to-end.
func (c *Checkr) InitiateCheck(ctx context.Context, courierUserID, email, firstName, lastName, phone string) error {
	if !c.enabled {
		// Dev stub: log the fake candidate, wait, then approve.
		candidateID := "cand_stub_" + courierUserID[:8]
		slog.Info("[checkr stub] created candidate",
			slog.String("candidate_id", candidateID),
			slog.String("email", email))

		// Store the ref so the webhook path could resolve it later in prod.
		_, _ = c.db.Exec(ctx,
			`UPDATE courier_profiles SET background_check_ref = $1, updated_at = NOW()
			 WHERE user_id = $2`, candidateID, courierUserID)

		// Auto-approve in the background (2s delay — fast enough for local
		// testing, slow enough to read as async). Uses context.Background()
		// because we want the goroutine to outlive the HTTP request.
		go func() {
			time.Sleep(2 * time.Second)
			bgCtx := context.Background()
			_, err := c.db.Exec(bgCtx,
				`UPDATE courier_profiles
				   SET onboarding_status = 'approved',
				       background_check_status = 'passed',
				       payout_ready = true,
				       updated_at = NOW()
				 WHERE user_id = $1 AND onboarding_status = 'pending_background'`,
				courierUserID)
			if err != nil {
				slog.Error("[checkr stub] auto-approve failed", slog.String("error", err.Error()))
				return
			}
			slog.Info("[checkr stub] auto-approved courier",
				slog.String("user_id", courierUserID))
		}()
		return nil
	}

	// Production path:
	// 1. POST /v1/candidates — create the subject of the check
	// 2. POST /v1/invitations — ask Checkr to email them the hosted flow
	// 3. Persist the candidate_id as background_check_ref so the eventual
	//    report.completed webhook can look up the right courier
	// Checkr uses HTTP Basic auth with the API key as username and an empty
	// password (https://docs.checkr.com/#section/Authentication).
	candidateID, err := c.createCandidate(ctx, email, firstName, lastName, phone)
	if err != nil {
		slog.Error("[checkr] createCandidate failed",
			slog.String("error", err.Error()),
			slog.String("user_id", courierUserID))
		return err
	}

	if err := c.createInvitation(ctx, candidateID); err != nil {
		// We still persist the candidate id — an admin can resend the
		// invitation manually from the Checkr dashboard.
		slog.Error("[checkr] createInvitation failed",
			slog.String("error", err.Error()),
			slog.String("candidate_id", candidateID))
	}

	_, err = c.db.Exec(ctx,
		`UPDATE courier_profiles SET background_check_ref = $1, updated_at = NOW()
		 WHERE user_id = $2`, candidateID, courierUserID)
	if err != nil {
		return err
	}

	slog.Info("[checkr] candidate created + invited",
		slog.String("candidate_id", candidateID),
		slog.String("user_id", courierUserID))
	return nil
}

// createCandidate POSTs a new candidate to Checkr and returns its id. The
// candidate is the real-world person being checked; an invitation attached
// to it actually runs the report.
func (c *Checkr) createCandidate(ctx context.Context, email, firstName, lastName, phone string) (string, error) {
	// Checkr requires at minimum first_name, last_name, email, and a
	// no_middle_name flag when middle name is absent.
	body := map[string]any{
		"first_name":     firstName,
		"last_name":      lastName,
		"email":          email,
		"phone":          phone,
		"no_middle_name": true,
		// DOB, SSN, and driver license info are collected by Checkr via the
		// hosted invitation flow — we never touch them on our servers.
	}

	var resp struct {
		ID    string `json:"id"`
		Error string `json:"error"`
	}
	if err := c.doJSON(ctx, "POST", "/candidates", body, &resp); err != nil {
		return "", err
	}
	if resp.ID == "" {
		return "", fmt.Errorf("checkr: empty candidate id (err=%q)", resp.Error)
	}
	return resp.ID, nil
}

// createInvitation kicks off a background check against an existing candidate
// using the configured package slug (e.g. "driver_pro"). Checkr emails the
// candidate a link to complete identity verification.
func (c *Checkr) createInvitation(ctx context.Context, candidateID string) error {
	body := map[string]any{
		"candidate_id": candidateID,
		"package":      c.cfg.CheckrPackage,
	}
	var resp struct {
		ID    string `json:"id"`
		Error string `json:"error"`
	}
	if err := c.doJSON(ctx, "POST", "/invitations", body, &resp); err != nil {
		return err
	}
	if resp.ID == "" {
		return fmt.Errorf("checkr: empty invitation id (err=%q)", resp.Error)
	}
	return nil
}

// doJSON is a tiny Checkr API helper: HTTP Basic auth with the API key as
// the username, JSON request + response, timeouts inherited from the shared
// client. Non-2xx responses return an error that includes the raw body to
// make debugging the first integration easier.
func (c *Checkr) doJSON(ctx context.Context, method, path string, reqBody, out any) error {
	var reader io.Reader
	if reqBody != nil {
		buf, err := json.Marshal(reqBody)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(buf)
	}

	req, err := http.NewRequestWithContext(ctx, method, checkrBaseURL+path, reader)
	if err != nil {
		return err
	}
	req.SetBasicAuth(c.cfg.CheckrAPIKey, "")
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	res, err := checkrHTTPClient.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()

	body, _ := io.ReadAll(res.Body)
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return fmt.Errorf("checkr %s %s: %d %s", method, path, res.StatusCode, string(body))
	}
	if out != nil && len(body) > 0 {
		if err := json.Unmarshal(body, out); err != nil {
			return fmt.Errorf("checkr decode: %w (body=%s)", err, string(body))
		}
	}
	return nil
}

// WebhookPayload is the subset of the Checkr report.completed webhook we
// care about. Full schema: https://docs.checkr.com/#section/Webhooks
type WebhookPayload struct {
	Type string `json:"type"` // e.g. "report.completed"
	Data struct {
		Object struct {
			ID          string `json:"id"`
			CandidateID string `json:"candidate_id"`
			Status      string `json:"status"` // "clear" | "consider" | "suspended"
		} `json:"object"`
	} `json:"data"`
}

// HandleWebhook maps a Checkr report.completed payload to an approval or
// rejection on the matching courier profile. "clear" → approved, anything
// else → keeps the courier in review (an admin can still manually approve
// via the admin dashboard).
func (c *Checkr) HandleWebhook(ctx context.Context, payload WebhookPayload) error {
	if payload.Type != "report.completed" {
		return nil
	}
	candidateID := payload.Data.Object.CandidateID
	approved := payload.Data.Object.Status == "clear"

	newStatus := "pending_background" // hold for admin review
	bgStatus := "consider"
	if approved {
		newStatus = "approved"
		bgStatus = "passed"
	}

	_, err := c.db.Exec(ctx,
		`UPDATE courier_profiles
		   SET onboarding_status = $1,
		       background_check_status = $2,
		       payout_ready = $3,
		       updated_at = NOW()
		 WHERE background_check_ref = $4`,
		newStatus, bgStatus, approved, candidateID)
	return err
}
