package com.example

import com.example.engine.thermal.FrameBudgetManager
import com.example.engine.thermal.ShaderPass
import com.example.engine.thermal.TextureDetailLevel
import com.example.engine.thermal.ThermalTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameBudgetManagerUnitTest {

    @Test
    fun testNormalTemperatureAndStandardBudget() {
        val manager = FrameBudgetManager()
        manager.setManualThermalStateForTesting(ThermalTier.NORMAL, 32.0f)

        // Simulate healthy 60 FPS frames (16.0 ms duration)
        for (i in 0 until 10) {
            manager.recordFrameTime(16.0f)
        }

        val state = manager.currentBudgetState
        assertFalse(state.isHighHeat)
        assertFalse(state.isBudgetOverrun)
        assertEquals(TextureDetailLevel.FULL, state.textureDetail)
        assertEquals(1.0f, state.textureScale, 0.001f)
        assertEquals(512, state.maxTextureResolution)

        // All shader passes enabled at full fidelity
        assertTrue(state.postProcessPassEnabled)
        assertTrue(state.shadowPassEnabled)
        assertTrue(state.visionConesPassEnabled)
        assertTrue(state.dynamicTracersPassEnabled)
        assertTrue(manager.isPassEnabled(ShaderPass.WORLD_GEOMETRY))
        assertTrue(manager.isPassEnabled(ShaderPass.POST_PROCESSING_QUAD))
        assertTrue(manager.isPassEnabled(ShaderPass.SHADOW_MAP_DEPTH))
    }

    @Test
    fun testHighHeatSensorTriggerLoweringTextureDetailAndDisablingNonEssentialPasses() {
        val manager = FrameBudgetManager()

        // 1. System thermal sensor reports MODERATE heat (41.0°C)
        manager.setManualThermalStateForTesting(ThermalTier.MODERATE, 41.0f)
        manager.recordFrameTime(20.0f)

        val state = manager.currentBudgetState
        assertTrue("System must flag high heat state", state.isHighHeat)

        // Texture detail must automatically downscale to HALF (50%, max res 256)
        assertEquals(TextureDetailLevel.HALF, state.textureDetail)
        assertEquals(0.5f, state.textureScale, 0.001f)
        assertEquals(256, state.maxTextureResolution)
        assertEquals(256, state.shadowMapResolution)

        // Non-essential fullscreen post-process quad pass must be disabled to relieve fill-rate
        assertFalse("Post-processing shader pass must be disabled under high heat", state.postProcessPassEnabled)
        assertFalse(manager.isPassEnabled(ShaderPass.POST_PROCESSING_QUAD))

        // Essential passes must remain active
        assertTrue(manager.isPassEnabled(ShaderPass.WORLD_GEOMETRY))
        assertTrue(manager.isPassEnabled(ShaderPass.CHARACTER_ENTITIES))
    }

    @Test
    fun testSevereHeatSensorTrigger() {
        val manager = FrameBudgetManager()

        // 2. System thermal sensor reports SEVERE heat (44.5°C)
        manager.setManualThermalStateForTesting(ThermalTier.SEVERE, 44.5f)
        manager.recordFrameTime(28.0f)

        val state = manager.currentBudgetState
        assertTrue(state.isHighHeat)

        // Texture detail drops to LOW (25%, max res 128)
        assertEquals(TextureDetailLevel.LOW, state.textureDetail)
        assertEquals(0.25f, state.textureScale, 0.001f)
        assertEquals(128, state.maxTextureResolution)

        // Non-essential passes disabled: Post-process, Shadow depth prepass, Vision cones blending
        assertFalse("Post-process pass must be disabled", state.postProcessPassEnabled)
        assertFalse("Shadow map depth pass must be disabled in severe heat", state.shadowPassEnabled)
        assertFalse("Vision cone alpha blending pass must be disabled in severe heat", state.visionConesPassEnabled)
        assertFalse(manager.isPassEnabled(ShaderPass.SHADOW_MAP_DEPTH))
        assertFalse(manager.isPassEnabled(ShaderPass.VISION_CONES_BLEND))
    }

    @Test
    fun testCriticalEmergencyHeatSensorTrigger() {
        val manager = FrameBudgetManager()

        // 3. System thermal sensor reports CRITICAL emergency heat (48.0°C)
        manager.setManualThermalStateForTesting(ThermalTier.CRITICAL, 48.0f)
        manager.recordFrameTime(35.0f)

        val state = manager.currentBudgetState
        assertTrue(state.isHighHeat)

        // Texture detail drops to MINIMAL (12.5%, max res 64)
        assertEquals(TextureDetailLevel.MINIMAL, state.textureDetail)
        assertEquals(0.125f, state.textureScale, 0.001f)
        assertEquals(64, state.maxTextureResolution)

        // All non-essential passes disabled
        assertFalse(state.postProcessPassEnabled)
        assertFalse(state.shadowPassEnabled)
        assertFalse(state.visionConesPassEnabled)
        assertFalse(state.dynamicTracersPassEnabled)

        // Only essential geometry and entities render
        assertTrue(manager.isPassEnabled(ShaderPass.WORLD_GEOMETRY))
        assertTrue(manager.isPassEnabled(ShaderPass.CHARACTER_ENTITIES))
    }

    @Test
    fun testProactiveBudgetOverrunLoweringTextureDetailAndPasses() {
        val manager = FrameBudgetManager()
        // Sensors report normal temperature (33°C), but frames are taking too long
        manager.setManualThermalStateForTesting(ThermalTier.NORMAL, 33.0f)

        // Target 60 FPS = 16.67ms budget. Feed 25.0ms frames (150% budget usage)
        for (i in 0 until 10) {
            manager.recordFrameTime(25.0f)
        }

        val state = manager.currentBudgetState
        assertTrue("Budget overrun must be flagged", state.isBudgetOverrun)
        assertTrue(state.budgetUsagePercent > 100.0f)

        // Texture detail proactively lowered to HALF to restore frame rate
        assertEquals(TextureDetailLevel.HALF, state.textureDetail)

        // Post-processing pass proactively disabled
        assertFalse(state.postProcessPassEnabled)
    }

    @Test
    fun testManualOverrideAndReset() {
        val manager = FrameBudgetManager()

        manager.forceTextureDetailForTesting(TextureDetailLevel.LOW)
        assertEquals(TextureDetailLevel.LOW, manager.getTextureDetail())

        manager.forcePassEnabledForTesting(ShaderPass.POST_PROCESSING_QUAD, false)
        assertFalse(manager.isPassEnabled(ShaderPass.POST_PROCESSING_QUAD))

        manager.reset()
        assertEquals(TextureDetailLevel.FULL, manager.getTextureDetail())
        assertTrue(manager.isPassEnabled(ShaderPass.POST_PROCESSING_QUAD))
    }
}
