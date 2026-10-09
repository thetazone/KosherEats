package storage

import (
	"context"
	"errors"
	"net/url"
	"strings"
	"testing"

	"github.com/koshereats/backend/internal/config"
)

const (
	testUser  = "3f2b8c1e-1111-4222-8333-944455556666"
	otherUser = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
	endpoint  = "https://fly.storage.tigris.dev"
)

// newTestClient builds a real client (presigning is offline, so fake keys are
// fine) with both the public and the private bucket configured.
func newTestClient(t *testing.T, withPrivate bool) *Client {
	t.Helper()
	t.Setenv("AWS_ACCESS_KEY_ID", "tid_public_test")
	t.Setenv("AWS_SECRET_ACCESS_KEY", "tsec_public_test")
	cfg := &config.Config{
		S3Bucket:    "koshereats-media",
		S3Region:    "auto",
		S3Endpoint:  endpoint,
		S3PublicURL: "https://koshereats-media.fly.storage.tigris.dev",
	}
	if withPrivate {
		cfg.PrivateS3Bucket = "koshereats-private"
		cfg.PrivateS3AccessKeyID = "tid_private_test"
		cfg.PrivateS3SecretAccessKey = "tsec_private_test"
	}
	c := New(cfg)
	if !c.enabled {
		t.Fatal("client should be enabled")
	}
	return c
}

func TestPrivateKindsGoToPrivateBucketWithSignedPreview(t *testing.T) {
	c := newTestClient(t, true)
	for _, kind := range []string{"courier/license", "courier/insurance", "courier/registration"} {
		res, err := c.Presign(context.Background(), testUser, kind, "image/jpeg")
		if err != nil {
			t.Fatalf("%s: presign: %v", kind, err)
		}
		for name, raw := range map[string]string{"upload": res.UploadURL, "public": res.PublicURL} {
			u, err := url.Parse(raw)
			if err != nil {
				t.Fatalf("%s %s url: %v", kind, name, err)
			}
			if u.Host != "fly.storage.tigris.dev" || !strings.HasPrefix(u.Path, "/koshereats-private/uploads/"+kind+"/"+testUser+"/") {
				t.Errorf("%s %s url not on the private bucket: %s", kind, name, raw)
			}
			if u.Query().Get("X-Amz-Signature") == "" {
				t.Errorf("%s %s url is not signed: %s", kind, name, raw)
			}
		}
		if q, _ := url.Parse(res.PublicURL); q.Query().Get("X-Amz-Expires") != "3600" {
			t.Errorf("%s preview TTL = %s, want 3600", kind, q.Query().Get("X-Amz-Expires"))
		}
	}
	// The courier's profile photo is shown to customers: stays public.
	res, err := c.Presign(context.Background(), testUser, "courier/profile", "image/jpeg")
	if err != nil {
		t.Fatalf("profile presign: %v", err)
	}
	if !strings.HasPrefix(res.PublicURL, "https://koshereats-media.fly.storage.tigris.dev/uploads/courier/profile/") {
		t.Errorf("profile photo should stay public, got %s", res.PublicURL)
	}
}

func TestPrivateKindsFailClosedWithoutPrivateBucket(t *testing.T) {
	c := newTestClient(t, false)
	if _, err := c.Presign(context.Background(), testUser, "courier/license", "image/jpeg"); !errors.Is(err, ErrPrivateStorageUnavailable) {
		t.Fatalf("err = %v, want ErrPrivateStorageUnavailable (never fall back to the public bucket)", err)
	}
	if got := c.DocumentURL(context.Background(), PrivateRefPrefix+"uploads/courier/license/"+testUser+"/0123456789abcdef.jpg"); got != "" {
		t.Fatalf("DocumentURL without private bucket = %q, want withheld", got)
	}
}

func TestNormalizeDocumentRef(t *testing.T) {
	c := newTestClient(t, true)
	own, err := c.Presign(context.Background(), testUser, "courier/insurance", "image/png")
	if err != nil {
		t.Fatal(err)
	}
	other, err := c.Presign(context.Background(), otherUser, "courier/license", "image/jpeg")
	if err != nil {
		t.Fatal(err)
	}
	ownKey := "uploads/courier/license/" + testUser + "/0123456789abcdef.jpg"

	cases := []struct {
		name, in, want string
		wantErr        bool
	}{
		{"empty stays empty", "", "", false},
		{"own presigned preview", own.PublicURL, PrivateRefPrefix + own.Key, false},
		{"own presigned upload url", own.UploadURL, PrivateRefPrefix + own.Key, false},
		{"echoed private ref", PrivateRefPrefix + ownKey, PrivateRefPrefix + ownKey, false},
		{"virtual-host style", "https://koshereats-private.fly.storage.tigris.dev/" + ownKey + "?X-Amz-Signature=x", PrivateRefPrefix + ownKey, false},
		{"another courier's upload", other.PublicURL, "", true},
		{"another courier's ref", PrivateRefPrefix + "uploads/courier/license/" + otherUser + "/0123456789abcdef.jpg", "", true},
		{"public bucket url", "https://koshereats-media.fly.storage.tigris.dev/" + ownKey, "", true},
		{"public bucket path-style", endpoint + "/koshereats-media/" + ownKey, "", true},
		{"external link", "https://example.com/license.jpg", "", true},
		{"plain http", "http://fly.storage.tigris.dev/koshereats-private/" + ownKey, "", true},
		{"non-document kind", PrivateRefPrefix + "uploads/courier/profile/" + testUser + "/0123456789abcdef.jpg", "", true},
		{"traversal", PrivateRefPrefix + "uploads/courier/license/" + testUser + "/../x/0123456789abcdef.jpg", "", true},
		{"bad file name", PrivateRefPrefix + "uploads/courier/license/" + testUser + "/evil.svg", "", true},
	}
	for _, tc := range cases {
		got, err := c.NormalizeDocumentRef(testUser, tc.in)
		if tc.wantErr {
			if !errors.Is(err, ErrInvalidDocumentRef) {
				t.Errorf("%s: err = %v, want ErrInvalidDocumentRef (got %q)", tc.name, err, got)
			}
			continue
		}
		if err != nil || got != tc.want {
			t.Errorf("%s: got %q, %v; want %q", tc.name, got, err, tc.want)
		}
	}
}

func TestDocumentURLSignsPrivateRefsAndPassesOthersThrough(t *testing.T) {
	c := newTestClient(t, true)
	ref := PrivateRefPrefix + "uploads/courier/registration/" + testUser + "/0123456789abcdef.jpg"
	got := c.DocumentURL(context.Background(), ref)
	u, err := url.Parse(got)
	if err != nil || u.Host != "fly.storage.tigris.dev" || u.Path != "/koshereats-private/uploads/courier/registration/"+testUser+"/0123456789abcdef.jpg" {
		t.Fatalf("signed url = %q", got)
	}
	if u.Query().Get("X-Amz-Expires") != "900" || u.Query().Get("X-Amz-Signature") == "" {
		t.Fatalf("read link should be signed for 15 minutes: %q", got)
	}
	for _, v := range []string{"", "https://koshereats-media.fly.storage.tigris.dev/uploads/courier/profile/x.jpg", "stub://uploads/x"} {
		if got := c.DocumentURL(context.Background(), v); got != v {
			t.Errorf("DocumentURL(%q) = %q, want unchanged", v, got)
		}
	}
}

func TestStubModePassesThrough(t *testing.T) {
	c := New(&config.Config{})
	if got, err := c.NormalizeDocumentRef(testUser, "stub://uploads/courier/license/x.jpg"); err != nil || got != "stub://uploads/courier/license/x.jpg" {
		t.Fatalf("stub normalize = %q, %v", got, err)
	}
	res, err := c.Presign(context.Background(), testUser, "courier/license", "image/jpeg")
	if err != nil || !strings.HasPrefix(res.UploadURL, "stub://") {
		t.Fatalf("stub presign = %+v, %v", res, err)
	}
}
