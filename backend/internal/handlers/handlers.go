package handlers

import (
	"encoding/json"
	"net/http"

	"github.com/koshereats/backend/internal/background"
	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/database"
	"github.com/koshereats/backend/internal/notify"
	"github.com/koshereats/backend/internal/payments"
	"github.com/koshereats/backend/internal/storage"
)

type Handler struct {
	db      *database.DB
	cfg     *config.Config
	notify  *notify.Notifier
	stripe  *payments.Client
	storage *storage.Client
	checkr  *background.Checkr
}

func New(db *database.DB, cfg *config.Config) *Handler {
	apns := notify.New(cfg)
	fcm := notify.NewFCM(cfg)
	return &Handler{
		db:      db,
		cfg:     cfg,
		notify:  notify.NewNotifier(db.Pool, apns, fcm),
		stripe:  payments.New(cfg),
		storage: storage.New(cfg),
		checkr:  background.New(cfg, db.Pool),
	}
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
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(dst)
}
