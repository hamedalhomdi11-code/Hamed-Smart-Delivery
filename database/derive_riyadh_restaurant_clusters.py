#!/usr/bin/env python3
"""Derive Riyadh restaurant-density clusters from actual active restaurant POIs.

Clusters are generated from the current SQLite records, not hand-invented
coordinates. DBSCAN uses haversine distance (300 m epsilon, 5 minimum points).
Each cluster stores its arithmetic geographic centroid and links member POIs.
"""
import hashlib
import json
import math
import sqlite3
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

EARTH_M = 6371000.0
EPS_METRES = 300.0
MIN_SAMPLES = 5
CELL_LAT = EPS_METRES / 111000.0
# Riyadh latitude ~24.7: one longitude degree is approximately 101 km.
CELL_LON = EPS_METRES / 101000.0

def haversine(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_M * math.asin(min(1.0, math.sqrt(a)))

def cell(lat, lon):
    return (math.floor(lat / CELL_LAT), math.floor(lon / CELL_LON))

def find(parent, x):
    while parent[x] != x:
        parent[x] = parent[parent[x]]
        x = parent[x]
    return x

def union(parent, a, b):
    ra, rb = find(parent, a), find(parent, b)
    if ra != rb:
        parent[rb] = ra

def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: derive_riyadh_restaurant_clusters.py <sqlite-db> <report-json>")
    db_path, report_path = sys.argv[1], Path(sys.argv[2])
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA foreign_keys=ON")
    rows = con.execute("""
      SELECT id,name,district,latitude,longitude,source_id,data_license
      FROM places
      WHERE is_active=1 AND city='Riyadh' AND country_code='SA'
        AND category='restaurant'
    """).fetchall()

    # Grid-assisted neighbourhood lookup keeps the DBSCAN pass practical
    # without requiring an additional compiled dependency.
    buckets = defaultdict(list)
    for i, row in enumerate(rows):
        buckets[cell(float(row[3]), float(row[4]))].append(i)

    neighbours = []
    for i, row in enumerate(rows):
        lat, lon = float(row[3]), float(row[4])
        cy, cx = cell(lat, lon)
        near = []
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for j in buckets.get((cy + dy, cx + dx), []):
                    other = rows[j]
                    if haversine(lat, lon, float(other[3]), float(other[4])) <= EPS_METRES:
                        near.append(j)
        neighbours.append(near)

    core = [len(n) >= MIN_SAMPLES for n in neighbours]
    parent = list(range(len(rows)))
    for i, near in enumerate(neighbours):
        if core[i]:
            for j in near:
                if core[j]:
                    union(parent, i, j)

    components = defaultdict(list)
    for i, is_core in enumerate(core):
        if is_core:
            components[find(parent, i)].append(i)

    # Attach non-core border points to their nearest core component.
    assigned = {i: root for root, members in components.items() for i in members}
    for i, is_core in enumerate(core):
        if is_core:
            continue
        candidates = [j for j in neighbours[i] if core[j]]
        if candidates:
            nearest = min(candidates, key=lambda j: haversine(
                float(rows[i][3]), float(rows[i][4]), float(rows[j][3]), float(rows[j][4])))
            assigned[i] = find(parent, nearest)

    groups = defaultdict(list)
    for i, root in assigned.items():
        groups[find(parent, root)].append(i)
    groups = [members for members in groups.values() if len(members) >= MIN_SAMPLES]
    groups.sort(key=lambda members: (-len(members),
        sum(float(rows[i][3]) for i in members) / len(members),
        sum(float(rows[i][4]) for i in members) / len(members)))

    con.executescript("""
      CREATE TABLE IF NOT EXISTS restaurant_clusters (
        cluster_id TEXT PRIMARY KEY NOT NULL,
        name TEXT NOT NULL,
        district TEXT,
        center_latitude REAL NOT NULL,
        center_longitude REAL NOT NULL,
        radius_m REAL NOT NULL,
        member_count INTEGER NOT NULL,
        algorithm TEXT NOT NULL,
        eps_m REAL NOT NULL,
        min_samples INTEGER NOT NULL,
        source_mix_json TEXT NOT NULL,
        data_licenses_json TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS restaurant_cluster_members (
        cluster_id TEXT NOT NULL REFERENCES restaurant_clusters(cluster_id) ON DELETE CASCADE,
        place_id TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
        distance_to_center_m REAL NOT NULL,
        PRIMARY KEY(cluster_id, place_id)
      );
      CREATE INDEX IF NOT EXISTS idx_restaurant_clusters_center
        ON restaurant_clusters(center_latitude, center_longitude);
      CREATE INDEX IF NOT EXISTS idx_restaurant_cluster_members_place
        ON restaurant_cluster_members(place_id);
    """)
    with con:
        con.execute("DELETE FROM restaurant_cluster_members")
        con.execute("DELETE FROM restaurant_clusters")
        now = datetime.now(timezone.utc).isoformat()
        cluster_summaries = []
        clustered_ids = set()
        for number, members in enumerate(groups, 1):
            member_rows = [rows[i] for i in members]
            center_lat = sum(float(r[3]) for r in member_rows) / len(member_rows)
            center_lon = sum(float(r[4]) for r in member_rows) / len(member_rows)
            radius = max(haversine(center_lat, center_lon, float(r[3]), float(r[4])) for r in member_rows)
            district_counts = Counter(str(r[2]).strip() for r in member_rows if r[2] and str(r[2]).strip())
            district = district_counts.most_common(1)[0][0] if district_counts else None
            source_mix = dict(Counter(str(r[5]) for r in member_rows))
            licenses = sorted({str(r[6]) for r in member_rows if r[6]})
            member_keys = sorted(str(r[0]) for r in member_rows)
            digest = hashlib.sha256(("riyadh-restaurant-cluster-v1:" + "|".join(member_keys)).encode()).hexdigest()[:20]
            cluster_id = "rcl_" + digest
            label = f"تجمع مطاعم الرياض {number:03d}" + (f" - {district}" if district else "")
            con.execute("""INSERT INTO restaurant_clusters
              (cluster_id,name,district,center_latitude,center_longitude,radius_m,member_count,
               algorithm,eps_m,min_samples,source_mix_json,data_licenses_json,created_at)
              VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
              (cluster_id,label,district,center_lat,center_lon,radius,len(member_rows),
               "DBSCAN-haversine-v1",EPS_METRES,MIN_SAMPLES,
               json.dumps(source_mix,ensure_ascii=False,sort_keys=True),
               json.dumps(licenses,ensure_ascii=False),now))
            for r in member_rows:
                distance = haversine(center_lat, center_lon, float(r[3]), float(r[4]))
                con.execute("""INSERT INTO restaurant_cluster_members
                  (cluster_id,place_id,distance_to_center_m) VALUES (?,?,?)""",
                  (cluster_id,r[0],distance))
                clustered_ids.add(r[0])
            cluster_summaries.append({
                "cluster_id": cluster_id, "name": label, "district": district,
                "center_latitude": round(center_lat, 7), "center_longitude": round(center_lon, 7),
                "radius_m": round(radius, 1), "restaurant_count": len(member_rows),
                "source_mix": source_mix
            })

    total = len(rows)
    clustered = len(clustered_ids)
    report = {
        "status": "success",
        "source": "Derived only from active restaurant rows in this SQLite database",
        "method": "DBSCAN with haversine distance; centroids are arithmetic means of member coordinates",
        "parameters": {"eps_metres": EPS_METRES, "min_samples": MIN_SAMPLES},
        "restaurant_poi_input_count": total,
        "cluster_count": len(groups),
        "clustered_restaurant_count": clustered,
        "unclustered_restaurant_count": total - clustered,
        "cluster_table": "restaurant_clusters",
        "membership_table": "restaurant_cluster_members",
        "clusters": cluster_summaries,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "caveat": "These are algorithmically inferred restaurant-density clusters, not official district boundaries or manually verified commercial zones."
    }
    con.close()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k != "clusters"} | {"clusters_preview": cluster_summaries[:10]},ensure_ascii=False))

if __name__ == "__main__":
    main()
