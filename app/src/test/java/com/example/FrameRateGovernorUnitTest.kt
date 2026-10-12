package com.example

import android.opengl.GLSurfaceView
import com.example.engine.thermal.FrameRateGovernor
import com.example.engine.thermal.PowerProfile
import com.example.engine.thermal.ThermalMonitor
import com.example.engine.thermal.ThermalTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameRateGovernorUnitTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testThermalMonitorNotifications() {
        val monitor = ThermalMonitor()
        var receivedTier = ThermalTier.NORMAL
        var receivedTemp = 0f

        monitor.addListener { tier, temp ->
            receivedTier = tier
            receivedTemp = temp
        }

        monitor.onBatteryTempChanged(42.0f)
        assertEquals(ThermalTier.MODERATE, receivedTier)
        assertEquals(42.0f, receivedTemp, 1e-4f)

        monitor.onBatteryTempChanged(47.0f)
        assertEquals(ThermalTier.CRITICAL, receivedTier)
        assertEquals(47.0f, receivedTemp, 1e-4f)
    }

    @Test
    fun testFrameRateGovernorRenderModeAndTargetFpsAdjustments() {
        val monitor = ThermalMonitor()
        val governor = FrameRateGovernor(monitor, testScope)

        // 1. Initial Cool State (< 37°C) -> Continuous rendering at 60 FPS
        monitor.onBatteryTempChanged(32.0f)
        assertEquals(60, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, governor.telemetry.value.renderMode)
        assertFalse(governor.telemetry.value.isThrottling)

        // 2. Warm State (38.5°C) -> RENDERMODE_WHEN_DIRTY at 50 FPS
        monitor.onBatteryTempChanged(38.5f)
        assertEquals(50, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, governor.telemetry.value.renderMode)
        assertTrue(governor.telemetry.value.isThrottling)

        // 3. Hot State (42.0°C) -> RENDERMODE_WHEN_DIRTY at 40 FPS
        monitor.onBatteryTempChanged(42.0f)
        assertEquals(40, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, governor.telemetry.value.renderMode)
        assertTrue(governor.telemetry.value.isThrottling)

        // 4. Severe Throttling State (44.5°C) -> RENDERMODE_WHEN_DIRTY at 30 FPS
        monitor.onBatteryTempChanged(44.5f)
        assertEquals(30, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, governor.telemetry.value.renderMode)
        assertTrue(governor.telemetry.value.isThrottling)

        // 5. Critical State (47.5°C) -> RENDERMODE_WHEN_DIRTY at 24 FPS
        monitor.onBatteryTempChanged(47.5f)
        assertEquals(24, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, governor.telemetry.value.renderMode)
        assertTrue(governor.telemetry.value.isThrottling)

        // 6. Device cools down back to 34.0°C -> switches back to RENDERMODE_CONTINUOUSLY at 60 FPS
        monitor.onBatteryTempChanged(34.0f)
        assertEquals(60, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, governor.telemetry.value.renderMode)
        assertFalse(governor.telemetry.value.isThrottling)

        governor.release()
    }

    @Test
    fun testPowerProfileOverridesOnGovernor() {
        val monitor = ThermalMonitor()
        val governor = FrameRateGovernor(monitor, testScope)
        monitor.onBatteryTempChanged(32.0f)

        // Battery saver forces dirty render mode at 30 FPS
        governor.setPowerProfile(PowerProfile.BATTERY_SAVER)
        assertEquals(30, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, governor.telemetry.value.renderMode)
        assertTrue(governor.telemetry.value.isThrottling)

        // Performance forces continuous mode at 60 FPS
        governor.setPowerProfile(PowerProfile.PERFORMANCE)
        assertEquals(60, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, governor.telemetry.value.renderMode)

        // Balanced forces dirty render mode at 45 FPS
        governor.setPowerProfile(PowerProfile.BALANCED)
        assertEquals(45, governor.getTargetFps())
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, governor.telemetry.value.renderMode)

        governor.release()
    }
}
