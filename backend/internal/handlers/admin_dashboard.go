package handlers

import (
	"crypto/subtle"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

// A no-login, key-gated admin page for reviewing restaurant applications — a
// fallback to the per-restaurant email magic links so approvals don't depend on
// email delivery or a working admin login. Gated by the ADMIN_DASHBOARD_KEY
// shared secret passed as ?key=; if the env var is unset the pages 404 (off).

func adminDashboardKey() string { return os.Getenv("ADMIN_DASHBOARD_KEY") }

func (h *Handler) adminKeyOK(r *http.Request) (string, bool) {
	key := adminDashboardKey()
	if key == "" {
		return "", false // dashboard disabled
	}
	return key, subtle.ConstantTimeCompare([]byte(r.URL.Query().Get("key")), []byte(key)) == 1
}

// AdminRestaurantsPage — GET /admin/restaurants?key=<ADMIN_DASHBOARD_KEY>
func (h *Handler) AdminRestaurantsPage(w http.ResponseWriter, r *http.Request) {
	key, ok := h.adminKeyOK(r)
	if adminDashboardKey() == "" {
		http.NotFound(w, r)
		return
	}
	if !ok {
		w.WriteHeader(http.StatusForbidden)
		writeAdminPage(w, "Forbidden", `<p>Invalid or missing key.</p>`)
		return
	}

	rows, err := h.db.Pool.Query(r.Context(),
		`SELECT id, name, COALESCE(street,''), COALESCE(city,''), COALESCE(state,''),
		        COALESCE(phone,''), COALESCE(array_to_string(cuisine_type, ', '), ''), created_at
		 FROM restaurants WHERE approval_status = 'pending' ORDER BY created_at DESC`)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		writeAdminPage(w, "Error", `<p>Could not load restaurants.</p>`)
		return
	}
	defer rows.Close()

	var body strings.Builder
	count := 0
	for rows.Next() {
		var id, name, street, city, state, phone, cuisine string
		var created time.Time
		if err := rows.Scan(&id, &name, &street, &city, &state, &phone, &cuisine, &created); err != nil {
			continue
		}
		count++
		addr := strings.Trim(strings.TrimSpace(fmt.Sprintf("%s, %s, %s", street, city, state)), ", ")
		action := fmt.Sprintf("/admin/restaurants/%s/decision?key=%s", id, url.QueryEscape(key))
		body.WriteString(fmt.Sprintf(`
		  <div class="card">
		    <h3>%s</h3>
		    <div class="meta">%s%s%s<div>Submitted: %s</div></div>
		    <form method="post" action="%s" style="display:inline">
		      <button name="action" value="approve" class="approve">Approve</button>
		    </form>
		    <form method="post" action="%s" style="display:inline">
		      <button name="action" value="reject" class="reject">Reject</button>
		    </form>
		  </div>`,
			html.EscapeString(name),
			htmlMeta("Address", addr), htmlMeta("Phone", phone), htmlMeta("Cuisine", cuisine),
			created.Format("Jan 2, 2006"), action, action))
	}
	if count == 0 {
		body.WriteString(`<p><em>No pending restaurants — all caught up. 🎉</em></p>`)
	}
	writeAdminPage(w, fmt.Sprintf("Pending restaurants (%d)", count), body.String())
}

// AdminRestaurantDecision — POST /admin/restaurants/{id}/decision?key=...
// Form: action=approve|reject. Mirrors AdminSetRestaurantApproval + emails the seller.
func (h *Handler) AdminRestaurantDecision(w http.ResponseWriter, r *http.Request) {
	key, ok := h.adminKeyOK(r)
	if adminDashboardKey() == "" {
		http.NotFound(w, r)
		return
	}
	if !ok {
		w.WriteHeader(http.StatusForbidden)
		writeAdminPage(w, "Forbidden", `<p>Invalid or missing key.</p>`)
		return
	}
	restID := chi.URLParam(r, "id")
	var status string
	switch strings.ToLower(r.FormValue("action")) {
	case "approve":
		status = "approved"
	case "reject":
		status = "rejected"
	default:
		w.WriteHeader(http.StatusBadRequest)
		writeAdminPage(w, "Bad request", `<p>Unknown action.</p>`)
		return
	}

	var prev string
	if err := h.db.Pool.QueryRow(r.Context(),
		`SELECT approval_status FROM restaurants WHERE id = $1`, restID).Scan(&prev); err != nil {
		w.WriteHeader(http.StatusNotFound)
		writeAdminPage(w, "Not found", `<p>Restaurant not found.</p>`)
		return
	}
	if prev != "pending" {
		w.WriteHeader(http.StatusConflict)
		writeAdminPage(w, "Already decided", fmt.Sprintf("<p>This application was already <b>%s</b>.</p>", html.EscapeString(prev)))
		return
	}
	isActive := status == "approved"
	if _, err := h.db.Pool.Exec(r.Context(),
		`UPDATE restaurants SET approval_status = $1, is_active = $2, reviewed_at = NOW(), updated_at = NOW() WHERE id = $3`,
		status, isActive, restID); err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		writeAdminPage(w, "Error", `<p>Update failed.</p>`)
		return
	}
	if prev != status {
		h.sendDecisionEmail(restID, status, "")
	}
	http.Redirect(w, r, "/admin/restaurants?key="+url.QueryEscape(key), http.StatusSeeOther)
}

func htmlMeta(label, val string) string {
	if strings.TrimSpace(val) == "" {
		return ""
	}
	return fmt.Sprintf(`<div><b>%s:</b> %s</div>`, label, html.EscapeString(val))
}

func writeAdminPage(w http.ResponseWriter, title, bodyHTML string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s — KosherEats admin</title>
<style>
 body{font-family:-apple-system,system-ui,sans-serif;background:#0d0d0d;color:#eee;margin:0 auto;padding:24px;max-width:640px}
 h1{font-size:22px}
 .card{background:#1a1a1a;border:1px solid #2a2a2a;border-radius:12px;padding:16px;margin:12px 0}
 .card h3{margin:0 0 8px}
 .meta{color:#aaa;font-size:14px;margin-bottom:12px;line-height:1.5}
 button{border:0;border-radius:8px;padding:10px 18px;font-size:15px;font-weight:600;margin-right:8px;cursor:pointer}
 .approve{background:#1db954;color:#fff}
 .reject{background:#3a1a1a;color:#ff6b6b}
</style></head><body>
<h1>%s</h1>
%s
</body></html>`, html.EscapeString(title), html.EscapeString(title), bodyHTML)
}
