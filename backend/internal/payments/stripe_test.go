package payments

import (
	"testing"

	"github.com/stripe/stripe-go/v78"
)

// TestVerifyPI_UserScoped asserts the PaymentIntent ownership guard that binds a
// succeeded charge to the authenticated caller. This is the write-path analogue
// of the read-path IDOR test (GetOrderByPaymentIntent): without this check an
// attacker who knows a victim's payment_intent_id could POST CreateOrder with it
// during the orphan-sweep window and get an order charged to the victim's card.
//
// We exercise verifyPI directly against fabricated PaymentIntents because the
// integration harness runs in stub mode (STRIPE_SECRET_KEY unset), where
// VerifyPaymentSucceeded short-circuits before ever reaching this guard.
func TestVerifyPI_UserScoped(t *testing.T) {
	const amount = 1599

	pi := func(userID string) *stripe.PaymentIntent {
		return &stripe.PaymentIntent{
			Status:   stripe.PaymentIntentStatusSucceeded,
			Amount:   amount,
			Metadata: map[string]string{"user_id": userID},
		}
	}

	tests := []struct {
		name    string
		pi      *stripe.PaymentIntent
		userID  string
		wantErr bool
	}{
		{
			name:    "matching user passes",
			pi:      pi("user_owner"),
			userID:  "user_owner",
			wantErr: false,
		},
		{
			name:    "mismatched user rejected (cross-user IDOR)",
			pi:      pi("user_victim"),
			userID:  "user_attacker",
			wantErr: true,
		},
		{
			name: "empty/missing metadata rejected",
			pi: &stripe.PaymentIntent{
				Status:   stripe.PaymentIntentStatusSucceeded,
				Amount:   amount,
				Metadata: map[string]string{},
			},
			userID:  "user_owner",
			wantErr: true,
		},
		{
			name: "nil metadata rejected",
			pi: &stripe.PaymentIntent{
				Status: stripe.PaymentIntentStatusSucceeded,
				Amount: amount,
			},
			userID:  "user_owner",
			wantErr: true,
		},
		{
			name:    "amount mismatch rejected",
			pi:      &stripe.PaymentIntent{Status: stripe.PaymentIntentStatusSucceeded, Amount: amount + 1, Metadata: map[string]string{"user_id": "user_owner"}},
			userID:  "user_owner",
			wantErr: true,
		},
		{
			name:    "non-succeeded status rejected",
			pi:      &stripe.PaymentIntent{Status: stripe.PaymentIntentStatusRequiresPaymentMethod, Amount: amount, Metadata: map[string]string{"user_id": "user_owner"}},
			userID:  "user_owner",
			wantErr: true,
		},
		{
			name: "refunded charge rejected",
			pi: &stripe.PaymentIntent{
				Status:       stripe.PaymentIntentStatusSucceeded,
				Amount:       amount,
				Metadata:     map[string]string{"user_id": "user_owner"},
				LatestCharge: &stripe.Charge{Refunded: true},
			},
			userID:  "user_owner",
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := verifyPI(tt.pi, tt.userID, amount)
			if tt.wantErr && err == nil {
				t.Fatalf("verifyPI() = nil, want error")
			}
			if !tt.wantErr && err != nil {
				t.Fatalf("verifyPI() = %v, want nil", err)
			}
		})
	}
}
