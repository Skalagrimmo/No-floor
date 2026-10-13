package com.example.engine.gl

import com.example.engine.math.Mat4
import com.example.engine.math.Vec3
import com.example.engine.thermal.ThermalTier

data class ShadowSettings(
    val resolution: Int,
    val pcfKernelSize: Int, // 0 = 1-tap, 1 = 4-tap (2x2), 2 = 9-tap (3x3)
    val bias: Float,
    val strength: Float,
    val enabled: Boolean
)

/**
 * Manages low-resolution directional shadow mapping, dynamic FBO resizing,
 * and PCF quality adjustment based on thermal throttling tiers for ARM Mali-400.
 */
class ShadowMapManager {

    var fbo: ShadowMapFbo? = null; private set
    val shader = ShadowShader()

    val lightSpaceMatrix = Mat4()
    private val lightViewMatrix = Mat4()
    private val lightProjMatrix = Mat4()

    var currentSettings = ShadowSettings(
        resolution = 512,
        pcfKernelSize = 2,
        bias = 0.0035f,
        strength = 0.55f,
        enabled = true
    ); private set

    fun init(shaderCache: ShaderCache? = null) {
        shader.init(shaderCache)
        fbo = ShadowMapFbo(currentSettings.resolution)
    }

    /**
     * Determines shadow resolution and PCF filtering complexity according to device thermal tier.
     */
    fun evaluateThermalQuality(tier: ThermalTier) {
        val newSettings = when (tier) {
            ThermalTier.NORMAL -> ShadowSettings(
                resolution = 512,
                pcfKernelSize = 2, // 9-tap PCF
                bias = 0.0035f,
                strength = 0.55f,
                enabled = true
            )
            ThermalTier.LIGHT -> ShadowSettings(
                resolution = 256,
                pcfKernelSize = 1, // 4-tap PCF
                bias = 0.0045f,
                strength = 0.50f,
                enabled = true
            )
            ThermalTier.MODERATE -> ShadowSettings(
                resolution = 256,
                pcfKernelSize = 1, // 4-tap PCF
                bias = 0.0055f,
                strength = 0.45f,
                enabled = true
            )
            ThermalTier.SEVERE -> ShadowSettings(
                resolution = 128,
                pcfKernelSize = 0, // 1-tap fast shadow
                bias = 0.0075f,
                strength = 0.40f,
                enabled = true
            )
            ThermalTier.CRITICAL -> ShadowSettings(
                resolution = 128,
                pcfKernelSize = 0,
                bias = 0.0090f,
                strength = 0.0f, // Disable shadow evaluation to allow Mali-400 GPU cooldown
                enabled = false
            )
        }

        if (newSettings.resolution != currentSettings.resolution) {
            fbo?.updateResolution(newSettings.resolution)
        }
        currentSettings = newSettings
    }

    /**
     * Updates directional light frustum centered around operative player.
     */
    fun updateLightFrustum(playerPos: Vec3, lightDir: Vec3) {
        val dist = 28f
        val eyeX = playerPos.x + lightDir.x * dist
        val eyeY = playerPos.y + lightDir.y * dist
        val eyeZ = playerPos.z + lightDir.z * dist

        lightViewMatrix.copyFrom(
            Mat4.lookAt(
                eyeX = eyeX, eyeY = eyeY, eyeZ = eyeZ,
                centerX = playerPos.x, centerY = playerPos.y, centerZ = playerPos.z,
                upX = 0f, upY = 1f, upZ = 0f
            )
        )

        val frustumSize = 18f
        lightProjMatrix.copyFrom(
            Mat4.ortho(
                -frustumSize, frustumSize,
                -frustumSize, frustumSize,
                1.0f, 65f
            )
        )

        lightSpaceMatrix.copyFrom(Mat4.multiply(lightProjMatrix, lightViewMatrix))
    }

    fun release() {
        fbo?.release()
        fbo = null
        shader.release()
    }
}
