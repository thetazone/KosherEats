package handlers

import (
	"encoding/json"
	"net/http"

	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/database"
)

type Handler struct {
	db  *database.DB
	cfg *config.Config
}

func New(db *database.DB, cfg *config.Config) *Handler {
	return &Handler{db: db, cfg: cfg}
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
