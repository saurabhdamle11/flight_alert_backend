CREATE TABLE notifications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID             NOT NULL REFERENCES users(id),
    icao24              VARCHAR(10)      NOT NULL,
    callsign            VARCHAR(20),
    origin_country      VARCHAR(100),
    altitude_m          DOUBLE PRECISION,
    speed_ms            DOUBLE PRECISION,
    heading             DOUBLE PRECISION,
    twilio_message_sid  VARCHAR(50),
    sent_at             TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_sent_at ON notifications(sent_at);
