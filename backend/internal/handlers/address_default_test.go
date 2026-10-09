package handlers

// DELETE /user/addresses/{id}/default — the off position of the consumer
// apps' "Set as default" toggle. Owner-scoped like the PATCH; the address
// itself must survive.

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/koshereats/backend/internal/models"
)

func TestIntegration_ClearDefaultAddress(t *testing.T) {
	harness.resetVolatile(t)
	token, _ := harness.registerUser(t, "addrdefault")
	otherToken, _ := harness.registerUser(t, "addrother")

	add := func(label string) string {
		t.Helper()
		rec := harness.do(http.MethodPost, "/api/v1/user/addresses", token, map[string]any{
			"label": label, "street": "1 Main St", "city": "Brooklyn", "state": "NY",
			"zip_code": "11218", "lat": 40.64, "lng": -73.97,
		})
		if rec.Code != http.StatusCreated {
			t.Fatalf("add address: status %d, body %s", rec.Code, rec.Body.String())
		}
		var a models.Address
		if err := json.Unmarshal(rec.Body.Bytes(), &a); err != nil {
			t.Fatalf("add address decode: %v", err)
		}
		return a.ID
	}
	defaults := func() map[string]bool {
		t.Helper()
		rec := harness.do(http.MethodGet, "/api/v1/user/addresses", token, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("list addresses: status %d, body %s", rec.Code, rec.Body.String())
		}
		var list []models.Address
		if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
			t.Fatalf("list addresses decode: %v", err)
		}
		out := map[string]bool{}
		for _, a := range list {
			out[a.ID] = a.IsDefault
		}
		return out
	}

	home := add("Home")
	work := add("Work")

	if rec := harness.do(http.MethodPatch, "/api/v1/user/addresses/"+home+"/default", token, nil); rec.Code != http.StatusOK {
		t.Fatalf("set default: status %d, body %s", rec.Code, rec.Body.String())
	}
	if d := defaults(); !d[home] || d[work] {
		t.Fatalf("after set: defaults = %v, want only home", d)
	}

	// Another user cannot clear it; an unknown id is a 404 too.
	if rec := harness.do(http.MethodDelete, "/api/v1/user/addresses/"+home+"/default", otherToken, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("clear default as other user: status %d, want 404", rec.Code)
	}
	if rec := harness.do(http.MethodDelete, "/api/v1/user/addresses/00000000-0000-0000-0000-000000000000/default", token, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("clear default unknown id: status %d, want 404", rec.Code)
	}
	if d := defaults(); !d[home] {
		t.Fatalf("home lost its default flag to a rejected request: %v", d)
	}

	// The owner can clear it, the address survives, and the call is idempotent.
	for i := 0; i < 2; i++ {
		if rec := harness.do(http.MethodDelete, "/api/v1/user/addresses/"+home+"/default", token, nil); rec.Code != http.StatusOK {
			t.Fatalf("clear default (call %d): status %d, body %s", i+1, rec.Code, rec.Body.String())
		}
	}
	d := defaults()
	if len(d) != 2 {
		t.Fatalf("clearing the default deleted an address: %v", d)
	}
	if d[home] || d[work] {
		t.Fatalf("after clear: defaults = %v, want none", d)
	}
}
