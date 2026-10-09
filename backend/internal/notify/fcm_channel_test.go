package notify

import (
	"encoding/json"
	"testing"
)

// The Android apps declare their notification channels client-side; a push
// that names no channel_id is rendered by FCM under the OS "Miscellaneous"
// channel when the app is backgrounded. Pin the id each app/payload gets.
func TestBuildFCMBodyChannelID(t *testing.T) {
	cases := []struct {
		name    string
		app     App
		payload Payload
		want    string // "" = no android.notification block
	}{
		{"consumer order update", AppConsumer, Payload{Title: "t", Data: map[string]string{"type": "order_accepted"}}, fcmChannelConsumer},
		{"seller new order rings the new-orders channel", AppSeller, Payload{Title: "t", Data: map[string]string{"type": "new_order"}}, fcmChannelSellerNewOrders},
		{"seller other event", AppSeller, Payload{Title: "t", Data: map[string]string{"type": "courier_assigned"}}, fcmChannelSeller},
		{"seller no data", AppSeller, Payload{Title: "t"}, fcmChannelSeller},
		{"courier has no channel", AppCourier, Payload{Title: "t", Data: map[string]string{"type": "delivery_available"}}, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			body, err := buildFCMBody("tok", tc.app, tc.payload)
			if err != nil {
				t.Fatalf("buildFCMBody: %v", err)
			}
			var msg struct {
				Message struct {
					Android struct {
						Priority     string `json:"priority"`
						Notification *struct {
							ChannelID string `json:"channel_id"`
						} `json:"notification"`
					} `json:"android"`
				} `json:"message"`
			}
			if err := json.Unmarshal(body, &msg); err != nil {
				t.Fatalf("decode: %v", err)
			}
			if msg.Message.Android.Priority != "HIGH" {
				t.Errorf("priority = %q, want HIGH", msg.Message.Android.Priority)
			}
			got := ""
			if msg.Message.Android.Notification != nil {
				got = msg.Message.Android.Notification.ChannelID
			}
			if got != tc.want {
				t.Errorf("channel_id = %q, want %q", got, tc.want)
			}
		})
	}
}
