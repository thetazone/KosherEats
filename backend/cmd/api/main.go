package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
	"github.com/koshereats/backend/internal/config"
	"github.com/koshereats/backend/internal/database"
	"github.com/koshereats/backend/internal/handlers"
)

func main() {
	cfg := config.Load()

	db, err := database.Connect(cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("failed to connect to database: %v", err)
	}
	defer db.Close()

	r := chi.NewRouter()

	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{"http://localhost:3000", cfg.WebURL},
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	h := handlers.New(db, cfg)

	// Health
	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok"}`))
	})

	// Auth
	r.Route("/api/v1/auth", func(r chi.Router) {
		r.Post("/register", h.Register)
		r.Post("/login", h.Login)
		r.Post("/refresh", h.RefreshToken)
		r.Post("/social", h.SocialLogin)
	})

	// Restaurants
	r.Route("/api/v1/restaurants", func(r chi.Router) {
		r.Get("/", h.ListRestaurants)
		r.Get("/{id}", h.GetRestaurant)
		r.Get("/{id}/menu", h.GetMenu)
		r.Get("/search", h.SearchRestaurants)
	})

	// Orders (authenticated)
	r.Route("/api/v1/orders", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Post("/", h.CreateOrder)
		r.Get("/", h.ListOrders)
		r.Get("/{id}", h.GetOrder)
		r.Patch("/{id}/cancel", h.CancelOrder)
	})

	// Cart
	r.Route("/api/v1/cart", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Get("/", h.GetCart)
		r.Post("/items", h.AddToCart)
		r.Patch("/items/{id}", h.UpdateCartItem)
		r.Delete("/items/{id}", h.RemoveCartItem)
		r.Delete("/", h.ClearCart)
	})

	// Payments
	r.Route("/api/v1/payments", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Post("/intent", h.CreatePaymentIntent)
		r.Post("/confirm", h.ConfirmPayment)
	})
	r.Post("/api/v1/webhooks/stripe", h.StripeWebhook)

	// Seller routes
	r.Route("/api/v1/seller", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
		r.Use(h.SellerMiddleware)

		r.Get("/restaurant", h.GetSellerRestaurant)
		r.Put("/restaurant", h.UpdateRestaurant)

		r.Route("/menu", func(r chi.Router) {
			r.Get("/", h.GetSellerMenu)
			r.Post("/items", h.CreateMenuItem)
			r.Put("/items/{id}", h.UpdateMenuItem)
			r.Delete("/items/{id}", h.DeleteMenuItem)
		})

		r.Route("/orders", func(r chi.Router) {
			r.Get("/", h.ListSellerOrders)
			r.Get("/{id}", h.GetSellerOrder)
			r.Patch("/{id}/accept", h.AcceptOrder)
			r.Patch("/{id}/ready", h.MarkOrderReady)
			r.Patch("/{id}/complete", h.CompleteOrder)
			r.Patch("/{id}/reject", h.RejectOrder)
		})
	})

	// User profile
	r.Route("/api/v1/user", func(r chi.Router) {
		r.Use(h.AuthMiddleware)
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

	go func() {
		log.Printf("KosherEats API server starting on port %s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("shutting down server...")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	srv.Shutdown(ctx)
}
