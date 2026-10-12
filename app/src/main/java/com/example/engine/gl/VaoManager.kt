package com.example.engine.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log

/**
 * High-performance Vertex Array Object (VAO) Manager.
 *
 * Encapsulates vertex array state into GPU-side objects, eliminating redundant CPU-to-GPU
 * attribute binding and pointer setup calls during every frame's draw cycle.
 *
 * Benefits for ARM Mali-400 and mobile tile-based architectures:
 * - Significantly cuts CPU-side driver overhead by replacing 7-9 JNI state calls per mesh
 *   (glBindBuffer, 3x glEnableVertexAttribArray, 3x glVertexAttribPointer, 3x glDisableVertexAttribArray)
 *   with a single lightweight glBindVertexArray call.
 * - Saves memory bus bandwidth and CPU cache invalidations on memory-constrained mobile chipsets.
 * - Provides seamless graceful fallback for legacy devices without native GLES 3.0 VAO support.
 */
class VaoManager {

    companion object {
        private const val TAG = "VaoManager"
    }

    private var isSupported: Boolean = false
    private var isInitialized: Boolean = false
    private val activeVaos = mutableSetOf<Int>()
    private var savedStateChangesCount: Long = 0L

    /**
     * Initializes VAO support detection within an active EGL OpenGL context.
     */
    fun init() {
        if (isInitialized) return

        try {
            val versionStr = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            if (versionStr.isEmpty()) {
                isSupported = false
                isInitialized = true
                return
            }

            val extensionsStr = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            val hasGles3 = versionStr.contains("OpenGL ES 3.") || versionStr.contains("OpenGL ES-CM 3.")
            val hasOesExtension = extensionsStr.contains("GL_OES_vertex_array_object")

            if (hasGles3 || hasOesExtension) {
                // Test generation to confirm driver validity
                val testArray = IntArray(1)
                GLES30.glGenVertexArrays(1, testArray, 0)
                if (testArray[0] != 0) {
                    GLES30.glDeleteVertexArrays(1, testArray, 0)
                    isSupported = true
                    Log.i(TAG, "Hardware Vertex Array Objects (VAO) successfully enabled ($versionStr)")
                } else {
                    isSupported = false
                    Log.w(TAG, "VAO glGenVertexArrays returned 0, falling back to standard VBO path")
                }
            } else {
                isSupported = false
                Log.i(TAG, "GLES 2.0 baseline detected, using standard VBO pipeline")
            }
        } catch (e: Throwable) {
            isSupported = false
            Log.w(TAG, "GLES 3.0 VAO not supported on this context/driver: ${e.message}, using fallback VBO path")
        }

        isInitialized = true
    }

    /**
     * Allows test fixtures to configure mock support.
     */
    fun setSupportedForTesting(supported: Boolean) {
        isSupported = supported
        isInitialized = true
    }

    fun isVaoSupported(): Boolean = isSupported

    /**
     * Creates and records attribute bindings into a persistent GPU Vertex Array Object.
     *
     * @param vbo The backing Vertex Buffer Object
     * @param posLoc Vertex shader a_Position attribute index
     * @param normLoc Vertex shader a_Normal attribute index
     * @param colorLoc Vertex shader a_Color attribute index
     * @return The generated VAO handle, or 0 if unsupported
     */
    fun createVao(vbo: MeshVbo, posLoc: Int, normLoc: Int, colorLoc: Int): Int {
        if (!isSupported || vbo.getVboId() == 0) return 0

        val vaoIds = IntArray(1)
        try {
            GLES30.glGenVertexArrays(1, vaoIds, 0)
            val vaoId = vaoIds[0]
            if (vaoId == 0) return 0

            // 1. Bind VAO: records all subsequent buffer & attribute state
            GLES30.glBindVertexArray(vaoId)

            // 2. Bind the VBO
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo.getVboId())

            // 3. Configure attribute pointers
            if (posLoc != -1) {
                GLES20.glEnableVertexAttribArray(posLoc)
                GLES20.glVertexAttribPointer(
                    posLoc,
                    3,
                    GLES20.GL_FLOAT,
                    false,
                    MeshVbo.STRIDE_BYTES,
                    MeshVbo.POSITION_OFFSET_BYTES
                )
            }

            if (normLoc != -1) {
                GLES20.glEnableVertexAttribArray(normLoc)
                GLES20.glVertexAttribPointer(
                    normLoc,
                    3,
                    GLES20.GL_FLOAT,
                    false,
                    MeshVbo.STRIDE_BYTES,
                    MeshVbo.NORMAL_OFFSET_BYTES
                )
            }

            if (colorLoc != -1) {
                GLES20.glEnableVertexAttribArray(colorLoc)
                GLES20.glVertexAttribPointer(
                    colorLoc,
                    4,
                    GLES20.GL_FLOAT,
                    false,
                    MeshVbo.STRIDE_BYTES,
                    MeshVbo.COLOR_OFFSET_BYTES
                )
            }

            // 4. Unbind VAO first to seal recorded state
            GLES30.glBindVertexArray(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            activeVaos.add(vaoId)
            return vaoId
        } catch (e: Throwable) {
            Log.e(TAG, "Failed creating VAO: ${e.message}")
            return 0
        }
    }

    /**
     * Binds and renders geometry using either fast VAO state or standard VBO fallback.
     */
    fun draw(
        vaoId: Int,
        vbo: MeshVbo,
        posLoc: Int,
        normLoc: Int,
        colorLoc: Int,
        primitiveMode: Int = GLES20.GL_TRIANGLES
    ) {
        val vertexCount = vbo.getVertexCount()
        if (vertexCount == 0) return

        if (isSupported && vaoId != 0) {
            try {
                // High-speed path: 1 single bind replaces 8 attribute/buffer calls
                GLES30.glBindVertexArray(vaoId)
                GLES20.glDrawArrays(primitiveMode, 0, vertexCount)
                GLES30.glBindVertexArray(0)
                savedStateChangesCount += 8
                return
            } catch (e: Throwable) {
                // If runtime fails, fall back below
            }
        }

        // Standard fallback path for GLES 2.0 without VAO
        vbo.bindAndDraw(posLoc, normLoc, colorLoc, primitiveMode)
    }

    fun deleteVao(vaoId: Int) {
        if (vaoId != 0 && isSupported) {
            try {
                GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
                activeVaos.remove(vaoId)
            } catch (ignored: Throwable) {
            }
        }
    }

    fun releaseAll() {
        if (isSupported && activeVaos.isNotEmpty()) {
            try {
                val array = activeVaos.toIntArray()
                GLES30.glDeleteVertexArrays(array.size, array, 0)
                activeVaos.clear()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun getActiveVaoCount(): Int = activeVaos.size

    fun getSavedStateChangesCount(): Long = savedStateChangesCount
}
