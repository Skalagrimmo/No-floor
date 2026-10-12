package com.example.engine.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors hardware SoC and battery thermal data.
 * Feeds live thermal events to FrameRateGovernor for dynamic render pacing.
 */
class ThermalMonitor(private val context: Context? = null) {

    private val _tier = MutableStateFlow(ThermalTier.NORMAL)
    val tier: StateFlow<ThermalTier> = _tier.asStateFlow()

    private val _temperatureCelsius = MutableStateFlow(32.0f)
    val temperatureCelsius: StateFlow<Float> = _temperatureCelsius.asStateFlow()

    private val listeners = mutableListOf<(ThermalTier, Float) -> Unit>()

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (tempRaw > 0) {
                    val tempC = tempRaw / 10.0f
                    onBatteryTempChanged(tempC)
                }
            }
        }
    }

    init {
        register()
    }

    fun register() {
        context?.let { ctx ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (powerManager != null) {
                        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                            onPlatformStatusChanged(status)
                        }
                        powerManager.addThermalStatusListener(ctx.mainExecutor, thermalListener!!)
                    }
                }
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                ctx.registerReceiver(batteryReceiver, filter)
            } catch (ignored: Exception) {
            }
        }
    }

    fun unregister() {
        context?.let { ctx ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
                    val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    powerManager?.removeThermalStatusListener(thermalListener!!)
                }
                ctx.unregisterReceiver(batteryReceiver)
            } catch (ignored: Exception) {
            }
        }
    }

    fun onPlatformStatusChanged(status: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val newTier = when (status) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalTier.NORMAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalTier.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalTier.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalTier.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalTier.CRITICAL
                else -> ThermalTier.NORMAL
            }
            updateState(newTier, _temperatureCelsius.value)
        }
    }

    fun onBatteryTempChanged(tempC: Float) {
        val newTier = when {
            tempC >= 46.0f -> ThermalTier.CRITICAL
            tempC >= 43.0f -> ThermalTier.SEVERE
            tempC >= 40.0f -> ThermalTier.MODERATE
            tempC >= 37.0f -> ThermalTier.LIGHT
            else -> ThermalTier.NORMAL
        }
        updateState(newTier, tempC)
    }

    fun setManualStateForTesting(tier: ThermalTier, tempC: Float) {
        updateState(tier, tempC)
    }

    private fun updateState(newTier: ThermalTier, tempC: Float) {
        _tier.value = newTier
        _temperatureCelsius.value = tempC
        synchronized(listeners) {
            for (listener in listeners) {
                listener(newTier, tempC)
            }
        }
    }

    fun addListener(listener: (ThermalTier, Float) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
            listener(_tier.value, _temperatureCelsius.value)
        }
    }

    fun removeListener(listener: (ThermalTier, Float) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
}
