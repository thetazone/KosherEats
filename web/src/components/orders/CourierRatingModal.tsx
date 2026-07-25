"use client";

import { orders as ordersApi } from "@/lib/api";
import { Loader2, Star, X } from "lucide-react";
import { useEffect, useState } from "react";

// Post-delivery courier rating prompt — web port of the iOS
// CourierRatingSheet. The consumer picks 1-5 stars and optionally leaves a
// short comment; POST /orders/{id}/rating recomputes the courier's aggregate
// rating server-side. The parent controls visibility — render only while
// open, pass onClose to dismiss.
const MAX_COMMENT_LENGTH = 500;

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

export function CourierRatingModal({
  token,
  orderId,
  courierFirstName,
  onSubmitted,
  onClose,
  onUnauthorized,
}: {
  token: string;
  orderId: string;
  // Omitted on the /orders list, where the payload has no courier info.
  courierFirstName?: string;
  onSubmitted: (stars: number) => void;
  onClose: () => void;
  onUnauthorized?: () => void;
}) {
  const [stars, setStars] = useState(5); // default 5, mirrors iOS
  const [hovered, setHovered] = useState<number | null>(null);
  const [comment, setComment] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Escape closes; lock body scroll while the modal is up (same pattern as
  // KosherCertificateModal).
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const trimmed = comment.trim();
      await ordersApi.rate(token, orderId, {
        stars,
        ...(trimmed !== "" ? { comment: trimmed } : {}),
      });
      onSubmitted(stars);
    } catch (err) {
      if (isUnauthorized(err)) {
        onUnauthorized?.();
        return;
      }
      setError(err instanceof Error ? err.message : "Failed to submit rating");
    } finally {
      setSubmitting(false);
    }
  }

  const displayStars = hovered ?? stars;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Rate your courier"
      className="fixed inset-0 z-[60] bg-dark-950/80 flex items-center justify-center p-4"
      onClick={onClose}
    >
      <div
        className="card p-6 w-full max-w-md max-h-[calc(100dvh-2rem)] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 mb-1">
          <h2 className="text-xl font-bold">
            {courierFirstName ? `How was ${courierFirstName}?` : "How was your courier?"}
          </h2>
          <button
            onClick={onClose}
            disabled={submitting}
            aria-label="Close rating dialog"
            className="min-w-[44px] min-h-[44px] -m-2 flex items-center justify-center rounded-xl text-dark-400 hover:text-white hover:bg-dark-800 transition-colors disabled:opacity-50"
          >
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>
        <p className="text-dark-400 text-sm mb-5">
          Your rating stays anonymous and helps couriers keep doing great work.
        </p>

        <form onSubmit={submit}>
          {/* Star row */}
          <div
            className="flex justify-center gap-2 mb-5"
            role="radiogroup"
            aria-label={`Rating: ${stars} of 5 stars`}
          >
            {[1, 2, 3, 4, 5].map((i) => (
              <button
                key={i}
                type="button"
                role="radio"
                aria-checked={i === stars}
                aria-label={`${i} star${i === 1 ? "" : "s"}`}
                onClick={() => setStars(i)}
                onMouseEnter={() => setHovered(i)}
                onMouseLeave={() => setHovered(null)}
                disabled={submitting}
                className="p-1 transition-transform hover:scale-110 disabled:opacity-50"
              >
                <Star
                  className={`w-9 h-9 transition-colors ${
                    i <= displayStars ? "text-yellow-400 fill-yellow-400" : "text-dark-600"
                  }`}
                  aria-hidden="true"
                />
              </button>
            ))}
          </div>

          {/* text-base (16px) so iOS Safari doesn't auto-zoom on focus */}
          <textarea
            value={comment}
            onChange={(e) => setComment(e.target.value.slice(0, MAX_COMMENT_LENGTH))}
            placeholder="Leave a comment (optional)"
            aria-label="Comment (optional)"
            rows={3}
            disabled={submitting}
            className="input w-full text-base resize-none"
          />
          {comment.length > 400 && (
            <p
              className={`text-xs mt-1 ${
                comment.length >= MAX_COMMENT_LENGTH ? "text-red-400" : "text-dark-500"
              }`}
            >
              {MAX_COMMENT_LENGTH - comment.length} characters remaining
            </p>
          )}

          {error && (
            <p className="text-red-400 text-sm mt-3" role="alert">
              {error}
            </p>
          )}

          <div className="flex gap-3 mt-5">
            <button
              type="submit"
              disabled={submitting}
              className="btn-primary flex-1 py-2.5 text-sm min-h-[44px] flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {submitting && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
              {submitting ? "Submitting…" : "Submit rating"}
            </button>
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="btn-secondary py-2.5 px-5 text-sm min-h-[44px] inline-flex items-center justify-center disabled:opacity-50"
            >
              Skip
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
