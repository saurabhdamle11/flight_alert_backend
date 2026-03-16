CREATE TABLE user_locations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID             NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label      VARCHAR(100),
    lat_min    DOUBLE PRECISION NOT NULL,
    lat_max    DOUBLE PRECISION NOT NULL,
    lon_min    DOUBLE PRECISION NOT NULL,
    lon_max    DOUBLE PRECISION NOT NULL,
    is_active  BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_locations_user_id ON user_locations(user_id);
CREATE INDEX idx_user_locations_active  ON user_locations(is_active) WHERE is_active = TRUE;
