#!/usr/bin/env python3
"""Merge the latest official Overture Places release into the Riyadh database.

The GERS ID is used as the cross-release identity key. Existing Bayanat/Overture
rows with the same GERS ID are refreshed in place; genuinely new IDs are added.
No fuzzy merge or destructive deletion is performed.
"""
import hashlib
import json
import re
import sqlite3
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
from shapely import wkb
from shapely.geometry import shape

SOURCE_ID = "overture_maps_latest"
SOURCE_URL = "https://docs.overturemaps.org/guides/places/"
SOURCE_ATTRIBUTION = "Overture Maps Foundation and contributing providers; see per-record source license."
LAT_MIN, LAT_MAX = 24.05, 25.15
LON_MIN, LON_MAX = 46.20, 47.35

LICENSE_BY_PROVIDER = {
    "alltheplaces": "CC0-1.0",
    "foursquare": "Apache-2.0",
}
DEFAULT_LICENSE = "CDLA-Permissive-2.0 (Overture Places; verify provider attribution)"

def get(obj, *path, default=None):
    cur = obj
    for key in path:
        if isinstance(cur, dict):
            cur = cur.get(key)
        else:
            return default
    return default if cur is None else cur

def first_text(*values):
    for value in values:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None

def as_list(value):
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]

def category_for(row):
    basic = str(row.get("basic_category") or "").strip().lower()
    taxonomy = row.get("taxonomy") or {}
    primary = str(get(taxonomy, "primary", default="") or "").lower()
    hierarchy = " ".join(str(x).lower() for x in as_list(get(taxonomy, "hierarchy")))
    labels = " ".join((basic, primary, hierarchy))
    if any(x in labels for x in ("pharmacy", "drugstore")):
        return "pharmacy"
    if "hypermarket" in labels:
        return "hypermarket"
    if any(x in labels for x in ("supermarket", "grocery_store", "food_store")):
        return "supermarket" if "supermarket" in labels else "grocery"
    if any(x in labels for x in ("convenience_store", "convenience")):
        return "grocery"
    if any(x in labels for x in ("bakery", "bake_shop")):
        return "bakery"
    if any(x in labels for x in ("coffee_shop", "cafe", "tea_house")):
        return "cafe"
    if any(x in labels for x in ("restaurant", "fast_food", "food_court", "pizza", "burger", "sushi", "meal_takeaway")):
        return "restaurant"
    if not (basic or primary or hierarchy):
        return None
    return "other"

def geometry_point(value):
    if value is None:
        return None
    try:
        if isinstance(value, (bytes, bytearray, memoryview)):
            geom = wkb.loads(bytes(value))
        elif isinstance(value, str):
            geom = wkb.loads(bytes.fromhex(value))
        elif hasattr(value, "geom_type"):
            geom = value
        else:
            geom = shape(value)
        if geom.is_empty:
            return None
        point = geom if geom.geom_type == "Point" else geom.representative_point()
        return float(point.y), float(point.x)
    except Exception:
        return None

def row_license(sources):
    providers = []
    for src in as_list(sources):
        if isinstance(src, dict):
            provider = str(src.get("dataset") or src.get("provider") or "").strip().lower()
            if provider:
                providers.append(provider)
    if not providers:
        return DEFAULT_LICENSE
    licenses = sorted({LICENSE_BY_PROVIDER.get(p, "CDLA-Permissive-2.0") for p in providers})
    return ", ".join(licenses) + " (source providers: " + ", ".join(sorted(set(providers))) + ")"

def norm_brand(value):
    s = unicodedata.normalize("NFKC", str(value or "")).casefold().strip()
    s = re.sub(r"[\u064B-\u065F\u0670]", "", s)
    s = s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ى", "ي")
    return re.sub(r"[^\w]", "", s, flags=re.UNICODE)

def clean(value):
    if value is None:
        return None
    if isinstance(value, str):
        value = value.strip()
        return value or None
    return str(value).strip() or None

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: sync_riyadh_overture.py <sqlite-db> <geoparquet>")
    db_path, parquet_path = sys.argv[1:3]
    df = pd.read_parquet(Path(parquet_path))
    required = {"id", "names", "geometry", "basic_category", "taxonomy"}
    missing = required - set(df.columns)
    if missing:
        raise SystemExit(f"Overture schema missing columns: {sorted(missing)}")

    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys=ON")
    now = datetime.now(timezone.utc).isoformat()
    con.execute("""INSERT OR IGNORE INTO data_sources
      (id,name,source_type,source_url,license_name,attribution)
      VALUES (?,?,?,?,?,?)""", (
        SOURCE_ID, "Overture Maps Places - latest monthly release", "open_map",
        SOURCE_URL, "CDLA-Permissive-2.0 / Apache-2.0 / CC0-1.0 (provider-dependent)",
        SOURCE_ATTRIBUTION
    ))

    inserted = updated = skipped = brand_links = 0
    reasons = {"no_name": 0, "no_geometry": 0, "outside_bbox": 0, "no_category": 0, "low_confidence": 0, "duplicate_id_in_file": 0}
    categories = {}
    license_counts = {}
    seen_ids = set()
    aliases = dict(con.execute("SELECT normalized_alias, brand_id FROM brand_aliases").fetchall())

    with con:
        for row in df.to_dict(orient="records"):
            gers_id = clean(row.get("id"))
            if not gers_id:
                skipped += 1
                reasons["duplicate_id_in_file"] += 1
                continue
            if gers_id in seen_ids:
                skipped += 1
                reasons["duplicate_id_in_file"] += 1
                continue
            seen_ids.add(gers_id)

            names = row.get("names") or {}
            name = first_text(get(names, "primary"), get(names, "common", "en"), get(names, "common", "ar"))
            if not name:
                skipped += 1
                reasons["no_name"] += 1
                continue

            point = geometry_point(row.get("geometry"))
            if not point:
                skipped += 1
                reasons["no_geometry"] += 1
                continue
            lat, lon = point
            if not (LAT_MIN <= lat <= LAT_MAX and LON_MIN <= lon <= LON_MAX):
                skipped += 1
                reasons["outside_bbox"] += 1
                continue

            category = category_for(row)
            if not category:
                skipped += 1
                reasons["no_category"] += 1
                continue
            try:
                confidence = float(row.get("confidence") if row.get("confidence") is not None else 0.5)
            except Exception:
                confidence = 0.5
            if confidence < 0.20:
                skipped += 1
                reasons["low_confidence"] += 1
                continue
            confidence = max(0.0, min(1.0, confidence))

            brand_obj = row.get("brand") or {}
            brand_name = first_text(get(brand_obj, "names", "primary"), get(brand_obj, "name"))
            address_items = [x for x in as_list(row.get("addresses")) if isinstance(x, dict)]
            address_obj = address_items[0] if address_items else {}
            address = first_text(address_obj.get("freeform"))
            district = first_text(address_obj.get("locality"), address_obj.get("neighborhood"))
            common = get(names, "common", default={})
            name_ar = None
            if isinstance(common, dict):
                name_ar = first_text(common.get("ar"))
                if isinstance(common.get("ar"), list):
                    name_ar = first_text(*common["ar"])
            elif isinstance(common, list):
                for item in common:
                    if isinstance(item, dict) and str(item.get("language") or item.get("language_code") or "").lower() == "ar":
                        name_ar = first_text(item.get("value"), item.get("name"))
                        if name_ar:
                            break

            license_text = row_license(row.get("sources"))
            license_counts[license_text.split(" (")[0]] = license_counts.get(license_text.split(" (")[0], 0) + 1
            existing = con.execute(
                "SELECT id FROM places WHERE source_record_id=? AND source_id IN ('bayanat_overture_sa_2026', ?) LIMIT 1",
                (gers_id, SOURCE_ID)
            ).fetchone()
            if existing:
                place_id = existing[0]
                con.execute("""UPDATE places SET source_id=?, name=?, name_ar=?, brand=?, category=?,
                  address=COALESCE(?,address), district=COALESCE(?,district), city='Riyadh',
                  country_code='SA', latitude=?, longitude=?, data_license=?, confidence=?,
                  is_active=1, last_verified_at=?, last_updated_at=? WHERE id=?""",
                  (SOURCE_ID, name, name_ar, brand_name, category, address, district,
                   lat, lon, license_text, confidence, now, now, place_id))
                updated += 1
            else:
                key = hashlib.sha256(("overture_latest:" + gers_id).encode()).hexdigest()
                place_id = "ovm_" + key[:24]
                con.execute("""INSERT OR IGNORE INTO places
                  (id,source_id,source_record_id,name,name_ar,brand,category,address,district,city,
                   country_code,latitude,longitude,dedupe_key,data_license,confidence,is_active,
                   last_verified_at,last_updated_at)
                  VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?)""",
                  (place_id,SOURCE_ID,gers_id,name,name_ar,brand_name,category,address,district,
                   "Riyadh","SA",lat,lon,key,license_text,confidence,now,now))
                inserted += con.execute("SELECT changes()").fetchone()[0]

            brand_key = norm_brand(brand_name) or norm_brand(name)
            brand_id = aliases.get(brand_key)
            if brand_id:
                con.execute("""INSERT OR IGNORE INTO brand_place_links
                  (brand_id,place_id,match_method,match_confidence) VALUES (?,?,?,?)""",
                  (brand_id, place_id, "source_brand_name" if brand_name else "exact_alias_name",
                   0.95 if brand_name else 0.85))
                brand_links += con.execute("SELECT changes()").fetchone()[0]
            categories[category] = categories.get(category, 0) + 1

    con.execute("UPDATE data_sources SET last_sync_at=? WHERE id=?", (now, SOURCE_ID))
    total = con.execute("SELECT COUNT(*) FROM places WHERE city='Riyadh' AND country_code='SA' AND is_active=1").fetchone()[0]
    source_total = con.execute("SELECT COUNT(*) FROM places WHERE source_id=? AND is_active=1", (SOURCE_ID,)).fetchone()[0]
    con.close()
    report = {
        "source": "Overture Maps Foundation Places theme",
        "release": "latest release resolved through Overture STAC at sync time",
        "source_url": SOURCE_URL,
        "license_policy": "CDLA-Permissive-2.0 / Apache-2.0 / CC0-1.0 depending on source provider",
        "attribution": SOURCE_ATTRIBUTION,
        "geoparquet_rows_received": int(len(df)),
        "inserted_new_gers_ids": inserted,
        "updated_existing_gers_ids": updated,
        "skipped": skipped,
        "skip_reasons": reasons,
        "records_seen_by_category": categories,
        "records_by_license": license_counts,
        "brand_links_created": brand_links,
        "overture_latest_active_records": source_total,
        "active_riyadh_places_total": total,
        "synced_at": now
    }
    Path(str(db_path) + ".overture.report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))

if __name__ == "__main__":
    main()
