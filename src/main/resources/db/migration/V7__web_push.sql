-- Web users register anonymously from the browser: no WhatsApp number, authenticated by a device token.
ALTER TABLE users ALTER COLUMN whatsapp_number DROP NOT NULL;
ALTER TABLE users ADD COLUMN api_token_hash VARCHAR(64) UNIQUE;

-- One row per browser/device that granted notification permission (W3C PushSubscription).
CREATE TABLE push_subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint        TEXT         NOT NULL UNIQUE,
    p256dh          VARCHAR(128) NOT NULL,
    auth            VARCHAR(64)  NOT NULL,
    user_agent      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_success_at TIMESTAMPTZ
);

CREATE INDEX idx_push_subscriptions_user_id ON push_subscriptions(user_id);

-- Notifications can now be delivered over more than one channel.
ALTER TABLE notifications RENAME COLUMN twilio_message_sid TO provider_message_id;
ALTER TABLE notifications ALTER COLUMN provider_message_id TYPE VARCHAR(255);
ALTER TABLE notifications ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP';
