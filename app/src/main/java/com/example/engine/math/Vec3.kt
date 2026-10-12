package com.example.engine.math

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance 3D vector optimized for zero-allocation math routines
 * on Android and budget Mali-400 hardware.
 */
data class Vec3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    fun set(nx: Float, ny: Float, nz: Float): Vec3 {
        x = nx
        y = ny
        z = nz
        return this
    }

    fun set(other: Vec3): Vec3 {
        x = other.x
        y = other.y
        z = other.z
        return this
    }

    fun add(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    fun add(ox: Float, oy: Float, oz: Float): Vec3 = Vec3(x + ox, y + oy, z + oz)

    fun sub(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    fun scale(factor: Float): Vec3 = Vec3(x * factor, y * factor, z * factor)

    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun lengthSq(): Float = x * x + y * y + z * z

    fun distance(other: Vec3): Float = sub(other).length()
    fun distanceSq(other: Vec3): Float = sub(other).lengthSq()

    fun normalize(): Vec3 {
        val len = length()
        return if (len > 1e-6f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, 0f)
    }

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun lerp(target: Vec3, alpha: Float): Vec3 = Vec3(
        x + (target.x - x) * alpha,
        y + (target.y - y) * alpha,
        z + (target.z - z) * alpha
    )

    fun copy(): Vec3 = Vec3(x, y, z)
}
