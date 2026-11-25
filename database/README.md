# Riyadh places database

Canonical interchange format: SQLite 3 (.sqlite or .db). CSV and GeoJSON are optional secondary exports.

## Status
This folder defines the database contract. It does not yet contain a complete verified Riyadh dataset. No synthetic branches or coordinates are inserted.

## Schema migrations
1. `001_riyadh_places.sql`: data sources, business branches, observations and active Riyadh places view.
2. `002_delivery_platform_orders.sql`: delivery platforms, platform-to-branch listing links, privacy-minimized order observations and recent order activity view. HungerStation is registered for Android share ingestion; this does not create a private API integration or fetch orders automatically.

## Brand index and branch hierarchy

- `003_brand_directory.sql` creates a separate `brands` registry, `brand_aliases`, and `brand_place_links` so chain names are not confused with physical branches.
- `riyadh_brand_index.csv` seeds known chain names and Arabic/English aliases (including Kudu, McDonald's, Bayt Al Shawarma, Rosmar and Fawwar Al Taif).
- The build script links a physical place to a brand only when its source brand name or full place name exactly matches a normalized registered alias. Unmatched branches remain unlinked; no branches or coordinates are fabricated.
- The report records brand counts and the number of linked branches. This is an initial index, not a complete catalogue of every chain in Riyadh.

## Scope
- Riyadh branches of restaurants, supermarkets, hypermarkets, groceries, pharmacies, cafes and bakeries.
- One record per physical branch, including coordinates, district, category, brand, source, license/attribution, confidence and verification timestamps.
- Deduplicate with a stable dedupe_key. Do not replace a higher-confidence record with a lower-confidence record without review.
- Delivery order observations are evidence received through an explicitly shared listing or an authorized feed; they are not inferred from public branch data.

## Important separation
Public business-place data establishes branch existence and mapped location; it does not establish current HungerStation order counts. Never infer order counts from branch counts, density, popularity or guesses. Import platform listing data only from a permitted official feed or a listing explicitly shared by the user, recording its provenance.

The order schema intentionally excludes customer names, phone numbers, pickup/delivery addresses and raw platform payloads. Platform order references are stored only as hashes when available.

## Candidate source register (confirm reuse terms before ingestion)
1. Royal Commission for Riyadh City dataset: https://opendata.rcrc.gov.sa/explore/dataset/commercial-services-by-category-sub-municipality-and-district-2024/api/ . Metadata says downloadable coordinates are available, but describes names and coordinates as extracted from Google Maps. Do not bulk-import or redistribute until reuse licensing and underlying-source terms are confirmed.
2. Riyadh Municipality Open Data: https://rmp.alriyadh.gov.sa/en/open-data
3. Ministry of Commerce Open Data: https://mc.gov.sa/en/OpenData/pages/default.aspx . Confirm API access, fields and usage terms before synchronization.
4. OpenStreetMap: comply with ODbL attribution and share-alike obligations. Overpass mirrors are not independent datasets.

## Data ingestion scripts

- `sync_riyadh_osm.py`: fetches mapped restaurant, cafe, grocery, supermarket, pharmacy and other shop features inside the Riyadh administrative area from OpenStreetMap via Overpass. It inserts/updates records in the existing SQLite schema, stores OSM element IDs and ODbL attribution, and prints a record-count summary.
- `import_riyadh_csv.py`: imports the Riyadh restaurant/cafe CSV format from the NearBite public dataset. Verify the upstream dataset's reuse terms before redistribution.

Run after applying the SQL migrations:
```bash
python database/sync_riyadh_osm.py path/to/hamed_smart_delivery.sqlite
python database/import_riyadh_csv.py path/to/hamed_smart_delivery.sqlite path/to/Riyadh2.csv
``

The sync scripts are committed, but they are not proof that the live app database has been populated. A database file and runtime access to the target environment are required to execute and verify ingestion. OpenStreetMap coverage depends on mapped features and is not guaranteed to include every business.

## Import/export contract
- Accept only SQLite files matching this schema version; validate before import.
- Validate Riyadh coverage, coordinates, required provenance and categories.
- Stage and validate before replacing live data; retain rollback copy.
- Export a consistent SQLite snapshot with schema version and source manifest.
- Show last successful update and record count in the app.
- Do not delete records merely because a source temporarily omits them; mark stale and re-verify.

## Android integration still required
The app still needs Room entities, DAOs, repository, migration tests, import/export UI and database-backed discovery. The Android share receiver must parse only supported, user-shared HungerStation content, map it to a known branch and write a minimized observation. Replace legacy discovery only after this path is integrated and tested. Preserve the current UI design.
