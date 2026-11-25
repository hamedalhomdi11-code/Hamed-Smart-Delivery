#!/usr/bin/env python3
"""Import a broad, named OpenStreetMap POI set from the Geofabrik GCC extract.

The import is intentionally additive and source-preserving. It includes food,
retail, health, public services, tourism, leisure and transport POIs. Non-food
POIs remain in the existing 'other' category to avoid changing the app schema.
Attribution: © OpenStreetMap contributors, ODbL 1.0.
"""
import hashlib
import json
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

SOURCE_ID = "openstreetmap_geofabrik_gcc"
SOURCE_URL = "https://download.geofabrik.de/asia/gcc-states.html"
USER_AGENT_NOTE = "© OpenStreetMap contributors"

def classify(tags):
    amenity = tags.get("amenity", "")
    shop = tags.get("shop", "")
    craft = tags.get("craft", "")
    healthcare = tags.get("healthcare", "")
    if amenity in {"restaurant", "fast_food", "food_court"}:
        return "restaurant"
    if amenity in {"cafe", "ice_cream"}:
        return "cafe"
    if craft == "bakery" or shop == "bakery":
        return "bakery"
    if shop == "pharmacy" or amenity == "pharmacy" or healthcare == "pharmacy":
        return "pharmacy"
    if shop == "supermarket":
        return "supermarket"
    if shop in {"department_store", "mall", "wholesale", "hypermarket"}:
        return "hypermarket"
    if shop in {"convenience", "greengrocer", "general", "grocery"}:
        return "grocery"
    return "other"

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: sync_riyadh_geofabrik.py <sqlite-db> <geojson>")
    db_path, geojson_path = sys.argv[1:]
    data = json.loads(Path(geojson_path).read_text(encoding="utf-8"))
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys=ON")
    now = datetime.now(timezone.utc).isoformat()
    con.execute("""INSERT OR IGNORE INTO data_sources
      (id,name,source_type,source_url,license_name,attribution)
      VALUES (?,?,?,?,?,?)""",
      (SOURCE_ID, "OpenStreetMap Geofabrik GCC extract (Riyadh clip)", "open_map",
       SOURCE_URL, "Open Database License (ODbL) 1.0", USER_AGENT_NOTE))
    inserted = skipped = 0
    skip_reasons = {"no_name": 0, "no_geometry": 0, "bad_geometry": 0, "outside_bbox": 0, "no_osm_id": 0}
    category_counts = {}
    for feature in data.get("features", []):
        props = feature.get("properties") or {}
        tags = {str(k): str(v) for k, v in props.items() if not str(k).startswith("@") and v is not None}
        name = (tags.get("name") or tags.get("name:ar") or tags.get("name:en")
                or tags.get("official_name") or tags.get("brand") or tags.get("brand:en")
                or tags.get("brand:ar") or tags.get("operator") or "").strip()
        if not name:
            skipped += 1
            skip_reasons["no_name"] += 1
            continue
        geom = feature.get("geometry") or {}
        coords = geom.get("coordinates")
        if not coords:
            skipped += 1
            skip_reasons["no_geometry"] += 1
            continue
        try:
            from shapely.geometry import shape
            point = shape(geom).representative_point()
            lon, lat = float(point.x), float(point.y)
        except Exception:
            skipped += 1
            skip_reasons["bad_geometry"] += 1
            continue
        if not (24.05 <= lat <= 25.15 and 46.20 <= lon <= 47.35):
            skipped += 1
            skip_reasons["outside_bbox"] += 1
            continue
        osm_identity = feature.get("id") or props.get("@id") or props.get("osm_id") or props.get("id")
        if not osm_identity:
            skipped += 1
            skip_reasons["no_osm_id"] += 1
            continue
        osm_identity = str(osm_identity)
        if "/" not in osm_identity:
            osm_type = props.get("@type") or props.get("type") or "feature"
            osm_identity = f"{osm_type}/{osm_identity}"
        key_material = "osm:" + osm_identity
        dedupe_key = hashlib.sha256(key_material.encode()).hexdigest()
        place_id = "osm_" + hashlib.sha256(key_material.encode()).hexdigest()[:24]
        address_parts = [tags.get(k) for k in (
            "addr:housenumber", "addr:street", "addr:suburb", "addr:city"
        ) if tags.get(k)]
        address = ", ".join(address_parts) or None
        district = tags.get("addr:suburb") or tags.get("addr:district")
        brand = tags.get("brand") or tags.get("brand:ar")
        category = classify(tags)
        cur = con.execute("""INSERT INTO places
          (id,source_id,source_record_id,name,name_ar,brand,category,address,district,city,
           country_code,latitude,longitude,dedupe_key,data_license,confidence,is_active,
           last_verified_at,last_updated_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)
          ON CONFLICT(dedupe_key) DO UPDATE SET
            name=excluded.name, name_ar=COALESCE(excluded.name_ar,places.name_ar),
            brand=COALESCE(excluded.brand,places.brand), category=excluded.category,
            address=COALESCE(excluded.address,places.address),
            district=COALESCE(excluded.district,places.district),
            latitude=excluded.latitude, longitude=excluded.longitude,
            confidence=0.9, is_active=1, last_verified_at=excluded.last_verified_at,
            last_updated_at=excluded.last_updated_at""",
          (place_id,SOURCE_ID,osm_identity,name,tags.get("name:ar"),brand,category,
           address,district,"Riyadh","SA",lat,lon,dedupe_key,"ODbL-1.0",0.9,now,now))
        if cur.rowcount:
            inserted += 1
        category_counts[category] = category_counts.get(category, 0) + 1
    con.execute("UPDATE data_sources SET last_sync_at=? WHERE id=?", (now,SOURCE_ID))
    con.commit()
    total = con.execute("SELECT COUNT(*) FROM places WHERE city='Riyadh' AND country_code='SA' AND is_active=1").fetchone()[0]
    source_total = con.execute("SELECT COUNT(*) FROM places WHERE source_id=? AND is_active=1", (SOURCE_ID,)).fetchone()[0]
    con.close()
    report = {
        "geojson_features_received": len(data.get("features", [])),
        "inserted_or_updated": inserted,
        "skipped": skipped,
        "skip_reasons": skip_reasons,
        "records_seen_by_category": category_counts,
        "geofabrik_source_records_active": source_total,
        "active_riyadh_places_total": total,
        "source": "OpenStreetMap / Geofabrik GCC extract",
        "scope": "named food, retail, health, public-service, tourism, leisure and transport POIs",
        "license": "ODbL-1.0",
        "attribution": "© OpenStreetMap contributors",
        "synced_at": now
    }
    print(json.dumps(report, ensure_ascii=False))

if __name__ == "__main__":
    main()
