package payments

import (
	"testing"

	"github.com/stripe/stripe-go/v78"
)

func TestValidateSucceededPaymentIntent(t *testing.T) {
	t.Run("accepts matching user and amount", func(t *testing.T) {
		pi := &stripe.PaymentIntent{
			Status:   stripe.PaymentIntentStatusSucceeded,
			Amount:   2599,
			Metadata: map[string]string{"user_id": "user_123"},
		}

		if err := validateSucceededPaymentIntent(pi, "user_123", 2599); err != nil {
			t.Fatalf("expected success, got error: %v", err)
		}
	})

	t.Run("rejects payment owned by another user", func(t *testing.T) {
		pi := &stripe.PaymentIntent{
			Status:   stripe.PaymentIntentStatusSucceeded,
			Amount:   2599,
			Metadata: map[string]string{"user_id": "other_user"},
		}

		if err := validateSucceededPaymentIntent(pi, "user_123", 2599); err == nil {
			t.Fatal("expected ownership error, got nil")
		}
	})

	t.Run("rejects payment missing user binding", func(t *testing.T) {
		pi := &stripe.PaymentIntent{
			Status: stripe.PaymentIntentStatusSucceeded,
			Amount: 2599,
		}

		if err := validateSucceededPaymentIntent(pi, "user_123", 2599); err == nil {
			t.Fatal("expected missing binding error, got nil")
		}
	})

	t.Run("rejects wrong amount", func(t *testing.T) {
		pi := &stripe.PaymentIntent{
			Status:   stripe.PaymentIntentStatusSucceeded,
			Amount:   1999,
			Metadata: map[string]string{"user_id": "user_123"},
		}

		if err := validateSucceededPaymentIntent(pi, "user_123", 2599); err == nil {
			t.Fatal("expected amount mismatch error, got nil")
		}
	})
}
