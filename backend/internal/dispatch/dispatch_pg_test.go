package dispatch

// DB-backed tests for the parts of Dispatch that only exist in SQL: the
// claim-before-create CAS, the external_dispatch_attempts cap, and the
// permanent-vs-transient fail() bookkeeping (attempts bump + delivery_mode
// fallback flip). The provider auction is exercised against local httptest
// servers.
//
// SAFETY: no request in this file can leave the machine. TestMain installs a
// RoundTripper over http.DefaultTransport that rewrites every outbound request
// to a loopback httptest server and FAILS on any host it doesn't recognize, so
// a real api.uber.com / openapi.doordash.com / api.shipday.com call is a test
// failure rather than a network call. Credentials are obvious fakes.
//
// Postgres comes from TEST_DATABASE_URL, defaulting to the project's
// docker-compose instance on :5433 — but this package always runs against its
// OWN database (the configured name with a "_dispatch" suffix), created on
// demand. Sharing one database with internal/handlers made `go test ./...`
// flaky: Go runs packages concurrently, and the handlers harness resets
// between tests with TRUNCATE ... orders CASCADE, which wiped rows these tests
// were still using. The symptom was an unrelated dispatch test failing with
// "no rows in result set" in roughly two runs out of five.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/database"
	"github.com/koshereats/backend/internal/doordash"
	"github.com/koshereats/backend/internal/shipday"
	"github.com/koshereats/backend/internal/uberdirect"
)

const defaultTestDatabaseURL = "postgres://postgres:postgres@localhost:5433/koshereats_test?sslmode=disable"

var (
	testPool     *pgxpool.Pool
	testOwnerID  string
	testRestID   string
	testConsumer string
)

// ---- fake provider network ----------------------------------------------

// providerRouter is the single loopback endpoint every provider client is
// redirected to. Tests install per-request handlers on it.
type providerRouter struct {
	mu       sync.Mutex
	handlers map[string]http.HandlerFunc // path prefix -> handler
	calls    map[string]*int32
}

var router = &providerRouter{handlers: map[string]http.HandlerFunc{}, calls: map[string]*int32{}}

func (pr *providerRouter) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	pr.mu.Lock()
	h := pr.handlers[r.URL.Path]
	if c, ok := pr.calls[r.URL.Path]; ok {
		atomic.AddInt32(c, 1)
	}
	pr.mu.Unlock()
	if h == nil {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":"no handler installed for this path in the test"}`))
		return
	}
	h(w, r)
}

// reset clears handlers between tests.
func (pr *providerRouter) reset() {
	pr.mu.Lock()
	defer pr.mu.Unlock()
	pr.handlers = map[string]http.HandlerFunc{}
	pr.calls = map[string]*int32{}
}

func (pr *providerRouter) on(path string, h http.HandlerFunc) {
	pr.mu.Lock()
	defer pr.mu.Unlock()
	pr.handlers[path] = h
}

// count starts counting calls to a path and returns the counter.
func (pr *providerRouter) count(path string) *int32 {
	pr.mu.Lock()
	defer pr.mu.Unlock()
	var c int32
	pr.calls[path] = &c
	return &c
}

func jsonHandler(status int, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}
}

// sandboxTransport rewrites every provider request to the local fake server.
// Any host outside the known provider set is refused so no test can ever open a
// connection to a real courier API.
type sandboxTransport struct {
	base *url.URL
	real http.RoundTripper
}

var allowedProviderHosts = map[string]bool{
	"api.uber.com":         true,
	"auth.uber.com":        true,
	"openapi.doordash.com": true,
	"api.shipday.com":      true,
}

func (st sandboxTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	if r.URL.Host == st.base.Host {
		return st.real.RoundTrip(r)
	}
	if !allowedProviderHosts[r.URL.Hostname()] {
		return nil, fmt.Errorf("sandbox: refusing request to unexpected host %q", r.URL.Host)
	}
	r2 := r.Clone(r.Context())
	r2.URL.Scheme = st.base.Scheme
	r2.URL.Host = st.base.Host
	r2.Host = ""
	return st.real.RoundTrip(r2)
}

// ---- harness -------------------------------------------------------------

func TestMain(m *testing.M) {
	srv := httptest.NewServer(router)
	defer srv.Close()
	base, err := url.Parse(srv.URL)
	if err != nil {
		fmt.Fprintf(os.Stderr, "dispatch tests: parse fake server url: %v\n", err)
		os.Exit(1)
	}
	// Every provider client builds its own http.Client with a nil Transport, so
	// it resolves DefaultTransport at request time — swapping it here captures
	// all three without touching non-test code.
	realTransport := http.DefaultTransport
	http.DefaultTransport = sandboxTransport{base: base, real: realTransport}
	defer func() { http.DefaultTransport = realTransport }()

	dbURL := os.Getenv("TEST_DATABASE_URL")
	if dbURL == "" {
		dbURL = defaultTestDatabaseURL
	}
	dbURL, err = ownDatabaseURL(dbURL)
	if err != nil {
		fmt.Fprintf(os.Stderr, "dispatch tests: derive database url: %v\n", err)
		os.Exit(1)
	}
	if err := ensureDatabaseExists(dbURL); err != nil {
		fmt.Fprintf(os.Stderr, "dispatch tests: ensure database: %v\n"+
			"  (is the project's docker-compose Postgres up on :5433?)\n", err)
		os.Exit(1)
	}
	db, err := database.Connect(dbURL)
	if err != nil {
		fmt.Fprintf(os.Stderr, "dispatch tests: connect %s: %v\n"+
			"  (is the project's docker-compose Postgres up on :5433?)\n", dbURL, err)
		os.Exit(1)
	}
	defer db.Close()
	if err := db.RunMigrations(context.Background(), migrationsDir()); err != nil {
		fmt.Fprintf(os.Stderr, "dispatch tests: migrations: %v\n", err)
		os.Exit(1)
	}
	testPool = db.Pool
	if err := seed(context.Background()); err != nil {
		fmt.Fprintf(os.Stderr, "dispatch tests: seed: %v\n", err)
		os.Exit(1)
	}
	os.Exit(m.Run())
}

func migrationsDir() string {
	_, thisFile, _, _ := runtime.Caller(0)
	return filepath.Join(filepath.Dir(thisFile), "..", "database", "migrations")
}

// ownDatabaseURL returns dbURL pointing at THIS package's own database: the
// configured name plus a "_dispatch" suffix.
//
// Package-level isolation, not politeness. `go test ./...` runs packages
// concurrently, and internal/handlers resets between its tests with
// `TRUNCATE cart_items, carts, order_items, orders RESTART IDENTITY CASCADE`.
// Pointed at the same database, that truncate deletes the orders these tests
// seeded, mid-test — every dispatch test that reads its order back after a
// Dispatch call could fail with "no rows in result set", and which one lost
// was pure timing. Separate databases remove the shared mutable state instead
// of trying to order the two suites against each other.
func ownDatabaseURL(dbURL string) (string, error) {
	cfg, err := pgx.ParseConfig(dbURL)
	if err != nil {
		return "", fmt.Errorf("parse url: %w", err)
	}
	if cfg.Database == "" {
		return "", fmt.Errorf("no database name in %q", dbURL)
	}
	if strings.HasSuffix(cfg.Database, dispatchDBSuffix) {
		return dbURL, nil
	}
	u, err := url.Parse(dbURL)
	if err != nil {
		return "", fmt.Errorf("parse url: %w", err)
	}
	u.Path = "/" + cfg.Database + dispatchDBSuffix
	return u.String(), nil
}

const dispatchDBSuffix = "_dispatch"

// ensureDatabaseExists creates the target database if it is missing, by
// connecting to the maintenance `postgres` database on the same server. Mirrors
// the helper in internal/handlers so a fresh checkout needs only an empty
// Postgres server.
func ensureDatabaseExists(dbURL string) error {
	cfg, err := pgx.ParseConfig(dbURL)
	if err != nil {
		return fmt.Errorf("parse url: %w", err)
	}
	target := cfg.Database

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if pool, perr := pgxpool.New(ctx, dbURL); perr == nil {
		pingErr := pool.Ping(ctx)
		pool.Close()
		if pingErr == nil {
			return nil
		}
	}

	adminCfg := cfg.Copy()
	adminCfg.Database = "postgres"
	adminConn, err := pgx.ConnectConfig(ctx, adminCfg)
	if err != nil {
		return fmt.Errorf("connect maintenance db: %w", err)
	}
	defer adminConn.Close(ctx)

	var exists bool
	if err := adminConn.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname = $1)`, target).Scan(&exists); err != nil {
		return fmt.Errorf("check database exists: %w", err)
	}
	if exists {
		return nil
	}
	// Identifiers can't be parameterized; target is derived from our own
	// env/default, not user input. Quote defensively all the same.
	if _, err := adminConn.Exec(ctx,
		fmt.Sprintf(`CREATE DATABASE %s`, pgx.Identifier{target}.Sanitize())); err != nil {
		return fmt.Errorf("create database %q: %w", target, err)
	}
	return nil
}

func seed(ctx context.Context) error {
	if err := testPool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ('dispatch-owner@example.test', '', 'Own', 'Er', '+17185551212', 'seller', 'kosher')
		 ON CONFLICT DO NOTHING RETURNING id`).Scan(&testOwnerID); err != nil {
		if err := testPool.QueryRow(ctx,
			`SELECT id FROM users WHERE email = 'dispatch-owner@example.test'`).Scan(&testOwnerID); err != nil {
			return fmt.Errorf("owner: %w", err)
		}
	}
	if err := testPool.QueryRow(ctx,
		`SELECT id FROM users WHERE email = 'dispatch-consumer@example.test'`).Scan(&testConsumer); err != nil {
		if err := testPool.QueryRow(ctx,
			`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
			 VALUES ('dispatch-consumer@example.test', '', 'Con', 'Sumer', '+13156645801', 'consumer', 'kosher')
			 RETURNING id`).Scan(&testConsumer); err != nil {
			return fmt.Errorf("consumer: %w", err)
		}
	}
	if err := testPool.QueryRow(ctx,
		`SELECT id FROM restaurants WHERE name = 'Dispatch Test Deli'`).Scan(&testRestID); err != nil {
		if err := testPool.QueryRow(ctx,
			`INSERT INTO restaurants (owner_id, name, street, city, state, zip_code, phone,
			   is_active, is_open, approval_status, vertical, delivery_mode)
			 VALUES ($1, 'Dispatch Test Deli', '1 Main St', 'Brooklyn', 'NY', '11218', '+17185551212',
			   true, true, 'approved', 'kosher', 'external') RETURNING id`, testOwnerID).Scan(&testRestID); err != nil {
			return fmt.Errorf("restaurant: %w", err)
		}
	}
	return nil
}

// orderOpts describes the order row a test starts from.
type orderOpts struct {
	status       string
	deliveryMode string // NULL when empty
	attempts     int
	courierID    *string
	extDelivery  *string
	extProvider  *string
}

func seedOrder(t *testing.T, o orderOpts) string {
	t.Helper()
	if o.status == "" {
		o.status = "ready"
	}
	var mode any
	if o.deliveryMode != "" {
		mode = o.deliveryMode
	}
	var id string
	err := testPool.QueryRow(context.Background(),
		`INSERT INTO orders (user_id, restaurant_id, status, subtotal, delivery_fee, service_fee, tax, total,
		   delivery_address, stripe_payment_id, courier_tip, fulfillment_type, delivery_mode,
		   external_dispatch_attempts, courier_id, external_delivery_id, external_provider)
		 VALUES ($1, $2, $3, 2599, 699, 0, 234, 3532, '2 Oak St, Brooklyn, NY 11218', $4, 500,
		   'delivery', $5, $6, $7, $8, $9) RETURNING id`,
		testConsumer, testRestID, o.status, fmt.Sprintf("pi_test_%d", nextPI()),
		mode, o.attempts, o.courierID, o.extDelivery, o.extProvider,
	).Scan(&id)
	if err != nil {
		t.Fatalf("seed order: %v", err)
	}
	t.Cleanup(func() {
		_, _ = testPool.Exec(context.Background(), `DELETE FROM orders WHERE id = $1`, id)
	})
	return id
}

var piSeq int64

func nextPI() int64 { return atomic.AddInt64(&piSeq, 1) }

// orderState is the subset of the row the dispatch bookkeeping touches.
type orderState struct {
	provider     string
	deliveryID   string
	trackingURL  string
	providerFee  int
	attempts     int
	deliveryMode string
	status       string
}

func readOrder(t *testing.T, id string) orderState {
	t.Helper()
	var s orderState
	if err := testPool.QueryRow(context.Background(),
		`SELECT COALESCE(external_provider,''), COALESCE(external_delivery_id,''),
		        COALESCE(external_tracking_url,''), COALESCE(provider_fee_cents,0),
		        external_dispatch_attempts, COALESCE(delivery_mode,''), status
		   FROM orders WHERE id = $1`, id).Scan(
		&s.provider, &s.deliveryID, &s.trackingURL, &s.providerFee,
		&s.attempts, &s.deliveryMode, &s.status); err != nil {
		t.Fatalf("read order %s: %v", id, err)
	}
	return s
}

func baseInput(orderID string) Input {
	return Input{
		OrderID:         orderID,
		RestaurantName:  "Dispatch Test Deli",
		RestAddress:     "1 Main St, Brooklyn, NY 11218",
		RestPhone:       "+17185551212",
		DeliveryAddress: "2 Oak St, Brooklyn, NY 11218",
		CustomerName:    "Con Sumer",
		CustomerPhone:   "+13156645801",
		Subtotal:        2599,
		TipCents:        500,
	}
}

// newDispatcher builds a dispatcher with only the named providers enabled.
// Credentials are placeholders; every request is redirected to the loopback
// fake server by TestMain's transport.
func newDispatcher(t *testing.T, uber, dd, sd bool) *ExternalDispatcher {
	t.Helper()
	router.reset()
	var u *uberdirect.Client
	var d *doordash.Client
	var s *shipday.Client
	if uber {
		u = uberdirect.New(uberdirect.Config{
			ClientID: "fake-id", ClientSecret: "fake-secret", CustomerID: "fake-customer",
		})
		router.on("/oauth/v2/token", jsonHandler(200, `{"access_token":"fake-token","expires_in":2592000}`))
	}
	if dd {
		d = doordash.New(doordash.Config{
			DeveloperID: "fake-dev", KeyID: "fake-key",
			// base64url of 32 bytes — enough for the local JWT mint, not a real key.
			SigningKey: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
		})
	}
	if sd {
		s = shipday.New(shipday.Config{APIKey: "fake-shipday-key"})
	}
	return New(testPool, u, d, s, nil, nil)
}

// ---- the claim CAS -------------------------------------------------------

// The claim CAS is the single guard that stops two callers (the sweep and an
// inline handler tap) both reaching the PAID CreateDelivery call for one order.
// Losing the claim is not an error — it must return an empty provider and nil.
func TestDispatch_ClaimIsExclusive(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))
	creates := router.count("/v1/customers/fake-customer/deliveries")
	router.on("/v1/customers/fake-customer/deliveries",
		jsonHandler(200, `{"id":"del_1","tracking_url":"https://t/1","fee":799,"status":"pending"}`))

	id := seedOrder(t, orderOpts{})

	const racers = 6
	var wg sync.WaitGroup
	results := make([]string, racers)
	errs := make([]error, racers)
	for i := 0; i < racers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			p, _, _, err := e.Dispatch(context.Background(), baseInput(id))
			results[i], errs[i] = p, err
		}(i)
	}
	wg.Wait()

	winners := 0
	for i, p := range results {
		if errs[i] != nil {
			t.Errorf("racer %d errored: %v", i, errs[i])
		}
		if p != "" {
			winners++
		}
	}
	if winners != 1 {
		t.Fatalf("%d racers won the claim, want exactly 1 (each extra winner is a second PAID delivery)", winners)
	}
	if got := atomic.LoadInt32(creates); got != 1 {
		t.Fatalf("CreateDelivery called %d times, want exactly 1 — the CAS exists to make this impossible", got)
	}
	st := readOrder(t, id)
	if st.provider != "uber_direct" || st.deliveryID != "del_1" {
		t.Errorf("order = %+v, want the uber delivery persisted", st)
	}
}

// Every predicate in the claim CAS must independently block a dispatch. Each of
// these states means someone else already owns the order — dispatching anyway
// buys a delivery for food that is already handled.
func TestDispatch_ClaimPreconditions(t *testing.T) {
	courier := testConsumer // any non-null uuid
	delID := "existing_delivery"
	prov := "uber_direct"

	cases := []struct {
		name string
		opts orderOpts
		in   func(Input) Input
	}{
		{"already has a platform courier", orderOpts{courierID: &courier}, nil},
		{"already has an external delivery", orderOpts{extDelivery: &delID, extProvider: &prov}, nil},
		{"another caller holds the dispatching sentinel", orderOpts{extProvider: ptr("dispatching")}, nil},
		// SellerPickupOrder ↔ dispatch race: an order the seller already picked up
		// must never be paid-dispatched.
		{"already picked up", orderOpts{status: "picked_up"}, nil},
		{"already delivered", orderOpts{status: "delivered"}, nil},
		{"cancelled", orderOpts{status: "cancelled"}, nil},
		{"still pending (not accepted yet)", orderOpts{status: "pending"}, nil},
		// Automatic dispatch must not grab an order the seller switched to
		// self-delivery; only an explicit seller escalation may.
		{"self-delivery order, automatic dispatch", orderOpts{deliveryMode: "restaurant"}, nil},
		// The attempt cap: an order that burned its retries never re-enters the
		// paid quote/create path on a sweep tick.
		{"attempts at the cap", orderOpts{attempts: maxExternalDispatchAttempts}, nil},
		{"attempts past the cap", orderOpts{attempts: maxExternalDispatchAttempts + 3}, nil},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newDispatcher(t, true, false, false)
			quotes := router.count("/v1/customers/fake-customer/delivery_quotes")
			creates := router.count("/v1/customers/fake-customer/deliveries")
			router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))
			router.on("/v1/customers/fake-customer/deliveries", jsonHandler(200, `{"id":"del_x","fee":799}`))

			id := seedOrder(t, tc.opts)
			in := baseInput(id)
			if tc.in != nil {
				in = tc.in(in)
			}
			p, _, _, err := e.Dispatch(context.Background(), in)
			if err != nil {
				t.Fatalf("a lost claim is not an error, got %v", err)
			}
			if p != "" {
				t.Fatalf("dispatch proceeded (provider %q) on an order it must not claim", p)
			}
			if atomic.LoadInt32(quotes) != 0 || atomic.LoadInt32(creates) != 0 {
				t.Fatalf("provider was called (%d quotes, %d creates) before the claim was won",
					atomic.LoadInt32(quotes), atomic.LoadInt32(creates))
			}
		})
	}
}

// A seller-initiated escalation is an explicit human action: it may take a
// self-delivery order and it may retry past the attempt cap.
func TestDispatch_EscalationBypassesModeAndCap(t *testing.T) {
	for _, tc := range []struct {
		name string
		opts orderOpts
	}{
		{"self-delivery mode", orderOpts{deliveryMode: "restaurant"}},
		{"attempts already at the cap", orderOpts{attempts: maxExternalDispatchAttempts}},
		{"both", orderOpts{deliveryMode: "restaurant", attempts: maxExternalDispatchAttempts + 2}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := newDispatcher(t, true, false, false)
			router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q1","fee":799}`))
			router.on("/v1/customers/fake-customer/deliveries",
				jsonHandler(200, `{"id":"del_esc","tracking_url":"https://t/esc","fee":799}`))

			id := seedOrder(t, tc.opts)
			in := baseInput(id)
			in.AllowRestaurantMode = true
			p, delID, fee, err := e.Dispatch(context.Background(), in)
			if err != nil {
				t.Fatalf("escalation failed: %v", err)
			}
			if p != "uber_direct" || delID != "del_esc" || fee != 799 {
				t.Fatalf("escalation returned (%q,%q,%d), want (uber_direct,del_esc,799)", p, delID, fee)
			}
		})
	}
}

// ---- the auction ---------------------------------------------------------

// Dispatch must pick the CHEAPEST quote and then create with THAT provider.
// Choosing any other provider spends more than the consumer was quoted at
// checkout, and creating with a provider we didn't quote would use a stale or
// missing quote id.
func TestDispatch_CheapestQuoteWins(t *testing.T) {
	cases := []struct {
		name         string
		uberFee      int
		ddFee        int
		shipdayFee   float64
		shipdayReg   float64
		wantProvider string
		wantFee      int
	}{
		{"uber cheapest", 599, 975, 8.99, 0, "uber_direct", 599},
		{"doordash cheapest", 1299, 975, 11.00, 0, "doordash_drive", 975},
		{"shipday cheapest", 1299, 975, 6.49, 1.99, "shipday", 848},
		// The regulatory fee is part of what Shipday bills; ignoring it here
		// would pick Shipday at a price we never quoted the consumer.
		{"regulatory fee flips shipday out of the win", 900, 1200, 8.50, 1.00, "uber_direct", 900},
		// Ties keep the first-quoted provider (Uber) — deterministic, no flapping.
		{"tie keeps the first provider", 800, 800, 12.00, 0, "uber_direct", 800},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newDispatcher(t, true, true, true)
			router.on("/v1/customers/fake-customer/delivery_quotes",
				jsonHandler(200, fmt.Sprintf(`{"id":"uq1","fee":%d}`, tc.uberFee)))
			router.on("/drive/v2/quotes",
				jsonHandler(200, fmt.Sprintf(`{"external_delivery_id":"q","fee":%d}`, tc.ddFee)))
			router.on("/on-demand/availability",
				jsonHandler(200, fmt.Sprintf(`[{"id":"est1","name":"DoorDash","fee":%v,"regulatoryFee":%v,"pickupDuration":10,"deliveryDuration":15}]`,
					tc.shipdayFee, tc.shipdayReg)))

			uberCreate := router.count("/v1/customers/fake-customer/deliveries")
			ddCreate := router.count("/drive/v2/deliveries")
			sdInsert := router.count("/orders")
			router.on("/v1/customers/fake-customer/deliveries",
				jsonHandler(200, fmt.Sprintf(`{"id":"udel","tracking_url":"https://u/1","fee":%d}`, tc.uberFee)))
			router.on("/drive/v2/deliveries",
				jsonHandler(200, fmt.Sprintf(`{"external_delivery_id":"dddel","tracking_url":"https://d/1","fee":%d}`, tc.ddFee)))
			router.on("/orders", jsonHandler(200, `{"success":true,"orderId":5150}`))
			router.on("/on-demand/assign",
				jsonHandler(200, fmt.Sprintf(`{"orderId":5150,"trackingUrl":"https://s/1","totalBillableAmount":%v,"status":"ASSIGNED"}`,
					tc.shipdayFee+tc.shipdayReg)))

			id := seedOrder(t, orderOpts{})
			provider, delID, fee, err := e.Dispatch(context.Background(), baseInput(id))
			if err != nil {
				t.Fatalf("dispatch: %v", err)
			}
			if provider != tc.wantProvider {
				t.Fatalf("provider = %q, want %q", provider, tc.wantProvider)
			}
			if fee != tc.wantFee {
				t.Errorf("fee = %d, want %d", fee, tc.wantFee)
			}

			// Exactly one create, and it went to the winner.
			created := map[string]int32{
				"uber_direct":    atomic.LoadInt32(uberCreate),
				"doordash_drive": atomic.LoadInt32(ddCreate),
				"shipday":        atomic.LoadInt32(sdInsert),
			}
			for p, n := range created {
				want := int32(0)
				if p == tc.wantProvider {
					want = 1
				}
				if n != want {
					t.Errorf("%s create called %d times, want %d", p, n, want)
				}
			}

			// The order row must reference the delivery we actually paid for.
			st := readOrder(t, id)
			if st.provider != tc.wantProvider || st.deliveryID != delID || st.deliveryID == "" {
				t.Errorf("persisted %+v, want provider %q delivery %q", st, tc.wantProvider, delID)
			}
			if st.providerFee != tc.wantFee {
				t.Errorf("provider_fee_cents = %d, want %d", st.providerFee, tc.wantFee)
			}
			if st.trackingURL == "" {
				t.Error("no tracking URL persisted — the consumer sees no tracking")
			}
		})
	}
}

// A provider whose quote fails simply drops out of the auction; the survivors
// still dispatch. Silently failing the whole order because one provider is down
// would strand paid orders whenever Uber hiccups.
func TestDispatch_FailedQuoteDropsProviderNotOrder(t *testing.T) {
	e := newDispatcher(t, true, true, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(503, `{"message":"unavailable"}`))
	router.on("/drive/v2/quotes", jsonHandler(200, `{"external_delivery_id":"q","fee":1150}`))
	router.on("/drive/v2/deliveries", jsonHandler(200, `{"external_delivery_id":"dd1","fee":1150}`))

	id := seedOrder(t, orderOpts{})
	provider, _, fee, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil {
		t.Fatalf("dispatch: %v", err)
	}
	if provider != "doordash_drive" || fee != 1150 {
		t.Fatalf("got (%q,%d), want the surviving doordash quote", provider, fee)
	}
}

// ---- fail(): attempts bookkeeping and the platform fallback --------------

// A permanent rejection can never succeed on retry, so ONE of them must retire
// the order from the external path immediately: attempts jump straight to the
// cap and delivery_mode flips to 'platform' in the same statement. Retrying
// would burn a real provider call every sweep tick forever.
func TestDispatch_PermanentFailureRetiresOrderAtOnce(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes",
		jsonHandler(400, `{"code":"invalid_params","message":"unserviceable address"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil {
		t.Fatal("want an error when every provider rejects the quote")
	}
	if !IsPermanent(err) {
		t.Errorf("a 400 validation rejection must classify permanent, got %v", err)
	}
	st := readOrder(t, id)
	if st.attempts != maxExternalDispatchAttempts {
		t.Errorf("attempts = %d, want the cap (%d) — one permanent failure retires the order",
			st.attempts, maxExternalDispatchAttempts)
	}
	if st.deliveryMode != "platform" {
		t.Errorf("delivery_mode = %q, want platform (the internal-pool fallback)", st.deliveryMode)
	}
	// The claim sentinel must be released or the order is welded shut forever.
	if st.provider != "" {
		t.Errorf("external_provider = %q, want cleared", st.provider)
	}
}

// A transient failure costs ONE attempt and must not touch delivery_mode — the
// order stays on the external path so the next sweep tick can retry it.
func TestDispatch_TransientFailureCountsOneAttempt(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(503, `{"message":"try later"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil {
		t.Fatal("want an error")
	}
	if IsPermanent(err) {
		t.Errorf("a 503 must classify transient, got permanent for %v", err)
	}
	st := readOrder(t, id)
	if st.attempts != 1 {
		t.Errorf("attempts = %d, want 1", st.attempts)
	}
	if st.deliveryMode != "external" {
		t.Errorf("delivery_mode = %q, want external (unchanged — a retry can still win)", st.deliveryMode)
	}
	if st.provider != "" {
		t.Errorf("external_provider = %q, want the sentinel released for the next sweep", st.provider)
	}
}

// The LAST transient attempt is what turns a retry storm into a fallback: the
// bump that reaches the cap must flip delivery_mode in the SAME statement, or a
// crash between two statements leaves a cap-blocked external order that no
// sweep can ever rescue (the CAS blocks re-entry, so fail() never runs again).
func TestDispatch_TransientExhaustionFlipsToPlatformAtomically(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(500, `{"message":"boom"}`))

	// One attempt below the cap: this failure is the one that exhausts it.
	id := seedOrder(t, orderOpts{deliveryMode: "external", attempts: maxExternalDispatchAttempts - 1})
	if _, _, _, err := e.Dispatch(context.Background(), baseInput(id)); err == nil {
		t.Fatal("want an error")
	}
	st := readOrder(t, id)
	if st.attempts != maxExternalDispatchAttempts {
		t.Fatalf("attempts = %d, want %d", st.attempts, maxExternalDispatchAttempts)
	}
	if st.deliveryMode != "platform" {
		t.Fatalf("delivery_mode = %q, want platform — a cap-blocked 'external' order is unrescuable", st.deliveryMode)
	}

	// And now the CAS refuses it, so it can never burn another provider call.
	quotes := router.count("/v1/customers/fake-customer/delivery_quotes")
	p, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err != nil || p != "" {
		t.Errorf("retired order dispatched again: provider %q err %v", p, err)
	}
	if atomic.LoadInt32(quotes) != 0 {
		t.Error("a retired order still reached the paid quote path")
	}
}

// A seller-initiated escalation is a human-gated one-shot: its failures must
// NOT count against the shared cap. Counting them lets repeated taps poison the
// budget and later cap-block the order out of the automatic sweep with no
// fallback — a stranded, paid-for order.
func TestDispatch_EscalationFailureDoesNotBurnTheCap(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(500, `{"message":"boom"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	in := baseInput(id)
	in.AllowRestaurantMode = true

	for i := 0; i < 3; i++ {
		if _, _, _, err := e.Dispatch(context.Background(), in); err == nil {
			t.Fatalf("tap %d: want an error", i)
		}
	}
	st := readOrder(t, id)
	if st.attempts != 0 {
		t.Errorf("attempts = %d after 3 failed seller taps, want 0", st.attempts)
	}
	if st.deliveryMode != "external" {
		t.Errorf("delivery_mode = %q, want external — a failed escalation must not silently reroute the seller's order", st.deliveryMode)
	}
	if st.provider != "" {
		t.Errorf("external_provider = %q, want the claim released", st.provider)
	}
}

// Permanence across a MIXED batch: the order is retired only when EVERY quote
// failed permanently. One transient failure means a retry could still win, so
// treating the batch as permanent would reroute a winnable order to a courier
// pool that may be empty.
func TestDispatch_MixedQuoteFailuresAreTransient(t *testing.T) {
	e := newDispatcher(t, true, true, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(400, `{"message":"bad address"}`))
	router.on("/drive/v2/quotes", jsonHandler(503, `{"message":"unavailable"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil {
		t.Fatal("want an error")
	}
	if IsPermanent(err) {
		t.Error("a batch containing a transient failure must not be permanent")
	}
	st := readOrder(t, id)
	if st.attempts != 1 || st.deliveryMode != "external" {
		t.Errorf("state = %+v, want attempts 1 and delivery_mode external", st)
	}
}

// All-permanent batch: every provider rejected this order's data, so retiring
// it is correct.
func TestDispatch_AllPermanentQuoteFailuresRetire(t *testing.T) {
	e := newDispatcher(t, true, true, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(400, `{"message":"bad address"}`))
	router.on("/drive/v2/quotes", jsonHandler(422, `{"message":"unserviceable"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil || !IsPermanent(err) {
		t.Fatalf("want a permanent error, got %v", err)
	}
	st := readOrder(t, id)
	if st.attempts != maxExternalDispatchAttempts || st.deliveryMode != "platform" {
		t.Errorf("state = %+v, want the order retired to the platform pool", st)
	}
}

// A missing phone is caught locally, BEFORE any provider call: the quote would
// succeed and only the create would 400, so an unguarded order burns a paid
// quote and then fails after the kitchen has started cooking.
func TestDispatch_MissingPhoneFailsBeforeAnyProviderCall(t *testing.T) {
	for _, tc := range []struct {
		name              string
		restPhone, custPh string
	}{
		{"no pickup phone", "", "+13156645801"},
		{"no dropoff phone (the social-signup consumer)", "+17185551212", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e := newDispatcher(t, true, false, false)
			quotes := router.count("/v1/customers/fake-customer/delivery_quotes")
			router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q","fee":799}`))

			id := seedOrder(t, orderOpts{deliveryMode: "external"})
			in := baseInput(id)
			in.RestPhone, in.CustomerPhone = tc.restPhone, tc.custPh
			_, _, _, err := e.Dispatch(context.Background(), in)
			if err == nil || !IsPermanent(err) {
				t.Fatalf("want a permanent error, got %v", err)
			}
			if atomic.LoadInt32(quotes) != 0 {
				t.Error("a paid quote was burned on an order no provider can accept")
			}
			st := readOrder(t, id)
			if st.attempts != maxExternalDispatchAttempts || st.deliveryMode != "platform" {
				t.Errorf("state = %+v, want the order retired to the platform pool", st)
			}
		})
	}
}

// ---- Shipday's two-call flow inside dispatch ----------------------------

// An assign whose outcome is UNKNOWN may already have engaged a paid courier.
// Shipday's insert+assign carries no idempotency key, so a sweep retry could
// buy a SECOND delivery for food already moving — the order must be retired
// (permanent) even though the HTTP status alone reads transient.
func TestDispatch_ShipdayUnknownAssignOutcomeRetiresOrder(t *testing.T) {
	e := newDispatcher(t, false, false, true)
	router.on("/on-demand/availability", jsonHandler(200, `[{"id":"e1","name":"Uber","fee":7.00}]`))
	router.on("/orders", jsonHandler(200, `{"success":true,"orderId":777}`))
	router.on("/on-demand/assign", jsonHandler(500, `{"message":"gateway"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil {
		t.Fatal("want an error")
	}
	st := readOrder(t, id)
	if st.attempts != maxExternalDispatchAttempts {
		t.Errorf("attempts = %d, want the cap — an unknown assign outcome must not be auto-retried", st.attempts)
	}
	if st.deliveryMode != "platform" {
		t.Errorf("delivery_mode = %q, want platform", st.deliveryMode)
	}
	if st.provider != "" || st.deliveryID != "" {
		t.Errorf("state = %+v, want no provider linkage recorded for an unknown assign", st)
	}
}

// A CLEAN 4xx assign rejection engaged nobody, so it classifies on its status
// code like any other provider rejection.
func TestDispatch_ShipdayCleanAssignRejectionIsPermanent(t *testing.T) {
	e := newDispatcher(t, false, false, true)
	router.on("/on-demand/availability", jsonHandler(200, `[{"id":"e1","name":"Uber","fee":7.00}]`))
	router.on("/orders", jsonHandler(200, `{"success":true,"orderId":778}`))
	router.on("/on-demand/assign", jsonHandler(400, `{"message":"unknown service"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil || !IsPermanent(err) {
		t.Fatalf("want a permanent error, got %v", err)
	}
}

// Regression: a 2xx insert carrying success:false is a validation rejection
// that no retry can fix, but it reaches dispatch as a plain error with no
// status code — so it is classified TRANSIENT and re-attempted up to the cap,
// re-inserting a fresh Shipday order every sweep tick. Documented here as the
// current behavior; see the round report.
func TestDispatch_ShipdayInsertRejectionIsClassifiedTransient(t *testing.T) {
	e := newDispatcher(t, false, false, true)
	router.on("/on-demand/availability", jsonHandler(200, `[{"id":"e1","name":"Uber","fee":7.00}]`))
	router.on("/orders", jsonHandler(200, `{"success":false,"response":"invalid customer address"}`))

	id := seedOrder(t, orderOpts{deliveryMode: "external"})
	_, _, _, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil {
		t.Fatal("want an error")
	}
	st := readOrder(t, id)
	if st.attempts != 1 {
		t.Fatalf("attempts = %d, want 1", st.attempts)
	}
	if IsPermanent(err) != (st.deliveryMode == "platform") {
		t.Fatalf("classification and delivery_mode disagree: permanent=%v mode=%q", IsPermanent(err), st.deliveryMode)
	}
	if !IsPermanent(err) {
		t.Logf("KNOWN GAP: a success:false insert (an unfixable validation rejection) is "+
			"classified transient and will be retried %d times, re-inserting a Shipday order each tick",
			maxExternalDispatchAttempts)
	}
}

// ---- persist step --------------------------------------------------------

// When a PAID delivery exists but the order row can't be updated, Dispatch must
// return BOTH the delivery details and an error — dropping either loses the
// only handle a human has to cancel or reconcile the delivery we were billed
// for.
func TestDispatch_OrphanedDeliverySurfacesIDsWithError(t *testing.T) {
	e := newDispatcher(t, true, false, false)
	router.on("/v1/customers/fake-customer/delivery_quotes", jsonHandler(200, `{"id":"q","fee":799}`))
	id := seedOrder(t, orderOpts{})
	// Steal the claim sentinel while "the provider is creating the delivery",
	// exactly reproducing the lost-sentinel window.
	router.on("/v1/customers/fake-customer/deliveries", func(w http.ResponseWriter, r *http.Request) {
		if _, err := testPool.Exec(context.Background(),
			`UPDATE orders SET external_provider = NULL WHERE id = $1`, id); err != nil {
			t.Errorf("steal sentinel: %v", err)
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "orphan_del", "tracking_url": "https://u/orphan", "fee": 799,
		})
	})

	provider, delID, fee, err := e.Dispatch(context.Background(), baseInput(id))
	if err == nil {
		t.Fatal("a paid delivery that could not be recorded must surface an error")
	}
	if provider != "uber_direct" || delID != "orphan_del" || fee != 799 {
		t.Errorf("got (%q,%q,%d); the caller must still learn what was paid for", provider, delID, fee)
	}
}

func ptr(s string) *string { return &s }
