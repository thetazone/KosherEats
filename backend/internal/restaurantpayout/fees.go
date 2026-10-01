// Package restaurantpayout is the restaurant (seller) side of KosherEats money
// movement: the per-order fee model, the payout ledger, and the sweep that
// transfers each restaurant's net to its Stripe Connect account.
//
// Fee model (decided by the owner):
//
//   - courier_delivery — delivery orders carried by an external provider
//     (Uber Direct / DoorDash Drive / Shipday) or a KosherEats platform courier.
//     KE fee = 10% of the food subtotal. Legally a "delivery fee" charged to the
//     restaurant under NYC Admin Code 20-563.3 (capped at 15%). KE absorbs the
//     Stripe processing fee.
//   - pickup — KE fee = 5% of the food subtotal (NYC basic-service-fee cap) plus
//     the ACTUAL Stripe processing fee of the order's charge, passed through
//     (NYC permits a transaction fee equal to the actual processing cost).
//   - self_delivery — the restaurant delivers with its own driver. Treated
//     exactly like pickup (5% + actual processing), and the restaurant keeps its
//     full own delivery fee and 100% of the tip.
//
// Restaurant net = food subtotal + sales tax (100% pass-through: in NY the
// restaurant is the vendor of record for restaurant meals) + (self_delivery
// only: delivery fee + tip) − KE fee − (pickup / self_delivery only: processing
// fee). Never negative: it clamps at 0 and the caller alerts.
package restaurantpayout

// Fulfillment is the fee class of an order.
type Fulfillment string

const (
	CourierDelivery Fulfillment = "courier_delivery"
	Pickup          Fulfillment = "pickup"
	SelfDelivery    Fulfillment = "self_delivery"
)

// Fee rates in basis points of the food subtotal.
const (
	// CourierDeliveryFeeBps: NYC Admin Code 20-563.3 "delivery fee" (cap 15%).
	CourierDeliveryFeeBps = 1000
	// BasicServiceFeeBps: NYC basic service fee (cap 5%), pickup + self-delivery.
	BasicServiceFeeBps = 500
)

// DealsRestaurantFunded records what the deals investigation found: every deal
// is created by the SELLER for their own restaurant (POST /seller/deals,
// handlers.CreateDeal → deals.restaurant_id = the seller's restaurant; the
// deals table, migrations 025/029, has no platform-funded promo concept, and no
// admin or KE code path inserts deals). The discount is therefore the
// restaurant's own price cut, so the food subtotal the fee is charged on — and
// that the restaurant is paid — is the DISCOUNTED subtotal
// (orders.subtotal − orders.discount_cents). If KE ever funds a promotion, pass
// false for that order and the restaurant is paid the undiscounted subtotal.
const DealsRestaurantFunded = true

// Valid reports whether f is one of the three classes.
func (f Fulfillment) Valid() bool {
	switch f {
	case CourierDelivery, Pickup, SelfDelivery:
		return true
	}
	return false
}

// FeeBps is the KE percentage for the class.
func FeeBps(f Fulfillment) int {
	if f == CourierDelivery {
		return CourierDeliveryFeeBps
	}
	return BasicServiceFeeBps
}

// DeductsProcessing reports whether the class passes the actual Stripe
// processing fee through to the restaurant.
func DeductsProcessing(f Fulfillment) bool { return f == Pickup || f == SelfDelivery }

// Classify assigns an order its fee class from who actually fulfilled it.
//
// Pickup orders are pickup. For delivery orders, WHO CARRIED IT wins over the
// configured mode — the same rule SellerDeliverOrder's seller_delivery_earnings
// guard uses: an order with a platform courier or an external provider attached
// is courier_delivery even if its delivery_mode still says 'restaurant' (a
// self-delivery order the seller escalated to Uber). Otherwise
// delivery_mode = 'restaurant' is self_delivery, and anything else
// (platform/external with no carrier recorded) is courier_delivery.
func Classify(fulfillmentType, deliveryMode string, hasCourier, hasExternalProvider bool) Fulfillment {
	if fulfillmentType == "pickup" {
		return Pickup
	}
	if hasCourier || hasExternalProvider {
		return CourierDelivery
	}
	if deliveryMode == "restaurant" {
		return SelfDelivery
	}
	return CourierDelivery
}

// FoodSubtotal is the subtotal the KE fee is charged on and the restaurant is
// paid: the item subtotal after the deal discount when the restaurant funded
// the deal, the undiscounted item subtotal when KE funded it. The discount is
// clamped into [0, itemSubtotal].
func FoodSubtotal(itemSubtotalCents, discountCents int, restaurantFunded bool) int {
	if itemSubtotalCents < 0 {
		itemSubtotalCents = 0
	}
	if !restaurantFunded || discountCents <= 0 {
		return itemSubtotalCents
	}
	if discountCents > itemSubtotalCents {
		discountCents = itemSubtotalCents
	}
	return itemSubtotalCents - discountCents
}

// RestaurantDeliveryShare is the restaurant's own delivery fee on a
// self-delivered order: the consumer-paid delivery fee minus the KosherEats
// marketplace markup (a separate KE line, frozen on the order as
// delivery_markup_cents at checkout). Never negative.
func RestaurantDeliveryShare(deliveryFeeCents, markupCents int) int {
	if markupCents < 0 {
		markupCents = 0
	}
	share := deliveryFeeCents - markupCents
	if share < 0 {
		return 0
	}
	return share
}

// PercentOf returns bps basis points of cents, rounded half up to the cent.
// Integer-only, like config.TaxOn.
func PercentOf(cents, bps int) int {
	if cents <= 0 || bps <= 0 {
		return 0
	}
	return int((int64(cents)*int64(bps) + 5_000) / 10_000)
}

// FeeInput is everything the net computation needs for one order.
type FeeInput struct {
	Fulfillment       Fulfillment
	FoodSubtotalCents int // see FoodSubtotal
	SalesTaxCents     int
	// RestaurantDeliveryFeeCents / TipCents are credited to the restaurant for
	// self_delivery only; ignored (recorded as 0) for the other classes.
	RestaurantDeliveryFeeCents int
	TipCents                   int
	// ProcessingFeeCents is the charge's actual Stripe fee. Recorded for every
	// class, deducted only where DeductsProcessing.
	ProcessingFeeCents int
}

// Breakdown is one ledger line's amounts.
type Breakdown struct {
	Fulfillment        Fulfillment
	FoodSubtotalCents  int
	SalesTaxCents      int
	DeliveryFeeCents   int // credited to the restaurant
	TipCents           int // credited to the restaurant
	KEFeeCents         int // the percentage fee only
	ProcessingFeeCents int
	NetCents           int
	// Clamped is true when the raw net was negative and NetCents was clamped
	// to 0; RawNetCents keeps the unclamped value for the alert.
	Clamped     bool
	RawNetCents int
}

// Compute applies the fee model. Negative inputs are treated as 0.
func Compute(in FeeInput) Breakdown {
	nonNeg := func(v int) int {
		if v < 0 {
			return 0
		}
		return v
	}
	b := Breakdown{
		Fulfillment:        in.Fulfillment,
		FoodSubtotalCents:  nonNeg(in.FoodSubtotalCents),
		SalesTaxCents:      nonNeg(in.SalesTaxCents),
		ProcessingFeeCents: nonNeg(in.ProcessingFeeCents),
	}
	if in.Fulfillment == SelfDelivery {
		b.DeliveryFeeCents = nonNeg(in.RestaurantDeliveryFeeCents)
		b.TipCents = nonNeg(in.TipCents)
	}
	b.KEFeeCents = PercentOf(b.FoodSubtotalCents, FeeBps(in.Fulfillment))

	net := b.FoodSubtotalCents + b.SalesTaxCents + b.DeliveryFeeCents + b.TipCents - b.KEFeeCents
	if DeductsProcessing(in.Fulfillment) {
		net -= b.ProcessingFeeCents
	}
	b.RawNetCents = net
	if net < 0 {
		net = 0
		b.Clamped = true
	}
	b.NetCents = net
	return b
}

// ReversalTarget is how much of a restaurant's net must be clawed back once the
// customer has been refunded refundedCents of a totalCents charge: the refunded
// SHARE of the order total applied to the net, rounded half up, so a partial
// refund costs the restaurant proportionally and a full refund claws back all
// of it. A charge with no usable total is treated as fully refunded (same
// conservative rule as the courier payout halt in StripeWebhook).
func ReversalTarget(netCents, refundedCents, totalCents int) int {
	if netCents <= 0 || refundedCents <= 0 {
		return 0
	}
	if totalCents <= 0 || refundedCents >= totalCents {
		return netCents
	}
	n, r, t := int64(netCents), int64(refundedCents), int64(totalCents)
	target := int((2*n*r + t) / (2 * t)) // round half up of n*r/t
	if target > netCents {
		target = netCents
	}
	return target
}
