package restaurantpayout

import (
	"testing"

	"github.com/koshereats/backend/internal/payments"
)

func TestPercentOfRoundsHalfUp(t *testing.T) {
	cases := []struct {
		cents, bps, want int
	}{
		{1000, 500, 50},       // exact
		{1005, 500, 50},       // 50.25 -> 50
		{1010, 500, 51},       // 50.50 -> 51 (half up)
		{1015, 500, 51},       // 50.75 -> 51
		{1005, 1000, 101},     // 100.5 -> 101 (half up)
		{1004, 1000, 100},     // 100.4 -> 100
		{1, 500, 0},           // 0.05 -> 0
		{10, 500, 1},          // 0.5 -> 1
		{0, 500, 0},           // nothing
		{-100, 500, 0},        // never negative
		{123456, 1000, 12346}, // 12345.6 -> 12346
	}
	for _, c := range cases {
		if got := PercentOf(c.cents, c.bps); got != c.want {
			t.Errorf("PercentOf(%d, %d) = %d, want %d", c.cents, c.bps, got, c.want)
		}
	}
}

func TestFoodSubtotalDealFunding(t *testing.T) {
	cases := []struct {
		name               string
		subtotal, discount int
		restaurantFunded   bool
		want               int
	}{
		{"restaurant-funded deal uses the discounted subtotal", 3000, 500, true, 2500},
		{"KE-funded deal pays the restaurant the undiscounted subtotal", 3000, 500, false, 3000},
		{"no deal", 3000, 0, true, 3000},
		{"discount larger than subtotal clamps to zero", 1000, 1500, true, 0},
		{"negative discount ignored", 1000, -50, true, 1000},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := FoodSubtotal(c.subtotal, c.discount, c.restaurantFunded); got != c.want {
				t.Errorf("FoodSubtotal = %d, want %d", got, c.want)
			}
		})
	}
	if !DealsRestaurantFunded {
		t.Error("deals are seller-created (POST /seller/deals); the ledger must treat them as restaurant-funded")
	}
}

func TestClassify(t *testing.T) {
	cases := []struct {
		name              string
		fulfillment, mode string
		courier, external bool
		want              Fulfillment
	}{
		{"pickup", "pickup", "platform", false, false, Pickup},
		{"pickup ignores carrier flags", "pickup", "restaurant", true, true, Pickup},
		{"external provider", "delivery", "external", false, true, CourierDelivery},
		{"platform courier", "delivery", "platform", true, false, CourierDelivery},
		{"restaurant drives it", "delivery", "restaurant", false, false, SelfDelivery},
		{"self-delivery escalated to Uber is courier_delivery", "delivery", "restaurant", false, true, CourierDelivery},
		{"self-delivery mode but a KE courier carried it", "delivery", "restaurant", true, false, CourierDelivery},
		{"platform mode with no carrier recorded", "delivery", "platform", false, false, CourierDelivery},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Classify(c.fulfillment, c.mode, c.courier, c.external); got != c.want {
				t.Errorf("Classify = %s, want %s", got, c.want)
			}
		})
	}
}

func TestComputeAllClasses(t *testing.T) {
	cases := []struct {
		name string
		in   FeeInput
		want Breakdown
	}{
		{
			name: "courier_delivery: 10% fee, processing recorded but absorbed by KE",
			in: FeeInput{Fulfillment: CourierDelivery, FoodSubtotalCents: 2500, SalesTaxCents: 222,
				RestaurantDeliveryFeeCents: 599, TipCents: 500, ProcessingFeeCents: 130},
			want: Breakdown{Fulfillment: CourierDelivery, FoodSubtotalCents: 2500, SalesTaxCents: 222,
				DeliveryFeeCents: 0, TipCents: 0, KEFeeCents: 250, ProcessingFeeCents: 130,
				NetCents: 2500 + 222 - 250, RawNetCents: 2500 + 222 - 250},
		},
		{
			name: "pickup: 5% + actual processing deducted",
			in: FeeInput{Fulfillment: Pickup, FoodSubtotalCents: 2000, SalesTaxCents: 178,
				ProcessingFeeCents: 93},
			want: Breakdown{Fulfillment: Pickup, FoodSubtotalCents: 2000, SalesTaxCents: 178,
				KEFeeCents: 100, ProcessingFeeCents: 93,
				NetCents: 2000 + 178 - 100 - 93, RawNetCents: 2000 + 178 - 100 - 93},
		},
		{
			name: "pickup ignores any delivery fee / tip",
			in: FeeInput{Fulfillment: Pickup, FoodSubtotalCents: 1000, SalesTaxCents: 89,
				RestaurantDeliveryFeeCents: 400, TipCents: 300, ProcessingFeeCents: 62},
			want: Breakdown{Fulfillment: Pickup, FoodSubtotalCents: 1000, SalesTaxCents: 89,
				KEFeeCents: 50, ProcessingFeeCents: 62, NetCents: 1000 + 89 - 50 - 62, RawNetCents: 1000 + 89 - 50 - 62},
		},
		{
			name: "self_delivery: 5% + processing, keeps full delivery fee and tip",
			in: FeeInput{Fulfillment: SelfDelivery, FoodSubtotalCents: 3000, SalesTaxCents: 266,
				RestaurantDeliveryFeeCents: 599, TipCents: 500, ProcessingFeeCents: 160},
			want: Breakdown{Fulfillment: SelfDelivery, FoodSubtotalCents: 3000, SalesTaxCents: 266,
				DeliveryFeeCents: 599, TipCents: 500, KEFeeCents: 150, ProcessingFeeCents: 160,
				NetCents: 3000 + 266 + 599 + 500 - 150 - 160, RawNetCents: 3000 + 266 + 599 + 500 - 150 - 160},
		},
		{
			name: "rounding: 5% of 1010 rounds half up to 51",
			in:   FeeInput{Fulfillment: Pickup, FoodSubtotalCents: 1010},
			want: Breakdown{Fulfillment: Pickup, FoodSubtotalCents: 1010, KEFeeCents: 51, NetCents: 959, RawNetCents: 959},
		},
		{
			name: "rounding: 10% of 1005 rounds half up to 101",
			in:   FeeInput{Fulfillment: CourierDelivery, FoodSubtotalCents: 1005},
			want: Breakdown{Fulfillment: CourierDelivery, FoodSubtotalCents: 1005, KEFeeCents: 101, NetCents: 904, RawNetCents: 904},
		},
		{
			name: "zero order nets zero without clamping",
			in:   FeeInput{Fulfillment: Pickup},
			want: Breakdown{Fulfillment: Pickup},
		},
		{
			name: "negative net clamps to zero and flags it",
			in:   FeeInput{Fulfillment: Pickup, FoodSubtotalCents: 0, SalesTaxCents: 0, ProcessingFeeCents: 30},
			want: Breakdown{Fulfillment: Pickup, ProcessingFeeCents: 30, NetCents: 0, Clamped: true, RawNetCents: -30},
		},
		{
			name: "negative inputs are treated as zero",
			in: FeeInput{Fulfillment: SelfDelivery, FoodSubtotalCents: -10, SalesTaxCents: -5,
				RestaurantDeliveryFeeCents: -1, TipCents: -1, ProcessingFeeCents: -1},
			want: Breakdown{Fulfillment: SelfDelivery},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := Compute(c.in); got != c.want {
				t.Errorf("Compute = %+v\nwant      %+v", got, c.want)
			}
		})
	}
}

// End to end through FoodSubtotal: a restaurant-funded deal lowers both the fee
// base and the restaurant's credit; a KE-funded one would not.
func TestComputeWithDealFunding(t *testing.T) {
	const subtotal, discount = 4000, 1000
	funded := Compute(FeeInput{Fulfillment: CourierDelivery, FoodSubtotalCents: FoodSubtotal(subtotal, discount, true)})
	if funded.FoodSubtotalCents != 3000 || funded.KEFeeCents != 300 || funded.NetCents != 2700 {
		t.Errorf("restaurant-funded: %+v, want food 3000 fee 300 net 2700", funded)
	}
	keFunded := Compute(FeeInput{Fulfillment: CourierDelivery, FoodSubtotalCents: FoodSubtotal(subtotal, discount, false)})
	if keFunded.FoodSubtotalCents != 4000 || keFunded.KEFeeCents != 400 || keFunded.NetCents != 3600 {
		t.Errorf("KE-funded: %+v, want food 4000 fee 400 net 3600", keFunded)
	}
}

func TestRestaurantDeliveryShare(t *testing.T) {
	cases := []struct{ fee, markup, want int }{
		{699, 100, 599},
		{799, 200, 599},
		{50, 100, 0}, // never negative
		{399, -5, 399},
		{0, 0, 0},
	}
	for _, c := range cases {
		if got := RestaurantDeliveryShare(c.fee, c.markup); got != c.want {
			t.Errorf("RestaurantDeliveryShare(%d, %d) = %d, want %d", c.fee, c.markup, got, c.want)
		}
	}
}

func TestReversalTarget(t *testing.T) {
	cases := []struct {
		name                 string
		net, refunded, total int
		want                 int
	}{
		{"no refund", 2000, 0, 3000, 0},
		{"full refund", 2000, 3000, 3000, 2000},
		{"over-refund still caps at net", 2000, 3500, 3000, 2000},
		{"half refund", 2000, 1500, 3000, 1000},
		{"rounds half up", 1001, 1, 2, 501},         // 500.5 -> 501
		{"rounds down below half", 1000, 1, 3, 333}, // 333.33 -> 333
		{"unknown total treated as full", 2000, 100, 0, 2000},
		{"zero net", 0, 3000, 3000, 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := ReversalTarget(c.net, c.refunded, c.total); got != c.want {
				t.Errorf("ReversalTarget(%d, %d, %d) = %d, want %d", c.net, c.refunded, c.total, got, c.want)
			}
		})
	}
}

// Every transfer retry replays one idempotency key, so the whole retry run
// must fit inside Stripe's key retention (the courier queue's double-pay bug).
func TestTransferRetryHorizonFitsIdempotencyWindow(t *testing.T) {
	if h := RetryHorizon(); h >= idempotencyGuardAfter || h >= payments.IdempotencyRetention {
		t.Fatalf("retry run spans %v; must stay under the %v guard and Stripe's %v retention",
			h, idempotencyGuardAfter, payments.IdempotencyRetention)
	}
	if idempotencyGuardAfter >= payments.IdempotencyRetention {
		t.Fatalf("guard at %v fires at/after Stripe forgets the key (%v)", idempotencyGuardAfter, payments.IdempotencyRetention)
	}
}

func TestIdempotencyKeysDeriveFromTheLine(t *testing.T) {
	if got := TransferIdempotencyKey("abc"); got != "restaurant_payout:abc" {
		t.Errorf("TransferIdempotencyKey = %q", got)
	}
	if a, b := ReversalIdempotencyKey("abc", 100), ReversalIdempotencyKey("abc", 200); a == b {
		t.Error("successive reversal steps must not share a key")
	}
	if PublicStatus(StatusProcessing) != StatusPending || PublicStatus(StatusPaid) != StatusPaid {
		t.Error("processing must be reported as pending; others unchanged")
	}
}
