ALTER TABLE user_locations ADD COLUMN h3_index BIGINT;

CREATE INDEX idx_user_locations_h3_index ON user_locations(h3_index) WHERE is_active = TRUE;
