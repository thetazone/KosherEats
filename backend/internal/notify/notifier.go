package notify

import (
	"context"
	"log"

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

// OrderClaimed: a courier claimed an order. Notify the consumer + seller.
func (n *Notifier) OrderClaimed(ctx context.Context, orderID, consumerID, restaurantID, courierFirstName string) {
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "A courier is on the way",
		Body:  courierFirstName + " is heading to the restaurant to pick up your order",
		Data:  map[string]string{"order_id": orderID, "type": "courier_assigned"},
	})

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
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Your order is on the way",
		Body:  "Your driver just picked up your food — tap to track",
		Data:  map[string]string{"order_id": orderID, "type": "picked_up"},
	})
}

// OrderDelivered: courier handed off the food. Notify the consumer.
func (n *Notifier) OrderDelivered(ctx context.Context, orderID, consumerID string) {
	n.dispatch(ctx, n.tokensForUser(ctx, consumerID, AppConsumer), AppConsumer, Payload{
		Title: "Enjoy your meal!",
		Body:  "Your order has been delivered.",
		Data:  map[string]string{"order_id": orderID, "type": "delivered"},
	})
}

func fmtMoney(cents int) string {
	dollars := cents / 100
	remainder := cents % 100
	return "$" + itoa(dollars) + "." + pad2(remainder)
}
func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	sign := ""
	if i < 0 {
		sign = "-"
		i = -i
	}
	var buf []byte
	for i > 0 {
		buf = append([]byte{byte('0' + i%10)}, buf...)
		i /= 10
	}
	return sign + string(buf)
}
func pad2(i int) string {
	s := itoa(i)
	if len(s) == 1 {
		return "0" + s
	}
	return s
}
