package com.hamed.smartdelivery

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Minimal device/emulator smoke test: verifies the real launcher activity can be created. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun launcherActivityStartsWithoutFinishing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity unexpectedly finished during launch", activity.isFinishing)
            }
        }
    }
    
    @Test
    fun bundledRiyadhDatabaseContainsFoodClustersAndPharmacies() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clusters = RiyadhPlacesDatabase.foodClusters(context)
        assertTrue("Expected restaurant clusters in bundled Riyadh database", clusters.any { it.category == "restaurant" })
        assertTrue("Expected cafe clusters in bundled Riyadh database", clusters.any { it.category == "cafe" })

        val pharmacies = RiyadhPlacesDatabase.nearestPharmacies(
            context, 24.7136, 46.6753, radiusMeters = 500_000, limit = 5
        )
        assertTrue("Expected active pharmacies in bundled Riyadh database", pharmacies.isNotEmpty())
    }

    @Test
    fun orderOriginIsPersistedInItsOwnDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("hamed_order_clusters.sqlite")
        val database = OrderClusterDatabase(context)
        val cluster = RestaurantCluster(
            latitude = 24.7136,
            longitude = 46.6753,
            restaurantCount = 12,
            names = listOf("test cluster"),
            distanceMeters = 250.0,
            clusterId = "test-cluster"
        )
        database.recordOrder(cluster, 1_800_000_000_000L)
        database.recordOrder(cluster, 1_800_000_001_000L)
        val count = database.readableDatabase.rawQuery(
            "SELECT order_count FROM order_cluster_totals WHERE cluster_id = ?",
            arrayOf("test-cluster")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
        assertTrue("Expected both order events to be counted", count == 2)
        database.close()
        context.deleteDatabase("hamed_order_clusters.sqlite")
    }

}
