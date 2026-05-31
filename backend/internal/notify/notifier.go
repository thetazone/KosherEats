package notify

import (
	"context"
	"fmt"
	"log"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Notifier is the app-level push facade. Handlers call it with semantic events
// like "new order for seller", and it takes care of resolving recipients and
// building payloads. It never returns errors — push failures are logged only.
//
// It owns both the APNs client (iOS) and the FCM client (Android). When
// dispatching, it routes each device token to the right transport based on
// the device_tokens.platform column.
type Notifier struct {
	db   *pgxpool.Pool
	apns *APNs
	fcm  *FCM
}

func NewNotifier(db *pgxpool.Pool, apns *APNs, fcm *FCM) *Notifier {
	return &Notifier{db: db, apns: apns, fcm: fcm}
}

// device carries a token along with the platform it belongs to so the
// dispatcher can pick APNs vs FCM. The `platform` column in device_tokens
// is written by the register-device handler based on the client that
// uploaded the token.
type device struct {
	token    string
	platform string // "ios" | "android"
}

// tokensForUser loads every device (token + platform) for a user + app combo.
// No platform filter — we send to both iOS and Android devices owned by the
// same user.
func (n *Notifier) tokensForUser(ctx context.Context, userID string, app App) []device {
	rows, err := n.db.Query(ctx,
		`SELECT token, platform FROM device_tokens WHERE user_id = $1 AND app = $2`,
		userID, string(app))
	if err != nil {
		log.Printf("[notify] fetch tokens for user=%s app=%s: %v", userID, app, err)
		return nil
	}
	defer rows.Close()
	var devices []device
	for rows.Next() {
		var d device
		if err := rows.Scan(&d.token, &d.platform); err != nil {
			continue
		}
		devices = append(devices, d)
	}
	return devices
}

// tokensForOnlineCouriers returns every online, approved courier's device
// (token + platform) — used to broadcast a "new delivery available" push.
func (n *Notifier) tokensForOnlineCouriers(ctx context.Context) []device {
	rows, err := n.db.Query(ctx, `
		SELECT dt.token, dt.platform
		  FROM device_tokens dt
		  JOIN courier_profiles cp ON cp.user_id = dt.user_id
		 WHERE dt.app = 'courier'
		   AND cp.is_online = true
		   AND cp.onboarding_status = 'approved'`)
	if err != nil {
		log.Printf("[notify] fetch online courier tokens: %v", err)
		return nil
	}
	defer rows.Close()
	var devices []device
	for rows.Next() {
		var d device
		if err := rows.Scan(&d.token, &d.platform); err != nil {
			continue
		}
		devices = append(devices, d)
	}
	return devices
}

// dispatch splits a slice of devices by platform and fans them out to the
// matching transport. This is the single place where platform routing lives
// — every semantic event below goes through it.
func (n *Notifier) dispatch(ctx context.Context, devices []device, app App, payload Payload) {
	if len(devices) == 0 {
		return
	}
	var iosTokens, androidTokens []string
	for _, d := range devices {
		switch d.platform {
		case "ios":
			iosTokens = append(iosTokens, d.token)
		case "android":
			androidTokens = append(androidTokens, d.token)
		default:
			log.Printf("[notify] unknown platform %q for token %s…", d.platform, safePrefix(d.token))
		}
	}
	if len(iosTokens) > 0 {
		n.apns.SendMulti(ctx, iosTokens, app, payload)
	}
	if len(androidTokens) > 0 {
		n.fcm.SendMulti(ctx, androidTokens, app, payload)
	}
}

// restaurantOwnerID looks up the seller user id that owns a given restaurant.
func (n *Notifier) restaurantOwnerID(ctx context.Context, restaurantID string) (string, error) {
	var ownerID string
	err := n.db.QueryRow(ctx,
		`SELECT owner_id FROM restaurants WHERE id = $1`, restaurantID,
	).Scan(&ownerID)
	return ownerID, err
}

// Category matches the user-facing toggles in Profile → Notifications.
// consumerOptedIn gates dispatch to a consumer recipient — if the toggle is
// off we return false and the caller skips the push. Sellers/couriers get
// operational pushes unconditionally (no consumer prefs table row for them
// means defaults-true, which is correct).
type Category string

const (
	CategoryOrderUpdates Category = "order_updates"
	CategoryChatMessages Category = "chat_messages"
	CategoryPromotions   Category = "promotions"
)

func (n *Notifier) consumerOptedIn(ctx context.Context, userID string, cat Category) bool {
	if userID == "" {
		return true
	}
	var orderUpdates, chatMessages, promotions bool
	err := n.db.QueryRow(ctx,
		`SELECT order_updates, chat_messages, promotions
		   FROM notification_preferences WHERE user_id = $1`,
		userID,
	).Scan(&orderUpdates, &chatMessages, &promotions)
	if err == pgx.ErrNoRows {
		return true // no row yet = all categories default to enabled
	}
	if err != nil {
		log.Printf("[notify] load prefs user=%s: %v", userID, err)
		return true // fail open — better to miss a toggle than drop a push
	}
	switch cat {
	case CategoryOrderUpdates:
		return orderUpdates
	case CategoryChatMessages:
		return chatMessages
	case CategoryPromotions:
		return promotions
	}
	return true
}

// -------- Semantic events --------

// OrderCreated: a consumer just placed an order. Notify the seller.
func (n *Notifier) OrderCreated(ctx context.Context, restaurantID, restaurantName, orderID string, total int) {
	ownerID, err := n.restaurantOwnerID(ctx, restaurantID)
	if err != nil {
		return
	}
	n.dispatch(ctx, n.tokensForUser(ctx, ownerID, AppSeller), AppSeller, Payload{
		Title: "New order",
		Body:  fmtMoney(total) + " — tap to accept",
		Data:  map[string]string{"order_id": orderID, "type": "new_order"},
	})
}

// OrderReady: the seller marked an order ready. Broadcast to online couriers.
func (n *Notifier) OrderReady(ctx context.Context, orderID, restaurantName string, payout int) {
	n.dispatch(ctx, n.tokensForOnlineCouriers(ctx), AppCourier, Payload{
		Title: "New delivery available",
		Body:  restaurantName + " • " + fmtMoney(payout),
		Data:  map[string]string{"order_id": orderID, "type": "delivery_available"},
	})
}

// CourierAutoAssigned: the dispatcher auto-assigned an order to a specific
// courier (they didn't self-claim from the marketplace broadcast). Notify
// that courier directly so they know they have a new delivery queued up.
func (n *Notifier) CourierAutoAssigned(ctx context.Context, orderID, courierUserID, restaurantName string, payout int) {
	n.dispatch(ctx, n.tokensForUser(ctx, courierUserID, AppCourier), AppCourier, Payload{
		Title: "New delivery assigned",
		Body:  restaurantName + " • " + fmtMoney(payout) + " — tap to view",
		Data:  map[string]string{"order_id": orderID, "type": "auto_assigned"},
	})
}

// OrderAccepted: seller accepted the order. Push to consumer.
func (n *Notifier) OrderAccepted(ctx context.Context, orderID, consumerID, restaurantName string) {
	if !n.consumerOptedIn(ctx, consumerID, CategoryOrderUpdates) {
		return
	}
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Order accepted!",
		Body:  restaurantName + " accepted your order and will start preparing it soon.",
		Data:  map[string]string{"order_id": orderID, "type": "order_accepted"},
	})
}

// OrderPreparing: seller started preparing the order. Push to consumer.
func (n *Notifier) OrderPreparing(ctx context.Context, orderID, consumerID, restaurantName string) {
	if !n.consumerOptedIn(ctx, consumerID, CategoryOrderUpdates) {
		return
	}
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Your food is being prepared",
		Body:  restaurantName + " is preparing your order now.",
		Data:  map[string]string{"order_id": orderID, "type": "order_preparing"},
	})
}

// OrderRejected: seller manually rejected the order. Push to consumer.
func (n *Notifier) OrderRejected(ctx context.Context, orderID, consumerID, restaurantName, reason string) {
	if !n.consumerOptedIn(ctx, consumerID, CategoryOrderUpdates) {
		return
	}
	body := restaurantName + " couldn't fulfill your order. You've been refunded."
	if reason != "" {
		body = restaurantName + ": " + reason + ". You've been refunded."
	}
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Order rejected — refund issued",
		Body:  body,
		Data:  map[string]string{"order_id": orderID, "type": "order_rejected"},
	})
}

// OrderAutoRejected: a pending order sat too long without seller acceptance
// and the stale-order sweep rejected + refunded it. Notify the consumer that
// their order was cancelled and money returned.
func (n *Notifier) OrderAutoRejected(ctx context.Context, orderID, consumerID, restaurantName string) {
	if !n.consumerOptedIn(ctx, consumerID, CategoryOrderUpdates) {
		return
	}
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Order cancelled — refund issued",
		Body:  restaurantName + " didn't confirm in time. You've been refunded.",
		Data:  map[string]string{"order_id": orderID, "type": "auto_rejected"},
	})
}

// ChatMessageSent: a participant sent a message on an order chat. Pushes a
// preview to every OTHER participant (consumer, seller, courier if assigned)
// on the app they belong to. Sender is skipped — they just hit send and
// don't need a buzz in their own pocket.
//
// Title is role-aware so the recipient's lockscreen shows meaningful context
// ("Message from the restaurant") rather than a generic "New message".
func (n *Notifier) ChatMessageSent(ctx context.Context, orderID, senderUserID, senderRole, text string) {
	// Resolve the order's three participants in one hop. courier_id can be
	// NULL (no courier assigned yet) so we scan into a *string.
	var consumerID, ownerID string
	var courierID *string
	err := n.db.QueryRow(ctx, `
		SELECT o.user_id, rest.owner_id, o.courier_id
		  FROM orders o
		  JOIN restaurants rest ON o.restaurant_id = rest.id
		 WHERE o.id = $1`, orderID,
	).Scan(&consumerID, &ownerID, &courierID)
	if err != nil {
		log.Printf("[notify] chat: resolve participants order=%s: %v", orderID, err)
		return
	}

	title := chatTitleFromSender(senderRole)
	preview := truncateForPush(text, 120)
	data := map[string]string{
		"order_id":    orderID,
		"type":        "chat_message",
		"sender_role": senderRole,
	}
	payload := func(t string) Payload {
		return Payload{Title: t, Body: preview, Data: data}
	}

	// Push to every participant except the sender. "Except the sender"
	// matters when two of the three roles map to the same user (e.g. in dev
	// a seller might also place an order on their own restaurant). Consumer
	// chat pushes respect the user's chat_messages toggle; seller/courier
	// operational chats always fire.
	if consumerID != "" && consumerID != senderUserID && n.consumerOptedIn(ctx, consumerID, CategoryChatMessages) {
		n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, payload(title))
	}
	if ownerID != "" && ownerID != senderUserID {
		n.dispatch(ctx, n.tokensForUser(ctx, ownerID, AppSeller), AppSeller, payload(title))
	}
	if courierID != nil && *courierID != "" && *courierID != senderUserID {
		n.dispatch(ctx, n.tokensForUser(ctx, *courierID, AppCourier), AppCourier, payload(title))
	}
}

// chatTitleFromSender maps a sender's role to the title recipients see on
// their lockscreen. Keeps the phrasing natural from the recipient's POV
// regardless of which app they're in.
func chatTitleFromSender(senderRole string) string {
	switch senderRole {
	case "consumer":
		return "Message from the customer"
	case "seller":
		return "Message from the restaurant"
	case "courier":
		return "Message from your courier"
	default:
		return "New message"
	}
}

// truncateForPush clips a message to max runes and appends an ellipsis so
// the push body fits cleanly on iOS/Android lockscreens without the system
// doing its own ugly mid-word cut.
func truncateForPush(s string, max int) string {
	runes := []rune(s)
	if len(runes) <= max {
		return s
	}
	return string(runes[:max]) + "…"
}

// OrderClaimed: a courier claimed an order. Notify the consumer + seller.
func (n *Notifier) OrderClaimed(ctx context.Context, orderID, consumerID, restaurantID, courierFirstName string) {
	if n.consumerOptedIn(ctx, consumerID, CategoryOrderUpdates) {
		n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
			Title: "A courier is on the way",
			Body:  courierFirstName + " is heading to the restaurant to pick up your order",
			Data:  map[string]string{"order_id": orderID, "type": "courier_assigned"},
		})
	}

	if ownerID, err := n.restaurantOwnerID(ctx, restaurantID); err == nil {
		n.dispatch(ctx, n.tokensForUser(ctx, ownerID, AppSeller), AppSeller, Payload{
			Title: "Courier assigned",
			Body:  courierFirstName + " is on the way to pick up the order",
			Data:  map[string]string{"order_id": orderID, "type": "courier_assigned"},
		})
	}
}

// OrderPickedUp: courier picked up the food. Notify the consumer.
func (n *Notifier) OrderPickedUp(ctx context.Context, orderID, consumerID string) {
	if !n.consumerOptedIn(ctx, consumerID, CategoryOrderUpdates) {
		return
	}
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Your order is on the way",
		Body:  "Your driver just picked up your food — tap to track",
		Data:  map[string]string{"order_id": orderID, "type": "picked_up"},
	})
}

// OrderCancelled: consumer cancelled the order. Notify the seller.
func (n *Notifier) OrderCancelled(ctx context.Context, orderID, restaurantID string) {
	ownerID, err := n.restaurantOwnerID(ctx, restaurantID)
	if err != nil {
		return
	}
	n.dispatch(ctx, n.tokensForUser(ctx, ownerID, AppSeller), AppSeller, Payload{
		Title: "Order cancelled",
		Body:  "A customer cancelled their order.",
		Data:  map[string]string{"order_id": orderID, "type": "order_cancelled"},
	})
}

// OrderCompleted: seller marked a pickup order as completed. Notify the consumer.
func (n *Notifier) OrderCompleted(ctx context.Context, orderID, consumerID string) {
	if !n.consumerOptedIn(ctx, consumerID, CategoryOrderUpdates) {
		return
	}
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Your pickup order is ready!",
		Body:  "Head over to pick up your order.",
		Data:  map[string]string{"order_id": orderID, "type": "completed"},
	})
}

// OrderDelivered: courier handed off the food. Notify the consumer.
func (n *Notifier) OrderDelivered(ctx context.Context, orderID, consumerID string) {
	if !n.consumerOptedIn(ctx, consumerID, CategoryOrderUpdates) {
		return
	}
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Enjoy your meal!",
		Body:  "Your order has been delivered.",
		Data:  map[string]string{"order_id": orderID, "type": "delivered"},
	})
}

func fmtMoney(cents int) string {
	if cents < 0 {
		return "-" + fmtMoney(-cents)
	}
	dollars := cents / 100
	remainder := cents % 100
	return fmt.Sprintf("$%d.%02d", dollars, remainder)
}
