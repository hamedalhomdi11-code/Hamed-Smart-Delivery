#!/usr/bin/env bash
set -euo pipefail
mkdir -p data/source data/output app/src/main/assets
curl -L --fail --retry 5 --retry-all-errors "https://huggingface.co/datasets/Bayanat-Labs/arab-world-places-atlas/resolve/main/data/places/places-SA.parquet?download=true" -o data/source/places-SA.parquet
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
if curl -L --fail --retry 3 --retry-all-errors "https://download.geofabrik.de/asia/gcc-states-latest.osm.pbf" -o data/source/gcc-states-latest.osm.pbf && test -s data/source/gcc-states-latest.osm.pbf; then
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
import json, sqlite3
p='data/output/hamed_smart_delivery_riyadh.sqlite'
c=sqlite3.connect(p)
assert c.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
assert c.execute('SELECT COUNT(*) FROM food_density_clusters_v3').fetchone()[0] > 0
assert c.execute("SELECT COUNT(*) FROM places WHERE category='pharmacy' AND is_active=1").fetchone()[0] > 0
print(json.dumps({'places':c.execute('SELECT COUNT(*) FROM places WHERE is_active=1').fetchone()[0],
'v3_clusters':c.execute('SELECT COUNT(*) FROM food_density_clusters_v3').fetchone()[0],
'pharmacies':c.execute("SELECT COUNT(*) FROM places WHERE category='pharmacy' AND is_active=1").fetchone()[0]},ensure_ascii=False))
c.close()
PY
cp data/output/hamed_smart_delivery_riyadh.sqlite app/src/main/assets/hamed_smart_delivery_riyadh.sqlite
cp data/output/hamed_smart_delivery_riyadh.sqlite.report.json data/output/riyadh-build-report.json
