package handlers

import (
	"net/http"
	"strings"
)

// Upload presigning. Clients call this to get a short-lived PUT URL, then
// upload directly to S3. The file never passes through our Go server.
//
// Uploaders are the courier app (onboarding docs, delivery proof) and the
// seller app (restaurant cover/logo/certificate, menu-item and deal images).
// The CONSUMER app uploads nothing today — every allowlisted kind below is
// role-gated to courier or seller/admin, so a consumer caller gets 403 for all
// of them. (An earlier version of this comment claimed consumers upload photos;
// there is no consumer upload feature — no avatar or review-photo path exists.
// If one is added, introduce a consumer-scoped kind AND its role gate together.)
//
// Accepted kinds are allowlisted so a malicious client can't use this to
// write arbitrary keys.

type PresignRequest struct {
	Kind        string `json:"kind"`         // "courier/license", "courier/insurance", etc.
	ContentType string `json:"content_type"` // "image/jpeg", "image/png", "image/heic"
}

var allowedUploadKinds = map[string]bool{
	"courier/license":        true,
	"courier/insurance":      true,
	"courier/registration":   true,
	"courier/profile":        true,
	"delivery_proof":         true,
	"restaurant/cover":       true,
	"restaurant/logo":        true,
	"restaurant/certificate": true,
	"menu_item":              true,
	"deal":                   true,
}

var allowedContentTypes = map[string]bool{
	"image/jpeg": true,
	"image/jpg":  true,
	"image/png":  true,
	"image/heic": true,
	"image/webp": true,
}

func (h *Handler) PresignUpload(w http.ResponseWriter, r *http.Request) {
	user, err := getUserFromContext(r)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req PresignRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if !allowedUploadKinds[req.Kind] {
		writeError(w, http.StatusBadRequest, "unsupported upload kind")
		return
	}
	if !allowedContentTypes[strings.ToLower(req.ContentType)] {
		writeError(w, http.StatusBadRequest, "unsupported content type")
		return
	}

	// Role-gate uploads by kind so consumers can't pollute seller/courier S3 prefixes.
	if strings.HasPrefix(req.Kind, "courier/") && user["role"] != "courier" && user["role"] != "admin" {
		writeError(w, http.StatusForbidden, "courier role required for this upload kind")
		return
	}
	if strings.HasPrefix(req.Kind, "restaurant/") && user["role"] != "seller" && user["role"] != "admin" {
		writeError(w, http.StatusForbidden, "seller role required for this upload kind")
		return
	}
	if (req.Kind == "menu_item" || req.Kind == "deal") && user["role"] != "seller" && user["role"] != "admin" {
		writeError(w, http.StatusForbidden, "seller role required for this upload kind")
		return
	}
	if req.Kind == "delivery_proof" && user["role"] != "courier" && user["role"] != "admin" {
		writeError(w, http.StatusForbidden, "courier role required for delivery proof uploads")
		return
	}

	result, err := h.storage.Presign(r.Context(), user["user_id"], req.Kind, req.ContentType)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to presign upload")
		return
	}

	writeJSON(w, http.StatusOK, result)
}
