"use client";

import { Header } from "@/components/layout/Header";
import { useState } from "react";

const MOCK_CART = {
  restaurant_name: "Jerusalem Grill",
  items: [
    { id: "1", name: "Mixed Grill", price: 2899, quantity: 1, notes: "" },
    { id: "2", name: "Hummus Plate", price: 1299, quantity: 2, notes: "" },
    { id: "3", name: "Fresh Lemonade", price: 499, quantity: 2, notes: "" },
  ],
  delivery_fee: 399,
  service_fee_pct: 15,
  tax_pct: 9,
};

export default function CartPage() {
  const [items, setItems] = useState(MOCK_CART.items);
  const [promoCode, setPromoCode] = useState("");

  const updateQuantity = (id: string, delta: number) => {
    setItems((prev) =>
      prev
        .map((item) =>
          item.id === id ? { ...item, quantity: Math.max(0, item.quantity + delta) } : item
        )
        .filter((item) => item.quantity > 0)
    );
  };

  const subtotal = items.reduce((sum, item) => sum + item.price * item.quantity, 0);
  const serviceFee = Math.round(subtotal * (MOCK_CART.service_fee_pct / 100));
  const tax = Math.round(subtotal * (MOCK_CART.tax_pct / 100));
  const total = subtotal + MOCK_CART.delivery_fee + serviceFee + tax;

  return (
    <>
      <Header />
      <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
        <h1 className="text-3xl font-extrabold mb-2">Your Cart</h1>
        <p className="text-dark-400 mb-8">
          From <span className="text-brand-400 font-medium">{MOCK_CART.restaurant_name}</span>
        </p>

        {items.length === 0 ? (
          <div className="card p-12 text-center">
            <svg className="w-16 h-16 text-dark-600 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 100 4 2 2 0 000-4z" />
            </svg>
            <h2 className="text-xl font-bold mb-2">Your cart is empty</h2>
            <p className="text-dark-400 mb-6">Add items from a restaurant to get started.</p>
            <a href="/" className="btn-primary inline-block">Browse Restaurants</a>
          </div>
        ) : (
          <div className="flex flex-col lg:flex-row gap-8">
            {/* Items */}
            <div className="flex-1 space-y-4">
              {items.map((item) => (
                <div key={item.id} className="card p-4 flex items-center justify-between">
                  <div className="flex-1">
                    <h3 className="font-semibold">{item.name}</h3>
                    <p className="text-brand-400 text-sm font-medium mt-0.5">
                      ${(item.price / 100).toFixed(2)}
                    </p>
                  </div>
                  <div className="flex items-center gap-3">
                    <div className="flex items-center gap-3 bg-dark-800 rounded-xl px-3 py-2">
                      <button
                        onClick={() => updateQuantity(item.id, -1)}
                        className="w-7 h-7 rounded-full bg-dark-700 hover:bg-dark-600 flex items-center justify-center text-white transition-colors"
                      >
                        {item.quantity === 1 ? (
                          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                          </svg>
                        ) : (
                          "-"
                        )}
                      </button>
                      <span className="font-semibold w-6 text-center">{item.quantity}</span>
                      <button
                        onClick={() => updateQuantity(item.id, 1)}
                        className="w-7 h-7 rounded-full bg-brand-500 hover:bg-brand-600 flex items-center justify-center text-white transition-colors"
                      >
                        +
                      </button>
                    </div>
                    <span className="text-sm font-semibold w-16 text-right">
                      ${((item.price * item.quantity) / 100).toFixed(2)}
                    </span>
                  </div>
                </div>
              ))}

              {/* Special Instructions */}
              <div className="card p-4">
                <label className="block text-sm text-dark-300 mb-2">Special instructions</label>
                <textarea
                  className="input w-full h-20 resize-none"
                  placeholder="Allergies, special requests, etc."
                />
              </div>
            </div>

            {/* Order Summary */}
            <div className="lg:w-80">
              <div className="card p-5 sticky top-24">
                <h3 className="font-bold text-lg mb-4">Order Summary</h3>

                {/* Promo Code */}
                <div className="flex gap-2 mb-4">
                  <input
                    type="text"
                    value={promoCode}
                    onChange={(e) => setPromoCode(e.target.value)}
                    className="input flex-1 py-2 text-sm"
                    placeholder="Promo code"
                  />
                  <button className="btn-secondary py-2 px-4 text-sm">Apply</button>
                </div>

                <div className="space-y-2 text-sm">
                  <div className="flex justify-between text-dark-400">
                    <span>Subtotal</span>
                    <span>${(subtotal / 100).toFixed(2)}</span>
                  </div>
                  <div className="flex justify-between text-dark-400">
                    <span>Delivery fee</span>
                    <span>${(MOCK_CART.delivery_fee / 100).toFixed(2)}</span>
                  </div>
                  <div className="flex justify-between text-dark-400">
                    <span>Service fee</span>
                    <span>${(serviceFee / 100).toFixed(2)}</span>
                  </div>
                  <div className="flex justify-between text-dark-400">
                    <span>Tax</span>
                    <span>${(tax / 100).toFixed(2)}</span>
                  </div>
                  <div className="border-t border-dark-700 pt-2 mt-2 flex justify-between font-bold text-base">
                    <span>Total</span>
                    <span className="text-brand-400">${(total / 100).toFixed(2)}</span>
                  </div>
                </div>

                {/* Delivery Address */}
                <div className="mt-6 mb-4">
                  <label className="block text-sm text-dark-300 mb-2">Delivery address</label>
                  <button className="input w-full text-left flex items-center gap-2">
                    <svg className="w-4 h-4 text-brand-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z" />
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 11a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                    <span className="text-dark-400 text-sm">Enter delivery address</span>
                  </button>
                </div>

                <button className="btn-primary w-full text-center">
                  Place Order · ${(total / 100).toFixed(2)}
                </button>
              </div>
            </div>
          </div>
        )}
      </main>
    </>
  );
}
