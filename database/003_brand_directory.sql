PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS brands (
  id TEXT PRIMARY KEY NOT NULL,
  name_en TEXT NOT NULL,
  name_ar TEXT,
  category TEXT NOT NULL CHECK (category IN ('restaurant','supermarket','hypermarket','grocery','pharmacy','cafe','bakery','other')),
  source_note TEXT NOT NULL DEFAULT 'Seeded brand index; aliases require validation'
);

CREATE TABLE IF NOT EXISTS brand_aliases (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  brand_id TEXT NOT NULL REFERENCES brands(id) ON DELETE CASCADE,
  alias TEXT NOT NULL,
  normalized_alias TEXT NOT NULL,
  UNIQUE(brand_id, normalized_alias)
);

CREATE INDEX IF NOT EXISTS idx_brand_aliases_normalized ON brand_aliases(normalized_alias);

CREATE TABLE IF NOT EXISTS brand_place_links (
  brand_id TEXT NOT NULL REFERENCES brands(id) ON DELETE CASCADE,
  place_id TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
  match_method TEXT NOT NULL CHECK (match_method IN ('source_brand_name','exact_alias_name','manual_verified')),
  match_confidence REAL NOT NULL DEFAULT 0.8 CHECK (match_confidence BETWEEN 0 AND 1),
  PRIMARY KEY (brand_id, place_id)
);

CREATE INDEX IF NOT EXISTS idx_brand_place_links_place ON brand_place_links(place_id);
