-- Migration 006: Order-scoped chat.
-- Each chat is keyed to an order. All three parties (consumer, seller,
-- courier) can read + write in the same room as long as they're associated
-- with the order. Kept deliberately simple: polling-based, one table, no
-- conversation/room abstraction.

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    sender_user_id UUID NOT NULL REFERENCES users(id),
    sender_role VARCHAR(20) NOT NULL CHECK (sender_role IN ('consumer', 'seller', 'courier')),
    text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_order_time ON chat_messages(order_id, created_at);
CREATE INDEX idx_chat_sender ON chat_messages(sender_user_id);
