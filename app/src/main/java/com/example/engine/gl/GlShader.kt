package com.example.engine.gl

import android.opengl.GLES20
import android.util.Log

/**
 * Utility class for compiling and linking OpenGL ES 2.0 shaders.
 * Designed with rigorous error checking and informative logging.
 */
object GlShader {
    private const val TAG = "GlShader"

    fun compileShader(shaderType: Int, source: String): Int {
        val shaderId = GLES20.glCreateShader(shaderType)
        if (shaderId == 0) {
            Log.e(TAG, "Failed to create shader of type $shaderType")
            return 0
        }

        GLES20.glShaderSource(shaderId, source)
        GLES20.glCompileShader(shaderId)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shaderId)
            Log.e(TAG, "Compilation error in shader type $shaderType:\n$log\nSource:\n$source")
            GLES20.glDeleteShader(shaderId)
            return 0
        }

        return shaderId
    }

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0

        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }

        val programId = GLES20.glCreateProgram()
        if (programId == 0) {
            Log.e(TAG, "Failed to create OpenGL ES program")
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return 0
        }

        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(programId)
            Log.e(TAG, "Error linking OpenGL ES program:\n$log")
            GLES20.glDeleteProgram(programId)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return 0
        }

        // Shaders can be flagged for deletion after being attached to a program
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return programId
    }
}
