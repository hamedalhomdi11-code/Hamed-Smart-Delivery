#!/usr/bin/env python3
"""Report cross-source duplicate candidates without deleting or merging records.

A candidate requires the same conservatively normalized name and coordinates
within 60 metres across Bayanat/Overture and OpenStreetMap. This is an audit
signal only: nearby branches can be distinct and must not be auto-merged.
"""
import json
import math
import re
import sqlite3
import sys
import unicodedata
from collections import defaultdict

CELL = 0.0005
MAX_METRES = 60.0

def norm(value):
    s = unicodedata.normalize("NFKC", str(value or "")).casefold().strip()
    s = re.sub(r"[\u064B-\u065F\u0670]", "", s)
    s = s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ى", "ي")
    return re.sub(r"[^\w]", "", s, flags=re.UNICODE)

def haversine(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2-lat1)
    dl = math.radians(lon2-lon1)
    a = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*r*math.asin(min(1.0, math.sqrt(a)))

def cell(lat, lon):
    return (math.floor(lat/CELL), math.floor(lon/CELL))

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: audit_riyadh_cross_source.py <sqlite-db> <report-json>")
    db_path, out_path = sys.argv[1:3]
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    base = con.execute("""
      SELECT id,name,category,latitude,longitude
      FROM places WHERE is_active=1 AND source_id IN ('bayanat_overture_sa_2026','overture_maps_latest')
    """).fetchall()
    osm = con.execute("""
      SELECT id,name,category,latitude,longitude
      FROM places WHERE is_active=1 AND source_id='openstreetmap_geofabrik_gcc'
    """).fetchall()
    index = defaultdict(list)
    for p in base:
        key = norm(p["name"])
        if key:
            index[(key, *cell(p["latitude"], p["longitude"]))].append(p)
    candidates = []
    distance_bands = {"0-15m": 0, "15-30m": 0, "30-60m": 0}
    for p in osm:
        key = norm(p["name"])
        if not key:
            continue
        cy, cx = cell(p["latitude"], p["longitude"])
        best = None
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for q in index.get((key, cy+dy, cx+dx), []):
                    d = haversine(p["latitude"], p["longitude"], q["latitude"], q["longitude"])
                    if d <= MAX_METRES and (best is None or d < best[0]):
                        best = (d, q)
        if best:
            d, q = best
            band = "0-15m" if d <= 15 else ("15-30m" if d <= 30 else "30-60m")
            distance_bands[band] += 1
            if len(candidates) < 500:
                candidates.append({
                    "osm_id": p["id"], "overture_id": q["id"],
                    "name": p["name"], "osm_category": p["category"],
                    "overture_category": q["category"],
                    "distance_metres": round(d, 1)
                })
    report = {
      "method": "exact normalized name + haversine distance <= 60m, cross-source only",
      "is_destructive": False,
      "overture_active_records": len(base),
      "overture_source_counts": dict(con.execute("SELECT source_id, COUNT(*) FROM places WHERE is_active=1 AND source_id IN ('bayanat_overture_sa_2026','overture_maps_latest') GROUP BY source_id").fetchall()),
      "osm_active_records": len(osm),
      "osm_records_with_likely_cross_source_duplicate": sum(distance_bands.values()),
      "distance_bands": distance_bands,
      "examples_capped_at": 500,
      "examples": candidates,
      "warning": "Candidates are not confirmed duplicates. Separate branches can be close together; no records were deleted or merged."
    }
    con.close()
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(json.dumps({k:v for k,v in report.items() if k != "examples"}, ensure_ascii=False))

if __name__ == "__main__":
    main()
