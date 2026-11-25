package com.hamed.smartdelivery

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
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
}
