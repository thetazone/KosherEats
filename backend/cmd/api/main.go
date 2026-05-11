package main

import (
	"context"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	chimiddleware "github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/database"
	"github.com/koshereats/backend/internal/handlers"
	kemiddleware "github.com/koshereats/backend/internal/middleware"
	"github.com/koshereats/backend/internal/scheduler"
	"golang.org/x/time/rate"
)

func main() {
	cfg := config.Load()

	// Structured logger. Switches to JSON in prod so ops tools can parse it.
	var logger *slog.Logger
	if os.Getenv("APP_ENV") == "production" {
		logger = slog.New(slog.NewJSONHandler(os.Stdout, nil))
	} else {
		logger = slog.New(slog.NewTextHandler(os.Stdout, nil))
	}
	slog.SetDefault(logger)

	if len(cfg.JWTSecret) < 32 {
		log.Fatal("JWT_SECRET must be set to a random value of at least 32 characters")
	}

	db, err := database.Connect(cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("failed to connect to database: %v", err)
	}
	defer db.Close()

	// Auto-apply any new migrations on boot. Uses a schema_migrations table
	// to track which .sql files have already run — idempotent across restarts.
	exePath, _ := os.Executable()
	migrationsPath := filepath.Join(filepath.Dir(exePath), "internal", "database", "migrations")
	if err := db.RunMigrations(context.Background(), migrationsPath); err != nil {
		log.Printf("migration warning: %v", err) // non-fatal; may not exist in all deploys
	}

	r := chi.NewRouter()

	// ── Middleware stack ────────────────────────────────────────
	// Order matters: RealIP must come before anything that reads the client
	// IP (logger, rate limiter). Recoverer catches panics before they kill
	// the process.
	r.Use(chimiddleware.RequestID)
	r.Use(chimiddleware.RealIP)
	r.Use(kemiddleware.RequestLogger(logger))
	r.Use(chimiddleware.Recoverer)

	// Tighter CORS. Accepts the dev web URL, configured prod web URL, and
	// iOS simulator loopback explicitly. No wildcards.
	allowedOrigins := []string{"http://localhost:3000", "http://10.0.2.2:8080"}
	if cfg.WebURL != "" && cfg.WebURL != "http://localhost:3000" {
		allowedOrigins = append(allowedOrigins, cfg.WebURL)
	}
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   allowedOrigins,
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	// Rate limiters. Auth endpoints are strict (credential stuffing defense),
	// authenticated endpoints are lenient (catch runaway clients only).
	authLimiter := kemiddleware.NewRateLimiter(rate.Limit(5), 10, 30*time.Minute)
	apiLimiter := kemiddleware.NewRateLimiter(rate.Limit(30), 60, 30*time.Minute)

	h := handlers.New(db, cfg)

	// ── Health check (pings DB so load balancers see the app as degraded
	//    when DB is unreachable) ─────────────────────────────────────────
	// Restaurant approval magic-link endpoint. Auth gate is the random
	// approval_token in the URL — only the admin who received the email
	// knows it. Lives outside /api/v1/admin specifically so AuthMiddleware
	// doesn't block the link.
	r.Get("/admin/restaurants/decision", h.RestaurantDecisionPage)
	r.Post("/admin/restaurants/decision", h.RestaurantDecisionPage)

	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := db.Pool.Ping(ctx); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			w.Write([]byte(`{"status":"degraded","reason":"db unreachable"}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok"}`))
	})

	// ── Auth (IP rate limited) ──────────────────────────────────
	r.Route("/api/v1/auth", func(r chi.Router) {
		r.Use(authLimiter.PerIP)
		r.Post("/register", h.Register)
		r.Post("/login", h.Login)
		r.Post("/refresh", h.RefreshToken)
		r.Post("/social", h.SocialLogin)
		// Used by the unified email entry on each app: given an email, says
		// whether a user exists so the client can route to "enter password"
		// vs "create account". Doesn't leak enough to be a useful enumeration
		// primitive beyond what /login already reveals via its error message.
		r.Post("/email/check", h.CheckEmail)
		// Phone OTP login (Twilio Verify). Used by the seller app's "Continue
		// with phone" flow. Start sends the SMS; verify trades a valid code
		// for a JWT if the phone is associated with an existing account.
		r.Post("/phone/start", h.StartPhoneLogin)
		r.Post("/phone/verify", h.VerifyPhoneLogin)
	})

	// App Store reviewer bypass — only registered when REVIEWER_SECRET is set.
	// Uses a dedicated tight limiter (5 req/min) to prevent secret brute-force.
	if cfg.ReviewerSecret != "" {
		reviewerLimiter := kemiddleware.NewRateLimiter(rate.Limit(5.0/60), 5, 30*time.Minute)
		r.Group(func(r chi.Router) {
			r.Use(reviewerLimiter.PerIP)
			r.Post("/api/v1/auth/reviewer/seller", h.ReviewerSellerLogin)
		})
	}

	// Restaurants (public, IP rate limited)
	r.Route("/api/v1/restaurants", func(r chi.Router) {
		r.Use(apiLimiter.PerIP)
		r.Get("/", h.ListRestaurants)
		r.Get("/search", h.SearchRestaurants)
		// Suggested restaurants — uses optional auth so logged-in users get
		// personalised results while guests get popular picks.
		r.Group(func(r chi.Router) {
			r.Use(h.OptionalAuthMiddleware)
			r.Get("/suggested", h.SuggestedRestaurants)
		})
		r.Get("/{id}", h.GetRestaurant)
		r.Get("/{id}/menu", h.GetMenu)
		r.Get("/{id}/deals", h.ListRestaurantDeals)
	})

	// Delivery fee quote (authenticated — checkout screen calls this)
	r.Route("/api/v1/delivery-quote", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Post("/", h.DeliveryQuote)
	})

	// Orders (authenticated, per-user rate limited)
	r.Route("/api/v1/orders", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Post("/", h.CreateOrder)
		r.Get("/", h.ListOrders)
		r.Get("/{id}", h.GetOrder)
		r.Patch("/{id}/cancel", h.CancelOrder)
		r.Post("/{id}/rating", h.RateOrder)
		// Live courier-location SSE stream. Consumer opens this while on the
		// order-tracking screen; each courier /location ping fans an event to
		// every open stream for that order.
		r.Get("/{id}/location/stream", h.StreamOrderLocation)
		// Order-scoped chat. Consumer, seller (via order's restaurant
		// ownership), and assigned courier all read + write the same thread.
		r.Get("/{id}/chat", h.ListChatMessages)
		r.Post("/{id}/chat", h.SendChatMessage)
	})

	// Favorites (authenticated)
	r.Route("/api/v1/favorites", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Get("/", h.ListFavorites)
		r.Get("/ids", h.ListFavoriteIDs)
		r.Post("/{restaurant_id}", h.AddFavorite)
		r.Delete("/{restaurant_id}", h.RemoveFavorite)
	})

	// Cart
	r.Route("/api/v1/cart", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Get("/", h.GetCart)
		r.Post("/items", h.AddToCart)
		r.Patch("/items/{id}", h.UpdateCartItem)
		r.Delete("/items/{id}", h.RemoveCartItem)
		r.Delete("/", h.ClearCart)
	})

	// Payments
	r.Route("/api/v1/payments", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Post("/intent", h.CreatePaymentIntent)
		r.Post("/confirm", h.ConfirmPayment)
		// Profile → Payment Methods screen (STPCustomerSheet).
		r.Get("/customer", h.GetPaymentCustomer)
		r.Post("/setup-intent", h.CreateSetupIntent)
	})
	r.Post("/api/v1/webhooks/stripe", h.StripeWebhook)
	r.Post("/api/v1/webhooks/checkr", h.CheckrWebhook)
	r.Post("/api/v1/webhooks/uber-direct", h.UberDirectWebhook)
	r.Post("/api/v1/webhooks/doordash", h.DoorDashWebhook)

	// Seller routes
	r.Route("/api/v1/seller", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(h.SellerMiddleware)
		r.Use(apiLimiter.PerUser)

		// Multi-restaurant support. Every /seller endpoint accepts an
		// optional ?restaurant_id= query param; if omitted we fall back to
		// the seller's first owned restaurant for single-restaurant sellers.
		r.Get("/restaurants", h.ListSellerRestaurants)
		r.Post("/restaurants", h.CreateRestaurant)
		r.Get("/restaurant", h.GetSellerRestaurant)
		r.Put("/restaurant", h.UpdateRestaurant)
		r.Patch("/restaurant/status", h.ToggleRestaurantStatus)

		r.Get("/dashboard/stats", h.GetDashboardStats)

		r.Route("/menu", func(r chi.Router) {
			r.Get("/", h.GetSellerMenu)
			r.Post("/items", h.CreateMenuItem)
			r.Put("/items/{id}", h.UpdateMenuItem)
			r.Delete("/items/{id}", h.DeleteMenuItem)
			r.Patch("/items/{id}/availability", h.ToggleItemAvailability)
			r.Post("/items/{itemId}/modifier-groups", h.CreateModifierGroup)
			r.Put("/modifier-groups/{groupId}", h.UpdateModifierGroup)
			r.Delete("/modifier-groups/{groupId}", h.DeleteModifierGroup)
			r.Post("/categories", h.CreateCategory)
			r.Delete("/categories/{id}", h.DeleteCategory)
		})

		r.Route("/orders", func(r chi.Router) {
			r.Get("/", h.ListSellerOrders)
			r.Get("/{id}", h.GetSellerOrder)
			r.Patch("/{id}/accept", h.AcceptOrder)
			r.Patch("/{id}/preparing", h.MarkOrderPreparing)
			r.Patch("/{id}/ready", h.MarkOrderReady)
			r.Patch("/{id}/complete", h.CompleteOrder)
			r.Patch("/{id}/reject", h.RejectOrder)
			r.Patch("/{id}/pickup", h.SellerPickupOrder)
			r.Patch("/{id}/deliver", h.SellerDeliverOrder)
		})

		r.Route("/deals", func(r chi.Router) {
			r.Post("/", h.CreateDeal)
			r.Get("/", h.ListSellerDeals)
			r.Delete("/{dealId}", h.DeactivateDeal)
		})
	})

	// Deals (public, IP rate limited — consumer "Deals" tab)
	r.Route("/api/v1/deals", func(r chi.Router) {
		r.Use(apiLimiter.PerIP)
		r.Get("/nearby", h.ListNearbyDeals)
	})

	// Courier routes. Signup is public but IP rate limited.
	r.Group(func(r chi.Router) {
		r.Use(authLimiter.PerIP)
		r.Post("/api/v1/courier/auth/register", h.CourierRegister)
	})

	r.Route("/api/v1/courier", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(h.CourierMiddleware)
		r.Use(apiLimiter.PerUser)

		// Onboarding + profile
		r.Get("/profile", h.GetCourierProfile)
		r.Post("/onboarding/phone/verify", h.VerifyCourierPhone)
		r.Put("/onboarding/vehicle", h.UpdateCourierVehicle)
		r.Put("/onboarding/documents", h.UpdateCourierDocuments)

		// Live state
		r.Post("/online", h.SetCourierOnline)
		r.Post("/location", h.UpdateCourierLocation)

		// Payouts (Stripe Connect)
		r.Post("/payouts/account", h.CreatePayoutAccount)
		r.Get("/payouts/link", h.GetPayoutLink)
		r.Get("/payouts/status", h.GetPayoutStatus)

		// Marketplace
		r.Get("/deliveries/available", h.ListAvailableDeliveries)
		r.Get("/deliveries/upcoming", h.ListUpcomingDeliveries)
		r.Get("/orders/active", h.ListCourierActiveOrders)
		r.Get("/orders/history", h.ListCourierHistory)
		r.Post("/orders/{id}/claim", h.ClaimOrder)
		r.Post("/orders/{id}/pickup", h.PickupOrder)
		r.Post("/orders/{id}/deliver", h.DeliverOrder)
	})

	// Devices (push notification tokens)
	r.Route("/api/v1/devices", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Post("/register", h.RegisterDevice)
		r.Post("/unregister", h.UnregisterDevice)
	})

	// Uploads (S3 presigned URLs)
	r.Route("/api/v1/uploads", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Post("/presign", h.PresignUpload)
	})

	// Admin routes (role=admin required). Runs platform operations from the
	// web admin dashboard: onboarding sellers + restaurants, reviewing
	// couriers, viewing orders.
	r.Route("/api/v1/admin", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(h.AdminMiddleware)
		r.Use(apiLimiter.PerUser)

		r.Get("/stats", h.AdminStats)

		r.Get("/restaurants", h.AdminListRestaurants)
		r.Post("/restaurants", h.AdminCreateRestaurant)
		r.Patch("/restaurants/{id}/approval", h.AdminSetRestaurantApproval)

		r.Post("/sellers", h.AdminCreateSeller)

		r.Get("/couriers", h.AdminListCouriers)
		r.Get("/couriers/{id}", h.AdminCourierDetail)
		r.Patch("/couriers/{id}/approve", h.AdminApproveCourier)
		r.Patch("/couriers/{id}/reject", h.AdminRejectCourier)

		r.Get("/orders", h.AdminListOrders)
	})

	// User profile
	r.Route("/api/v1/user", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Get("/profile", h.GetProfile)
		r.Put("/profile", h.UpdateProfile)
		r.Get("/addresses", h.ListAddresses)
		r.Post("/addresses", h.AddAddress)
		r.Delete("/addresses/{id}", h.DeleteAddress)
		r.Patch("/addresses/{id}/default", h.SetDefaultAddress)
		r.Get("/notification-preferences", h.GetNotificationPreferences)
		r.Put("/notification-preferences", h.UpdateNotificationPreferences)
		r.Delete("/account", h.DeleteAccount)

		// Linked auth providers (account linking)
		r.Get("/linked-providers", h.ListLinkedProviders)
		r.Post("/linked-providers", h.LinkProvider)
		r.Delete("/linked-providers/{provider}", h.UnlinkProvider)
	})

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      r,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start the background scheduler. Three sweeps run every minute:
	//   (1) scheduled-order promotion — flips future-dated orders to
	//       'pending' 30 min before their delivery window.
	//   (2) courier auto-dispatch — assigns a nearest online courier to any
	//       'ready' order that hasn't been self-claimed within the grace
	//       period, so orders never sit unassigned indefinitely.
	//   (3) stale-pending auto-rejection — refunds + rejects any 'pending'
	//       order the seller never acted on within the SLA, so customers
	//       aren't stuck watching a dead order.
	schedulerCtx, schedulerCancel := context.WithCancel(context.Background())
	defer schedulerCancel()
	scheduler.New(db.Pool, h.Notifier(), h.Stripe(), h.UberDirect(), h.DoorDash()).Start(schedulerCtx)

	go func() {
		logger.Info("server starting", slog.String("port", cfg.Port))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	// ── Graceful shutdown ──────────────────────────────────────
	// SIGINT/SIGTERM → stop accepting new connections, wait up to 30s for
	// in-flight requests to finish, then close the DB pool.
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("shutdown initiated, draining in-flight requests")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("server shutdown error", slog.String("error", err.Error()))
	}
	logger.Info("shutdown complete")
}
