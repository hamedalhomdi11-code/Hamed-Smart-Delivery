PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS data_sources (
 id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL,
 source_type TEXT NOT NULL CHECK (source_type IN ('government_open_data','official_business_directory','open_map','manual_verified')),
 source_url TEXT NOT NULL, license_name TEXT, attribution TEXT,
 enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)), last_sync_at TEXT,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS places (
 id TEXT PRIMARY KEY NOT NULL, source_id TEXT NOT NULL REFERENCES data_sources(id), source_record_id TEXT,
 name TEXT NOT NULL, name_ar TEXT, brand TEXT,
 category TEXT NOT NULL CHECK (category IN ('restaurant','supermarket','hypermarket','grocery','pharmacy','cafe','bakery','other')),
 address TEXT, district TEXT, city TEXT NOT NULL DEFAULT 'Riyadh', country_code TEXT NOT NULL DEFAULT 'SA',
 latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90), longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
 dedupe_key TEXT NOT NULL UNIQUE, data_license TEXT,
 confidence REAL NOT NULL DEFAULT 0.5 CHECK (confidence BETWEEN 0 AND 1),
 is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
 first_seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, last_verified_at TEXT,
 last_updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_places_category_active ON places(category, is_active);
CREATE INDEX IF NOT EXISTS idx_places_brand ON places(brand);
CREATE INDEX IF NOT EXISTS idx_places_city_district ON places(city, district);
CREATE INDEX IF NOT EXISTS idx_places_lat_lon ON places(latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_places_last_verified ON places(last_verified_at);

CREATE TABLE IF NOT EXISTS place_observations (
 id INTEGER PRIMARY KEY AUTOINCREMENT, place_id TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
 observed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 observation_type TEXT NOT NULL CHECK (observation_type IN ('existence_check','location_check','opening_status','delivery_listing_shared_by_user')),
 value TEXT, source_id TEXT REFERENCES data_sources(id), confidence REAL NOT NULL DEFAULT 0.5 CHECK (confidence BETWEEN 0 AND 1)
);
CREATE INDEX IF NOT EXISTS idx_observations_place_time ON place_observations(place_id, observed_at DESC);

CREATE VIEW IF NOT EXISTS active_riyadh_places AS SELECT id,name,name_ar,brand,category,address,district,city,latitude,longitude,confidence,last_verified_at FROM places WHERE is_active=1 AND city='Riyadh' AND country_code='SA';
