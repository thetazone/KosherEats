package handlers

// GET /seller/orders `status` filter + cursor pagination. The seller app's
// status chips send this; before the parameter existed every chip fetched the
// whole list and filtered client-side, which fell apart past the first page.

import (
	"encoding/json"
	"net/http"
	"testing"
	"time"
)

func TestIntegration_SellerOrdersStatusFilter(t *testing.T) {
	s := newSellerEnv(t)

	// Distinct created_at so cursor ordering is deterministic.
	ageMinutes := func(n int) func(id string) {
		return func(id string) {
			mustExec(t, `UPDATE orders SET created_at = NOW() - make_interval(mins => $2) WHERE id = $1`, id, n)
		}
	}
	pending := s.order(t, "pending", ageMinutes(1))
	accepted := s.order(t, "accepted", ageMinutes(2))
	ready := s.order(t, "ready", ageMinutes(3))
	completed := s.order(t, "completed", ageMinutes(4))

	type row struct {
		ID        string    `json:"id"`
		Status    string    `json:"status"`
		CreatedAt time.Time `json:"created_at"`
	}
	list := func(query string) ([]row, int) {
		t.Helper()
		rec := doRequest(s.router, http.MethodGet, "/api/v1/seller/orders/?"+query, s.token, nil)
		if rec.Code != http.StatusOK {
			return nil, rec.Code
		}
		var rows []row
		if err := json.Unmarshal(rec.Body.Bytes(), &rows); err != nil {
			t.Fatalf("decode %q: %v", query, err)
		}
		return rows, rec.Code
	}
	ids := func(rows []row) []string {
		out := make([]string, 0, len(rows))
		for _, r := range rows {
			out = append(out, r.ID)
		}
		return out
	}
	equal := func(got, want []string) bool {
		if len(got) != len(want) {
			return false
		}
		for i := range got {
			if got[i] != want[i] {
				return false
			}
		}
		return true
	}

	all, code := list("")
	if code != http.StatusOK || !equal(ids(all), []string{pending, accepted, ready, completed}) {
		t.Fatalf("unfiltered: code %d ids %v", code, ids(all))
	}

	cases := []struct {
		query string
		want  []string
	}{
		{"status=pending", []string{pending}},
		{"status=pending,accepted", []string{pending, accepted}},
		{"status=Pending,%20ACCEPTED", []string{pending, accepted}},
		{"status=accepted,accepted,ready", []string{accepted, ready}},
		{"status=completed,delivered", []string{completed}},
		{"status=cancelled", []string{}},
	}
	for _, tc := range cases {
		got, code := list(tc.query)
		if code != http.StatusOK {
			t.Errorf("%s: status %d, want 200", tc.query, code)
			continue
		}
		if !equal(ids(got), tc.want) {
			t.Errorf("%s: ids %v, want %v", tc.query, ids(got), tc.want)
		}
	}

	for _, bad := range []string{"status=bogus", "status=pending,bogus", "status=,"} {
		if _, code := list(bad); code != http.StatusBadRequest {
			t.Errorf("%s: status %d, want 400", bad, code)
		}
	}
	// Blank means "no filter", same as omitting it.
	if got, code := list("status=%20"); code != http.StatusOK || len(got) != 4 {
		t.Errorf("blank status: code %d, %d rows; want 200 with all 4", code, len(got))
	}

	// Cursor and status compose: page the open orders one at a time.
	page1, _ := list("status=pending,accepted,ready&limit=1")
	if !equal(ids(page1), []string{pending}) {
		t.Fatalf("page 1: %v", ids(page1))
	}
	page2, _ := list("status=pending,accepted,ready&limit=1&cursor=" + page1[0].CreatedAt.Format(time.RFC3339Nano))
	if !equal(ids(page2), []string{accepted}) {
		t.Fatalf("page 2: %v", ids(page2))
	}
	page3, _ := list("status=pending,accepted,ready&limit=1&cursor=" + page2[0].CreatedAt.Format(time.RFC3339Nano))
	if !equal(ids(page3), []string{ready}) {
		t.Fatalf("page 3: %v", ids(page3))
	}
	page4, _ := list("status=pending,accepted,ready&limit=1&cursor=" + page3[0].CreatedAt.Format(time.RFC3339Nano))
	if len(page4) != 0 {
		t.Fatalf("page 4 should be empty, got %v", ids(page4))
	}
	if _, code := list("cursor=not-a-time"); code != http.StatusBadRequest {
		t.Errorf("bad cursor: status %d, want 400", code)
	}
}
