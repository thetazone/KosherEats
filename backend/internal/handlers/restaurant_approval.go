package handlers

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"html"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"
)

// Restaurant approval workflow.
//
// When a seller submits their first restaurant via CreateRestaurant, the row
// is inserted with approval_status='pending' and is_active=false so it stays
// off the consumer marketplace. The platform admin (ADMIN_EMAIL) gets an
// email with the restaurant details, certificate image, and magic links to
// approve or reject — each link carries a random approval_token stored on
// the row so we don't need a separate auth check for the admin's email
// client.
//
// Clicking the magic link lands on /admin/restaurants/decision, which renders
// a small HTML page where the admin can optionally add notes before
// confirming. The form posts back to the same endpoint with the action,
// which updates the row and emails the seller the decision + notes.

// generateApprovalToken returns 32 hex chars (16 bytes of entropy). Stored
// on the restaurant row and used as the auth gate for the magic links.
func generateApprovalToken() string {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		// Fall back to a timestamp-based token if the system RNG is broken.
		// Still hard to guess, just not perfectly random.
		return fmt.Sprintf("%032d", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}

// sendNewSubmissionAdminEmail formats and sends the new-application alert
// to the platform admin. Failures here log but never block restaurant
// creation — the seller's flow shouldn't fail because the admin's inbox
// is unreachable.
func (h *Handler) sendNewSubmissionAdminEmail(restID, token string) {
	if h.email == nil {
		return
	}

	type row struct {
		Name                 string
		Description          string
		Phone                string
		Email                string
		Street               string
		City                 string
		State                string
		ZipCode              string
		ImageURL             string
		LogoURL              string
		KosherCertificateURL string
		KosherCertification  string
		CertifyingAgency     string
		OwnerEmail           string
		OwnerFirstName       string
		OwnerLastName        string
	}
	var r row
	ctx := context.Background()
	err := h.db.Pool.QueryRow(ctx,
		`SELECT r.name, r.description, r.phone, r.email, r.street, r.city, r.state, r.zip_code,
		        r.image_url, r.logo_url, r.kosher_certificate_url, r.kosher_certification, r.certifying_agency,
		        u.email, u.first_name, u.last_name
		   FROM restaurants r JOIN users u ON u.id = r.owner_id
		  WHERE r.id = $1`, restID).Scan(
		&r.Name, &r.Description, &r.Phone, &r.Email, &r.Street, &r.City, &r.State, &r.ZipCode,
		&r.ImageURL, &r.LogoURL, &r.KosherCertificateURL, &r.KosherCertification, &r.CertifyingAgency,
		&r.OwnerEmail, &r.OwnerFirstName, &r.OwnerLastName,
	)
	if err != nil {
		slog.Error("approval email: failed to load restaurant",
			slog.String("restaurant_id", restID), slog.String("error", err.Error()))
		return
	}

	base := h.publicBaseURL()
	approveLink := fmt.Sprintf("%s/admin/restaurants/decision?token=%s&action=approve", base, token)
	rejectLink := fmt.Sprintf("%s/admin/restaurants/decision?token=%s&action=reject", base, token)

	subject := fmt.Sprintf("New seller application: %s", r.Name)
	text := fmt.Sprintf(`A new restaurant has applied to join KosherEats.

Restaurant: %s
Description: %s
Owner: %s %s (%s)
Phone: %s
Address: %s, %s, %s %s
Kosher certification: %s — %s

Picture: %s
Logo: %s
Kosher certificate: %s

Approve: %s
Reject: %s
`, r.Name, r.Description, r.OwnerFirstName, r.OwnerLastName, r.OwnerEmail,
		r.Phone, r.Street, r.City, r.State, r.ZipCode,
		r.KosherCertification, r.CertifyingAgency,
		r.ImageURL, r.LogoURL, r.KosherCertificateURL,
		approveLink, rejectLink)

	htmlBody := fmt.Sprintf(`<!doctype html>
<html><body style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:600px;margin:24px auto;color:#111;line-height:1.5">
<h2 style="margin-bottom:4px">New seller application</h2>
<p style="color:#555;margin-top:0">Review the application and approve or reject below.</p>

<h3>%s</h3>
<p>%s</p>

<table style="border-collapse:collapse;width:100%%;margin:16px 0">
  <tr><td style="padding:4px 8px;color:#666">Owner</td><td style="padding:4px 8px"><b>%s %s</b> &lt;%s&gt;</td></tr>
  <tr><td style="padding:4px 8px;color:#666">Phone</td><td style="padding:4px 8px">%s</td></tr>
  <tr><td style="padding:4px 8px;color:#666">Address</td><td style="padding:4px 8px">%s, %s, %s %s</td></tr>
  <tr><td style="padding:4px 8px;color:#666">Certification</td><td style="padding:4px 8px">%s &mdash; %s</td></tr>
</table>

<h4 style="margin-bottom:6px">Restaurant picture</h4>
<img src="%s" alt="Restaurant picture" style="max-width:100%%;border-radius:8px"/>

%s

<h4 style="margin-bottom:6px;margin-top:18px">Kosher certificate</h4>
<img src="%s" alt="Kosher certificate" style="max-width:100%%;border-radius:8px"/>

<div style="margin-top:24px">
  <a href="%s" style="background:#19a559;color:#fff;text-decoration:none;padding:12px 20px;border-radius:8px;font-weight:600;display:inline-block;margin-right:8px">Approve %s</a>
  <a href="%s" style="background:#c0392b;color:#fff;text-decoration:none;padding:12px 20px;border-radius:8px;font-weight:600;display:inline-block">Reject</a>
</div>

<p style="color:#999;font-size:12px;margin-top:24px">The buttons take you to a confirmation page where you can add notes before sending the decision to the seller.</p>
</body></html>`,
		html.EscapeString(r.Name),
		html.EscapeString(r.Description),
		html.EscapeString(r.OwnerFirstName), html.EscapeString(r.OwnerLastName), html.EscapeString(r.OwnerEmail),
		html.EscapeString(r.Phone),
		html.EscapeString(r.Street), html.EscapeString(r.City), html.EscapeString(r.State), html.EscapeString(r.ZipCode),
		html.EscapeString(r.KosherCertification), html.EscapeString(r.CertifyingAgency),
		html.EscapeString(r.ImageURL),
		logoImageBlock(r.LogoURL),
		html.EscapeString(r.KosherCertificateURL),
		approveLink, html.EscapeString(r.Name),
		rejectLink,
	)

	if err := h.email.Send(h.email.AdminEmail(), subject, text, htmlBody); err != nil {
		slog.Error("approval email: send failed",
			slog.String("restaurant_id", restID), slog.String("error", err.Error()))
	}
}

func logoImageBlock(logoURL string) string {
	if strings.TrimSpace(logoURL) == "" {
		return ""
	}
	return fmt.Sprintf(`<h4 style="margin-bottom:6px;margin-top:18px">Logo</h4><img src="%s" alt="Logo" style="max-width:160px;border-radius:50%%"/>`,
		html.EscapeString(logoURL))
}

// RestaurantDecisionPage handles GET (render confirmation form) and POST
// (apply the decision). The auth gate is the random approval_token in the
// URL — only someone who received the admin email knows it.
func (h *Handler) RestaurantDecisionPage(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		token = r.FormValue("token")
	}
	action := strings.ToLower(r.URL.Query().Get("action"))
	if action == "" {
		action = strings.ToLower(r.FormValue("action"))
	}
	if action != "approve" && action != "reject" {
		writeDecisionMessage(w, "Bad request", "Missing or invalid action.")
		return
	}
	if token == "" {
		writeDecisionMessage(w, "Bad request", "Missing token.")
		return
	}

	// Verify the token matches a pending restaurant.
	var restID, restName, status string
	err := h.db.Pool.QueryRow(r.Context(),
		`SELECT id, name, approval_status FROM restaurants WHERE approval_token = $1`, token,
	).Scan(&restID, &restName, &status)
	if err != nil {
		writeDecisionMessage(w, "Link expired or invalid",
			"This approval link is no longer valid. If the seller is still waiting on a decision, ask them to re-submit their application.")
		return
	}
	if status != "pending" {
		writeDecisionMessage(w, "Already decided",
			fmt.Sprintf("This application has already been marked as <b>%s</b>.", html.EscapeString(status)))
		return
	}

	if r.Method == http.MethodGet {
		// Render the confirmation form so the admin can add notes before
		// committing the decision.
		writeDecisionForm(w, token, action, restName)
		return
	}

	// POST: apply the decision.
	notes := strings.TrimSpace(r.FormValue("notes"))
	newStatus := "approved"
	isActive := true
	if action == "reject" {
		newStatus = "rejected"
		isActive = false
	}

	_, err = h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants
		    SET approval_status = $1,
		        approval_notes  = $2,
		        is_active       = $3,
		        reviewed_at     = NOW()
		  WHERE id = $4`,
		newStatus, notes, isActive, restID)
	if err != nil {
		writeDecisionMessage(w, "Database error", "Failed to record the decision. Please try again.")
		return
	}

	// Best-effort seller notification email.
	h.sendDecisionEmail(restID, newStatus, notes)

	writeDecisionMessage(w, "Decision recorded",
		fmt.Sprintf("<b>%s</b> has been <b>%s</b>. The seller has been notified by email.",
			html.EscapeString(restName), html.EscapeString(newStatus)))
}

func (h *Handler) sendDecisionEmail(restID, status, notes string) {
	if h.email == nil {
		return
	}

	var sellerEmail, sellerFirst, restName string
	ctx := context.Background()
	err := h.db.Pool.QueryRow(ctx,
		`SELECT u.email, u.first_name, r.name
		   FROM restaurants r JOIN users u ON u.id = r.owner_id
		  WHERE r.id = $1`, restID,
	).Scan(&sellerEmail, &sellerFirst, &restName)
	if err != nil {
		slog.Error("decision email: failed to load seller",
			slog.String("restaurant_id", restID), slog.String("error", err.Error()))
		return
	}

	var subject, intro string
	if status == "approved" {
		subject = fmt.Sprintf("%s is now live on KosherEats", restName)
		intro = fmt.Sprintf("Great news! Your restaurant <b>%s</b> has been approved and is now visible to customers on the KosherEats marketplace.", html.EscapeString(restName))
	} else {
		subject = fmt.Sprintf("Update on your KosherEats application for %s", restName)
		intro = fmt.Sprintf("Thanks for applying to list <b>%s</b> on KosherEats. We weren't able to approve your application at this time. See the reviewer's notes below for next steps.", html.EscapeString(restName))
	}

	notesBlock := ""
	notesTextBlock := ""
	if notes != "" {
		notesBlock = fmt.Sprintf(`<h4 style="margin-bottom:6px">Reviewer notes</h4><p style="background:#f5f5f7;padding:12px;border-radius:8px;white-space:pre-wrap">%s</p>`, html.EscapeString(notes))
		notesTextBlock = "\n\nReviewer notes:\n" + notes
	}

	textBody := fmt.Sprintf(`Hi %s,

%s

Status: %s%s

— KosherEats
`, sellerFirst, stripHTML(intro), status, notesTextBlock)

	htmlBody := fmt.Sprintf(`<!doctype html>
<html><body style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:600px;margin:24px auto;color:#111;line-height:1.6">
<p>Hi %s,</p>
<p>%s</p>
%s
<p style="color:#999;font-size:12px;margin-top:24px">If you have questions, reply to this email.</p>
</body></html>`,
		html.EscapeString(sellerFirst), intro, notesBlock)

	if err := h.email.Send(sellerEmail, subject, textBody, htmlBody); err != nil {
		slog.Error("decision email: send failed",
			slog.String("restaurant_id", restID), slog.String("error", err.Error()))
	}
}

func writeDecisionForm(w http.ResponseWriter, token, action, restName string) {
	verb := "Approve"
	verbColor := "#19a559"
	if action == "reject" {
		verb = "Reject"
		verbColor = "#c0392b"
	}
	body := fmt.Sprintf(`<!doctype html>
<html><head><title>%s %s</title></head>
<body style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:520px;margin:48px auto;color:#111;line-height:1.5">
<h2>%s <i>%s</i></h2>
<p>Add optional notes to send to the seller, then confirm.</p>
<form method="POST" action="/admin/restaurants/decision">
  <input type="hidden" name="token" value="%s"/>
  <input type="hidden" name="action" value="%s"/>
  <textarea name="notes" rows="6" style="width:100%%;padding:10px;border:1px solid #ccc;border-radius:8px;font:inherit" placeholder="Notes for the seller (optional)…"></textarea>
  <button type="submit" style="margin-top:12px;background:%s;color:#fff;border:none;padding:12px 22px;border-radius:8px;font:inherit;font-weight:600;cursor:pointer">Confirm %s</button>
</form>
</body></html>`,
		verb, html.EscapeString(restName), verb, html.EscapeString(restName),
		html.EscapeString(token), html.EscapeString(action),
		verbColor, verb)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, body)
}

func writeDecisionMessage(w http.ResponseWriter, title, htmlMessage string) {
	body := fmt.Sprintf(`<!doctype html>
<html><head><title>%s</title></head>
<body style="font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:520px;margin:48px auto;color:#111;line-height:1.6;text-align:center">
<h2>%s</h2>
<p>%s</p>
</body></html>`,
		html.EscapeString(title), html.EscapeString(title), htmlMessage)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, body)
}

// publicBaseURL returns the URL the admin email links will point at. Uses
// the PUBLIC_BASE_URL env var if set, otherwise falls back to the prod Fly
// hostname so a missing env doesn't render dead links.
func (h *Handler) publicBaseURL() string {
	if v := strings.TrimRight(strings.TrimSpace(os.Getenv("PUBLIC_BASE_URL")), "/"); v != "" {
		return v
	}
	return "https://koshereats-api.fly.dev"
}

// stripHTML strips simple HTML tags out of a string for the text body
// fallback. Naive but sufficient for the small templates we send here.
func stripHTML(s string) string {
	var b strings.Builder
	inTag := false
	for _, r := range s {
		switch r {
		case '<':
			inTag = true
		case '>':
			inTag = false
		default:
			if !inTag {
				b.WriteRune(r)
			}
		}
	}
	return b.String()
}
