package com.example.engine.math

import android.opengl.Matrix

/**
 * 4x4 matrix encapsulation wrapping Android's native OpenGL Matrix routines.
 * Uses 16-element column-major float array standard in OpenGL ES 2.0 / 3.0.
 */
class Mat4 {
    val values: FloatArray = FloatArray(16)

    init {
        identity()
    }

    fun identity(): Mat4 {
        Matrix.setIdentityM(values, 0)
        return this
    }

    fun translate(x: Float, y: Float, z: Float): Mat4 {
        Matrix.translateM(values, 0, x, y, z)
        return this
    }

    fun rotate(angleDegrees: Float, x: Float, y: Float, z: Float): Mat4 {
        Matrix.rotateM(values, 0, angleDegrees, x, y, z)
        return this
    }

    fun scale(sx: Float, sy: Float, sz: Float): Mat4 {
        Matrix.scaleM(values, 0, sx, sy, sz)
        return this
    }

    fun multiply(other: Mat4): Mat4 {
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, this.values, 0, other.values, 0)
        System.arraycopy(result, 0, this.values, 0, 16)
        return this
    }

    fun copyFrom(other: Mat4): Mat4 {
        System.arraycopy(other.values, 0, this.values, 0, 16)
        return this
    }

    companion object {
        fun ortho(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): Mat4 {
            val m = Mat4()
            Matrix.orthoM(m.values, 0, left, right, bottom, top, near, far)
            return m
        }

        fun perspective(fovyDegrees: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val m = Mat4()
            Matrix.perspectiveM(m.values, 0, fovyDegrees, aspect, near, far)
            return m
        }

        fun lookAt(
            eyeX: Float, eyeY: Float, eyeZ: Float,
            centerX: Float, centerY: Float, centerZ: Float,
            upX: Float, upY: Float, upZ: Float
        ): Mat4 {
            val m = Mat4()
            Matrix.setLookAtM(m.values, 0, eyeX, eyeY, eyeZ, centerX, centerY, centerZ, upX, upY, upZ)
            return m
        }

        fun multiply(left: Mat4, right: Mat4): Mat4 {
            val m = Mat4()
            Matrix.multiplyMM(m.values, 0, left.values, 0, right.values, 0)
            return m
        }
    }
}
