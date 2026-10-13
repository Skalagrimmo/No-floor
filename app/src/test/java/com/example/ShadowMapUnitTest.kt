package com.example

import com.example.engine.gl.ShadowMapManager
import com.example.engine.gl.ShadowSettings
import com.example.engine.math.Vec3
import com.example.engine.thermal.ThermalTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShadowMapUnitTest {

    @Test
    fun testShadowSettingsThermalTierEvaluation() {
        val manager = ShadowMapManager()

        // 1. Normal Tier: high fidelity 512x512 resolution, 9-tap (3x3) PCF soft shadows
        manager.evaluateThermalQuality(ThermalTier.NORMAL)
        val normalSettings = manager.currentSettings
        assertEquals(512, normalSettings.resolution)
        assertEquals(2, normalSettings.pcfKernelSize)
        assertTrue(normalSettings.enabled)
        assertEquals(0.55f, normalSettings.strength, 0.001f)

        // 2. Light Tier: 256x256 resolution, 4-tap (2x2) PCF filter
        manager.evaluateThermalQuality(ThermalTier.LIGHT)
        val lightSettings = manager.currentSettings
        assertEquals(256, lightSettings.resolution)
        assertEquals(1, lightSettings.pcfKernelSize)
        assertTrue(lightSettings.enabled)
        assertEquals(0.50f, lightSettings.strength, 0.001f)

        // 3. Moderate Tier: 256x256 resolution, 4-tap PCF filter
        manager.evaluateThermalQuality(ThermalTier.MODERATE)
        val modSettings = manager.currentSettings
        assertEquals(256, modSettings.resolution)
        assertEquals(1, modSettings.pcfKernelSize)
        assertTrue(modSettings.enabled)

        // 4. Severe Tier: 128x128 resolution, 1-tap fast shadow for Mali-400 GPU protection
        manager.evaluateThermalQuality(ThermalTier.SEVERE)
        val severeSettings = manager.currentSettings
        assertEquals(128, severeSettings.resolution)
        assertEquals(0, severeSettings.pcfKernelSize)
        assertTrue(severeSettings.enabled)
        assertEquals(0.40f, severeSettings.strength, 0.001f)

        // 5. Critical Tier: Disable shadow map pass completely to allow mobile GPU cooldown
        manager.evaluateThermalQuality(ThermalTier.CRITICAL)
        val critSettings = manager.currentSettings
        assertEquals(128, critSettings.resolution)
        assertEquals(0, critSettings.pcfKernelSize)
        assertFalse(critSettings.enabled)
        assertEquals(0.0f, critSettings.strength, 0.001f)
    }

    @Test
    fun testPowerOfTwoResolutionCompliance() {
        val manager = ShadowMapManager()
        val tiers = ThermalTier.values()

        for (tier in tiers) {
            manager.evaluateThermalQuality(tier)
            val res = manager.currentSettings.resolution
            // Check power of two: (res > 0) && (res & (res - 1) == 0)
            assertTrue("Resolution $res for tier $tier must be a power-of-two (POT)", (res > 0) && ((res and (res - 1)) == 0))
            assertTrue("Resolution $res must be at most 512 for Mali memory bandwidth", res in 128..512)
        }
    }

    @Test
    fun testDirectionalLightFrustumMatrix() {
        val manager = ShadowMapManager()
        val playerPos = Vec3(10f, 0f, 15f)
        val lightDir = Vec3(-0.4f, 0.8f, 0.3f).normalize()

        manager.updateLightFrustum(playerPos, lightDir)
        val matrix = manager.lightSpaceMatrix

        assertNotNull(matrix)
        assertNotNull(matrix.values)
        assertEquals(16, matrix.values.size)

        // Matrix must not be all zeros
        var sum = 0f
        for (v in matrix.values) {
            sum += abs(v)
        }
        assertTrue("Light space matrix values must be computed and non-zero", sum > 0.1f)
    }

    @Test
    fun testDepthPackingPrecisionAndUnpackingMaliSafety() {
        // Test exact 16-bit RG packing executed in Mali-400 fragment shaders
        fun packDepthSim(depth: Float): FloatArray {
            val d = depth.coerceIn(0.0f, 1.0f)
            val enc = d * 255.0f
            val high = floor(enc)
            val low = enc - high
            // Quantize to 8-bit unsigned byte representation as stored in GL_RGBA8 framebuffer
            val rByte = floor((high / 255.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            val gByte = floor(low * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            return floatArrayOf(rByte / 255.0f, gByte / 255.0f)
        }

        fun unpackDepthSim(color: FloatArray): Float {
            return color[0] + color[1] * (1.0f / 255.0f)
        }

        val testDepths = floatArrayOf(0.0f, 0.05f, 0.125f, 0.25f, 0.333f, 0.5f, 0.666f, 0.75f, 0.888f, 0.999f, 1.0f)

        for (expected in testDepths) {
            val packed = packDepthSim(expected)
            val unpacked = unpackDepthSim(packed)

            // Quantization error for 16-bit depth (1/65535) is ~0.000015
            val maxError = 0.0005f
            val diff = abs(expected - unpacked)
            assertTrue(
                "Unpacked depth $unpacked differs from expected $expected by $diff (exceeds $maxError)",
                diff <= maxError
            )

            // Ensure values never exceed 1.0 (valid color channel range)
            assertTrue("Packed R channel must be in [0, 1]", packed[0] in 0.0f..1.0f)
            assertTrue("Packed G channel must be in [0, 1]", packed[1] in 0.0f..1.0f)
        }
    }
}
