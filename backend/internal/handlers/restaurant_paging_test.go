package handlers

// GET /restaurants page/per_page. The Android consumer app pages with
// per_page=20 and treats a short page as the end, so the contract that
// matters is: pages are disjoint, stable, and eventually empty — and a
// malformed value is rejected rather than silently handed the whole feed.

import (
	"encoding/json"
	"net/http"
	"strconv"
	"testing"
)

func TestIntegration_RestaurantsPaging(t *testing.T) {
	harness.resetVolatile(t)

	ids := func(t *testing.T, path string) []string {
		t.Helper()
		rec := harness.do(http.MethodGet, path, "", nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("%s: status %d, body %s", path, rec.Code, rec.Body.String())
		}
		var rows []struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &rows); err != nil {
			t.Fatalf("%s: decode: %v", path, err)
		}
		out := make([]string, 0, len(rows))
		for _, r := range rows {
			out = append(out, r.ID)
		}
		return out
	}

	all := ids(t, "/api/v1/restaurants/")
	if len(all) < 2 {
		t.Fatalf("fixtures should expose at least two live restaurants, got %d", len(all))
	}

	// Walk the feed one restaurant at a time and reassemble it.
	var walked []string
	seen := map[string]bool{}
	for page := 1; page <= len(all)+1; page++ {
		got := ids(t, "/api/v1/restaurants/?page="+itoa(page)+"&per_page=1")
		if page > len(all) {
			if len(got) != 0 {
				t.Fatalf("page %d past the end returned %v, want empty", page, got)
			}
			break
		}
		if len(got) != 1 {
			t.Fatalf("page %d returned %d restaurants, want 1", page, len(got))
		}
		if seen[got[0]] {
			t.Fatalf("page %d repeated restaurant %s (unstable ordering)", page, got[0])
		}
		seen[got[0]] = true
		walked = append(walked, got[0])
	}
	if len(walked) != len(all) {
		t.Fatalf("walked %d restaurants page by page, the unpaged feed has %d", len(walked), len(all))
	}
	for i := range all {
		if walked[i] != all[i] {
			t.Fatalf("page-walked order differs from the unpaged feed at %d: %v vs %v", i, walked, all)
		}
	}

	// The same page, requested twice, is the same page.
	if a, b := ids(t, "/api/v1/restaurants/?page=2&per_page=1"), ids(t, "/api/v1/restaurants/?page=2&per_page=1"); len(a) != 1 || len(b) != 1 || a[0] != b[0] {
		t.Fatalf("page 2 not stable across requests: %v vs %v", a, b)
	}

	// Distance sort pages the same way.
	near := ids(t, "/api/v1/restaurants/?lat=40.64&lng=-73.97&page=1&per_page=1")
	if len(near) != 1 {
		t.Fatalf("distance-sorted page returned %d, want 1", len(near))
	}

	for _, bad := range []string{"per_page=0", "per_page=abc", "page=0", "page=-1", "page=x", "page=100001"} {
		rec := harness.do(http.MethodGet, "/api/v1/restaurants/?"+bad, "", nil)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("%s: status %d, want 400 (body %s)", bad, rec.Code, rec.Body.String())
		}
	}
	// Oversized per_page is clamped, not rejected.
	if rec := harness.do(http.MethodGet, "/api/v1/restaurants/?per_page=999", "", nil); rec.Code != http.StatusOK {
		t.Errorf("per_page=999: status %d, want 200 (clamped)", rec.Code)
	}
}

func itoa(n int) string {
	return strconv.Itoa(n)
}
