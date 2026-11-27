#!/usr/bin/env python3
"""Build a second, category-separated food hotspot layer without replacing v1 clusters."""
import sqlite3, math, json, hashlib, sys
from collections import defaultdict, Counter
from pathlib import Path
from datetime import datetime, timezone

EARTH=6371000.0
EPS=100.0
MIN=4
CLAT=EPS/111000.0
CLON=EPS/101000.0

def hav(a,b,c,d):
    p1,p2=math.radians(a),math.radians(c)
    dp=math.radians(c-a); dl=math.radians(d-b)
    x=math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*EARTH*math.asin(min(1,math.sqrt(x)))

def root(p,x):
    while p[x]!=x:
        p[x]=p[p[x]]; x=p[x]
    return x

def main():
    if len(sys.argv)!=3: raise SystemExit("Usage: build_riyadh_food_hotspots.py DB REPORT")
    db, out=sys.argv[1],Path(sys.argv[2])
    con=sqlite3.connect(db); con.execute("PRAGMA foreign_keys=ON")
    con.executescript("""
    CREATE TABLE IF NOT EXISTS food_density_clusters_v3(
      cluster_id TEXT PRIMARY KEY, category TEXT NOT NULL, name TEXT NOT NULL,
      district TEXT, center_latitude REAL NOT NULL, center_longitude REAL NOT NULL,
      radius_m REAL NOT NULL, member_count INTEGER NOT NULL, eps_m REAL NOT NULL,
      min_samples INTEGER NOT NULL, source_mix_json TEXT NOT NULL, created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS food_density_cluster_members_v3(
      cluster_id TEXT NOT NULL REFERENCES food_density_clusters_v3(cluster_id) ON DELETE CASCADE,
      place_id TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
      distance_to_center_m REAL NOT NULL, PRIMARY KEY(cluster_id,place_id));
    CREATE INDEX IF NOT EXISTS idx_food_density_v3_center ON food_density_clusters_v3(center_latitude,center_longitude);
    """)
    results=[]
    with con:
      con.execute("DELETE FROM food_density_cluster_members_v3")
      con.execute("DELETE FROM food_density_clusters_v3")
      for category in ("restaurant","cafe"):
        rows=con.execute("""SELECT id,name,district,latitude,longitude,source_id FROM places
          WHERE is_active=1 AND city='Riyadh' AND country_code='SA' AND category=?""",(category,)).fetchall()
        buckets=defaultdict(list)
        def cell(lat,lon): return (math.floor(lat/CLAT),math.floor(lon/CLON))
        for i,r in enumerate(rows): buckets[cell(float(r[3]),float(r[4]))].append(i)
        neigh=[]
        for i,r in enumerate(rows):
          lat,lon=float(r[3]),float(r[4]); cy,cx=cell(lat,lon); ns=[]
          for dy in (-1,0,1):
            for dx in (-1,0,1):
              for j in buckets.get((cy+dy,cx+dx),[]):
                q=rows[j]
                if hav(lat,lon,float(q[3]),float(q[4]))<=EPS: ns.append(j)
          neigh.append(ns)
        core=[len(n)>=MIN for n in neigh]; parent=list(range(len(rows)))
        for i,ns in enumerate(neigh):
          if core[i]:
            for j in ns:
              if core[j]:
                a,b=root(parent,i),root(parent,j)
                if a!=b: parent[b]=a
        groups=defaultdict(list)
        for i,v in enumerate(core):
          if v: groups[root(parent,i)].append(i)
        assigned={i:root(parent,i) for g in groups.values() for i in g}
        for i,v in enumerate(core):
          if not v:
            cand=[j for j in neigh[i] if core[j]]
            if cand:
              j=min(cand,key=lambda j:hav(float(rows[i][3]),float(rows[i][4]),float(rows[j][3]),float(rows[j][4])))
              assigned[i]=root(parent,j)
        final=defaultdict(list)
        for i,g in assigned.items(): final[root(parent,g)].append(i)
        comps=[v for v in final.values() if len(v)>=MIN]
        comps.sort(key=lambda v:(-len(v),sum(float(rows[i][3]) for i in v)/len(v)))
        for n,idxs in enumerate(comps,1):
          members=[rows[i] for i in idxs]
          lat=sum(float(r[3]) for r in members)/len(members)
          lon=sum(float(r[4]) for r in members)/len(members)
          radius=max(hav(lat,lon,float(r[3]),float(r[4])) for r in members)
          districts=Counter(str(r[2]).strip() for r in members if r[2] and str(r[2]).strip())
          district=districts.most_common(1)[0][0] if districts else None
          mix=dict(Counter(str(r[5]) for r in members))
          digest=hashlib.sha256((category+"|"+"|".join(sorted(str(r[0]) for r in members))).encode()).hexdigest()[:20]
          cid="fd3_"+digest; label=f"{'تجمع مطاعم' if category=='restaurant' else 'تجمع مقاهي'} الرياض {n:03d}"
          now=datetime.now(timezone.utc).isoformat()
          con.execute("INSERT INTO food_density_clusters_v3 VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            (cid,category,label,district,lat,lon,radius,len(members),EPS,MIN,json.dumps(mix,ensure_ascii=False),now))
          for r in members:
            con.execute("INSERT INTO food_density_cluster_members_v3 VALUES(?,?,?)",
              (cid,r[0],hav(lat,lon,float(r[3]),float(r[4]))))
          results.append({"cluster_id":cid,"category":category,"name":label,"district":district,
            "center_latitude":round(lat,7),"center_longitude":round(lon,7),"radius_m":round(radius,1),
            "member_count":len(members),"source_mix":mix})
    totals={}
    for cat in ("restaurant","cafe"):
      totals[cat]={"input":con.execute("SELECT COUNT(*) FROM places WHERE is_active=1 AND city='Riyadh' AND country_code='SA' AND category=?",(cat,)).fetchone()[0],
        "clustered":con.execute("SELECT COALESCE(SUM(member_count),0) FROM food_density_clusters_v3 WHERE category=?",(cat,)).fetchone()[0],
        "clusters":con.execute("SELECT COUNT(*) FROM food_density_clusters_v3 WHERE category=?",(cat,)).fetchone()[0]}
    con.close()
    report={"status":"success","method":"category-separated DBSCAN with haversine distance; arithmetic-mean centroids from member POI coordinates",
      "parameters":{"eps_metres":EPS,"min_samples":MIN},"totals":totals,
      "cluster_count":len(results),"clusters":results,
      "caveat":"Experimental v3 layer. Categories are never mixed. DBSCAN can still chain adjacent dense blocks; inspect radius_m before treating a cluster as one compact hotspot. Existing restaurant_clusters v1 tables are preserved."}
    out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({k:v for k,v in report.items() if k!="clusters"},ensure_ascii=False))
if __name__=="__main__": main()
