-- Allow orders.user_id to be NULL so we can anonymize orders when a user
-- deletes their account (keep the order for accounting, remove the link).
ALTER TABLE orders ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_user_id_fkey;
ALTER TABLE orders ADD CONSTRAINT orders_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

-- Same for chat messages — anonymize sender on account deletion.
ALTER TABLE chat_messages ALTER COLUMN sender_user_id DROP NOT NULL;
ALTER TABLE chat_messages DROP CONSTRAINT IF EXISTS chat_messages_sender_user_id_fkey;
ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_sender_user_id_fkey
    FOREIGN KEY (sender_user_id) REFERENCES users(id) ON DELETE SET NULL;
