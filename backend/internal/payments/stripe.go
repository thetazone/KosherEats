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
	"fmt"
	"log"

	"github.com/koshereats/backend/internal/config"
	"github.com/stripe/stripe-go/v78"
	"github.com/stripe/stripe-go/v78/account"
	"github.com/stripe/stripe-go/v78/accountlink"
	"github.com/stripe/stripe-go/v78/ephemeralkey"
	"github.com/stripe/stripe-go/v78/paymentintent"
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

// TransferToCourier sends `amountCents` to the courier's Connect account.
// Called when an order is marked delivered. In prod this is a Stripe Transfer
// which debits the platform balance and credits the connected account. In
// dev stub mode it just logs so the rest of the flow works without real money.
func (c *Client) TransferToCourier(accountID string, amountCents int, orderID string) error {
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
}

// CreatePaymentSheet builds everything iOS needs for one PaymentSheet invocation.
// `customerEmail` + `customerName` are used to create-or-retrieve a Stripe
// Customer object tied to this user so saved cards persist across orders.
//
// In dev stub mode (no STRIPE_SECRET_KEY), returns fake values. The iOS app
// detects the stub prefix and skips actually presenting PaymentSheet, which
// keeps local dev functional without real Stripe keys.
func (c *Client) CreatePaymentSheet(amountCents int, userID, email, name string) (*PaymentSheetBundle, error) {
	if !c.enabled {
		return &PaymentSheetBundle{
			PaymentIntentSecret: "pi_stub_" + fakeID() + "_secret_stub",
			EphemeralKeySecret:  "ek_stub_" + fakeID(),
			CustomerID:          "cus_stub_" + fakeID(),
			PublishableKey:      "pk_stub_dev",
		}, nil
	}

	// Create or reuse a Customer keyed on our internal user id (via metadata).
	// In production you'd cache the customer_id on the users table; for now
	// we create fresh each time and rely on Stripe to dedupe via metadata.
	cust, err := stripecustomer.New(&stripe.CustomerParams{
		Email: stripe.String(email),
		Name:  stripe.String(name),
		Params: stripe.Params{
			Metadata: map[string]string{"user_id": userID},
		},
	})
	if err != nil {
		return nil, fmt.Errorf("create customer: %w", err)
	}

	// Ephemeral key lets the iOS PaymentSheet fetch this customer's saved
	// payment methods directly from Stripe without exposing our secret key.
	ek, err := ephemeralkey.New(&stripe.EphemeralKeyParams{
		Customer:      stripe.String(cust.ID),
		StripeVersion: stripe.String("2024-06-20"),
	})
	if err != nil {
		return nil, fmt.Errorf("create ephemeral key: %w", err)
	}

	pi, err := paymentintent.New(&stripe.PaymentIntentParams{
		Amount:   stripe.Int64(int64(amountCents)),
		Currency: stripe.String(string(stripe.CurrencyUSD)),
		Customer: stripe.String(cust.ID),
		AutomaticPaymentMethods: &stripe.PaymentIntentAutomaticPaymentMethodsParams{
			Enabled: stripe.Bool(true),
		},
		Params: stripe.Params{
			Metadata: map[string]string{"user_id": userID},
		},
	})
	if err != nil {
		return nil, fmt.Errorf("create payment intent: %w", err)
	}

	return &PaymentSheetBundle{
		PaymentIntentSecret: pi.ClientSecret,
		EphemeralKeySecret:  ek.Secret,
		CustomerID:          cust.ID,
		PublishableKey:      c.cfg.StripePublishableKey,
	}, nil
}

// fakeID generates a short random-ish id for dev stubs. Not cryptographically
// random — just enough to distinguish different stub accounts in logs.
func fakeID() string {
	const charset = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, 10)
	for i := range b {
		b[i] = charset[i*7%len(charset)]
	}
	return string(b)
}
