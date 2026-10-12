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
    val memoryUsageMb: Long = 0L
)

/**
 * High-level Thermal Governor combining ThermalMonitor and FrameRateGovernor
 * for comprehensive thermal throttling and battery protection.
 */
class ThermalGovernor(
    context: Context? = null,
    val thermalMonitor: ThermalMonitor = ThermalMonitor(context),
    val frameRateGovernor: FrameRateGovernor = FrameRateGovernor(thermalMonitor)
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

    fun onFrameRendered(drawCalls: Int) {
        val now = System.nanoTime()
        val frameDurationNs = now - lastFrameTimestampNs
        lastFrameTimestampNs = now
        lastFrameTimeMs = frameDurationNs / 1_000_000.0f
        lastDrawCallsCount = drawCalls

        frameRateGovernor.onFrameRendered()
        updateTelemetryState()
    }

    private fun updateTelemetryState() {
        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        _telemetry.value = ThermalTelemetry(
            currentFps = frameRateGovernor.telemetry.value.currentFps,
            frameTimeMs = lastFrameTimeMs,
            targetFps = getTargetFps(),
            tier = thermalMonitor.tier.value,
            batteryTempC = thermalMonitor.temperatureCelsius.value,
            profile = profile,
            drawCalls = lastDrawCallsCount,
            memoryUsageMb = usedMemMb
        )
    }
}
