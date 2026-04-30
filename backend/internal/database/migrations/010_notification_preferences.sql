-- Per-user notification opt-in/out. One row per user; rows created lazily on
-- first GET or PUT. Columns default true so existing users keep getting pushes
-- until they explicitly opt out.
--
-- Categories match the consumer-facing toggles in Profile → Notifications.
-- Backend notifier.go checks these before dispatching.

CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    order_updates BOOLEAN NOT NULL DEFAULT true,
    chat_messages BOOLEAN NOT NULL DEFAULT true,
    promotions BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
