#!/usr/bin/env python3
"""Import named Riyadh-area public-interest POIs from Wikidata (CC0 structured data).

Only items with coordinates inside the project's Riyadh metro bounding box and
an explicit POI class are considered. Exact normalized-name matches within 35m
of an existing record are skipped; this is conservative and non-destructive.
"""
import hashlib
import json
import math
import re
import sqlite3
import sys
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

import requests

LAT_MIN, LAT_MAX = 24.05, 25.15
LON_MIN, LON_MAX = 46.20, 47.35
SOURCE_ID = "wikidata_riyadh_pois"
ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "HamedSmartDelivery-RiyadhPOI/1.0 (open-data importer; contact via GitHub repository)"

# Common POI classes and useful subclasses. Wikidata's subclass path expands
# these seeds while retaining an explicit, auditable class constraint.
CLASSES = [
    "Q11707", "Q27686", "Q16917", "Q3914", "Q3918", "Q11315",
    "Q22698", "Q33506", "Q7075", "Q1248784", "Q55488", "Q928830",
    "Q18674739", "Q32815", "Q16970", "Q839954", "Q570116",
    "Q180846", "Q13107184", "Q41253", "Q24354", "Q483110",
    "Q43501", "Q2281788", "Q515", "Q174782", "Q13226383",
    "Q856584", "Q124936", "Q15243209", "Q163740", "Q17350442"
]

def norm(value):
    s = unicodedata.normalize("NFKC", str(value or "")).casefold().strip()
    s = re.sub(r"[\u064B-\u065F\u0670]", "", s)
    s = s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ى", "ي")
    return re.sub(r"[^\w]", "", s, flags=re.UNICODE)

def metres(a_lat, a_lon, b_lat, b_lon):
    r = 6371000.0
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = math.radians(b_lat-a_lat), math.radians(b_lon-a_lon)
    x = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*r*math.asin(min(1.0, math.sqrt(x)))

def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: sync_riyadh_wikidata.py <sqlite-db>")
    db_path = sys.argv[1]
    values = " ".join("wd:" + x for x in CLASSES)
    query = f"""
    SELECT DISTINCT ?item ?itemLabel ?labelAr ?coord WHERE {{
      SERVICE wikibase:box {{
        ?item wdt:P625 ?coord .
        bd:serviceParam wikibase:cornerSouthWest "Point(46.20 24.05)"^^geo:wktLiteral .
        bd:serviceParam wikibase:cornerNorthEast "Point(47.35 25.15)"^^geo:wktLiteral .
      }}
      ?item wdt:P31/wdt:P279* ?class .
      VALUES ?class {{ {values} }}
      OPTIONAL {{ ?item rdfs:label ?labelAr . FILTER(LANG(?labelAr) = "ar") }}
      SERVICE wikibase:label {{ bd:serviceParam wikibase:language "en". }}
    }} LIMIT 10000
    """
    response = None
    for attempt in range(2):
        try:
            response = requests.get(
                ENDPOINT, params={"query": query, "format": "json"},
                headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"},
                timeout=(15, 60)
            )
            response.raise_for_status()
            break
        except requests.RequestException:
            if attempt == 1:
                raise
            time.sleep(5)
    bindings = response.json().get("results", {}).get("bindings", [])
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys=ON")
    now = datetime.now(timezone.utc).isoformat()
    con.execute("""INSERT OR IGNORE INTO data_sources
      (id,name,source_type,source_url,license_name,attribution)
      VALUES (?,?,?,?,?,?)""", (
        SOURCE_ID, "Wikidata named points of interest - Riyadh area", "open_map",
        "https://www.wikidata.org/wiki/Wikidata:Data_access",
        "CC0-1.0 (Wikidata structured data)",
        "Wikidata contributors; structured data released under CC0 1.0"
    ))
    existing = con.execute(
        "SELECT name, latitude, longitude FROM places WHERE is_active=1"
    ).fetchall()
    index = {}
    for name, lat, lon in existing:
        key = norm(name)
        if key:
            index.setdefault(key, []).append((float(lat), float(lon)))
    inserted = skipped = 0
    reasons = {"no_name": 0, "bad_coordinate": 0, "outside_bbox": 0, "near_existing_same_name": 0}
    for row in bindings:
        uri = row.get("item", {}).get("value", "")
        qid = uri.rsplit("/", 1)[-1]
        name = row.get("itemLabel", {}).get("value", "").strip()
        name_ar = row.get("labelAr", {}).get("value", "").strip() or None
        coord = row.get("coord", {}).get("value", "")
        if not name or name == qid:
            skipped += 1; reasons["no_name"] += 1; continue
        match = re.match(r"Point\(([-0-9.]+) ([-0-9.]+)\)", coord)
        if not match:
            skipped += 1; reasons["bad_coordinate"] += 1; continue
        lon, lat = float(match.group(1)), float(match.group(2))
        if not (LAT_MIN <= lat <= LAT_MAX and LON_MIN <= lon <= LON_MAX):
            skipped += 1; reasons["outside_bbox"] += 1; continue
        key = norm(name)
        if any(metres(lat, lon, x, y) <= 35 for x, y in index.get(key, [])):
            skipped += 1; reasons["near_existing_same_name"] += 1; continue
        dedupe = hashlib.sha256(("wikidata:" + qid).encode()).hexdigest()
        pid = "wd_" + dedupe[:24]
        cur = con.execute("""INSERT OR IGNORE INTO places
          (id,source_id,source_record_id,name,name_ar,category,city,country_code,
           latitude,longitude,dedupe_key,data_license,confidence,is_active,
           last_verified_at,last_updated_at)
          VALUES (?,?,?,?,?,'other','Riyadh','SA',?,?,?,?,0.75,1,?,?)""",
          (pid,SOURCE_ID,qid,name,name_ar,lat,lon,dedupe,"CC0-1.0",now,now))
        if cur.rowcount:
            inserted += 1
            index.setdefault(key, []).append((lat, lon))
        else:
            skipped += 1
    con.execute("UPDATE data_sources SET last_sync_at=? WHERE id=?", (now,SOURCE_ID))
    con.commit()
    total = con.execute("SELECT COUNT(*) FROM places WHERE is_active=1 AND city='Riyadh' AND country_code='SA'").fetchone()[0]
    source_total = con.execute("SELECT COUNT(*) FROM places WHERE source_id=? AND is_active=1",(SOURCE_ID,)).fetchone()[0]
    con.close()
    report = {
        "source": "Wikidata Query Service / Wikidata structured data",
        "license": "CC0-1.0",
        "query_items_received": len(bindings),
        "inserted_new_records": inserted,
        "skipped": skipped,
        "skip_reasons": reasons,
        "wikidata_active_records": source_total,
        "active_riyadh_places_total": total,
        "dedupe_policy": "same normalized name within 35m of an existing active record; non-destructive",
        "synced_at": now
    }
    Path(str(db_path) + ".wikidata.report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))

if __name__ == "__main__":
    main()
