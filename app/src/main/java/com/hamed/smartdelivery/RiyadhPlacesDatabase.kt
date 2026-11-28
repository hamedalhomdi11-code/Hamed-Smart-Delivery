package com.hamed.smartdelivery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import java.io.File
import kotlin.math.*

data class NearbyPlace(
    val id: String,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val district: String?
)

/**
 * Read-only access to the app's bundled Riyadh database.
 *
 * The database is copied atomically from assets on first launch (or when the
 * bundled asset version changes). Runtime POI discovery never calls Overpass
 * or another external place-data service.
 */
object RiyadhPlacesDatabase {
    private const val ASSET_NAME = "hamed_smart_delivery_riyadh.sqlite"
    private const val DATABASE_NAME = "hamed_smart_delivery_riyadh.sqlite"
    private const val ASSET_VERSION = "riyadh-food-hotspots-v3-20261128-r2"

    @Volatile private var cachedDatabase: SQLiteDatabase? = null
    @Volatile private var cachedPath: String? = null

    @Synchronized
    private fun open(context: Context): SQLiteDatabase {
        val target = context.getDatabasePath(DATABASE_NAME)
        val versionFile = File(context.filesDir, "riyadh_places_asset.version")
        if (!target.exists() || !versionFile.exists() || versionFile.readText() != ASSET_VERSION) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "$DATABASE_NAME.importing")
            if (temporary.exists()) temporary.delete()
            context.assets.open(ASSET_NAME).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            val validation = SQLiteDatabase.openDatabase(
                temporary.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            try {
                val integrity = validation.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else "missing"
                }
                check(integrity == "ok") { "قاعدة بيانات الرياض غير سليمة: $integrity" }
                validation.rawQuery(
                    "SELECT COUNT(*) FROM food_density_clusters_v3 WHERE category IN ('restaurant', 'cafe')",
                    null
                ).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) > 0) {
                        "قاعدة الرياض لا تحتوي تجمعات مطاعم/مقاهي صالحة"
                    }
                }
            } finally {
                validation.close()
            }
            cachedDatabase?.close()
            cachedDatabase = null
            if (target.exists() && !target.delete()) {
                temporary.delete()
                error("تعذر استبدال نسخة قاعدة بيانات الرياض")
            }
            check(temporary.renameTo(target)) { "تعذر تثبيت قاعدة بيانات الرياض" }
            versionFile.writeText(ASSET_VERSION)
        }
        if (cachedDatabase == null || cachedPath != target.absolutePath || cachedDatabase?.isOpen != true) {
            cachedDatabase?.close()
            cachedDatabase = SQLiteDatabase.openDatabase(
                target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            cachedPath = target.absolutePath
        }
        return cachedDatabase!!
    }

    fun foodClusters(context: Context): List<RestaurantCluster> {
        val db = open(context.applicationContext)
        val result = ArrayList<RestaurantCluster>()
        db.rawQuery(
            """SELECT cluster_id, category, name, district, center_latitude,
                      center_longitude, radius_m, member_count
               FROM food_density_clusters_v3
               WHERE category IN ('restaurant', 'cafe')""".trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += RestaurantCluster(
                    latitude = cursor.getDouble(4),
                    longitude = cursor.getDouble(5),
                    restaurantCount = cursor.getInt(7),
                    names = listOf(cursor.getString(2).orEmpty()).filter { it.isNotBlank() },
                    distanceMeters = 0.0,
                    searchRadiusMeters = 5000,
                    category = cursor.getString(1),
                    clusterId = cursor.getString(0),
                    district = cursor.getString(3),
                    clusterRadiusMeters = cursor.getDouble(6)
                )
            }
        }
        return result
    }

    fun nearestPharmacies(
        context: Context,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 5000,
        limit: Int = 30
    ): List<NearbyPlace> {
        val db = open(context.applicationContext)
        val candidates = ArrayList<Pair<NearbyPlace, Double>>()
        db.rawQuery(
            """SELECT id, name, category, latitude, longitude, district
               FROM places
               WHERE category = 'pharmacy' AND is_active = 1""".trimIndent(),
            null
        ).use { cursor ->
            val distance = FloatArray(1)
            while (cursor.moveToNext()) {
                val lat = cursor.getDouble(3)
                val lon = cursor.getDouble(4)
                Location.distanceBetween(latitude, longitude, lat, lon, distance)
                val meters = distance[0].toDouble()
                if (meters <= radiusMeters) {
                    candidates += NearbyPlace(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        category = cursor.getString(2),
                        latitude = lat,
                        longitude = lon,
                        district = cursor.getString(5)
                    ) to meters
                }
            }
        }
        return candidates.sortedBy { it.second }.take(limit).map { it.first }
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }
}
