package payments

// Restaurant (seller) payouts over Stripe Connect.
//
// Customer charges land on the KosherEats platform account (separate charges
// and transfers — KE is merchant of record). Each restaurant gets its own
// Express account, and its share of an order moves there as a Transfer whose
// source_transaction is that order's charge, so the funds come from the charge
// itself even if the platform balance has already been paid out to the bank.
//
// Every method here honors the client's dev stub mode (no STRIPE_SECRET_KEY):
// nothing dials out and plausible fake values come back, so local dev and the
// test suite never touch the network.

import (
	"fmt"
	"log"
	"net/mail"
	"strings"
	"time"

	"github.com/stripe/stripe-go/v78"
	"github.com/stripe/stripe-go/v78/account"
	"github.com/stripe/stripe-go/v78/paymentintent"
	"github.com/stripe/stripe-go/v78/transfer"
	"github.com/stripe/stripe-go/v78/transferreversal"
)

// transferKindMetaKey / restaurantPayoutKind tag every restaurant payout
// transfer. Restaurant and courier transfers for the same order share the
// "order_<id>" transfer_group, so the tag is what lets FindCourierTransfer and
// FindRestaurantTransfer tell them apart.
const (
	transferKindMetaKey    = "kind"
	restaurantPayoutKind   = "restaurant_payout"
	restaurantReversalKind = "restaurant_payout_reversal"
)

// IsPermanentError reports whether a failed Stripe call can never succeed on
// retry (an invalid_request_error about the object, e.g. "No such
// payment_intent"). Rate limits are NOT permanent.
func IsPermanentError(err error) bool { return isPermanentStripeReadError(err) }

// CreateRestaurantExpressAccount creates a Stripe Connect Express account for a
// restaurant. Unlike the courier account it does not pin business_type to
// "individual": Stripe's hosted onboarding asks whether the restaurant is a
// company or a sole proprietor and collects the matching KYC.
//
// The idempotency key is the restaurant id, so two concurrent "set up payouts"
// taps (or a retry after we failed to persist the id) get the SAME account back
// from Stripe instead of minting a second one.
func (c *Client) CreateRestaurantExpressAccount(restaurantID, email, businessName string) (string, error) {
	if !c.enabled {
		return "acct_stub_" + fakeID(), nil
	}
	if restaurantID == "" {
		return "", fmt.Errorf("create restaurant account: empty restaurant id")
	}
	params := &stripe.AccountParams{
		Type:    stripe.String(string(stripe.AccountTypeExpress)),
		Country: stripe.String("US"),
		Capabilities: &stripe.AccountCapabilitiesParams{
			Transfers: &stripe.AccountCapabilitiesTransfersParams{Requested: stripe.Bool(true)},
		},
		BusinessProfile: &stripe.AccountBusinessProfileParams{
			ProductDescription: stripe.String("Restaurant selling prepared kosher meals through KosherEats"),
			MCC:                stripe.String("5812"), // eating places, restaurants
		},
	}
	if name := strings.TrimSpace(businessName); name != "" {
		params.BusinessProfile.Name = stripe.String(name)
	}
	// Same rule as GetOrCreateCustomer: a malformed address makes Stripe reject
	// the whole create, and the email is only a prefill for onboarding.
	if _, perr := mail.ParseAddress(email); perr == nil {
		params.Email = stripe.String(email)
	}
	params.AddMetadata("restaurant_id", restaurantID)
	params.SetIdempotencyKey("restaurant_connect_account:" + restaurantID)

	acct, err := account.New(params)
	if err != nil {
		return "", err
	}
	return acct.ID, nil
}

// ChargeFee is what the restaurant-payout ledger needs to know about an order's
// charge: its id (the transfer's source_transaction), Stripe's actual
// processing fee, and the amounts for refund proration.
type ChargeFee struct {
	ChargeID            string
	FeeCents            int
	AmountCents         int
	AmountRefundedCents int
}

// ChargeFeeForPaymentIntent resolves a checkout PaymentIntent to its charge id
// and the balance-transaction fee in cents (the ACTUAL Stripe processing cost of
// that order — what the pickup / self-delivery classes pass through).
//
// Stub mode and an empty PaymentIntent id answer a zero ChargeFee with no
// error: there is no charge and no fee. A charge whose balance transaction does
// not exist yet is an error the caller should retry.
func (c *Client) ChargeFeeForPaymentIntent(paymentIntentID string) (ChargeFee, error) {
	if c == nil || !c.enabled || paymentIntentID == "" {
		return ChargeFee{}, nil
	}
	params := &stripe.PaymentIntentParams{}
	params.AddExpand("latest_charge.balance_transaction")
	pi, err := paymentintent.Get(paymentIntentID, params)
	if err != nil {
		return ChargeFee{}, fmt.Errorf("retrieve payment intent %s: %w", paymentIntentID, err)
	}
	ch := pi.LatestCharge
	if ch == nil || ch.ID == "" {
		return ChargeFee{}, fmt.Errorf("payment intent %s has no charge", paymentIntentID)
	}
	out := ChargeFee{
		ChargeID:            ch.ID,
		AmountCents:         int(ch.Amount),
		AmountRefundedCents: int(ch.AmountRefunded),
	}
	if ch.BalanceTransaction == nil {
		return out, fmt.Errorf("charge %s has no balance transaction yet", ch.ID)
	}
	out.FeeCents = int(ch.BalanceTransaction.Fee)
	return out, nil
}

// RestaurantTransfer is one restaurant payout.
type RestaurantTransfer struct {
	AccountID    string
	AmountCents  int
	OrderID      string
	RestaurantID string
	// ChargeID becomes the transfer's source_transaction. Empty (stub/dev
	// orders with no charge) transfers from the platform balance instead.
	ChargeID string
	// IdempotencyKey must be derived from the ledger line id and replayed
	// verbatim (with the same amount) on every retry.
	IdempotencyKey string
}

// TransferToRestaurant moves a restaurant's net for one order to its connected
// account and returns the transfer id.
//
// A transfer funded by the order's charge (source_transaction) takes the
// charge's transfer group — Stripe assigns it ("group_<pi>" when the charge has
// none) — so we only name a group, "order_<id>" like the courier payout, when
// there is no charge to fund from. The reconcile lookup (FindRestaurantTransfer)
// matches on destination + metadata, never on the group.
func (c *Client) TransferToRestaurant(t RestaurantTransfer) (string, error) {
	if !c.enabled {
		log.Printf("[stripe stub] restaurant transfer $%d.%02d -> %s for order %s (source %s)",
			t.AmountCents/100, t.AmountCents%100, t.AccountID, t.OrderID, t.ChargeID)
		return "tr_stub_" + fakeID(), nil
	}
	if t.AccountID == "" || t.AmountCents <= 0 || t.OrderID == "" || t.IdempotencyKey == "" {
		return "", fmt.Errorf("invalid restaurant transfer parameters")
	}

	p := &stripe.TransferParams{
		Amount:      stripe.Int64(int64(t.AmountCents)),
		Currency:    stripe.String(string(stripe.CurrencyUSD)),
		Destination: stripe.String(t.AccountID),
		Description: stripe.String("KosherEats order " + t.OrderID),
	}
	if t.ChargeID != "" {
		p.SourceTransaction = stripe.String(t.ChargeID)
	} else {
		p.TransferGroup = stripe.String(courierTransferGroup(t.OrderID))
	}
	p.AddMetadata(transferKindMetaKey, restaurantPayoutKind)
	p.AddMetadata("order_id", t.OrderID)
	if t.RestaurantID != "" {
		p.AddMetadata("restaurant_id", t.RestaurantID)
	}
	p.SetIdempotencyKey(t.IdempotencyKey)

	tr, err := transfer.New(p)
	if err != nil {
		return "", err
	}
	return tr.ID, nil
}

// ReverseRestaurantTransfer claws `amountCents` of a restaurant payout back to
// the platform (a customer refund on that order). idempotencyKey must be stable
// for this exact reversal step.
func (c *Client) ReverseRestaurantTransfer(transferID string, amountCents int, idempotencyKey string) error {
	if !c.enabled {
		log.Printf("[stripe stub] reverse $%d.%02d of restaurant transfer %s",
			amountCents/100, amountCents%100, transferID)
		return nil
	}
	if transferID == "" || amountCents <= 0 || idempotencyKey == "" {
		return fmt.Errorf("invalid transfer reversal parameters")
	}
	params := &stripe.TransferReversalParams{
		ID:     stripe.String(transferID),
		Amount: stripe.Int64(int64(amountCents)),
	}
	params.AddMetadata(transferKindMetaKey, restaurantReversalKind)
	params.SetIdempotencyKey(idempotencyKey)
	_, err := transferreversal.New(params)
	return err
}

// FindRestaurantTransfer reports whether a restaurant payout for `orderID`
// already exists at Stripe on the `destination` account, returning its id and
// amount ("" when none). This is the reconcile escape hatch for retries that may
// fall outside IdempotencyRetention — callers must NOT transfer on error.
//
// It matches on destination + our metadata rather than transfer_group, because
// a transfer created with a source_transaction may carry the charge's group.
func (c *Client) FindRestaurantTransfer(destination, orderID string, since time.Time) (string, int, error) {
	if !c.enabled {
		return "", 0, nil
	}
	if destination == "" || orderID == "" {
		return "", 0, fmt.Errorf("find restaurant transfer: empty destination or order id")
	}
	params := &stripe.TransferListParams{Destination: stripe.String(destination)}
	if !since.IsZero() {
		params.CreatedRange = &stripe.RangeQueryParams{GreaterThanOrEqual: since.Add(-time.Hour).Unix()}
	}
	params.Limit = stripe.Int64(100)
	it := transfer.List(params)
	for it.Next() {
		t := it.Transfer()
		if t.Metadata[transferKindMetaKey] == restaurantPayoutKind && t.Metadata["order_id"] == orderID {
			return t.ID, int(t.Amount), nil
		}
	}
	if err := it.Err(); err != nil {
		return "", 0, fmt.Errorf("list transfers to %s: %w", destination, err)
	}
	return "", 0, nil
}
