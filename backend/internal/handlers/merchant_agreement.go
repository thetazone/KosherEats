package handlers

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	kemiddleware "github.com/koshereats/backend/internal/middleware"
)

// Click-to-accept merchant agreement.
//
// A restaurant that is not grandfathered (restaurants.agreement_exempt, set
// TRUE for every pre-existing non-preview restaurant by migration 064) and has
// not accepted the CURRENT agreement version is invisible to consumers: it is
// left out of every listing/search/menu/deals query and restaurantOrderable
// rejects it, so AddToCart, CreatePaymentIntent and CreateOrder all refuse it.
// The client apps treat the gate as fail-open on network errors, so this
// server-side predicate is the real control.

// CurrentMerchantAgreementVersion is the agreement version a non-exempt
// restaurant must accept. Bumping it re-gates every non-exempt restaurant until
// it accepts the new version.
const CurrentMerchantAgreementVersion = "2026-10-01"

// merchantTermsPath is appended to cfg.WebURL for the terms page.
const merchantTermsPath = "/restaurant-terms"

// maxLegalNameRunes bounds the typed legal name on acceptance.
const maxLegalNameRunes = 200

// agreementOKSQL / agreementOKSQLr are the agreement half of "consumers may see
// and order from this restaurant", for queries over an unaliased `restaurants`
// table and over one aliased `r`. The version is a compile-time constant, never
// user input, so splicing it into the SQL text is safe.
const (
	agreementOKSQL = `(restaurants.agreement_exempt OR EXISTS (SELECT 1 FROM merchant_agreements ma ` +
		`WHERE ma.restaurant_id = restaurants.id AND ma.agreement_version = '` + CurrentMerchantAgreementVersion + `'))`
	agreementOKSQLr = `(r.agreement_exempt OR EXISTS (SELECT 1 FROM merchant_agreements ma ` +
		`WHERE ma.restaurant_id = r.id AND ma.agreement_version = '` + CurrentMerchantAgreementVersion + `'))`

	// liveRestaurantSQL / liveRestaurantSQLr: the full "standard, live,
	// orderable" predicate — active, approved, standard visibility, and
	// agreement satisfied. Single definition for listing and orderability.
	liveRestaurantSQL  = `(restaurants.is_active AND restaurants.approval_status = 'approved' AND restaurants.listing_visibility = 'standard' AND ` + agreementOKSQL + `)`
	liveRestaurantSQLr = `(r.is_active AND r.approval_status = 'approved' AND r.listing_visibility = 'standard' AND ` + agreementOKSQLr + `)`
)

// AgreementStatusResponse is GET /seller/agreement and the accept response.
type AgreementStatusResponse struct {
	Required        bool       `json:"required"`
	Accepted        bool       `json:"accepted"`
	CurrentVersion  string     `json:"current_version"`
	AcceptedVersion *string    `json:"accepted_version,omitempty"`
	AcceptedAt      *time.Time `json:"accepted_at,omitempty"`
	TermsURL        string     `json:"terms_url"`
}

// AcceptAgreementRequest is POST /seller/agreement/accept.
type AcceptAgreementRequest struct {
	LegalName string `json:"legal_name"`
	Version   string `json:"version"`
}

func (h *Handler) merchantTermsURL() string {
	return strings.TrimRight(h.cfg.WebURL, "/") + merchantTermsPath
}

// sellerOwnsAnyRestaurant distinguishes "no restaurant yet" (a brand-new seller
// mid-onboarding) from "asked for a restaurant they don't own".
func (h *Handler) sellerOwnsAnyRestaurant(ctx context.Context, userID string) (bool, error) {
	var ok bool
	err := h.db.Pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM restaurants WHERE owner_id = $1)`, userID).Scan(&ok)
	return ok, err
}

// loadAgreementStatus builds the status for one restaurant.
func (h *Handler) loadAgreementStatus(ctx context.Context, restaurantID string) (AgreementStatusResponse, error) {
	resp := AgreementStatusResponse{
		CurrentVersion: CurrentMerchantAgreementVersion,
		TermsURL:       h.merchantTermsURL(),
	}
	var exempt bool
	var latestVersion *string
	var latestAt *time.Time
	err := h.db.Pool.QueryRow(ctx, `
		SELECT r.agreement_exempt,
		       EXISTS (SELECT 1 FROM merchant_agreements
		                WHERE restaurant_id = r.id AND agreement_version = $2),
		       la.agreement_version, la.accepted_at
		  FROM restaurants r
		  LEFT JOIN LATERAL (
		        SELECT agreement_version, accepted_at FROM merchant_agreements
		         WHERE restaurant_id = r.id
		         ORDER BY (agreement_version = $2) DESC, accepted_at DESC
		         LIMIT 1) la ON TRUE
		 WHERE r.id = $1`, restaurantID, CurrentMerchantAgreementVersion,
	).Scan(&exempt, &resp.Accepted, &latestVersion, &latestAt)
	if err != nil {
		return resp, err
	}
	resp.AcceptedVersion = latestVersion
	resp.AcceptedAt = latestAt
	resp.Required = !exempt && !resp.Accepted
	return resp, nil
}

// SellerGetAgreement — GET /seller/agreement.
//
// A brand-new seller who has not created a restaurant yet gets 200 with
// required=true (there is nothing to be exempt), so onboarding can show the
// terms before the restaurant form. An explicit ?restaurant_id= the seller does
// not own is still a 404.
func (h *Handler) SellerGetAgreement(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		if r.URL.Query().Get("restaurant_id") == "" {
			if owns, oerr := h.sellerOwnsAnyRestaurant(r.Context(), user["user_id"]); oerr == nil && !owns {
				writeJSON(w, http.StatusOK, AgreementStatusResponse{
					Required:       true,
					CurrentVersion: CurrentMerchantAgreementVersion,
					TermsURL:       h.merchantTermsURL(),
				})
				return
			}
		}
		writeError(w, http.StatusNotFound, "restaurant not found")
		return
	}
	resp, err := h.loadAgreementStatus(r.Context(), restID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load agreement status")
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

// SellerAcceptAgreement — POST /seller/agreement/accept.
//
// Records the click-to-accept (legal name, accepting user, client IP from the
// RealIP / X-Forwarded-For chain, version, timestamp). Idempotent: accepting
// the current version again returns the existing acceptance without a new row.
// A version other than the current one is a 409 (the client rendered stale
// terms). A seller with no restaurant yet gets 404 — an acceptance is recorded
// against a restaurant, so they must create it first (GET still answers 200).
func (h *Handler) SellerAcceptAgreement(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var req AcceptAgreementRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	legalName := strings.TrimSpace(req.LegalName)
	if legalName == "" {
		writeError(w, http.StatusBadRequest, "legal_name is required")
		return
	}
	if utf8.RuneCountInString(legalName) > maxLegalNameRunes {
		writeError(w, http.StatusBadRequest, "legal_name too long (max 200)")
		return
	}
	if req.Version != CurrentMerchantAgreementVersion {
		writeError(w, http.StatusConflict,
			"agreement version mismatch: the current version is "+CurrentMerchantAgreementVersion)
		return
	}
	restID, err := h.resolveSellerRestaurant(r, user["user_id"])
	if err != nil {
		writeError(w, http.StatusNotFound, "restaurant not found — create your restaurant first")
		return
	}

	tx, err := h.db.Pool.Begin(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to record acceptance")
		return
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck

	// Row lock serializes concurrent accepts for the same restaurant so the
	// existence check + insert can't both pass (the prod table has no UNIQUE
	// constraint to lean on).
	var locked string
	if err := tx.QueryRow(r.Context(),
		`SELECT id FROM restaurants WHERE id = $1 FOR UPDATE`, restID).Scan(&locked); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			writeError(w, http.StatusNotFound, "restaurant not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to record acceptance")
		return
	}
	var already bool
	if err := tx.QueryRow(r.Context(),
		`SELECT EXISTS (SELECT 1 FROM merchant_agreements WHERE restaurant_id = $1 AND agreement_version = $2)`,
		restID, CurrentMerchantAgreementVersion).Scan(&already); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to record acceptance")
		return
	}
	if !already {
		if _, err := tx.Exec(r.Context(), `
			INSERT INTO merchant_agreements
			    (id, restaurant_id, user_id, legal_name, ip_address, agreement_version, accepted_at)
			VALUES (gen_random_uuid(), $1, $2, $3, $4, $5, NOW())`,
			restID, user["user_id"], legalName, kemiddleware.ClientIP(r), CurrentMerchantAgreementVersion); err != nil {
			slog.Error("SellerAcceptAgreement insert failed",
				slog.String("restaurant_id", restID), slog.String("error", err.Error()))
			writeError(w, http.StatusInternalServerError, "failed to record acceptance")
			return
		}
	}
	if err := tx.Commit(r.Context()); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to record acceptance")
		return
	}

	resp, err := h.loadAgreementStatus(r.Context(), restID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "acceptance recorded but status reload failed")
		return
	}
	writeJSON(w, http.StatusOK, resp)
}
