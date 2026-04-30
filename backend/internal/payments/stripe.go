// Package payments wraps the Stripe SDK for all KosherEats money movement.
//
// Right now this is focused on Stripe Connect — the Express onboarding flow
// that lets couriers receive payouts. Consumer payments (PaymentIntents) are
// still stubbed out in handlers/payments.go and should eventually move here.
//
// Dev stub mode: if STRIPE_SECRET_KEY is empty, every method returns fake
// data instead of calling Stripe, so local development works without real
// keys. Production mode kicks in as soon as the env var is set.
package payments

import (
	"context"
	cryptorand "crypto/rand"
	"fmt"
	"log"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/config"
	"github.com/stripe/stripe-go/v78"
	"github.com/stripe/stripe-go/v78/account"
	"github.com/stripe/stripe-go/v78/accountlink"
	"github.com/stripe/stripe-go/v78/ephemeralkey"
	"github.com/stripe/stripe-go/v78/paymentintent"
	"github.com/stripe/stripe-go/v78/refund"
	"github.com/stripe/stripe-go/v78/setupintent"
	"github.com/stripe/stripe-go/v78/transfer"
	stripecustomer "github.com/stripe/stripe-go/v78/customer"
)

type Client struct {
	cfg     *config.Config
	enabled bool
}

// looksLikeRealStripeKey filters out the "sk_test_your_stripe_secret_key"
// placeholders commonly left in .env.example files. Any real Stripe secret
// key starts with "sk_" and contains either "_live_" or "_test_" followed
// by a reasonably long opaque string — the placeholder is too short to
// match. We flip to dev stub mode if the key doesn't look real.
func looksLikeRealStripeKey(k string) bool {
	if k == "" {
		return false
	}
	// Real keys are >= 30 chars and don't end in "_key".
	if len(k) < 30 {
		return false
	}
	if len(k) >= 4 && k[len(k)-4:] == "_key" {
		return false
	}
	return true
}

func New(cfg *config.Config) *Client {
	c := &Client{cfg: cfg}
	if looksLikeRealStripeKey(cfg.StripeSecretKey) {
		stripe.Key = cfg.StripeSecretKey
		c.enabled = true
	} else {
		log.Println("[stripe] STRIPE_SECRET_KEY not set or placeholder detected — running in dev stub mode")
	}
	return c
}

// CreateExpressAccount creates a Stripe Connect Express account for a courier.
// Express = Stripe hosts the onboarding UI (KYC, bank info, tax) — we just
// redirect the courier to a generated link. Matches how UberEats / DoorDash
// onboard their drivers for direct deposit.
func (c *Client) CreateExpressAccount(email, firstName, lastName, phone string) (string, error) {
	if !c.enabled {
		// Stub: return a fake account id so the rest of the flow works in dev.
		return "acct_stub_" + fakeID(), nil
	}

	params := &stripe.AccountParams{
		Type:    stripe.String(string(stripe.AccountTypeExpress)),
		Country: stripe.String("US"),
		Email:   stripe.String(email),
		Capabilities: &stripe.AccountCapabilitiesParams{
			Transfers: &stripe.AccountCapabilitiesTransfersParams{
				Requested: stripe.Bool(true),
			},
		},
		BusinessType: stripe.String("individual"),
		Individual: &stripe.PersonParams{
			FirstName: stripe.String(firstName),
			LastName:  stripe.String(lastName),
			Email:     stripe.String(email),
			Phone:     stripe.String(phone),
		},
		BusinessProfile: &stripe.AccountBusinessProfileParams{
			ProductDescription: stripe.String("Kosher food delivery courier"),
			MCC:                stripe.String("5812"), // eating places, restaurants
		},
	}

	acct, err := account.New(params)
	if err != nil {
		return "", err
	}
	return acct.ID, nil
}

// CreateAccountLink returns a Stripe-hosted onboarding URL. The iOS app
// opens this in SFSafariViewController. The courier fills out KYC on
// Stripe's domain and returns via the refresh/return URL we supply.
func (c *Client) CreateAccountLink(accountID, returnURL, refreshURL string) (string, error) {
	if !c.enabled {
		return "https://example.com/stub/stripe-onboarding?account=" + accountID, nil
	}

	params := &stripe.AccountLinkParams{
		Account:    stripe.String(accountID),
		RefreshURL: stripe.String(refreshURL),
		ReturnURL:  stripe.String(returnURL),
		Type:       stripe.String("account_onboarding"),
	}
	link, err := accountlink.New(params)
	if err != nil {
		return "", err
	}
	return link.URL, nil
}

// AccountStatus is the subset of Stripe account fields we care about.
type AccountStatus struct {
	ID                 string
	ChargesEnabled     bool
	PayoutsEnabled     bool
	DetailsSubmitted   bool
	RequirementsOpen   bool
}

// GetAccountStatus polls Stripe for the current state of a courier's account.
// Typically called after the courier returns from the hosted onboarding flow
// so we can refresh our local payout_ready flag.
func (c *Client) GetAccountStatus(accountID string) (*AccountStatus, error) {
	if !c.enabled {
		// Stub: pretend stub accounts are fully onboarded after first poll.
		return &AccountStatus{
			ID:               accountID,
			ChargesEnabled:   true,
			PayoutsEnabled:   true,
			DetailsSubmitted: true,
		}, nil
	}

	acct, err := account.GetByID(accountID, nil)
	if err != nil {
		return nil, err
	}
	return &AccountStatus{
		ID:               acct.ID,
		ChargesEnabled:   acct.ChargesEnabled,
		PayoutsEnabled:   acct.PayoutsEnabled,
		DetailsSubmitted: acct.DetailsSubmitted,
		RequirementsOpen: acct.Requirements != nil && len(acct.Requirements.CurrentlyDue) > 0,
	}, nil
}

// RefundPaymentIntent issues a full refund for the given PaymentIntent id.
// Called when an order is rejected (either manually by the seller or
// automatically by the stale-order sweep) so the customer isn't charged for
// food they won't receive. In dev stub mode this just logs so rejection
// flows work without real Stripe keys.
func (c *Client) RefundPaymentIntent(paymentIntentID string) error {
	if !c.enabled {
		log.Printf("[stripe stub] refund payment_intent=%s", paymentIntentID)
		return nil
	}
	if paymentIntentID == "" {
		return fmt.Errorf("refund: empty payment intent id")
	}
	_, err := refund.New(&stripe.RefundParams{
		PaymentIntent: stripe.String(paymentIntentID),
	})
	return err
}

// TransferToCourier sends `amountCents` to the courier's Connect account.
// Called when an order is marked delivered. In prod this is a Stripe Transfer
// which debits the platform balance and credits the connected account. In
// dev stub mode it just logs so the rest of the flow works without real money.
//
// idempotencyKey should be a stable unique identifier (e.g. the payout queue
// row's id) so that retries after a partial failure don't double-send money.
// Pass empty string to skip idempotency (not recommended for production).
func (c *Client) TransferToCourier(accountID string, amountCents int, orderID string, idempotencyKey string) error {
	if !c.enabled {
		log.Printf("[stripe stub] transfer $%d.%02d -> %s for order %s",
			amountCents/100, amountCents%100, accountID, orderID)
		return nil
	}

	if accountID == "" || amountCents <= 0 {
		return fmt.Errorf("invalid transfer parameters")
	}

	params := &stripe.TransferParams{
		Amount:      stripe.Int64(int64(amountCents)),
		Currency:    stripe.String(string(stripe.CurrencyUSD)),
		Destination: stripe.String(accountID),
		TransferGroup: stripe.String("order_" + orderID),
	}
	if idempotencyKey != "" {
		params.IdempotencyKey = stripe.String(idempotencyKey)
	}
	_, err := transfer.New(params)
	return err
}

// PaymentSheetBundle is everything iOS's StripePaymentSheet needs to initialize:
// a Customer id, an ephemeral key for that customer (so the sheet can list and
// save cards), and a PaymentIntent client_secret for this particular order.
//
// This matches the "mobile integration" pattern in Stripe's docs — we return
// all three in one API call so the iOS checkout flow only has to hit us once.
type PaymentSheetBundle struct {
	PaymentIntentSecret string `json:"payment_intent_secret"`
	EphemeralKeySecret  string `json:"ephemeral_key_secret"`
	CustomerID          string `json:"customer_id"`
	PublishableKey      string `json:"publishable_key"`
	// Populated when the customer has a saved card on file. The iOS checkout
	// uses these to render a "Paying with •••• 4242" preview so the user knows
	// which card PaymentSheet will default to without having to open the sheet.
	DefaultCardBrand string `json:"default_card_brand,omitempty"`
	DefaultCardLast4 string `json:"default_card_last4,omitempty"`
}

// GetOrCreateCustomer looks up the Stripe Customer id cached on the users
// row; if the user has never had one (new account, or first checkout after
// the stripe_customer_id migration), it creates a Customer and persists the
// id back. Subsequent calls return the same id so saved payment methods
// persist across orders and are visible in the profile's Payment Methods
// screen.
//
// In stub mode this just returns a stub id without touching the DB.
func (c *Client) GetOrCreateCustomer(ctx context.Context, pool *pgxpool.Pool, userID, email, name string) (string, error) {
	if !c.enabled {
		return "cus_stub_" + fakeID(), nil
	}

	var existing *string
	err := pool.QueryRow(ctx,
		`SELECT stripe_customer_id FROM users WHERE id = $1`, userID,
	).Scan(&existing)
	if err == nil && existing != nil && *existing != "" {
		return *existing, nil
	}

	cust, err := stripecustomer.New(&stripe.CustomerParams{
		Email: stripe.String(email),
		Name:  stripe.String(name),
		Params: stripe.Params{
			Metadata: map[string]string{"user_id": userID},
		},
	})
	if err != nil {
		return "", fmt.Errorf("create customer: %w", err)
	}

	if _, err := pool.Exec(ctx,
		`UPDATE users SET stripe_customer_id = $1, updated_at = NOW() WHERE id = $2`,
		cust.ID, userID,
	); err != nil {
		// Non-fatal — we can still return the customer id even if caching
		// fails. Next call will just create another customer, which Stripe
		// permits. Log so ops notices if this is systematic.
		log.Printf("[stripe] persist customer id for user=%s: %v", userID, err)
	}
	return cust.ID, nil
}

// CreatePaymentSheet builds everything iOS needs for one PaymentSheet invocation.
// Uses the persistent Stripe Customer (see GetOrCreateCustomer) so saved
// cards carry over between checkouts and are visible in Profile → Payment
// Methods.
//
// In dev stub mode (no STRIPE_SECRET_KEY), returns fake values. The iOS app
// detects the stub prefix and skips actually presenting PaymentSheet, which
// keeps local dev functional without real Stripe keys.
func (c *Client) CreatePaymentSheet(ctx context.Context, pool *pgxpool.Pool, amountCents int, userID, email, name string) (*PaymentSheetBundle, error) {
	if !c.enabled {
		return &PaymentSheetBundle{
			PaymentIntentSecret: "pi_stub_" + fakeID() + "_secret_stub",
			EphemeralKeySecret:  "ek_stub_" + fakeID(),
			CustomerID:          "cus_stub_" + fakeID(),
			PublishableKey:      "pk_stub_dev",
		}, nil
	}

	customerID, err := c.GetOrCreateCustomer(ctx, pool, userID, email, name)
	if err != nil {
		return nil, err
	}

	// Ephemeral key lets the iOS PaymentSheet fetch this customer's saved
	// payment methods directly from Stripe without exposing our secret key.
	ek, err := ephemeralkey.New(&stripe.EphemeralKeyParams{
		Customer:      stripe.String(customerID),
		StripeVersion: stripe.String("2024-06-20"),
	})
	if err != nil {
		return nil, fmt.Errorf("create ephemeral key: %w", err)
	}

	// Restrict to card only. Apple Pay rides on top of "card" in Stripe's
	// model (it's a tokenized card), so the iOS PaymentSheet still surfaces
	// it when the device is Apple Pay capable — but bank debits, Amazon Pay,
	// and Cash App don't appear.
	//
	// SetupFutureUsage = off_session tells Stripe to save the card to the
	// Customer after a successful charge, so the next checkout can pick it
	// from "Saved" without re-entering details. The iOS PaymentSheet also
	// sets the newly added method as the Customer's default automatically,
	// which satisfies the "most recent payment = default" requirement.
	pi, err := paymentintent.New(&stripe.PaymentIntentParams{
		Amount:             stripe.Int64(int64(amountCents)),
		Currency:           stripe.String(string(stripe.CurrencyUSD)),
		Customer:           stripe.String(customerID),
		PaymentMethodTypes: stripe.StringSlice([]string{"card"}),
		SetupFutureUsage:   stripe.String("off_session"),
		Params: stripe.Params{
			Metadata: map[string]string{"user_id": userID},
		},
	})
	if err != nil {
		return nil, fmt.Errorf("create payment intent: %w", err)
	}

	brand, last4 := lookupDefaultCard(customerID)

	return &PaymentSheetBundle{
		PaymentIntentSecret: pi.ClientSecret,
		EphemeralKeySecret:  ek.Secret,
		CustomerID:          customerID,
		PublishableKey:      c.cfg.StripePublishableKey,
		DefaultCardBrand:    brand,
		DefaultCardLast4:    last4,
	}, nil
}

// lookupDefaultCard returns the brand + last4 for the customer's
// `invoice_settings.default_payment_method`, which PaymentSheet automatically
// populates on the first successful off-session setup. Returns empty strings
// if the customer has no saved card yet or the lookup fails — iOS treats
// those as "no preview to show".
func lookupDefaultCard(customerID string) (brand, last4 string) {
	params := &stripe.CustomerParams{}
	params.AddExpand("invoice_settings.default_payment_method")
	cust, err := stripecustomer.Get(customerID, params)
	if err != nil || cust == nil || cust.InvoiceSettings == nil {
		return "", ""
	}
	pm := cust.InvoiceSettings.DefaultPaymentMethod
	if pm == nil || pm.Card == nil {
		return "", ""
	}
	return string(pm.Card.Brand), pm.Card.Last4
}

// CustomerSheetBundle is what iOS's STPCustomerSheet needs to manage saved
// payment methods outside of a checkout flow: the persistent Customer id,
// a fresh ephemeral key scoped to that customer, and the publishable key.
// No PaymentIntent — CustomerSheet uses SetupIntents instead (see
// CreateSetupIntent below).
type CustomerSheetBundle struct {
	CustomerID         string `json:"customer_id"`
	EphemeralKeySecret string `json:"ephemeral_key_secret"`
	PublishableKey     string `json:"publishable_key"`
}

// CreateCustomerBundle returns everything iOS's STPCustomerSheet needs to
// list/add/delete the user's saved payment methods on the profile screen.
func (c *Client) CreateCustomerBundle(ctx context.Context, pool *pgxpool.Pool, userID, email, name string) (*CustomerSheetBundle, error) {
	if !c.enabled {
		return &CustomerSheetBundle{
			CustomerID:         "cus_stub_" + fakeID(),
			EphemeralKeySecret: "ek_stub_" + fakeID(),
			PublishableKey:     "pk_stub_dev",
		}, nil
	}

	customerID, err := c.GetOrCreateCustomer(ctx, pool, userID, email, name)
	if err != nil {
		return nil, err
	}

	ek, err := ephemeralkey.New(&stripe.EphemeralKeyParams{
		Customer:      stripe.String(customerID),
		StripeVersion: stripe.String("2024-06-20"),
	})
	if err != nil {
		return nil, fmt.Errorf("create ephemeral key: %w", err)
	}

	return &CustomerSheetBundle{
		CustomerID:         customerID,
		EphemeralKeySecret: ek.Secret,
		PublishableKey:     c.cfg.StripePublishableKey,
	}, nil
}

// CreateSetupIntent returns a SetupIntent client_secret for the user's
// persistent Stripe Customer. STPCustomerSheet uses this to let the user
// add a new card without charging it.
func (c *Client) CreateSetupIntent(ctx context.Context, pool *pgxpool.Pool, userID, email, name string) (string, error) {
	if !c.enabled {
		return "seti_stub_" + fakeID() + "_secret_stub", nil
	}

	customerID, err := c.GetOrCreateCustomer(ctx, pool, userID, email, name)
	if err != nil {
		return "", err
	}

	si, err := setupintent.New(&stripe.SetupIntentParams{
		Customer: stripe.String(customerID),
		AutomaticPaymentMethods: &stripe.SetupIntentAutomaticPaymentMethodsParams{
			Enabled: stripe.Bool(true),
		},
		Usage: stripe.String("off_session"),
	})
	if err != nil {
		return "", fmt.Errorf("create setup intent: %w", err)
	}
	return si.ClientSecret, nil
}

// fakeID generates a short random id for dev stubs using crypto/rand.
func fakeID() string {
	const charset = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, 10)
	if _, err := cryptorand.Read(b); err != nil {
		// Fallback: if crypto/rand fails, return a timestamp-based id.
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	for i := range b {
		b[i] = charset[int(b[i])%len(charset)]
	}
	return string(b)
}
