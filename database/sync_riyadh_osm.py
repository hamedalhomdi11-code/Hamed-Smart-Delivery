#!/usr/bin/env python3
"""Sync public Riyadh restaurant and retail POIs from OpenStreetMap into HAMED SMART DELIVERY.

Requirements: Python 3.10+, requests
Usage:
  python database/sync_riyadh_osm.py path/to/hamed_smart_delivery.sqlite

The script expects migrations 001_riyadh_places.sql and 002_delivery_platform_orders.sql
to have been applied. It uses OSM's Riyadh administrative boundary and Overpass API.
Respect the OpenStreetMap ODbL; attribution must be shown in the app.
"""
import hashlib
import json
import sqlite3
import sys
import time
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen

OVERPASS = "https://overpass-api.de/api/interpreter"
SOURCE_ID = "openstreetmap_riyadh"
SOURCE_URL = "https://www.openstreetmap.org/copyright"
USER_AGENT = "HAMED-SMART-DELIVERY/1.0 (Riyadh POI database sync)"
QUERY = """
[out:json][timeout:180];
area["name"="Riyadh"]["boundary"="administrative"]->.riyadh;
(
  nwr["amenity"~"^(restaurant|fast_food|cafe|ice_cream|food_court|bar)$"](area.riyadh);
  nwr["shop"](area.riyadh);
  nwr["craft"~"^(bakery|butcher|confectionery)$"](area.riyadh);
);
out center tags;
"""

def category(tags):
    amenity = tags.get("amenity", "")
    shop = tags.get("shop", "")
    craft = tags.get("craft", "")
    if amenity in {"restaurant", "fast_food", "food_court"}:
        return "restaurant"
    if amenity in {"cafe", "ice_cream", "bar"}:
        return "cafe"
    if craft == "bakery" or shop == "bakery":
        return "bakery"
    if shop in {"supermarket"}:
        return "supermarket"
    if shop in {"department_store", "mall", "wholesale"}:
        return "hypermarket"
    if shop in {"convenience", "greengrocer", "general"}:
        return "grocery"
    if shop == "pharmacy":
        return "pharmacy"
    return "other"

def fetch():
    body = urlencode({"data": QUERY}).encode()
    req = Request(OVERPASS, data=body, headers={
        "User-Agent": USER_AGENT,
        "Content-Type": "application/x-www-form-urlencoded; charset=utf-8",
    })
    with urlopen(req, timeout=240) as response:
        return json.loads(response.read().decode("utf-8"))

def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: sync_riyadh_osm.py <existing-sqlite-database>")
    db_path = sys.argv[1]
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys=ON")
    tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if not {"data_sources", "places"}.issubset(tables):
        raise SystemExit("Apply database/001_riyadh_places.sql before syncing.")
    con.execute("""INSERT OR IGNORE INTO data_sources
      (id,name,source_type,source_url,license_name,attribution)
      VALUES (?,?,?,?,?,?)""",
      (SOURCE_ID, "OpenStreetMap Riyadh POIs", "open_map", SOURCE_URL,
       "Open Database License (ODbL)", "© OpenStreetMap contributors"))
    payload = fetch()
    now = datetime.now(timezone.utc).isoformat()
    inserted = updated = skipped = 0
    with con:
        for el in payload.get("elements", []):
            tags = el.get("tags") or {}
            name = (tags.get("name") or tags.get("name:ar") or tags.get("brand") or "").strip()
            if not name:
                skipped += 1
                continue
            if el["type"] == "node":
                lat, lon = el.get("lat"), el.get("lon")
            else:
                center = el.get("center") or {}
                lat, lon = center.get("lat"), center.get("lon")
            try:
                lat, lon = float(lat), float(lon)
            except (TypeError, ValueError):
                skipped += 1
                continue
            address_parts = [tags.get(k) for k in ("addr:housenumber", "addr:street", "addr:suburb", "addr:city") if tags.get(k)]
            address = ", ".join(address_parts) or None
            district = tags.get("addr:suburb") or tags.get("addr:district")
            osm_id = f'{el["type"]}/{el["id"]}'
            key_material = f"osm:{osm_id}"
            dedupe_key = hashlib.sha256(key_material.encode()).hexdigest()
            place_id = "osm_" + hashlib.sha256(key_material.encode()).hexdigest()[:24]
            brand = tags.get("brand") or tags.get("brand:ar")
            con.execute("""INSERT INTO places
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
                confidence=0.85, is_active=1, last_verified_at=excluded.last_verified_at,
                last_updated_at=excluded.last_updated_at""",
              (place_id,SOURCE_ID,osm_id,name,tags.get("name:ar"),brand,category(tags),
               address,district,"Riyadh","SA",lat,lon,dedupe_key,"ODbL-1.0",0.85,now,now))
            if con.execute("SELECT changes()").fetchone()[0]:
                updated += 1
    total = con.execute("SELECT COUNT(*) FROM places WHERE city='Riyadh' AND country_code='SA' AND is_active=1").fetchone()[0]
    con.execute("UPDATE data_sources SET last_sync_at=? WHERE id=?", (now,SOURCE_ID))
    con.commit()
    con.close()
    print(json.dumps({"osm_elements_received":len(payload.get("elements",[])),
                      "processed_or_updated":updated,"skipped":skipped,
                      "active_riyadh_places_total":total,
                      "source":"OpenStreetMap","synced_at":now},ensure_ascii=False))

if __name__ == "__main__":
    main()
