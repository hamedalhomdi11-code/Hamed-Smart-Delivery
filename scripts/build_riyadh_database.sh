#!/usr/bin/env bash
set -euo pipefail
mkdir -p data/source data/output app/src/main/assets
curl -L --fail --connect-timeout 20 --max-time 300 --retry 2 --retry-all-errors "https://huggingface.co/datasets/Bayanat-Labs/arab-world-places-atlas/resolve/main/data/places/places-SA.parquet?download=true" -o data/source/places-SA.parquet
test -s data/source/places-SA.parquet
python3 database/build_riyadh_atlas.py data/source/places-SA.parquet data/output/hamed_smart_delivery_riyadh.sqlite

# Latest Overture Places refresh; fail the build rather than silently shipping a stale/partial DB.
overturemaps download --bbox=46.20,24.05,47.35,25.15 -f geoparquet --type=place -o data/source/overture-riyadh-places.parquet
test -s data/source/overture-riyadh-places.parquet
python3 database/sync_riyadh_overture.py data/output/hamed_smart_delivery_riyadh.sqlite data/source/overture-riyadh-places.parquet

# Optional secondary sources; preserve the core build if public endpoints are temporarily unavailable.
if python3 database/sync_riyadh_wikidata.py data/output/hamed_smart_delivery_riyadh.sqlite > data/output/wikidata_sync.report.json 2>&1; then
  echo "Wikidata import completed"
else
  echo '{"status":"unavailable","source":"Wikidata Query Service"}' > data/output/wikidata_sync.report.json
fi
if curl -L --fail --connect-timeout 20 --max-time 240 --retry 1 --retry-all-errors "https://download.geofabrik.de/asia/gcc-states-latest.osm.pbf" -o data/source/gcc-states-latest.osm.pbf && test -s data/source/gcc-states-latest.osm.pbf; then
  osmium extract -b 46.20,24.05,47.35,25.15 data/source/gcc-states-latest.osm.pbf -o data/source/riyadh.osm.pbf --overwrite
  osmium tags-filter data/source/riyadh.osm.pbf nwr/shop nwr/amenity nwr/healthcare nwr/education nwr/tourism nwr/leisure nwr/sport nwr/craft nwr/office nwr/public_transport nwr/highway=bus_stop,services nwr/railway nwr/emergency nwr/aeroway nwr/club -o data/source/riyadh-pois.osm.pbf --overwrite
  osmium export data/source/riyadh-pois.osm.pbf --add-unique-id=type_id -o data/source/riyadh-pois.geojson --overwrite
  python3 database/sync_riyadh_geofabrik.py data/output/hamed_smart_delivery_riyadh.sqlite data/source/riyadh-pois.geojson > data/output/osm_sync.report.json
else
  echo '{"status":"unavailable","source":"Geofabrik OSM"}' > data/output/osm_sync.report.json
fi

python3 database/derive_riyadh_restaurant_clusters.py data/output/hamed_smart_delivery_riyadh.sqlite data/output/restaurant_clusters.report.json
python3 database/build_riyadh_food_hotspots_v2.py data/output/hamed_smart_delivery_riyadh.sqlite data/output/food_density_clusters_v2.report.json
python3 database/build_riyadh_food_hotspots_v3.py data/output/hamed_smart_delivery_riyadh.sqlite data/output/food_density_clusters_v3.report.json
python3 database/audit_riyadh_cross_source.py data/output/hamed_smart_delivery_riyadh.sqlite data/output/cross_source_duplicate_audit.json
python3 - <<'PY'
import hashlib, json, sqlite3
from pathlib import Path

db_path = Path('data/output/hamed_smart_delivery_riyadh.sqlite')
con = sqlite3.connect(db_path)
integrity = con.execute('PRAGMA integrity_check').fetchone()[0]
assert integrity == 'ok', f'SQLite integrity check failed: {integrity}'
active_total = con.execute("SELECT COUNT(*) FROM places WHERE is_active=1").fetchone()[0]
places_by_category = dict(con.execute(
    "SELECT category, COUNT(*) FROM places WHERE is_active=1 GROUP BY category"
).fetchall())
clusters_by_category = dict(con.execute(
    "SELECT category, COUNT(*) FROM food_density_clusters_v3 GROUP BY category"
).fetchall())
cluster_total = con.execute("SELECT COUNT(*) FROM food_density_clusters_v3").fetchone()[0]
pharmacies = con.execute(
    "SELECT COUNT(*) FROM places WHERE category='pharmacy' AND is_active=1"
).fetchone()[0]
source_counts = dict(con.execute(
    "SELECT source_id, COUNT(*) FROM places WHERE is_active=1 GROUP BY source_id"
).fetchall())
assert active_total >= 45000, f'Unexpectedly small Riyadh DB after the core Bayanat + Overture imports: {active_total}'
assert cluster_total >= 500, f'Expected at least 500 food clusters, got {cluster_total}'
assert clusters_by_category.get('restaurant', 0) > 0 and clusters_by_category.get('cafe', 0) > 0
assert pharmacies > 0

def load_report(path):
    file = Path(path)
    if not file.exists():
        return None
    try:
        return json.loads(file.read_text(encoding='utf-8'))
    except Exception:
        return {"status": "unreadable", "path": str(file)}

atlas = load_report(str(db_path) + '.report.json') or {}
overture = load_report(str(db_path) + '.overture.report.json') or {}
wikidata = load_report('data/output/wikidata_sync.report.json') or {}
geofabrik = load_report('data/output/osm_sync.report.json') or {}
clusters = load_report('data/output/food_density_clusters_v3.report.json') or {}
audit = load_report('data/output/cross_source_duplicate_audit.json') or {}

report = {
    "status": "success",
    "database_integrity": integrity,
    "active_riyadh_places_total": active_total,
    "active_places_by_category": places_by_category,
    "active_source_counts": source_counts,
    "v3_clusters_total": cluster_total,
    "v3_clusters_by_category": clusters_by_category,
    "active_pharmacies": pharmacies,
    "bayanat_source_rows_in_bbox": atlas.get("source_rows_in_riyadh_bbox"),
    "overture_geoparquet_rows_received": overture.get("geoparquet_rows_received"),
    "overture_latest_active_records": overture.get("overture_latest_active_records"),
    "wikidata_sync_status": wikidata.get("status", "completed_or_reported"),
    "geofabrik_sync_status": geofabrik.get("status", "completed_or_reported"),
    "cluster_method": clusters.get("method"),
    "cross_source_audit_candidates": audit.get("osm_records_with_likely_cross_source_duplicate"),
    "database_size_bytes": db_path.stat().st_size,
    "database_sha256": hashlib.sha256(db_path.read_bytes()).hexdigest()
}
Path('data/output/riyadh-build-report.json').write_text(
    json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8'
)
print(json.dumps(report, ensure_ascii=False))
con.close()
PY
cp data/output/hamed_smart_delivery_riyadh.sqlite app/src/main/assets/hamed_smart_delivery_riyadh.sqlite
