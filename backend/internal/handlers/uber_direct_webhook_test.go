package handlers

import (
	"encoding/json"
	"testing"
)

// Uber Direct sends courier.rating (and sometimes lat/lng) as quoted strings,
// e.g. "4.9". Before flexFloat this failed the whole-payload unmarshal, so the
// bundled status update (pickup/delivered) was silently dropped and the order
// stranded. Lock in that both string and numeric forms parse.
func TestUberWebhookPayload_FlexNumericFields(t *testing.T) {
	cases := []struct {
		name       string
		body       string
		wantRating float64
		wantLat    float64
	}{
		{
			name:       "rating and latlng as strings",
			body:       `{"kind":"event.delivery_status","data":{"status":"pickup","courier":{"name":"Robo","rating":"4.9","location":{"lat":"40.62","lng":"-73.96"}}}}`,
			wantRating: 4.9,
			wantLat:    40.62,
		},
		{
			name:       "rating and latlng as numbers",
			body:       `{"kind":"event.delivery_status","data":{"status":"pickup","courier":{"name":"Robo","rating":5,"location":{"lat":40.62,"lng":-73.96}}}}`,
			wantRating: 5,
			wantLat:    40.62,
		},
		{
			name:       "rating absent",
			body:       `{"kind":"event.delivery_status","data":{"status":"pickup","courier":{"name":"Robo"}}}`,
			wantRating: 0,
			wantLat:    0,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var p uberWebhookPayload
			if err := json.Unmarshal([]byte(tc.body), &p); err != nil {
				t.Fatalf("unmarshal failed (regression): %v", err)
			}
			if p.Data.Courier == nil {
				t.Fatal("courier unexpectedly nil")
			}
			if got := float64(p.Data.Courier.Rating); got != tc.wantRating {
				t.Errorf("rating = %v, want %v", got, tc.wantRating)
			}
			if p.Data.Courier.Location != nil {
				if got := float64(p.Data.Courier.Location.Lat); got != tc.wantLat {
					t.Errorf("lat = %v, want %v", got, tc.wantLat)
				}
			}
		})
	}
}
