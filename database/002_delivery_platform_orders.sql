PRAGMA foreign_keys = ON;

-- Migration 002: delivery-platform listing and order observations.
-- Only store data explicitly shared by the driver or supplied by an authorized feed.
-- Never store customer names, phone numbers, pickup/delivery addresses, or raw payloads.

CREATE TABLE IF NOT EXISTS delivery_platforms (
    id TEXT PRIMARY KEY NOT NULL,
    display_name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
    ingestion_mode TEXT NOT NULL CHECK (ingestion_mode IN ('android_share','authorized_feed','manual')),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT OR IGNORE INTO delivery_platforms(id, display_name, ingestion_mode)
VALUES ('hungerstation', 'HungerStation', 'android_share');

CREATE TABLE IF NOT EXISTS platform_place_links (
    id TEXT PRIMARY KEY NOT NULL,
    platform_id TEXT NOT NULL REFERENCES delivery_platforms(id),
    place_id TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    external_listing_key_hash TEXT,
    listing_name TEXT,
    last_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confidence REAL NOT NULL DEFAULT 0.5 CHECK (confidence BETWEEN 0 AND 1),
    UNIQUE(platform_id, external_listing_key_hash)
);

CREATE INDEX IF NOT EXISTS idx_platform_place_links_place
ON platform_place_links(place_id, platform_id);

CREATE TABLE IF NOT EXISTS delivery_order_observations (
    id TEXT PRIMARY KEY NOT NULL,
    platform_id TEXT NOT NULL REFERENCES delivery_platforms(id),
    place_id TEXT NOT NULL REFERENCES places(id),
    platform_order_key_hash TEXT,
    order_status TEXT NOT NULL CHECK (
        order_status IN ('offered','accepted','picked_up','completed','cancelled','unknown')
    ),
    observed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    order_total_minor INTEGER CHECK (order_total_minor IS NULL OR order_total_minor >= 0),
    currency_code TEXT NOT NULL DEFAULT 'SAR' CHECK (length(currency_code) = 3),
    item_count INTEGER CHECK (item_count IS NULL OR item_count >= 0),
    ingestion_mode TEXT NOT NULL CHECK (ingestion_mode IN ('android_share','authorized_feed','manual')),
    source_note TEXT,
    confidence REAL NOT NULL DEFAULT 0.7 CHECK (confidence BETWEEN 0 AND 1),
    UNIQUE(platform_id, platform_order_key_hash)
);

CREATE INDEX IF NOT EXISTS idx_delivery_orders_place_time
ON delivery_order_observations(place_id, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_delivery_orders_platform_time
ON delivery_order_observations(platform_id, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_delivery_orders_status
ON delivery_order_observations(order_status, observed_at DESC);

CREATE VIEW IF NOT EXISTS recent_delivery_order_activity AS
SELECT
    o.id, o.platform_id, p.name AS place_name, p.name_ar, p.category,
    p.district, o.order_status, o.observed_at, o.order_total_minor,
    o.currency_code, o.item_count, o.confidence, o.ingestion_mode
FROM delivery_order_observations o
JOIN places p ON p.id = o.place_id
WHERE p.is_active = 1;
