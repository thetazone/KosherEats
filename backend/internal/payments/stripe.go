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
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"net/mail"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/koshereats/backend/internal/config"
	"github.com/stripe/stripe-go/v78"
	"github.com/stripe/stripe-go/v78/account"
	"github.com/stripe/stripe-go/v78/accountlink"
	stripecustomer "github.com/stripe/stripe-go/v78/customer"
	"github.com/stripe/stripe-go/v78/ephemeralkey"
	"github.com/stripe/stripe-go/v78/paymentintent"
	"github.com/stripe/stripe-go/v78/refund"
	"github.com/stripe/stripe-go/v78/setupintent"
	"github.com/stripe/stripe-go/v78/transfer"
)

type Client struct {
	cfg     *config.Config
	enabled bool
}

// orphanMarkerKey / orphanMarkerValue is the metadata stamp we put on every
// consumer-checkout PaymentIntent we create (see CreatePaymentSheet). It's the
// "created-by-us" marker the orphan-payment reconcile sweep uses to be sure a
// charged-but-unmatched PaymentIntent is ours before refunding it. Listing the
// PaymentIntents API can't filter by metadata server-side, so the sweep filters
// on this stamp client-side and refuses to touch anything that lacks it.
const (
	orphanMarkerKey   = "kosher_eats_checkout"
	orphanMarkerValue = "1"
)

// deliveryFeeMetaKey is the PaymentIntent metadata key under which we record the
// delivery fee the charge total was computed against. CreateOrder reads it back
// (StampedDeliveryFee) so the order it records is priced against the same fee
// that was charged — never a fresh, slightly-different live courier quote.
const deliveryFeeMetaKey = "delivery_fee"

// fulfillmentMetaKey records the fulfillment_type the PaymentIntent was priced
// for. CreateOrder rejects an order whose fulfillment_type doesn't match, so a
// client can't mint a cheap pickup PI (delivery_fee = 0) and then redeem it on a
// delivery order for free delivery.
const fulfillmentMetaKey = "fulfillment_type"

// deliveryAddrMetaKey records a hash of the delivery destination the
// PaymentIntent's delivery fee was quoted against. The fee depends on the
// distance to that address; binding it stops the "quote near, deliver far"
// exploit — a client could otherwise quote a cheap fee against a nearby address,
// then submit CreateOrder with the same PI but a distant address. CreateOrder
// reuses the stamped (cheap) fee verbatim, so without this guard the platform
// eats the real distance cost on the external dispatch (or under-pays its own
// courier). CreateOrder rejects a delivery order whose destination doesn't match
// this stamp — the exact analogue of fulfillmentMetaKey. We store a hash, not the
// raw address, to keep PII out of Stripe metadata.
const deliveryAddrMetaKey = "delivery_addr_hash"

// DeliveryAddrHash normalizes a free-text delivery address (lowercase, trimmed,
// internal whitespace collapsed) and returns a stable hex SHA-256 fingerprint of
// it. Both CreatePaymentSheet (stamp) and CreateOrder (verify) derive the
// fingerprint the same way, so the legitimate flow — where the same selected
// address is sent to both endpoints — always matches, while a swapped address
// does not. Empty in → empty out (pickup orders / pre-stamp PIs carry no stamp).
func DeliveryAddrHash(addr string) string {
	norm := strings.Join(strings.Fields(strings.ToLower(addr)), " ")
	if norm == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(norm))
	return hex.EncodeToString(sum[:])
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

// HasRealKey reports whether the configured Stripe secret key is a real key
// rather than empty/placeholder — i.e. whether New() will run in live mode
// instead of the dev stub. Lets startup hard-fail when prod is misconfigured:
// stub mode makes VerifyPaymentSucceeded a no-op, so a missing key in prod = free
// orders at attacker-chosen totals.
func HasRealKey(cfg *config.Config) bool { return looksLikeRealStripeKey(cfg.StripeSecretKey) }

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
	ID               string
	ChargesEnabled   bool
	PayoutsEnabled   bool
	DetailsSubmitted bool
	RequirementsOpen bool
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

// VerifyPaymentSucceeded checks that a PaymentIntent has actually succeeded
// and matches the expected user and amount. In dev stub mode it always passes.
func (c *Client) VerifyPaymentSucceeded(paymentIntentID, userID string, expectedAmountCents int) error {
	if !c.enabled {
		log.Printf("[stripe stub] verify payment_intent=%s user=%s amount=%d", paymentIntentID, userID, expectedAmountCents)
		return nil
	}
	// Expand the latest charge so we can inspect its refund state. A fully
	// refunded PaymentIntent KEEPS status 'succeeded' (the refund lives on the
	// Charge, not the PI), so without this a PI that the orphan sweep already
	// refunded would still pass verification and create a free order.
	params := &stripe.PaymentIntentParams{}
	params.AddExpand("latest_charge")
	pi, err := paymentintent.Get(paymentIntentID, params)
	if err != nil {
		return fmt.Errorf("retrieve payment intent: %w", err)
	}
	return verifyPI(pi, userID, expectedAmountCents)
}

// StampedDeliveryFee returns the delivery fee (in cents) that the given
// PaymentIntent was created against, read from the metadata CreatePaymentSheet
// stamps. ok is false when the PI predates this stamp (older in-flight
// checkouts) or in dev stub mode — callers then fall back to re-quoting. This
// is what lets CreateOrder price the order against the exact fee that was
// charged, instead of a fresh live courier quote that drifts by a few cents and
// trips the amount-match guard.
func (c *Client) StampedDeliveryFee(paymentIntentID string) (cents int, ok bool, err error) {
	if !c.enabled || paymentIntentID == "" {
		return 0, false, nil
	}
	pi, err := paymentintent.Get(paymentIntentID, nil)
	if err != nil {
		return 0, false, fmt.Errorf("retrieve payment intent: %w", err)
	}
	raw, present := pi.Metadata[deliveryFeeMetaKey]
	if !present {
		return 0, false, nil
	}
	v, convErr := strconv.Atoi(raw)
	if convErr != nil {
		return 0, false, nil
	}
	return v, true, nil
}

// StampedFulfillmentType returns the fulfillment_type the PaymentIntent was
// priced for. ok is false when the PI predates this stamp (older in-flight
// checkouts) or in dev stub mode — callers then skip the match check.
func (c *Client) StampedFulfillmentType(paymentIntentID string) (value string, ok bool, err error) {
	if !c.enabled || paymentIntentID == "" {
		return "", false, nil
	}
	pi, err := paymentintent.Get(paymentIntentID, nil)
	if err != nil {
		return "", false, fmt.Errorf("retrieve payment intent: %w", err)
	}
	raw, present := pi.Metadata[fulfillmentMetaKey]
	if !present || raw == "" {
		return "", false, nil
	}
	return raw, true, nil
}

// StampedDeliveryAddrHash returns the delivery-address fingerprint the
// PaymentIntent's fee was quoted against (see deliveryAddrMetaKey). ok is false
// when the PI predates this stamp, was a pickup (no address stamped), or in dev
// stub mode — callers then skip the destination-match check. Compare the return
// value against payments.DeliveryAddrHash(orderAddress) to detect a swapped
// delivery destination.
func (c *Client) StampedDeliveryAddrHash(paymentIntentID string) (hash string, ok bool, err error) {
	if !c.enabled || paymentIntentID == "" {
		return "", false, nil
	}
	pi, err := paymentintent.Get(paymentIntentID, nil)
	if err != nil {
		return "", false, fmt.Errorf("retrieve payment intent: %w", err)
	}
	raw, present := pi.Metadata[deliveryAddrMetaKey]
	if !present || raw == "" {
		return "", false, nil
	}
	return raw, true, nil
}

// verifyPI is the pure verification core shared by VerifyPaymentSucceeded. It
// takes an already-retrieved PaymentIntent (with latest_charge expanded) and
// asserts it succeeded, matches the expected amount, isn't refunded, and — most
// importantly — belongs to the authenticated caller. Factored out so the
// ownership guard can be unit-tested against a fabricated *stripe.PaymentIntent
// without a live Stripe key (the enabled path is otherwise unreachable in stub
// mode).
func verifyPI(pi *stripe.PaymentIntent, userID string, expectedAmountCents int) error {
	if pi.Status != stripe.PaymentIntentStatusSucceeded {
		return fmt.Errorf("payment intent status is %s, expected succeeded", pi.Status)
	}
	if pi.Amount != int64(expectedAmountCents) {
		return fmt.Errorf("payment amount mismatch: got %d, expected %d", pi.Amount, expectedAmountCents)
	}
	if ch := pi.LatestCharge; ch != nil && (ch.Refunded || ch.AmountRefunded > 0) {
		return fmt.Errorf("payment intent has been refunded")
	}
	// Bind the charge to the authenticated caller. Every checkout PI is stamped
	// with Metadata["user_id"] in CreatePaymentSheet; reject if it is missing or
	// belongs to a different user. Without this, an attacker who knows a victim's
	// payment_intent_id (succeeded but whose CreateOrder never landed — the 20m
	// orphan-sweep window) could POST CreateOrder with the victim's PI and get an
	// order charged to the victim's card attributed to themselves (cross-user IDOR).
	if pi.Metadata["user_id"] != userID {
		return fmt.Errorf("payment intent does not belong to this user")
	}
	return nil
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
	params := &stripe.RefundParams{
		PaymentIntent: stripe.String(paymentIntentID),
	}
	// Stable idempotency key keyed on the PI so two genuinely CONCURRENT full
	// refunds of the same payment (e.g. a cancel handler's post-commit refund
	// still in flight when the reconcile reaper fires) collapse to ONE refund at
	// Stripe — the already_refunded swallow below only protects sequential
	// retries, not an in-flight overlap. One full refund per PI is the only
	// refund we ever issue, so a fixed key is safe.
	params.SetIdempotencyKey("refund:" + paymentIntentID)
	_, err := refund.New(params)
	// Idempotent: a PaymentIntent that's already fully refunded (by a prior
	// attempt, the reconcile reaper, or a human in the Dashboard) is success,
	// not an error. This lets every caller — and the retry reaper — call refund
	// repeatedly without double-refunding or getting stuck on a hard error.
	if err != nil {
		var se *stripe.Error
		if errors.As(err, &se) && se.Code == stripe.ErrorCodeChargeAlreadyRefunded {
			return nil
		}
	}
	return err
}

// OrphanCandidate is a slim projection of a SUCCEEDED PaymentIntent we created
// that the orphan-payment reconcile sweep evaluates. AlreadyRefunded is derived
// from the expanded latest charge so the sweep never re-refunds a PI we (or a
// human in the dashboard) already refunded.
type OrphanCandidate struct {
	PaymentIntentID string
	UserID          string
	AmountCents     int
	CreatedUnix     int64
	AlreadyRefunded bool
}

// ListOrphanCandidates returns the SUCCEEDED PaymentIntents we created whose
// `created` timestamp falls within [olderThan, youngerThan] ago. The window is
// bounded on both ends on purpose: `olderThan` (a grace period) keeps us from
// racing a checkout that's mid-flight (PI confirmed, CreateOrder about to land),
// and `youngerThan` bounds the scan so we don't page through all of Stripe's
// history every minute.
//
// It only returns PaymentIntents carrying our orphan marker metadata, so a
// caller can refund a candidate knowing it's clearly ours. The latest charge is
// expanded inline so AlreadyRefunded is populated without an extra API call.
//
// In dev stub mode (no Stripe key) this returns nil — the sweep no-ops.
func (c *Client) ListOrphanCandidates(olderThan, youngerThan time.Duration) ([]OrphanCandidate, error) {
	if !c.enabled {
		return nil, nil
	}

	now := time.Now()
	gteUnix := now.Add(-youngerThan).Unix() // lower bound: not older than youngerThan
	lteUnix := now.Add(-olderThan).Unix()   // upper bound: at least olderThan old

	params := &stripe.PaymentIntentListParams{
		CreatedRange: &stripe.RangeQueryParams{
			GreaterThanOrEqual: gteUnix,
			LesserThanOrEqual:  lteUnix,
		},
	}
	// Expand the latest charge so we can read its refund state without a
	// per-PaymentIntent round trip.
	params.AddExpand("data.latest_charge")
	params.Limit = stripe.Int64(100)

	var out []OrphanCandidate
	it := paymentintent.List(params)
	for it.Next() {
		pi := it.PaymentIntent()
		// Only ours: must carry the marker we stamp in CreatePaymentSheet.
		if pi.Metadata == nil || pi.Metadata[orphanMarkerKey] != orphanMarkerValue {
			continue
		}
		// Only clean, fully-captured successes are refund candidates.
		if pi.Status != stripe.PaymentIntentStatusSucceeded {
			continue
		}
		refunded := false
		if ch := pi.LatestCharge; ch != nil {
			refunded = ch.Refunded || ch.AmountRefunded > 0
		}
		out = append(out, OrphanCandidate{
			PaymentIntentID: pi.ID,
			UserID:          pi.Metadata["user_id"],
			AmountCents:     int(pi.Amount),
			CreatedUnix:     pi.Created,
			AlreadyRefunded: refunded,
		})
	}
	if err := it.Err(); err != nil {
		return nil, fmt.Errorf("list payment intents: %w", err)
	}
	return out, nil
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
		Amount:        stripe.Int64(int64(amountCents)),
		Currency:      stripe.String(string(stripe.CurrencyUSD)),
		Destination:   stripe.String(accountID),
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

	// Only attach the email if it actually parses as an address. A malformed
	// value (e.g. a doubled domain like "x@gmail.com@gmail.com" from a bad
	// signup) makes Stripe reject the whole Customer create with
	// email_invalid, which would 500 checkout for that account. The email is a
	// convenience field on the Customer, not required to charge — so when it's
	// junk we create the customer without it and log, rather than block the
	// order. The bad value should be corrected at its source in the users row.
	custParams := &stripe.CustomerParams{
		Name: stripe.String(name),
		Params: stripe.Params{
			Metadata: map[string]string{"user_id": userID},
		},
	}
	if _, perr := mail.ParseAddress(email); perr == nil {
		custParams.Email = stripe.String(email)
	} else if email != "" {
		log.Printf("[stripe] user=%s has unparseable email %q; creating Stripe customer without email", userID, email)
	}

	cust, err := stripecustomer.New(custParams)
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
func (c *Client) CreatePaymentSheet(ctx context.Context, pool *pgxpool.Pool, amountCents, deliveryFeeCents int, userID, email, name, fulfillmentType, deliveryAddr string) (*PaymentSheetBundle, error) {
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
	piMetadata := map[string]string{
		"user_id":       userID,
		orphanMarkerKey: orphanMarkerValue,
		// Stamp the delivery fee that this charge total was computed
		// against. CreateOrder reuses it (see StampedDeliveryFee) instead
		// of re-quoting the courier API, which would return a slightly
		// different live quote and fail the amount-match guard. This is
		// what binds the recorded order total to the charged amount.
		deliveryFeeMetaKey: strconv.Itoa(deliveryFeeCents),
		fulfillmentMetaKey: fulfillmentType,
	}
	// Bind the destination the fee was quoted for (delivery orders only). This
	// closes the "quote near, deliver far" gap — see deliveryAddrMetaKey.
	if h := DeliveryAddrHash(deliveryAddr); h != "" {
		piMetadata[deliveryAddrMetaKey] = h
	}
	pi, err := paymentintent.New(&stripe.PaymentIntentParams{
		Amount:             stripe.Int64(int64(amountCents)),
		Currency:           stripe.String(string(stripe.CurrencyUSD)),
		Customer:           stripe.String(customerID),
		PaymentMethodTypes: stripe.StringSlice([]string{"card"}),
		SetupFutureUsage:   stripe.String("off_session"),
		Params: stripe.Params{
			Metadata: piMetadata,
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

	// Card only — match the checkout PaymentIntent. AutomaticPaymentMethods
	// would surface Link and ACH bank debits in the "add a card" CustomerSheet,
	// which we don't want for a food-delivery wallet. Apple Pay still rides on
	// "card" when the device supports it.
	si, err := setupintent.New(&stripe.SetupIntentParams{
		Customer:           stripe.String(customerID),
		PaymentMethodTypes: stripe.StringSlice([]string{"card"}),
		Usage:              stripe.String("off_session"),
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
