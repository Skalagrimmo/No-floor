package com.example.game.entities

import com.example.engine.math.Vec3

data class ThrownGadget(
    val kind: String, // "noise", "emp", "smoke"
    val startPos: Vec3,
    val targetPos: Vec3,
    var progress: Float = 0f,
    val duration: Float = 0.5f
) {
    val currentPos: Vec3 get() {
        val t = progress.coerceIn(0f, 1f)
        val x = startPos.x + (targetPos.x - startPos.x) * t
        val z = startPos.z + (targetPos.z - startPos.z) * t
        // Parabolic ballistic arc
        val arcH = kotlin.math.sin(t * Math.PI.toFloat()) * 2.5f
        val y = startPos.y + (targetPos.y - startPos.y) * t + arcH
        return Vec3(x, y, z)
    }
}

data class SmokeCloud(
    val pos: Vec3,
    val radius: Float = 4.0f,
    var remainingTime: Float = 12.0f
)

data class NoisePulse(
    val pos: Vec3,
    val radius: Float = 12.0f,
    var timer: Float = 0f,
    var pulsesLeft: Int = 4
)
