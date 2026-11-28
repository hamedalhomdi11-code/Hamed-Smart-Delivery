package com.hamed.smartdelivery

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class OrderClusterStats(val orderCount: Int, val lastOrderAtEpochMs: Long)

/** Independent order-origin store. It never opens or mutates the bundled Riyadh places DB. */
class OrderClusterDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "hamed_order_clusters.sqlite",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE order_cluster_events (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                cluster_id TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                occurred_at_epoch_ms INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE order_cluster_totals (
                cluster_id TEXT PRIMARY KEY,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                order_count INTEGER NOT NULL DEFAULT 0,
                last_order_at_epoch_ms INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_order_cluster_events_cluster_time ON order_cluster_events(cluster_id, occurred_at_epoch_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun orderStats(): Map<String, OrderClusterStats> {
        val result = linkedMapOf<String, OrderClusterStats>()
        readableDatabase.rawQuery(
            "SELECT cluster_id, order_count, last_order_at_epoch_ms FROM order_cluster_totals WHERE order_count > 0",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = OrderClusterStats(cursor.getInt(1), cursor.getLong(2))
            }
        }
        return result
    }

    fun orderProducingClusterIds(): Set<String> {
        val ids = linkedSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT cluster_id FROM order_cluster_totals WHERE order_count > 0",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        return ids
    }

    fun recordOrder(cluster: RestaurantCluster, occurredAtEpochMs: Long = System.currentTimeMillis()) {
        val id = cluster.clusterId?.takeIf { it.isNotBlank() }
            ?: "geo:${"%.5f".format(java.util.Locale.US, cluster.latitude)}:${"%.5f".format(java.util.Locale.US, cluster.longitude)}"
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO order_cluster_events(cluster_id, latitude, longitude, occurred_at_epoch_ms) VALUES(?, ?, ?, ?)",
                arrayOf<Any>(id, cluster.latitude, cluster.longitude, occurredAtEpochMs)
            )
            db.execSQL(
                """INSERT OR IGNORE INTO order_cluster_totals(
                       cluster_id, latitude, longitude, order_count, last_order_at_epoch_ms
                   ) VALUES(?, ?, ?, 0, ?)""".trimIndent(),
                arrayOf<Any>(id, cluster.latitude, cluster.longitude, occurredAtEpochMs)
            )
            db.execSQL(
                """UPDATE order_cluster_totals
                   SET latitude=?, longitude=?, order_count=order_count+1, last_order_at_epoch_ms=?
                   WHERE cluster_id=?""".trimIndent(),
                arrayOf<Any>(cluster.latitude, cluster.longitude, occurredAtEpochMs, id)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
