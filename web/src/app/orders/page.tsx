"use client";

import { Header } from "@/components/layout/Header";
import { useState } from "react";

type OrderStatus = "pending" | "accepted" | "preparing" | "ready" | "delivered" | "cancelled";

const STATUS_CONFIG: Record<OrderStatus, { label: string; color: string; bg: string }> = {
  pending: { label: "Pending", color: "text-yellow-400", bg: "bg-yellow-900/30" },
  accepted: { label: "Accepted", color: "text-blue-400", bg: "bg-blue-900/30" },
  preparing: { label: "Preparing", color: "text-brand-400", bg: "bg-brand-900/30" },
  ready: { label: "Ready", color: "text-green-400", bg: "bg-green-900/30" },
  delivered: { label: "Delivered", color: "text-green-400", bg: "bg-green-900/30" },
  cancelled: { label: "Cancelled", color: "text-red-400", bg: "bg-red-900/30" },
};

const MOCK_ORDERS = [
  {
    id: "ord-001",
    restaurant_name: "Jerusalem Grill",
    status: "preparing" as OrderStatus,
    items: [
      { name: "Mixed Grill", quantity: 1, price: 2899 },
      { name: "Hummus Plate", quantity: 2, price: 1299 },
    ],
    total: 5496,
    created_at: "2026-03-30T20:15:00Z",
    est_delivery_time: "2026-03-30T21:00:00Z",
  },
  {
    id: "ord-002",
    restaurant_name: "Shalom Sushi",
    status: "delivered" as OrderStatus,
    items: [
      { name: "Salmon Roll", quantity: 2, price: 1599 },
      { name: "Edamame", quantity: 1, price: 699 },
    ],
    total: 4897,
    created_at: "2026-03-29T19:30:00Z",
    est_delivery_time: "2026-03-29T20:15:00Z",
  },
  {
    id: "ord-003",
    restaurant_name: "Kosher Burger Co.",
    status: "delivered" as OrderStatus,
    items: [
      { name: "Double Smash Burger", quantity: 1, price: 1899 },
      { name: "Loaded Fries", quantity: 1, price: 899 },
      { name: "Milkshake", quantity: 1, price: 799 },
    ],
    total: 4597,
    created_at: "2026-03-28T12:00:00Z",
    est_delivery_time: "2026-03-28T12:40:00Z",
  },
];

export default function OrdersPage() {
  const [filter, setFilter] = useState<"active" | "past">("active");

  const activeOrders = MOCK_ORDERS.filter(
    (o) => !["delivered", "cancelled"].includes(o.status)
  );
  const pastOrders = MOCK_ORDERS.filter((o) =>
    ["delivered", "cancelled"].includes(o.status)
  );

  const orders = filter === "active" ? activeOrders : pastOrders;

  return (
    <>
      <Header />
      <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
        <h1 className="text-3xl font-extrabold mb-6">Your Orders</h1>

        {/* Filter Tabs */}
        <div className="flex bg-dark-800 rounded-xl p-1 mb-8 max-w-xs">
          <button
            onClick={() => setFilter("active")}
            className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
              filter === "active"
                ? "bg-brand-500 text-white"
                : "text-dark-400 hover:text-white"
            }`}
          >
            Active ({activeOrders.length})
          </button>
          <button
            onClick={() => setFilter("past")}
            className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
              filter === "past"
                ? "bg-brand-500 text-white"
                : "text-dark-400 hover:text-white"
            }`}
          >
            Past ({pastOrders.length})
          </button>
        </div>

        {orders.length === 0 ? (
          <div className="card p-12 text-center">
            <svg className="w-16 h-16 text-dark-600 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
            </svg>
            <h2 className="text-xl font-bold mb-2">
              No {filter} orders
            </h2>
            <p className="text-dark-400 mb-6">
              {filter === "active"
                ? "You don't have any active orders right now."
                : "Your order history will appear here."}
            </p>
            <a href="/" className="btn-primary inline-block">
              Browse Restaurants
            </a>
          </div>
        ) : (
          <div className="space-y-4">
            {orders.map((order) => {
              const statusConfig = STATUS_CONFIG[order.status];
              return (
                <div key={order.id} className="card p-5 hover:border-dark-600 transition-colors cursor-pointer">
                  <div className="flex items-start justify-between mb-3">
                    <div>
                      <h3 className="font-bold text-lg">{order.restaurant_name}</h3>
                      <p className="text-dark-500 text-sm">
                        {new Date(order.created_at).toLocaleDateString("en-US", {
                          month: "short",
                          day: "numeric",
                          hour: "numeric",
                          minute: "2-digit",
                        })}
                      </p>
                    </div>
                    <span className={`${statusConfig.bg} ${statusConfig.color} text-sm font-medium px-3 py-1 rounded-full`}>
                      {statusConfig.label}
                    </span>
                  </div>

                  {/* Progress bar for active orders */}
                  {filter === "active" && (
                    <div className="mb-4">
                      <div className="flex justify-between text-xs text-dark-500 mb-1">
                        <span>Order placed</span>
                        <span>Preparing</span>
                        <span>Ready</span>
                        <span>Delivered</span>
                      </div>
                      <div className="h-1.5 bg-dark-800 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-brand-500 rounded-full transition-all"
                          style={{
                            width:
                              order.status === "pending"
                                ? "12%"
                                : order.status === "accepted"
                                ? "25%"
                                : order.status === "preparing"
                                ? "50%"
                                : order.status === "ready"
                                ? "75%"
                                : "100%",
                          }}
                        />
                      </div>
                    </div>
                  )}

                  <div className="flex items-center justify-between">
                    <div className="text-sm text-dark-400">
                      {order.items.map((item) => `${item.quantity}x ${item.name}`).join(", ")}
                    </div>
                    <span className="font-semibold text-brand-400">
                      ${(order.total / 100).toFixed(2)}
                    </span>
                  </div>

                  {filter === "past" && (
                    <div className="mt-3 flex gap-3">
                      <button className="btn-primary py-2 px-4 text-sm">Reorder</button>
                      <button className="btn-secondary py-2 px-4 text-sm">View Receipt</button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </main>
    </>
  );
}
