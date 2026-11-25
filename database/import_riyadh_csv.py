#!/usr/bin/env python3
"""Import Riyadh restaurant/cafe CSV rows into the existing HAMED SMART DELIVERY SQLite schema.

Expected CSV columns (from NearBite dataset):
name,type,full_address,street,city,latitude,longitude,phone,rating,working_hours,business_status,location_link
Run: python database/import_riyadh_csv.py <database.sqlite> <Riyadh2.csv>
"""
import csv
import hashlib
import json
import sqlite3
import sys
from pathlib import Path

SOURCE_ID = "nearbite_riyadh_csv"
SOURCE_URL = "https://github.com/MrSanad26/NearBite"
ALLOWED = {"restaurant", "cafe", "bakery", "other"}

def category(value):
    v = (value or "").strip().lower()
    if any(x in v for x in ("cafe", "coffee")):
        return "cafe"
    if any(x in v for x in ("bakery", "pastry")):
        return "bakery"
    if any(x in v for x in ("restaurant", "food", "مطعم")):
        return "restaurant"
    return "other"

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: import_riyadh_csv.py <database.sqlite> <Riyadh2.csv>")
    db_path, csv_path = map(Path, sys.argv[1:])
    if not db_path.is_file() or not csv_path.is_file():
        raise SystemExit("Database or CSV file not found.")
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("""INSERT OR IGNORE INTO data_sources
      (id,name,source_type,source_url,license_name,attribution)
      VALUES (?,?,?,?,?,?)""",
      (SOURCE_ID, "NearBite Riyadh restaurant/cafe dataset", "open_map",
       SOURCE_URL, None, "Dataset attribution: MrSanad26/NearBite; verify upstream terms before redistribution."))
    inserted = skipped = 0
    with csv_path.open("r", encoding="utf-8-sig", newline="") as f, con:
        reader = csv.DictReader(f)
        for row in reader:
            name = (row.get("name") or "").strip()
            city = (row.get("city") or "Riyadh").strip()
            if not name or "riyadh" not in city.lower() and "الرياض" not in city:
                skipped += 1
                continue
            try:
                lat = float(row.get("latitude", ""))
                lon = float(row.get("longitude", ""))
                if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                    raise ValueError
            except (TypeError, ValueError):
                skipped += 1
                continue
            address = (row.get("full_address") or row.get("street") or "").strip() or None
            source_record_id = (row.get("location_link") or "").strip() or None
            raw_key = "|".join((name.casefold(), address or "", f"{lat:.6f}", f"{lon:.6f}"))
            dedupe_key = hashlib.sha256(raw_key.encode("utf-8")).hexdigest()
            place_id = "nb_" + dedupe_key[:24]
            con.execute("""INSERT OR IGNORE INTO places
              (id,source_id,source_record_id,name,brand,category,address,city,country_code,
               latitude,longitude,dedupe_key,confidence,is_active)
              VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1)""",
              (place_id, SOURCE_ID, source_record_id, name, None,
               category(row.get("type")), address, "Riyadh", "SA", lat, lon,
               dedupe_key, 0.75))
            if con.execute("SELECT changes()").fetchone()[0]:
                inserted += 1
            else:
                skipped += 1
    total = con.execute("SELECT COUNT(*) FROM places WHERE city='Riyadh' AND country_code='SA'").fetchone()[0]
    con.close()
    print(json.dumps({"inserted": inserted, "skipped_or_duplicate": skipped, "total_riyadh_places": total}, ensure_ascii=False))

if __name__ == "__main__":
    main()
