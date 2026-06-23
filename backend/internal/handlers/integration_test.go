package handlers

// Integration test harness + critical-path tests.
//
// These tests exercise real HTTP handlers against a real Postgres database via
// httptest. They are the first DB-backed tests in the repo (social_auth_test.go
// only covers pure functions), so this file also carries the shared harness:
// connect/create the test DB, run migrations, build a *Handler, seed minimal
// fixtures, and reset state between tests.
//
// Run them with:
//
//	cd backend && go test ./internal/handlers/ -run Integration
//
// The DB is taken from TEST_DATABASE_URL, defaulting to
// postgres://postgres:postgres@localhost:5433/koshereats_test?sslmode=disable
// (the project's docker-compose Postgres on host port 5433). If that database
// does not exist the harness creates it by connecting to the maintenance
// `postgres` database on the same server. Stripe runs in dev stub mode (no
// STRIPE_SECRET_KEY), so VerifyPaymentSucceeded always passes.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/database"
)

const defaultTestDatabaseURL = "postgres://postgres:postgres@localhost:5433/koshereats_test?sslmode=disable"

// testEnv holds everything a test needs: the built Handler, a chi router wired
// with the same routes/middleware as production for the paths under test, and
// the IDs of the seeded fixtures.
type testEnv struct {
	h      *Handler
	router http.Handler

	// Seeded fixture IDs.
	approvedRestID string // approved, active, kosher restaurant (menu visible)
	pendingRestID  string // pending-approval restaurant (menu must 404)
	otherRestID    string // a second approved restaurant for cross-restaurant tests
	menuItemID     string // an available item on approvedRestID
	otherItemID    string // an available item on otherRestID
}

// harness is the process-wide shared environment, built once in TestMain.
var harness *testEnv

func TestMain(m *testing.M) {
	dbURL := os.Getenv("TEST_DATABASE_URL")
	if dbURL == "" {
		dbURL = defaultTestDatabaseURL
	}

	// If the target database doesn't exist yet, create it by connecting to the
	// maintenance `postgres` database on the same server. This keeps the suite
	// runnable on a fresh checkout with only an empty Postgres server up.
	if err := ensureDatabaseExists(dbURL); err != nil {
		fmt.Fprintf(os.Stderr, "integration harness: ensure database: %v\n"+
			"  (is Postgres running? default is the project's docker-compose DB on :5433)\n", err)
		os.Exit(1)
	}

	db, err := database.Connect(dbURL)
	if err != nil {
		fmt.Fprintf(os.Stderr, "integration harness: connect %s: %v\n", dbURL, err)
		os.Exit(1)
	}
	defer db.Close()

	ctx := context.Background()
	if err := db.RunMigrations(ctx, migrationsDir()); err != nil {
		fmt.Fprintf(os.Stderr, "integration harness: run migrations: %v\n", err)
		os.Exit(1)
	}

	cfg := &config.Config{
		// A non-empty JWT secret so generated tokens verify in AuthMiddleware.
		JWTSecret:      "integration-test-secret",
		TaxRatePercent: 9,
		// StripeSecretKey intentionally empty -> payments.Client runs in dev
		// stub mode, so VerifyPaymentSucceeded always returns nil.
	}

	h := New(db, cfg)

	harness = &testEnv{
		h:      h,
		router: buildRouter(h),
	}

	if err := harness.seed(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "integration harness: seed: %v\n", err)
		os.Exit(1)
	}

	os.Exit(m.Run())
}

// ensureDatabaseExists creates the target database if it is missing. It parses
// the database name out of dbURL, connects to the maintenance `postgres`
// database on the same server, and issues CREATE DATABASE if needed.
func ensureDatabaseExists(dbURL string) error {
	cfg, err := pgx.ParseConfig(dbURL)
	if err != nil {
		return fmt.Errorf("parse url: %w", err)
	}
	target := cfg.Database
	if target == "" {
		return fmt.Errorf("no database name in TEST_DATABASE_URL")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Try connecting directly first — if it works, the DB already exists.
	if pool, err := pgxpool.New(ctx, dbURL); err == nil {
		pingErr := pool.Ping(ctx)
		pool.Close()
		if pingErr == nil {
			return nil
		}
	}

	// Connect to the maintenance DB and create the target.
	adminCfg := cfg.Copy()
	adminCfg.Database = "postgres"
	adminConn, err := pgx.ConnectConfig(ctx, adminCfg)
	if err != nil {
		return fmt.Errorf("connect maintenance db: %w", err)
	}
	defer adminConn.Close(ctx)

	var exists bool
	if err := adminConn.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname = $1)`, target,
	).Scan(&exists); err != nil {
		return fmt.Errorf("check database exists: %w", err)
	}
	if exists {
		return nil
	}
	// Identifiers can't be parameterized; target comes from our own env/default,
	// not user input. Quote defensively all the same.
	if _, err := adminConn.Exec(ctx, fmt.Sprintf(`CREATE DATABASE %s`, pgx.Identifier{target}.Sanitize())); err != nil {
		return fmt.Errorf("create database %q: %w", target, err)
	}
	return nil
}

// migrationsDir resolves the path to the migrations directory relative to this
// source file, so the suite works regardless of the test's working directory.
func migrationsDir() string {
	_, thisFile, _, _ := runtime.Caller(0)
	// internal/handlers/integration_test.go -> internal/database/migrations
	return filepath.Join(filepath.Dir(thisFile), "..", "database", "migrations")
}

// buildRouter wires the subset of production routes the critical-path tests
// hit, using the same middleware (AuthMiddleware / OptionalAuthMiddleware) and
// URL-param shapes ({id}, {pi}) as cmd/api/main.go.
func buildRouter(h *Handler) http.Handler {
	r := chi.NewRouter()

	r.Post("/api/v1/auth/register", h.Register)
	r.Post("/api/v1/auth/login", h.Login)

	r.Route("/api/v1/restaurants", func(r chi.Router) {
		r.Use(h.OptionalAuthMiddleware)
		r.Get("/{id}/menu", h.GetMenu)
	})

	r.Route("/api/v1/orders", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Post("/", h.CreateOrder)
		r.Get("/by-payment-intent/{pi}", h.GetOrderByPaymentIntent)
	})

	r.Route("/api/v1/cart", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Post("/items", h.AddToCart)
	})

	return r
}

// ---- fixtures ------------------------------------------------------------

// seed inserts the minimal fixtures every test relies on:
//   - one approved + active kosher restaurant (menu visible) with a category
//     and an available menu item
//   - one pending-approval restaurant (menu must 404)
//   - a second approved restaurant with its own item (cross-restaurant tests)
//
// A throwaway owner user backs the restaurants (owner_id is a NOT NULL FK).
// Per-test consumer users are created inside each test via /auth/register so
// they don't collide.
func (e *testEnv) seed(ctx context.Context) error {
	pool := e.h.db.Pool

	var ownerID string
	if err := pool.QueryRow(ctx,
		`INSERT INTO users (email, password_hash, first_name, last_name, phone, role, vertical)
		 VALUES ($1, '', 'Owner', 'User', '', 'seller', 'kosher') RETURNING id`,
		uniqueEmail("owner"),
	).Scan(&ownerID); err != nil {
		return fmt.Errorf("seed owner: %w", err)
	}

	approvedID, approvedItemID, err := e.seedRestaurant(ctx, ownerID, "Approved Deli", "approved")
	if err != nil {
		return fmt.Errorf("seed approved restaurant: %w", err)
	}
	pendingID, _, err := e.seedRestaurant(ctx, ownerID, "Pending Deli", "pending")
	if err != nil {
		return fmt.Errorf("seed pending restaurant: %w", err)
	}
	otherID, otherItemID, err := e.seedRestaurant(ctx, ownerID, "Other Deli", "approved")
	if err != nil {
		return fmt.Errorf("seed other restaurant: %w", err)
	}

	e.approvedRestID = approvedID
	e.pendingRestID = pendingID
	e.otherRestID = otherID
	e.menuItemID = approvedItemID
	e.otherItemID = otherItemID
	return nil
}

// seedRestaurant inserts a restaurant in the given approval state plus one
// category and one available menu item, returning the restaurant + item IDs.
func (e *testEnv) seedRestaurant(ctx context.Context, ownerID, name, approval string) (restID, itemID string, err error) {
	pool := e.h.db.Pool
	if err = pool.QueryRow(ctx,
		`INSERT INTO restaurants
		   (owner_id, name, street, city, state, zip_code, is_active, is_open,
		    approval_status, vertical)
		 VALUES ($1, $2, '1 Main St', 'Brooklyn', 'NY', '11218', true, true, $3, 'kosher')
		 RETURNING id`,
		ownerID, name, approval,
	).Scan(&restID); err != nil {
		return "", "", fmt.Errorf("insert restaurant: %w", err)
	}

	var categoryID string
	if err = pool.QueryRow(ctx,
		`INSERT INTO menu_categories (restaurant_id, name, sort_order)
		 VALUES ($1, 'Mains', 0) RETURNING id`, restID,
	).Scan(&categoryID); err != nil {
		return "", "", fmt.Errorf("insert category: %w", err)
	}

	if err = pool.QueryRow(ctx,
		`INSERT INTO menu_items
		   (restaurant_id, category_id, name, price, is_available, sort_order)
		 VALUES ($1, $2, 'Pastrami Sandwich', 1500, true, 0) RETURNING id`,
		restID, categoryID,
	).Scan(&itemID); err != nil {
		return "", "", fmt.Errorf("insert menu item: %w", err)
	}
	return restID, itemID, nil
}

// resetVolatile truncates the per-test tables (carts, orders, …) between tests
// so order/cart state from one test never leaks into another. The seeded
// fixtures (users, restaurants, menus) are preserved.
func (e *testEnv) resetVolatile(t *testing.T) {
	t.Helper()
	_, err := e.h.db.Pool.Exec(context.Background(),
		`TRUNCATE cart_items, carts, order_items, orders RESTART IDENTITY CASCADE`)
	if err != nil {
		t.Fatalf("reset volatile tables: %v", err)
	}
}

// uniqueEmail returns a per-run-unique email so re-running the suite against a
// persistent DB (we don't drop it) never trips the (email, role, vertical)
// unique index.
func uniqueEmail(prefix string) string {
	return fmt.Sprintf("%s+%d@example.com", prefix, time.Now().UnixNano())
}

// ---- HTTP helpers --------------------------------------------------------

func (e *testEnv) do(method, path, token string, body any) *httptest.ResponseRecorder {
	var rdr *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rdr = bytes.NewReader(b)
	} else {
		rdr = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, path, rdr)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	e.router.ServeHTTP(rec, req)
	return rec
}

// registerUser hits /auth/register and returns the access token + user id.
func (e *testEnv) registerUser(t *testing.T, prefix string) (token, userID string) {
	t.Helper()
	email := uniqueEmail(prefix)
	rec := e.do(http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email":      email,
		"password":   "password123",
		"first_name": "Test",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("register %s: status %d, body %s", prefix, rec.Code, rec.Body.String())
	}
	var resp AuthResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("register %s: decode: %v", prefix, err)
	}
	if resp.Token == "" {
		t.Fatalf("register %s: empty token", prefix)
	}
	return resp.Token, resp.User.ID
}

// ---- tests ---------------------------------------------------------------

// (1) register then login returns a usable token.
func TestIntegration_RegisterThenLoginReturnsToken(t *testing.T) {
	harness.resetVolatile(t)

	email := uniqueEmail("login-flow")
	regRec := harness.do(http.MethodPost, "/api/v1/auth/register", "", map[string]any{
		"email":      email,
		"password":   "password123",
		"first_name": "Login",
	})
	if regRec.Code != http.StatusCreated {
		t.Fatalf("register: status %d, body %s", regRec.Code, regRec.Body.String())
	}
	var reg AuthResponse
	if err := json.Unmarshal(regRec.Body.Bytes(), &reg); err != nil {
		t.Fatalf("register decode: %v", err)
	}
	if reg.Token == "" || reg.RefreshToken == "" {
		t.Fatalf("register returned empty tokens: %+v", reg)
	}
	if reg.User.Email != email {
		t.Fatalf("register user email = %q, want %q", reg.User.Email, email)
	}

	loginRec := harness.do(http.MethodPost, "/api/v1/auth/login", "", map[string]any{
		"email":    email,
		"password": "password123",
	})
	if loginRec.Code != http.StatusOK {
		t.Fatalf("login: status %d, body %s", loginRec.Code, loginRec.Body.String())
	}
	var login AuthResponse
	if err := json.Unmarshal(loginRec.Body.Bytes(), &login); err != nil {
		t.Fatalf("login decode: %v", err)
	}
	if login.Token == "" {
		t.Fatalf("login returned empty token")
	}

	// The login token must actually authenticate a protected route. A 200 here
	// proves the token verifies in AuthMiddleware (any non-401 would do).
	probe := harness.do(http.MethodGet, "/api/v1/orders/by-payment-intent/nope", login.Token, nil)
	if probe.Code == http.StatusUnauthorized {
		t.Fatalf("login token rejected by AuthMiddleware: %s", probe.Body.String())
	}
}

// (2) GetMenu returns 404 for a pending restaurant but 200 for an approved one.
func TestIntegration_GetMenuVisibilityGate(t *testing.T) {
	harness.resetVolatile(t)

	approved := harness.do(http.MethodGet,
		"/api/v1/restaurants/"+harness.approvedRestID+"/menu", "", nil)
	if approved.Code != http.StatusOK {
		t.Fatalf("approved menu: status %d, body %s", approved.Code, approved.Body.String())
	}

	pending := harness.do(http.MethodGet,
		"/api/v1/restaurants/"+harness.pendingRestID+"/menu", "", nil)
	if pending.Code != http.StatusNotFound {
		t.Fatalf("pending menu: status %d (want 404), body %s", pending.Code, pending.Body.String())
	}
}

// (3) CreateOrder twice with the same payment_intent_id returns the SAME order
// (idempotent replay), not a duplicate.
func TestIntegration_CreateOrderIdempotentReplay(t *testing.T) {
	harness.resetVolatile(t)

	token, _ := harness.registerUser(t, "buyer")
	pi := fmt.Sprintf("pi_replay_%d", time.Now().UnixNano())

	firstID := harness.placeOrder(t, token, harness.approvedRestID, harness.menuItemID, pi)

	// Re-add to cart (the first order consumed it) and replay CreateOrder with
	// the SAME payment_intent_id. The handler must return the existing order.
	harness.addToCart(t, token, harness.approvedRestID, harness.menuItemID)
	rec := harness.do(http.MethodPost, "/api/v1/orders/", token, harness.orderPayload(pi))
	if rec.Code != http.StatusOK { // replay path returns 200, not 201
		t.Fatalf("replay order: status %d (want 200), body %s", rec.Code, rec.Body.String())
	}
	var replay struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &replay); err != nil {
		t.Fatalf("replay decode: %v", err)
	}
	if replay.ID != firstID {
		t.Fatalf("replay returned order %q, want same as first %q", replay.ID, firstID)
	}

	// Belt-and-suspenders: exactly one order row exists for this PaymentIntent.
	var count int
	if err := harness.h.db.Pool.QueryRow(context.Background(),
		`SELECT COUNT(*) FROM orders WHERE stripe_payment_id = $1`, pi,
	).Scan(&count); err != nil {
		t.Fatalf("count orders: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected exactly 1 order for payment intent, got %d", count)
	}
}

// (4) GET /orders/by-payment-intent/{pi} is user-scoped: a different user gets
// 404 for an order they don't own.
func TestIntegration_OrderByPaymentIntentIsUserScoped(t *testing.T) {
	harness.resetVolatile(t)

	ownerTok, _ := harness.registerUser(t, "owner-of-order")
	pi := fmt.Sprintf("pi_scope_%d", time.Now().UnixNano())
	harness.placeOrder(t, ownerTok, harness.approvedRestID, harness.menuItemID, pi)

	// The owning user can fetch it.
	mine := harness.do(http.MethodGet, "/api/v1/orders/by-payment-intent/"+pi, ownerTok, nil)
	if mine.Code != http.StatusOK {
		t.Fatalf("owner lookup: status %d (want 200), body %s", mine.Code, mine.Body.String())
	}

	// A different user must NOT — the global unique index would leak the order
	// without the user_id filter (an IDOR). Expect 404.
	otherTok, _ := harness.registerUser(t, "other-user")
	theirs := harness.do(http.MethodGet, "/api/v1/orders/by-payment-intent/"+pi, otherTok, nil)
	if theirs.Code != http.StatusNotFound {
		t.Fatalf("cross-user lookup: status %d (want 404), body %s", theirs.Code, theirs.Body.String())
	}
}

// (5) AddToCart rejects a menu item that belongs to a different restaurant.
func TestIntegration_AddToCartRejectsCrossRestaurantItem(t *testing.T) {
	harness.resetVolatile(t)

	token, _ := harness.registerUser(t, "cart-user")

	// menuItemID belongs to approvedRestID; claim it lives at otherRestID.
	rec := harness.do(http.MethodPost, "/api/v1/cart/items", token, AddToCartRequest{
		MenuItemID:   harness.menuItemID,
		RestaurantID: harness.otherRestID,
		Quantity:     1,
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("cross-restaurant add: status %d (want 400), body %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "menu item not found") {
		t.Fatalf("cross-restaurant add: unexpected error body %s", rec.Body.String())
	}

	// Sanity: the same item DOES add when restaurant_id matches.
	ok := harness.do(http.MethodPost, "/api/v1/cart/items", token, AddToCartRequest{
		MenuItemID:   harness.menuItemID,
		RestaurantID: harness.approvedRestID,
		Quantity:     1,
	})
	if ok.Code != http.StatusOK {
		t.Fatalf("matching add: status %d (want 200), body %s", ok.Code, ok.Body.String())
	}
}

// ---- order helpers -------------------------------------------------------

// orderPayload builds a pickup CreateOrderRequest body. Pickup avoids the
// delivery-fee quote (which would make a network call) and requires no address.
func (e *testEnv) orderPayload(pi string) map[string]any {
	return map[string]any{
		"restaurant_id":     e.approvedRestID,
		"payment_intent_id": pi,
		"fulfillment_type":  "pickup",
	}
}

// addToCart adds one of the given item to the user's cart, failing the test on
// any non-200.
func (e *testEnv) addToCart(t *testing.T, token, restID, itemID string) {
	t.Helper()
	rec := e.do(http.MethodPost, "/api/v1/cart/items", token, AddToCartRequest{
		MenuItemID:   itemID,
		RestaurantID: restID,
		Quantity:     1,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("add to cart: status %d, body %s", rec.Code, rec.Body.String())
	}
}

// placeOrder adds an item to the cart and creates an order, returning the new
// order id. Asserts the create returns 201.
func (e *testEnv) placeOrder(t *testing.T, token, restID, itemID, pi string) string {
	t.Helper()
	e.addToCart(t, token, restID, itemID)
	rec := e.do(http.MethodPost, "/api/v1/orders/", token, map[string]any{
		"restaurant_id":     restID,
		"payment_intent_id": pi,
		"fulfillment_type":  "pickup",
	})
	if rec.Code != http.StatusCreated {
		t.Fatalf("create order: status %d (want 201), body %s", rec.Code, rec.Body.String())
	}
	var order struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &order); err != nil {
		t.Fatalf("create order decode: %v", err)
	}
	if order.ID == "" {
		t.Fatalf("create order returned empty id, body %s", rec.Body.String())
	}
	return order.ID
}
