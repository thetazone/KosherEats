package main

import (
	"context"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
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

	// Refuse to start in production with the default JWT secret. Dev mode
	// just warns so `docker compose up` works out of the box.
	if cfg.JWTSecret == "change-me-in-production" {
		if os.Getenv("APP_ENV") == "production" {
			log.Fatal("JWT_SECRET must be set to a real value in production")
		}
		logger.Warn("using default JWT_SECRET — fine for dev, never deploy like this")
	}

	db, err := database.Connect(cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("failed to connect to database: %v", err)
	}
	defer db.Close()

	// Auto-apply any new migrations on boot. Uses a schema_migrations table
	// to track which .sql files have already run — idempotent across restarts.
	if err := db.RunMigrations(context.Background(), "internal/database/migrations"); err != nil {
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
	})

	// Restaurants (public, IP rate limited)
	r.Route("/api/v1/restaurants", func(r chi.Router) {
		r.Use(apiLimiter.PerIP)
		r.Get("/", h.ListRestaurants)
		r.Get("/{id}", h.GetRestaurant)
		r.Get("/{id}/menu", h.GetMenu)
		r.Get("/search", h.SearchRestaurants)
	})

	// Orders (authenticated, per-user rate limited)
	r.Route("/api/v1/orders", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(apiLimiter.PerUser)
		r.Post("/", h.CreateOrder)
		r.Get("/", h.ListOrders)
		r.Get("/{id}", h.GetOrder)
		r.Patch("/{id}/cancel", h.CancelOrder)
		// Order-scoped chat. Consumer, seller (via order's restaurant
		// ownership), and assigned courier all read + write the same thread.
		r.Get("/{id}/chat", h.ListChatMessages)
		r.Post("/{id}/chat", h.SendChatMessage)
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
	})
	r.Post("/api/v1/webhooks/stripe", h.StripeWebhook)
	r.Post("/api/v1/webhooks/checkr", h.CheckrWebhook)

	// Seller routes
	r.Route("/api/v1/seller", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(h.SellerMiddleware)
		r.Use(apiLimiter.PerUser)

		// Multi-restaurant support. Every /seller endpoint accepts an
		// optional ?restaurant_id= query param; if omitted we fall back to
		// the seller's first owned restaurant for single-restaurant sellers.
		r.Get("/restaurants", h.ListSellerRestaurants)
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
			r.Post("/categories", h.CreateCategory)
			r.Delete("/categories/{id}", h.DeleteCategory)
		})

		r.Route("/orders", func(r chi.Router) {
			r.Get("/", h.ListSellerOrders)
			r.Get("/{id}", h.GetSellerOrder)
			r.Patch("/{id}/accept", h.AcceptOrder)
			r.Patch("/{id}/preparing", h.MarkOrderPreparing)
			r.Patch("/{id}/ready", h.MarkOrderReady)
			r.Patch("/{id}/reject", h.RejectOrder)
		})
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
	})

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      r,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Start the scheduled-order dispatcher. Sweeps every minute, flipping
	// any order whose scheduled_for is within the next 30 minutes into
	// 'pending' so sellers can start preparing.
	schedulerCtx, schedulerCancel := context.WithCancel(context.Background())
	defer schedulerCancel()
	scheduler.New(db.Pool).Start(schedulerCtx)

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
