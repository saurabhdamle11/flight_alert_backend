CREATE TABLE regions (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lat_min    DOUBLE PRECISION NOT NULL,
    lat_max    DOUBLE PRECISION NOT NULL,
    lon_min    DOUBLE PRECISION NOT NULL,
    lon_max    DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_regions_bbox ON regions (lat_min, lat_max, lon_min, lon_max);

ALTER TABLE user_locations
    ADD COLUMN region_id UUID REFERENCES regions(id) ON DELETE SET NULL;

CREATE INDEX idx_user_locations_region ON user_locations(region_id);
