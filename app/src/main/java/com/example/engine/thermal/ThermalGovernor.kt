package com.example.engine.thermal

import android.content.Context
import android.opengl.GLSurfaceView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thermal status tiers for mobile device power management.
 */
enum class ThermalTier(val displayName: String, val baseTargetFps: Int, val description: String) {
    NORMAL("Normal", 60, "Cool / Full fidelity (60 FPS)"),
    LIGHT("Light", 50, "Mild warm / Energy saving (50 FPS)"),
    MODERATE("Moderate", 40, "Warm / Throttle down (40 FPS)"),
    SEVERE("Severe", 30, "Hot / Mali-400 Protection (30 FPS)"),
    CRITICAL("Critical", 24, "Overheat Prevention (24 FPS)")
}

enum class PowerProfile(val displayName: String) {
    AUTO("Auto Thermal"),
    PERFORMANCE("Performance (60 FPS)"),
    BALANCED("Balanced (45 FPS)"),
    BATTERY_SAVER("Cool & Efficient (30 FPS)")
}

data class ThermalTelemetry(
    val currentFps: Float = 60f,
    val frameTimeMs: Float = 16.6f,
    val targetFps: Int = 60,
    val tier: ThermalTier = ThermalTier.NORMAL,
    val batteryTempC: Float = 32.0f,
    val profile: PowerProfile = PowerProfile.AUTO,
    val drawCalls: Int = 0,
    val memoryUsageMb: Long = 0L,
    val budgetUsagePercent: Float = 100f,
    val textureDetail: String = "Full Detail (100%)",
    val postProcessEnabled: Boolean = true,
    val shadowsEnabled: Boolean = true,
    val isHighHeat: Boolean = false,
    val occlusionMode: String = "Aggressive",
    val culledChunks: Int = 0,
    val culledGuards: Int = 0,
    val drawCallsSaved: Int = 0,
    val verticesSaved: Long = 0L,
    val batchingMode: String = "Material Batched",
    val batchedDrawCalls: Int = 4,
    val batchingDrawCallsSaved: Int = 60,
    val batchedVertices: Int = 0
)

/**
 * High-level Thermal Governor combining ThermalMonitor, FrameRateGovernor,
 * and FrameBudgetManager for comprehensive thermal throttling and battery protection.
 */
class ThermalGovernor(
    context: Context? = null,
    val thermalMonitor: ThermalMonitor = ThermalMonitor(context),
    val frameRateGovernor: FrameRateGovernor = FrameRateGovernor(thermalMonitor),
    val frameBudgetManager: FrameBudgetManager = FrameBudgetManager(thermalMonitor)
) {
    private val _telemetry = MutableStateFlow(ThermalTelemetry())
    val telemetry: StateFlow<ThermalTelemetry> = _telemetry.asStateFlow()

    private var profile: PowerProfile = PowerProfile.AUTO
    private var lastDrawCallsCount = 0
    private var lastFrameTimestampNs: Long = System.nanoTime()
    private var lastFrameTimeMs: Float = 16.6f

    init {
        thermalMonitor.addListener { _, _ ->
            updateTelemetryState()
        }
    }

    fun attachSurfaceView(glSurfaceView: GLSurfaceView) {
        frameRateGovernor.attach(glSurfaceView)
    }

    fun unregisterListeners() {
        thermalMonitor.unregister()
        frameRateGovernor.release()
    }

    fun onPlatformThermalStatusChanged(status: Int) {
        thermalMonitor.onPlatformStatusChanged(status)
        updateTelemetryState()
    }

    fun onBatteryTempChanged(tempC: Float) {
        thermalMonitor.onBatteryTempChanged(tempC)
        updateTelemetryState()
    }

    fun updateTier(newTier: ThermalTier) {
        thermalMonitor.setManualStateForTesting(newTier, thermalMonitor.temperatureCelsius.value)
        updateTelemetryState()
    }

    fun setPowerProfile(newProfile: PowerProfile) {
        profile = newProfile
        frameRateGovernor.setPowerProfile(newProfile)
        updateTelemetryState()
    }

    fun getTargetFps(): Int = frameRateGovernor.getTargetFps()

    fun getTargetFrameIntervalNs(): Long = (1_000_000_000L / getTargetFps())

    private var lastOcclusionTelemetry: com.example.engine.gl.OcclusionTelemetry? = null
    private var lastBatchingTelemetry: com.example.engine.gl.BatchingTelemetry? = null

    fun onFrameRendered(
        drawCalls: Int,
        cullingTelemetry: com.example.engine.gl.OcclusionTelemetry? = null,
        batchingTelemetry: com.example.engine.gl.BatchingTelemetry? = null
    ) {
        val now = System.nanoTime()
        val frameDurationNs = now - lastFrameTimestampNs
        lastFrameTimestampNs = now
        lastFrameTimeMs = frameDurationNs / 1_000_000.0f
        lastDrawCallsCount = drawCalls
        if (cullingTelemetry != null) {
            lastOcclusionTelemetry = cullingTelemetry
        }
        if (batchingTelemetry != null) {
            lastBatchingTelemetry = batchingTelemetry
        }

        frameRateGovernor.onFrameRendered()
        frameBudgetManager.recordFrameTime(lastFrameTimeMs)
        updateTelemetryState()
    }

    private fun updateTelemetryState() {
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val budget = frameBudgetManager.currentBudgetState
        val occlusion = lastOcclusionTelemetry
        val batching = lastBatchingTelemetry

        _telemetry.value = ThermalTelemetry(
            currentFps = frameRateGovernor.telemetry.value.currentFps,
            frameTimeMs = lastFrameTimeMs,
            targetFps = getTargetFps(),
            tier = thermalMonitor.tier.value,
            batteryTempC = thermalMonitor.temperatureCelsius.value,
            profile = profile,
            drawCalls = lastDrawCallsCount,
            memoryUsageMb = usedMemMb,
            budgetUsagePercent = budget.budgetUsagePercent,
            textureDetail = budget.textureDetail.displayName,
            postProcessEnabled = budget.postProcessPassEnabled,
            shadowsEnabled = budget.shadowPassEnabled,
            isHighHeat = budget.isHighHeat,
            occlusionMode = occlusion?.mode?.displayName ?: "Aggressive",
            culledChunks = occlusion?.chunksCulled ?: 0,
            culledGuards = occlusion?.guardsCulled ?: 0,
            drawCallsSaved = occlusion?.estimatedDrawCallsSaved ?: 0,
            verticesSaved = occlusion?.estimatedVerticesSaved ?: 0L,
            batchingMode = batching?.batchMode?.displayName ?: "Material Batched",
            batchedDrawCalls = batching?.batchedDrawCalls ?: 4,
            batchingDrawCallsSaved = batching?.drawCallsSaved ?: 60,
            batchedVertices = batching?.totalVertices ?: 0
        )
    }
}
