package com.example.engine.thermal

import android.opengl.GLSurfaceView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

data class GovernorTelemetry(
    val currentFps: Float = 60f,
    val targetFps: Int = 60,
    val renderMode: Int = GLSurfaceView.RENDERMODE_CONTINUOUSLY,
    val tier: ThermalTier = ThermalTier.NORMAL,
    val temperatureC: Float = 32.0f,
    val isThrottling: Boolean = false,
    val profile: PowerProfile = PowerProfile.AUTO
)

/**
 * Dynamically adjusts the GLSurfaceView render mode (RENDERMODE_CONTINUOUSLY vs RENDERMODE_WHEN_DIRTY)
 * or requestRender() frequency based on the temperature data provided by ThermalMonitor.
 *
 * Prevents device overheating and eliminates CPU/GPU bus contention on ARM Mali-400 hardware.
 */
class FrameRateGovernor(
    val thermalMonitor: ThermalMonitor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    private var surfaceViewRef: WeakReference<GLSurfaceView>? = null

    private val _telemetry = MutableStateFlow(GovernorTelemetry())
    val telemetry: StateFlow<GovernorTelemetry> = _telemetry.asStateFlow()

    private var profile: PowerProfile = PowerProfile.AUTO
    private var tickerJob: Job? = null

    private var frameCount = 0
    private var lastFpsTimestampNs: Long = System.nanoTime()
    private var calculatedFps: Float = 60f

    private val thermalListener: (ThermalTier, Float) -> Unit = { tier, tempC ->
        onThermalStateChanged(tier, tempC)
    }

    init {
        thermalMonitor.addListener(thermalListener)
    }

    fun attach(glSurfaceView: GLSurfaceView) {
        surfaceViewRef = WeakReference(glSurfaceView)
        applyRenderStrategy()
    }

    fun detach() {
        stopTicker()
        surfaceViewRef?.clear()
        surfaceViewRef = null
    }

    fun setPowerProfile(newProfile: PowerProfile) {
        profile = newProfile
        applyRenderStrategy()
    }

    fun getTargetFps(): Int {
        return when (profile) {
            PowerProfile.PERFORMANCE -> 60
            PowerProfile.BALANCED -> 45
            PowerProfile.BATTERY_SAVER -> 30
            PowerProfile.AUTO -> thermalMonitor.tier.value.baseTargetFps
        }
    }

    private fun onThermalStateChanged(tier: ThermalTier, tempC: Float) {
        applyRenderStrategy()
    }

    /**
     * Dynamically switches GLSurfaceView render mode and requestRender() pacing frequency
     * based on live thermal data.
     */
    fun applyRenderStrategy() {
        val view = surfaceViewRef?.get()
        val targetFps = getTargetFps()
        val tier = thermalMonitor.tier.value
        val tempC = thermalMonitor.temperatureCelsius.value

        // When cool and at 60 FPS, use native continuous rendering
        if (targetFps >= 60 && tier == ThermalTier.NORMAL && profile != PowerProfile.BATTERY_SAVER) {
            stopTicker()
            view?.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            _telemetry.value = GovernorTelemetry(
                currentFps = calculatedFps,
                targetFps = 60,
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY,
                tier = tier,
                temperatureC = tempC,
                isThrottling = false,
                profile = profile
            )
        } else {
            // When warm or throttled, switch to on-demand dirty rendering driven by timed requestRender()
            view?.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            startTicker(targetFps)
            _telemetry.value = GovernorTelemetry(
                currentFps = calculatedFps,
                targetFps = targetFps,
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY,
                tier = tier,
                temperatureC = tempC,
                isThrottling = (tier != ThermalTier.NORMAL || profile == PowerProfile.BATTERY_SAVER),
                profile = profile
            )
        }
    }

    private fun startTicker(fps: Int) {
        tickerJob?.cancel()
        val intervalMs = (1000L / fps.coerceIn(15, 60))

        tickerJob = scope.launch {
            while (isActive) {
                surfaceViewRef?.get()?.requestRender()
                delay(intervalMs)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Called by GLSurfaceView.Renderer on each completed frame to track actual FPS.
     */
    fun onFrameRendered() {
        frameCount++
        val now = System.nanoTime()
        val elapsed = now - lastFpsTimestampNs
        if (elapsed >= 500_000_000L) {
            calculatedFps = (frameCount * 1_000_000_000.0f) / elapsed
            frameCount = 0
            lastFpsTimestampNs = now

            val current = _telemetry.value
            _telemetry.value = current.copy(currentFps = calculatedFps)
        }
    }

    fun release() {
        stopTicker()
        thermalMonitor.removeListener(thermalListener)
        thermalMonitor.unregister()
        surfaceViewRef?.clear()
        surfaceViewRef = null
    }
}
