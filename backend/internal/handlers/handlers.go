package handlers

import (
	"encoding/json"
	"net/http"

	"github.com/koshereats/backend/internal/background"
	"github.com/koshereats/backend/internal/broker"
	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/database"
	"github.com/koshereats/backend/internal/dispatch"
	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/email"
	"github.com/koshereats/backend/internal/notify"
	"github.com/koshereats/backend/internal/payments"
	"github.com/koshereats/backend/internal/payout"
	"github.com/koshereats/backend/internal/pos"
	"github.com/koshereats/backend/internal/pos/clover"
	"github.com/koshereats/backend/internal/sms"
	"github.com/koshereats/backend/internal/storage"
	"github.com/koshereats/backend/internal/uberdirect"
)

type Handler struct {
	db            *database.DB
	cfg           *config.Config
	notify        *notify.Notifier
	stripe        *payments.Client
	storage       *storage.Client
	checkr        *background.Checkr
	location      *broker.Broker
	sms           *sms.Client
	email         *email.Client
	uber          *uberdirect.Client
	doordash      *doordash.Client
	posRegistry   *pos.Registry
	payoutStarter *payout.Starter
	// dispatcher is the shared external-courier dispatcher, used inline to
	// dispatch Uber/DoorDash the instant a seller marks an 'external'-mode order
	// ready or escalates a self-delivery order. Same claim-before-create logic as
	// the scheduler's sweep.
	dispatcher *dispatch.ExternalDispatcher
}

func New(db *database.DB, cfg *config.Config) *Handler {
	apns := notify.New(cfg)
	fcm := notify.NewFCM(cfg)
	h := &Handler{
		db:       db,
		cfg:      cfg,
		notify:   notify.NewNotifier(db.Pool, apns, fcm),
		stripe:   payments.New(cfg),
		storage:  storage.New(cfg),
		checkr:   background.New(cfg, db.Pool),
		location: broker.New(),
		sms:      sms.New(cfg),
		email:    email.New(),
		uber: uberdirect.New(uberdirect.Config{
			ClientID:     cfg.UberDirectClientID,
			ClientSecret: cfg.UberDirectClientSecret,
			CustomerID:   cfg.UberDirectCustomerID,
			WebhookSec:   cfg.UberDirectWebhookSec,
			Stub:         cfg.UberDirectStub,
		}),
		doordash: doordash.New(doordash.Config{
			DeveloperID: cfg.DoorDashDeveloperID,
			KeyID:       cfg.DoorDashKeyID,
			SigningKey:  cfg.DoorDashSigningKey,
			WebhookSec:  cfg.DoorDashWebhookSec,
		}),
		posRegistry: pos.NewRegistry(db.Pool, clover.New()),
	}
	h.dispatcher = dispatch.New(h.db.Pool, h.uber, h.doordash)
	return h
}

// SetPayoutStarter injects the Temporal payout-workflow starter. When nil (the
// default), payout starts are disabled and the legacy direct-transfer sweep
// remains the sole payout path — a nil *payout.Starter is a safe no-op.
func (h *Handler) SetPayoutStarter(s *payout.Starter) { h.payoutStarter = s }

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

// Alerter builds an admin anomaly alerter from the configured AdminAlertEmail
// and the shared email client, so the scheduler can alert on auto-refunds and
// permanently failed payouts using the same address/transport as the handler.
func (h *Handler) Alerter() *notify.Alerter {
	return notify.NewAlerter(h.cfg.AdminAlertEmail, h.email)
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func readJSON(r *http.Request, dst interface{}) error {
	r.Body = http.MaxBytesReader(nil, r.Body, 1<<20) // 1 MB
	return json.NewDecoder(r.Body).Decode(dst)
}
