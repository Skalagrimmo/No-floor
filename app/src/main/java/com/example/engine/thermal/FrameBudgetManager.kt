package com.example.engine.thermal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * Texture detail scaling tiers for mobile memory bandwidth and GPU cache optimization.
 */
enum class TextureDetailLevel(
    val displayName: String,
    val scale: Float,
    val maxResolution: Int,
    val description: String
) {
    FULL("Full Detail (100%)", 1.0f, 512, "Uncompressed high fidelity"),
    HALF("Half Detail (50%)", 0.5f, 256, "50% downsampled texture memory"),
    LOW("Low Detail (25%)", 0.25f, 128, "25% quarter detail for thermal protection"),
    MINIMAL("Minimal Detail (12.5%)", 0.125f, 64, "Minimal cache footprint emergency fallback")
}

/**
 * Discrete rendering passes identified as essential or non-essential for frame budget management.
 */
enum class ShaderPass(val displayName: String, val isEssential: Boolean) {
    WORLD_GEOMETRY("World Geometry Pass", isEssential = true),
    CHARACTER_ENTITIES("Character Entities Pass", isEssential = true),
    SHADOW_MAP_DEPTH("Shadow Map Depth Pre-pass", isEssential = false),
    POST_PROCESSING_QUAD("Fullscreen Post-Processing FX", isEssential = false),
    VISION_CONES_BLEND("Guard Vision Cones Alpha Blending", isEssential = false),
    DYNAMIC_TRACERS("Dynamic Lasers & Weapon Tracers", isEssential = false)
}

/**
 * Comprehensive snapshot of real-time frame budget allocations, thermal status,
 * active texture detail, and shader pass enablement.
 */
data class FrameBudgetState(
    val targetFps: Int = 60,
    val targetBudgetMs: Float = 16.67f,
    val lastFrameTimeMs: Float = 16.67f,
    val averageFrameTimeMs: Float = 16.67f,
    val budgetUsagePercent: Float = 100.0f,
    val tier: ThermalTier = ThermalTier.NORMAL,
    val temperatureCelsius: Float = 32.0f,
    val isHighHeat: Boolean = false,
    val isBudgetOverrun: Boolean = false,
    val textureDetail: TextureDetailLevel = TextureDetailLevel.FULL,
    val textureScale: Float = 1.0f,
    val maxTextureResolution: Int = 512,
    val shadowPassEnabled: Boolean = true,
    val shadowMapResolution: Int = 512,
    val pcfKernelQuality: Int = 2,
    val postProcessPassEnabled: Boolean = true,
    val visionConesPassEnabled: Boolean = true,
    val dynamicTracersPassEnabled: Boolean = true,
    val enabledPassCount: Int = 6,
    val disabledPassCount: Int = 0
)

/**
 * Frame Budget Manager that automatically lowers texture detail and disables non-essential
 * shader passes when system thermal sensors report high heat or when frame times exceed
 * the allotted per-frame execution budget.
 *
 * Specifically designed for mobile tile-based GPUs (ARM Mali-400 / Mali-450) where memory bandwidth,
 * texture cache contention, and fragment fill-rate are primary hardware bottlenecks.
 */
class FrameBudgetManager(
    val thermalMonitor: ThermalMonitor? = null
) {
    companion object {
        const val HIGH_HEAT_TEMP_THRESHOLD_C = 40.0f
        const val SEVERE_HEAT_TEMP_THRESHOLD_C = 43.0f
        const val CRITICAL_HEAT_TEMP_THRESHOLD_C = 46.0f
        const val FRAME_WINDOW_SIZE = 10
    }

    private var currentTargetFps: Int = 60
    private var frameStartNs: Long = 0L
    private val recentFrameTimes = FloatArray(FRAME_WINDOW_SIZE) { 16.67f }
    private var frameIndex = 0
    private var frameSamplesCount = 0

    private var forcedTextureDetail: TextureDetailLevel? = null
    private val forcedPasses = mutableMapOf<ShaderPass, Boolean>()

    private val _state = MutableStateFlow(evaluateCurrentState(32.0f, ThermalTier.NORMAL, 16.67f, 16.67f))
    val state: StateFlow<FrameBudgetState> = _state.asStateFlow()

    init {
        thermalMonitor?.addListener { tier, tempC ->
            onThermalUpdate(tier, tempC)
        }
    }

    /**
     * Marks the beginning of a new rendering frame and configures current target FPS.
     */
    fun beginFrame(targetFps: Int = 60): FrameBudgetState {
        currentTargetFps = targetFps.coerceIn(15, 60)
        frameStartNs = System.nanoTime()
        return _state.value
    }

    /**
     * Completes frame measurement and computes updated budget metrics and pass enablement.
     */
    fun endFrame(drawCalls: Int = 0): FrameBudgetState {
        val now = System.nanoTime()
        val durationNs = if (frameStartNs > 0L) (now - frameStartNs) else 16_666_667L
        val frameDurationMs = (durationNs / 1_000_000.0f).coerceIn(0.1f, 200.0f)

        recordFrameTime(frameDurationMs)
        return _state.value
    }

    /**
     * Records a frame duration (useful for both runtime and test fixtures).
     */
    fun recordFrameTime(durationMs: Float) {
        recentFrameTimes[frameIndex] = durationMs
        frameIndex = (frameIndex + 1) % FRAME_WINDOW_SIZE
        if (frameSamplesCount < FRAME_WINDOW_SIZE) frameSamplesCount++

        val avgTimeMs = calculateAverageFrameTime()
        val currentTemp = thermalMonitor?.temperatureCelsius?.value ?: _state.value.temperatureCelsius
        val currentTier = thermalMonitor?.tier?.value ?: _state.value.tier

        _state.value = evaluateCurrentState(currentTemp, currentTier, durationMs, avgTimeMs)
    }

    private fun calculateAverageFrameTime(): Float {
        val count = max(1, frameSamplesCount)
        var sum = 0.0f
        for (i in 0 until count) {
            sum += recentFrameTimes[i]
        }
        return sum / count
    }

    private fun onThermalUpdate(tier: ThermalTier, tempC: Float) {
        val avgTimeMs = calculateAverageFrameTime()
        val lastTimeMs = _state.value.lastFrameTimeMs
        _state.value = evaluateCurrentState(tempC, tier, lastTimeMs, avgTimeMs)
    }

    /**
     * Evaluates budget and thermal conditions to select texture detail and toggle shader passes.
     */
    private fun evaluateCurrentState(
        tempC: Float,
        tier: ThermalTier,
        lastFrameMs: Float,
        avgFrameMs: Float
    ): FrameBudgetState {
        val targetBudgetMs = 1000.0f / currentTargetFps
        val budgetUsagePercent = (avgFrameMs / targetBudgetMs) * 100.0f

        val isHighHeat = (tempC >= HIGH_HEAT_TEMP_THRESHOLD_C ||
                tier == ThermalTier.MODERATE ||
                tier == ThermalTier.SEVERE ||
                tier == ThermalTier.CRITICAL)

        val isSevereHeat = (tempC >= SEVERE_HEAT_TEMP_THRESHOLD_C ||
                tier == ThermalTier.SEVERE ||
                tier == ThermalTier.CRITICAL)

        val isCriticalHeat = (tempC >= CRITICAL_HEAT_TEMP_THRESHOLD_C ||
                tier == ThermalTier.CRITICAL)

        val isBudgetOverrun = (avgFrameMs > targetBudgetMs * 1.05f)

        // 1. Determine Texture Detail Level
        val calculatedTextureDetail = when {
            isCriticalHeat -> TextureDetailLevel.MINIMAL
            isSevereHeat -> TextureDetailLevel.LOW
            isHighHeat || isBudgetOverrun -> TextureDetailLevel.HALF
            tempC >= 37.0f || tier == ThermalTier.LIGHT -> TextureDetailLevel.FULL
            else -> TextureDetailLevel.FULL
        }
        val finalTextureDetail = forcedTextureDetail ?: calculatedTextureDetail

        // 2. Determine Non-Essential Shader Pass Enablement
        // Fullscreen post-process quad pass: disabled immediately on high heat or budget overrun to save fill-rate
        val postProcessEnabled = forcedPasses[ShaderPass.POST_PROCESSING_QUAD] ?: (!isHighHeat && !isBudgetOverrun)

        // Shadow depth map pass: disabled completely on severe/critical heat; downscaled on moderate/high heat
        val shadowPassEnabled = forcedPasses[ShaderPass.SHADOW_MAP_DEPTH] ?: (!isSevereHeat && !isCriticalHeat)

        // Shadow map resolution & PCF kernel complexity governed by thermal tier & texture detail
        val shadowMapResolution = when (finalTextureDetail) {
            TextureDetailLevel.FULL -> 512
            TextureDetailLevel.HALF -> 256
            TextureDetailLevel.LOW -> 128
            TextureDetailLevel.MINIMAL -> 64
        }

        val pcfKernelQuality = when {
            isSevereHeat || isCriticalHeat -> 0 // 1-tap fast shadow
            isHighHeat || isBudgetOverrun -> 1   // 4-tap PCF
            tier == ThermalTier.LIGHT -> 1       // 4-tap PCF
            else -> 2                            // 9-tap smooth PCF
        }

        // Alpha blending pass for guard vision cones: non-essential when severe heat occurs
        val visionConesEnabled = forcedPasses[ShaderPass.VISION_CONES_BLEND] ?: (!isSevereHeat && !isCriticalHeat)

        // Dynamic lasers & tracers pass: disabled in critical emergency heat to minimize draw calls
        val dynamicTracersEnabled = forcedPasses[ShaderPass.DYNAMIC_TRACERS] ?: (!isCriticalHeat)

        // Count enabled vs disabled passes
        var enabledCount = 2 // World and Characters are always essential
        var disabledCount = 0

        if (shadowPassEnabled) enabledCount++ else disabledCount++
        if (postProcessEnabled) enabledCount++ else disabledCount++
        if (visionConesEnabled) enabledCount++ else disabledCount++
        if (dynamicTracersEnabled) enabledCount++ else disabledCount++

        return FrameBudgetState(
            targetFps = currentTargetFps,
            targetBudgetMs = targetBudgetMs,
            lastFrameTimeMs = lastFrameMs,
            averageFrameTimeMs = avgFrameMs,
            budgetUsagePercent = budgetUsagePercent,
            tier = tier,
            temperatureCelsius = tempC,
            isHighHeat = isHighHeat,
            isBudgetOverrun = isBudgetOverrun,
            textureDetail = finalTextureDetail,
            textureScale = finalTextureDetail.scale,
            maxTextureResolution = finalTextureDetail.maxResolution,
            shadowPassEnabled = shadowPassEnabled,
            shadowMapResolution = shadowMapResolution,
            pcfKernelQuality = pcfKernelQuality,
            postProcessPassEnabled = postProcessEnabled,
            visionConesPassEnabled = visionConesEnabled,
            dynamicTracersPassEnabled = dynamicTracersEnabled,
            enabledPassCount = enabledCount,
            disabledPassCount = disabledCount
        )
    }

    fun isPassEnabled(pass: ShaderPass): Boolean {
        if (pass.isEssential) return true
        val current = _state.value
        return when (pass) {
            ShaderPass.WORLD_GEOMETRY,
            ShaderPass.CHARACTER_ENTITIES -> true
            ShaderPass.SHADOW_MAP_DEPTH -> current.shadowPassEnabled
            ShaderPass.POST_PROCESSING_QUAD -> current.postProcessPassEnabled
            ShaderPass.VISION_CONES_BLEND -> current.visionConesPassEnabled
            ShaderPass.DYNAMIC_TRACERS -> current.dynamicTracersPassEnabled
        }
    }

    fun getTextureDetail(): TextureDetailLevel = _state.value.textureDetail

    fun getTextureScale(): Float = _state.value.textureScale

    fun getMaxTextureResolution(): Int = _state.value.maxTextureResolution

    fun isHighHeat(): Boolean = _state.value.isHighHeat

    val currentBudgetState: FrameBudgetState get() = _state.value

    // -------------------------------------------------------------------------
    // Testing and override hooks
    // -------------------------------------------------------------------------

    fun setManualThermalStateForTesting(tier: ThermalTier, tempC: Float) {
        onThermalUpdate(tier, tempC)
    }

    fun forceTextureDetailForTesting(detail: TextureDetailLevel?) {
        forcedTextureDetail = detail
        val current = _state.value
        _state.value = evaluateCurrentState(
            current.temperatureCelsius,
            current.tier,
            current.lastFrameTimeMs,
            current.averageFrameTimeMs
        )
    }

    fun forcePassEnabledForTesting(pass: ShaderPass, enabled: Boolean?) {
        if (enabled == null) {
            forcedPasses.remove(pass)
        } else {
            forcedPasses[pass] = enabled
        }
        val current = _state.value
        _state.value = evaluateCurrentState(
            current.temperatureCelsius,
            current.tier,
            current.lastFrameTimeMs,
            current.averageFrameTimeMs
        )
    }

    fun reset() {
        forcedTextureDetail = null
        forcedPasses.clear()
        frameIndex = 0
        frameSamplesCount = 0
        recentFrameTimes.fill(16.67f)
        _state.value = evaluateCurrentState(32.0f, ThermalTier.NORMAL, 16.67f, 16.67f)
    }
}
