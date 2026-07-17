"use client";

import { orders as ordersApi } from "@/lib/api";
import type { ChatMessage } from "@/types";
import { MessageCircle, Send } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

// Order-scoped chat thread shared by the consumer, seller, and assigned
// courier — web port of the iOS OrderChatView. Polls GET /orders/{id}/chat
// every 5s while mounted (visibility-aware: paused when the tab is hidden),
// POSTs on send and appends the returned message so the thread updates
// without waiting for the next poll.
const POLL_INTERVAL_MS = 5_000;

// Backend rejects messages over 2000 chars (SendChatMessage).
const MAX_MESSAGE_LENGTH = 2000;

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
}

// Sender label per role — mirrors iOS ChatMessage.senderLabel so both
// clients narrate the thread identically.
function senderLabel(role: string): string {
  switch (role) {
    case "courier":
      return "Driver";
    case "seller":
      return "Restaurant";
    case "consumer":
      return "You";
    default:
      return role.charAt(0).toUpperCase() + role.slice(1);
  }
}

// The backend HTML-escapes message text on write (SendChatMessage runs
// html.EscapeString), so "&" is stored as "&amp;". React escapes on render,
// so decoding back to plain text here is safe and avoids showing literal
// entities. &amp; is decoded last so double-escaped input round-trips.
function decodeEntities(text: string): string {
  return text
    .replace(/&#34;/g, '"')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

export function OrderChat({
  token,
  orderId,
  onUnauthorized,
}: {
  token: string;
  orderId: string;
  onUnauthorized?: () => void;
}) {
  // null = initial load not finished yet (skeleton state).
  const [messages, setMessages] = useState<ChatMessage[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  const threadRef = useRef<HTMLDivElement>(null);
  // Guard against overlapping polls on a slow connection.
  const inFlightRef = useRef(false);
  // Once we've shown the thread, background poll failures keep the last
  // known messages on screen instead of flipping to the error state.
  const hasLoadedRef = useRef(false);

  const loadMessages = useCallback(async () => {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    try {
      const list = await ordersApi.chat.list(token, orderId);
      hasLoadedRef.current = true;
      setMessages(list);
      setLoadError(null);
    } catch (err) {
      if (isUnauthorized(err)) {
        onUnauthorized?.();
        return;
      }
      if (!hasLoadedRef.current) {
        setLoadError(err instanceof Error ? err.message : "Failed to load messages");
      }
    } finally {
      inFlightRef.current = false;
    }
  }, [token, orderId, onUnauthorized]);

  // Initial load + visibility-aware 5s polling (same pattern as the order
  // status poll on /orders/[id]): pause when the tab is hidden, refresh
  // immediately + resume on return.
  useEffect(() => {
    void loadMessages();

    let interval: number | null = null;
    const start = () => {
      if (interval == null) {
        interval = window.setInterval(() => void loadMessages(), POLL_INTERVAL_MS);
      }
    };
    const stop = () => {
      if (interval != null) {
        window.clearInterval(interval);
        interval = null;
      }
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        void loadMessages();
        start();
      } else {
        stop();
      }
    };

    if (document.visibilityState === "visible") start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [loadMessages]);

  // Keep the newest message in view as the thread grows (initial load and
  // each new message; poll replaces with the same count don't re-fire).
  const messageCount = messages?.length ?? 0;
  useEffect(() => {
    const el = threadRef.current;
    if (el && messageCount > 0) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messageCount]);

  const trimmed = input.trim();
  const canSend = trimmed.length > 0 && !sending;

  async function handleSend(e: React.FormEvent) {
    e.preventDefault();
    if (!canSend) return;
    setSending(true);
    setSendError(null);
    try {
      const sent = await ordersApi.chat.send(token, orderId, trimmed);
      hasLoadedRef.current = true;
      setMessages((prev) => (prev ? [...prev, sent] : [sent]));
      setInput("");
    } catch (err) {
      if (isUnauthorized(err)) {
        onUnauthorized?.();
        return;
      }
      setSendError(err instanceof Error ? err.message : "Failed to send message");
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="card p-5 mb-4">
      <h3 className="font-bold mb-3 flex items-center gap-2">
        <MessageCircle className="w-4 h-4 text-brand-400" aria-hidden="true" />
        Messages
      </h3>

      {/* Thread (skeleton / error / empty / bubbles) */}
      <div
        ref={threadRef}
        className="max-h-80 overflow-y-auto pr-1 space-y-3"
        aria-live="polite"
        aria-label="Chat messages"
      >
        {messages === null && !loadError ? (
          <div className="space-y-3 animate-pulse py-1" aria-hidden="true">
            <div className="h-9 w-2/3 bg-dark-800 rounded-2xl" />
            <div className="h-9 w-1/2 bg-dark-800 rounded-2xl ml-auto" />
            <div className="h-9 w-3/5 bg-dark-800 rounded-2xl" />
          </div>
        ) : loadError ? (
          <div className="text-center py-6">
            <p className="text-dark-400 text-sm mb-3">{loadError}</p>
            <button onClick={() => void loadMessages()} className="btn-secondary py-2 px-4 text-sm">
              Retry
            </button>
          </div>
        ) : messages && messages.length === 0 ? (
          <div className="text-center py-6">
            <MessageCircle className="w-8 h-8 text-dark-600 mx-auto mb-2" aria-hidden="true" />
            <p className="font-semibold text-sm text-dark-300">No messages yet</p>
            <p className="text-dark-500 text-xs mt-1">
              Send a note to your driver or the restaurant.
            </p>
          </div>
        ) : (
          messages?.map((m) => {
            const mine = m.sender_role === "consumer";
            return (
              <div key={m.id} className={`flex ${mine ? "justify-end" : "justify-start"}`}>
                <div className={`max-w-[75%] ${mine ? "text-right" : "text-left"}`}>
                  {!mine && (
                    <p className="text-xs font-bold text-brand-400 mb-0.5">
                      {senderLabel(m.sender_role)}
                    </p>
                  )}
                  <div
                    className={`inline-block px-3.5 py-2 rounded-2xl text-sm whitespace-pre-wrap break-words text-left ${
                      mine ? "bg-brand-500 text-white" : "bg-dark-800 text-white"
                    }`}
                  >
                    {decodeEntities(m.text)}
                  </div>
                  <p className="text-[11px] text-dark-500 mt-0.5">{formatTime(m.created_at)}</p>
                </div>
              </div>
            );
          })
        )}
      </div>

      {sendError && (
        <p className="text-red-400 text-xs mt-2" role="alert">
          {sendError}
        </p>
      )}

      {/* Input bar */}
      <form onSubmit={handleSend} className="mt-3 flex items-center gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          maxLength={MAX_MESSAGE_LENGTH}
          placeholder="Type a message…"
          aria-label="Message"
          className="input flex-1 text-sm"
          disabled={sending}
        />
        <button
          type="submit"
          disabled={!canSend}
          aria-label="Send message"
          className="btn-primary p-2.5 rounded-full flex-shrink-0 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <Send className="w-4 h-4" aria-hidden="true" />
        </button>
      </form>
    </div>
  );
}
