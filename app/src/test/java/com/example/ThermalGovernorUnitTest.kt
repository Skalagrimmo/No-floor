package com.example

import com.example.engine.thermal.PowerProfile
import com.example.engine.thermal.ThermalGovernor
import com.example.engine.thermal.ThermalTier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThermalGovernorUnitTest {

    @Test
    fun testThermalTierTransitionsAndTargetFps() {
        val governor = ThermalGovernor()

        // Initial normal state
        governor.updateTier(ThermalTier.NORMAL)
        assertEquals(60, governor.getTargetFps())

        // Light warm state
        governor.updateTier(ThermalTier.LIGHT)
        assertEquals(50, governor.getTargetFps())

        // Moderate state (warm)
        governor.updateTier(ThermalTier.MODERATE)
        assertEquals(40, governor.getTargetFps())

        // Severe state (hot throttling to protect Mali-400 GPU)
        governor.updateTier(ThermalTier.SEVERE)
        assertEquals(30, governor.getTargetFps())

        // Critical emergency state
        governor.updateTier(ThermalTier.CRITICAL)
        assertEquals(24, governor.getTargetFps())
    }

    @Test
    fun testBatteryTemperatureThresholdMapping() {
        val governor = ThermalGovernor()

        // 34 degrees celsius -> NORMAL
        governor.onBatteryTempChanged(34.0f)
        assertEquals(ThermalTier.NORMAL, governor.telemetry.value.tier)
        assertEquals(60, governor.getTargetFps())

        // 38.5 degrees celsius -> LIGHT
        governor.onBatteryTempChanged(38.5f)
        assertEquals(ThermalTier.LIGHT, governor.telemetry.value.tier)
        assertEquals(50, governor.getTargetFps())

        // 41.5 degrees celsius -> MODERATE
        governor.onBatteryTempChanged(41.5f)
        assertEquals(ThermalTier.MODERATE, governor.telemetry.value.tier)
        assertEquals(40, governor.getTargetFps())

        // 44.0 degrees celsius -> SEVERE
        governor.onBatteryTempChanged(44.0f)
        assertEquals(ThermalTier.SEVERE, governor.telemetry.value.tier)
        assertEquals(30, governor.getTargetFps())

        // 47.0 degrees celsius -> CRITICAL
        governor.onBatteryTempChanged(47.0f)
        assertEquals(ThermalTier.CRITICAL, governor.telemetry.value.tier)
        assertEquals(24, governor.getTargetFps())
    }

    @Test
    fun testPowerProfileOverrides() {
        val governor = ThermalGovernor()
        governor.updateTier(ThermalTier.CRITICAL)

        // Force Performance profile overrides thermal tier to 60 FPS
        governor.setPowerProfile(PowerProfile.PERFORMANCE)
        assertEquals(60, governor.getTargetFps())

        // Force Balanced profile sets 45 FPS
        governor.setPowerProfile(PowerProfile.BALANCED)
        assertEquals(45, governor.getTargetFps())

        // Force Battery Saver sets 30 FPS
        governor.setPowerProfile(PowerProfile.BATTERY_SAVER)
        assertEquals(30, governor.getTargetFps())

        // Back to Auto follows thermal tier
        governor.setPowerProfile(PowerProfile.AUTO)
        assertEquals(24, governor.getTargetFps())
    }
}
