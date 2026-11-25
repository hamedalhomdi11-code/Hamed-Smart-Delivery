#!/usr/bin/env python3
"""Build a Riyadh-area SQLite POI database from Bayanat Labs' Saudi Places Parquet.

Source: Bayanat Labs Arab World Places Atlas v1.0.0 / Overture Maps 2026-10-21.
This is a mapped POI dataset, not a field-verified business register.
"""
import hashlib, json, sqlite3, sys
from pathlib import Path
import pandas as pd

PARQUET = Path(sys.argv[1])
OUT = Path(sys.argv[2])
SCHEMA = Path("database/001_riyadh_places.sql")

# Riyadh metropolitan-area envelope. Keep this explicit rather than claiming it
# is an official administrative polygon; records retain locality/region fields.
LAT_MIN, LAT_MAX = 24.05, 25.15
LON_MIN, LON_MAX = 46.20, 47.35

def as_list(v):
    if v is None:
        return []
    if isinstance(v, (list, tuple, set)):
        return [str(x).strip().lower() for x in v if x is not None]
    try:
        if pd.isna(v):
            return []
    except Exception:
        pass
    return [str(v).strip().lower()]

def classify(row):
    vals = set(as_list(row.get("taxonomy_hierarchy")))
    vals.update(as_list(row.get("category")))
    vals.update(as_list(row.get("taxonomy_primary")))
    s = " ".join(vals)
    if any(x in s for x in ("pharmacy", "drugstore")):
        return "pharmacy"
    if any(x in s for x in ("supermarket", "hypermarket", "grocery_store", "convenience_store", "food_store")):
        return "supermarket" if "supermarket" in s else ("hypermarket" if "hypermarket" in s else "grocery")
    if any(x in s for x in ("bakery", "bake_shop")):
        return "bakery"
    if any(x in s for x in ("coffee_shop", "cafe", "tea_house")):
        return "cafe"
    if any(x in s for x in ("restaurant", "fast_food", "food_court", "pizza", "burger", "sushi", "meal_takeaway")):
        return "restaurant"
    # Keep shopping/retail and malls in the allowed catch-all category.
    if any(x in s for x in ("shopping", "store", "mall", "retail", "market")):
        return "other"
    return None

def main():
    if not PARQUET.exists():
        raise SystemExit(f"Missing source parquet: {PARQUET}")
    df = pd.read_parquet(PARQUET)
    required = {"id","name","latitude","longitude","country_code"}
    missing = required - set(df.columns)
    if missing:
        raise SystemExit(f"Source schema missing columns: {sorted(missing)}")

    df["latitude"] = pd.to_numeric(df["latitude"], errors="coerce")
    df["longitude"] = pd.to_numeric(df["longitude"], errors="coerce")
    mask = (
        df["country_code"].astype(str).str.upper().eq("SA")
        & df["latitude"].between(LAT_MIN, LAT_MAX)
        & df["longitude"].between(LON_MIN, LON_MAX)
    )
    if "region_name_en" in df.columns:
        region = df["region_name_en"].fillna("").astype(str).str.lower()
        mask &= region.str.contains("riyadh", regex=False)
    df = df.loc[mask].copy()

    OUT.parent.mkdir(parents=True, exist_ok=True)
    if OUT.exists():
        OUT.unlink()
    con = sqlite3.connect(OUT)
    con.executescript(SCHEMA.read_text(encoding="utf-8"))
    con.execute("""INSERT INTO data_sources
      (id,name,source_type,source_url,license_name,attribution)
      VALUES (?,?,?,?,?,?)""", (
        "bayanat_overture_sa_2026",
        "Bayanat Labs Arab World Places Atlas - Saudi Arabia",
        "open_map",
        "https://huggingface.co/datasets/Bayanat-Labs/arab-world-places-atlas",
        "Per-record source_licenses; see DATA_LICENSE.md",
        "Bayanat Labs Arab World Places Atlas (2026); Overture Maps Foundation; © OpenStreetMap contributors where applicable."
    ))

    inserted = skipped = 0
    with con:
        for _, r in df.iterrows():
            raw_name = r.get("name")
            name = "" if raw_name is None or pd.isna(raw_name) else str(raw_name).strip()
            if not name or name.lower() == "nan":
                skipped += 1; continue
            try:
                lat, lon = float(r["latitude"]), float(r["longitude"])
            except Exception:
                skipped += 1; continue
            category = classify(r)
            if not category:
                skipped += 1; continue
            source_id = str(r.get("id") or "")
            if not source_id:
                skipped += 1; continue
            address = r.get("address")
            district = r.get("locality")
            name_ar = r.get("name_ar")
            brand = r.get("brand_name")
            licenses = as_list(r.get("source_licenses"))
            license_text = ",".join(licenses) if licenses else "See source dataset rights"
            confidence = r.get("confidence", 0.5)
            try: confidence = max(0.0, min(1.0, float(confidence)))
            except Exception: confidence = 0.5
            dedupe = hashlib.sha256(("overture:" + source_id).encode()).hexdigest()
            pid = "ov_" + hashlib.sha256(("overture:" + source_id).encode()).hexdigest()[:24]
            def clean(v):
                if v is None: return None
                try:
                    if pd.isna(v): return None
                except Exception: pass
                s = str(v).strip()
                return s if s and s.lower() != "nan" else None
            con.execute("""INSERT OR IGNORE INTO places
              (id,source_id,source_record_id,name,name_ar,brand,category,address,district,city,
               country_code,latitude,longitude,dedupe_key,data_license,confidence,is_active)
              VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)""",
              (pid,"bayanat_overture_sa_2026",source_id,name,clean(name_ar),clean(brand),category,
               clean(address),clean(district),"Riyadh","SA",lat,lon,dedupe,license_text,confidence))
            changed = con.execute("SELECT changes()").fetchone()[0]
            inserted += changed
            if not changed:
                skipped += 1

    # Build a separate brand index and link matched physical branches.
    # Brand aliases are only seeds; a link is created only on an exact normalized
    # match against source brand_name or the full place name.
    import csv, re, unicodedata
    def norm_brand(value):
        if value is None:
            return ""
        s = unicodedata.normalize("NFKC", str(value)).casefold().strip()
        s = re.sub(r"[\\u064B-\\u065F\\u0670]", "", s)
        s = s.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ى", "ي")
        return re.sub(r"[^\\w]", "", s, flags=re.UNICODE)

    con.executescript(Path("database/003_brand_directory.sql").read_text(encoding="utf-8"))
    alias_lookup = {}
    with open("database/riyadh_brand_index.csv", newline="", encoding="utf-8-sig") as fp:
        for item in csv.DictReader(fp):
            con.execute(
                "INSERT OR IGNORE INTO brands(id,name_en,name_ar,category) VALUES (?,?,?,?)",
                (item["brand_id"], item["name_en"], item["name_ar"], item["category"])
            )
            for alias in item["aliases"].split("|"):
                normalized = norm_brand(alias)
                if not normalized:
                    continue
                con.execute(
                    "INSERT OR IGNORE INTO brand_aliases(brand_id,alias,normalized_alias) VALUES (?,?,?)",
                    (item["brand_id"], alias, normalized)
                )
                alias_lookup.setdefault(normalized, item["brand_id"])

    linked = 0
    for place_id, place_name, source_brand in con.execute(
        "SELECT id,name,brand FROM places"
    ).fetchall():
        brand_key = norm_brand(source_brand)
        method = "source_brand_name"
        if not brand_key:
            brand_key = norm_brand(place_name)
            method = "exact_alias_name"
        brand_id = alias_lookup.get(brand_key)
        if brand_id:
            con.execute(
                "INSERT OR IGNORE INTO brand_place_links(brand_id,place_id,match_method,match_confidence) VALUES (?,?,?,?)",
                (brand_id, place_id, method, 0.95 if method == "source_brand_name" else 0.85)
            )
            linked += con.execute("SELECT changes()").fetchone()[0]

    brand_counts = dict(con.execute("""
        SELECT b.name_en, COUNT(l.place_id)
        FROM brands b LEFT JOIN brand_place_links l ON l.brand_id=b.id
        GROUP BY b.id ORDER BY COUNT(l.place_id) DESC, b.name_en
    """).fetchall())
    total = con.execute("SELECT COUNT(*) FROM places").fetchone()[0]
    by_cat = dict(con.execute("SELECT category,COUNT(*) FROM places GROUP BY category").fetchall())
    con.close()
    report = {"total_records": total, "inserted": inserted, "skipped": skipped,
              "category_counts": by_cat, "brand_index_count": len(brand_counts),
              "brand_branch_links": linked, "branches_by_brand": brand_counts,
              "source_rows_in_riyadh_bbox": int(len(df)),
              "coverage": "Riyadh Region + metropolitan bounding box; not an official boundary polygon",
              "source": "Bayanat Labs Arab World Places Atlas v1.0.0 / Overture 2026-10-21"}
    Path(str(OUT)+".report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False))

if __name__ == "__main__":
    main()
