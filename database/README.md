# Riyadh places database

Canonical interchange format: SQLite 3 (.sqlite or .db). CSV and GeoJSON are optional secondary exports.

## Status
This folder defines the database contract. It does not yet contain a complete verified Riyadh dataset. No synthetic branches or coordinates are inserted.

## Scope
- Riyadh branches of restaurants, supermarkets, hypermarkets, groceries, pharmacies, cafes and bakeries.
- One record per physical branch, including coordinates, district, category, brand, source, license/attribution, confidence and verification timestamps.
- Deduplicate with a stable dedupe_key. Do not replace a higher-confidence record with a lower-confidence record without review.
- Observations are provenance records, not live order counts.

## Important separation
Public business-place data establishes branch existence and mapped location; it does not establish current HungerStation order counts. Never infer order counts from branch counts, density, popularity or guesses. Import platform listing data only from a permitted official feed or a listing explicitly shared by the user, recording its provenance.

## Candidate source register (confirm reuse terms before ingestion)
1. Royal Commission for Riyadh City dataset: https://opendata.rcrc.gov.sa/explore/dataset/commercial-services-by-category-sub-municipality-and-district-2024/api/ . Metadata says downloadable coordinates are available, but describes names and coordinates as extracted from Google Maps. Do not bulk-import or redistribute until reuse licensing and underlying-source terms are confirmed.
2. Riyadh Municipality Open Data: https://rmp.alriyadh.gov.sa/en/open-data
3. Ministry of Commerce Open Data: https://mc.gov.sa/en/OpenData/pages/default.aspx . Confirm API access, fields and usage terms before synchronization.
4. OpenStreetMap: comply with ODbL attribution and share-alike obligations. Overpass mirrors are not independent datasets.

## Import/export contract
- Accept only SQLite files matching this schema version; validate before import.
- Validate Riyadh coverage, coordinates, required provenance and categories.
- Stage and validate before replacing live data; retain rollback copy.
- Export a consistent SQLite snapshot with schema version and source manifest.
- Show last successful update and record count in the app.
- Do not delete records merely because a source temporarily omits them; mark stale and re-verify.

## Android integration still required
The app still needs Room entities, DAOs, repository, migration tests, import/export UI and database-backed discovery. Replace legacy discovery only after this path is integrated and tested. Preserve the current UI design.
