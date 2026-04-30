package handlers

import (
	"encoding/json"
	"net/http"

	"github.com/koshereats/backend/internal/background"
	"github.com/koshereats/backend/internal/broker"
	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/database"
	"github.com/koshereats/backend/internal/notify"
	"github.com/koshereats/backend/internal/payments"
	"github.com/koshereats/backend/internal/sms"
	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/storage"
	"github.com/koshereats/backend/internal/uberdirect"
)

type Handler struct {
	db       *database.DB
	cfg      *config.Config
	notify   *notify.Notifier
	stripe   *payments.Client
	storage  *storage.Client
	checkr   *background.Checkr
	location *broker.Broker
	sms      *sms.Client
	uber     *uberdirect.Client
	doordash *doordash.Client
}

func New(db *database.DB, cfg *config.Config) *Handler {
	apns := notify.New(cfg)
	fcm := notify.NewFCM(cfg)
	return &Handler{
		db:       db,
		cfg:      cfg,
		notify:   notify.NewNotifier(db.Pool, apns, fcm),
		stripe:   payments.New(cfg),
		storage:  storage.New(cfg),
		checkr:   background.New(cfg, db.Pool),
		location: broker.New(),
		sms:      sms.New(cfg),
		uber: uberdirect.New(uberdirect.Config{
			ClientID:     cfg.UberDirectClientID,
			ClientSecret: cfg.UberDirectClientSecret,
			CustomerID:   cfg.UberDirectCustomerID,
			WebhookSec:   cfg.UberDirectWebhookSec,
		}),
		doordash: doordash.New(doordash.Config{
			DeveloperID: cfg.DoorDashDeveloperID,
			KeyID:       cfg.DoorDashKeyID,
			SigningKey:   cfg.DoorDashSigningKey,
			WebhookSec:  cfg.DoorDashWebhookSec,
		}),
	}
}

// Notifier exposes the shared push-notification facade so background workers
// (like the scheduler's auto-dispatch sweep) can fire the same semantic events
// that HTTP handlers do.
func (h *Handler) Notifier() *notify.Notifier { return h.notify }

// Stripe exposes the shared Stripe client so background workers (like the
// stale-order sweep) can issue refunds without constructing a second client.
func (h *Handler) Stripe() *payments.Client { return h.stripe }

// UberDirect exposes the Uber Direct client so the scheduler can dispatch
// fallback deliveries when no own courier is available.
func (h *Handler) UberDirect() *uberdirect.Client { return h.uber }

// DoorDash exposes the DoorDash Drive client for the scheduler's external
// dispatch fallback chain.
func (h *Handler) DoorDash() *doordash.Client { return h.doordash }

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func readJSON(r *http.Request, dst interface{}) error {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(dst)
}
