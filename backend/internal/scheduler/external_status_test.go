package scheduler

import "testing"

func TestMapUberDeliveryStatus(t *testing.T) {
	tests := []struct {
		name   string
		status string
		want   uberDeliveryStatusAction
	}{
		{name: "pending", status: "pending", want: uberDeliveryNoop},
		{name: "pickup", status: "pickup", want: uberDeliveryNoop},
		{name: "pickup complete", status: "pickup_complete", want: uberDeliveryPickedUp},
		{name: "dropoff", status: "dropoff", want: uberDeliveryPickedUp},
		{name: "ongoing", status: "ongoing", want: uberDeliveryPickedUp},
		{name: "old api en route to dropoff", status: "EN_ROUTE_TO_DROPOFF", want: uberDeliveryPickedUp},
		{name: "delivered", status: "delivered", want: uberDeliveryDelivered},
		{name: "old api completed", status: "COMPLETED", want: uberDeliveryDelivered},
		{name: "canceled", status: "canceled", want: uberDeliveryCanceled},
		{name: "cancelled", status: "cancelled", want: uberDeliveryCanceled},
		{name: "failed", status: "failed", want: uberDeliveryCanceled},
		{name: "unknown", status: "mystery_status", want: uberDeliveryNoop},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := mapUberDeliveryStatus(tt.status); got != tt.want {
				t.Fatalf("mapUberDeliveryStatus(%q) = %v, want %v", tt.status, got, tt.want)
			}
		})
	}
}
