package com.example.engine.gl

import android.opengl.GLES20
import com.example.engine.math.Mat4

/**
 * Optimized depth-rendering shader for directional shadow map generation.
 * Packs gl_FragCoord.z into 8-bit RGBA channels for 100% OpenGL ES 2.0/3.0 compatibility
 * on Mali-400 GPUs without requiring extension-dependent depth textures.
 */
class ShadowShader {

    companion object {
        const val VERTEX_SOURCE = """
            precision mediump float;
            uniform mat4 u_LightMVPMatrix;
            attribute vec4 a_Position;

            void main() {
                gl_Position = u_LightMVPMatrix * a_Position;
            }
        """

        const val FRAGMENT_SOURCE = """
            precision mediump float;

            // 16-bit depth packing into RG8:
            // Eliminates 255^3 fp16 precision overflow on ARM Mali-400 GPUs
            // while matching native GL_DEPTH_COMPONENT16 depth buffer resolution.
            vec4 packDepth(float depth) {
                float d = clamp(depth, 0.0, 1.0);
                float enc = d * 255.0;
                float high = floor(enc);
                float low = enc - high;
                return vec4(high / 255.0, low, 0.0, 1.0);
            }

            void main() {
                gl_FragColor = packDepth(gl_FragCoord.z);
            }
        """
    }

    private var programId: Int = 0
    var aPositionLocation: Int = -1; private set
    private var uLightMVPMatrixLocation: Int = -1

    fun init(shaderCache: ShaderCache? = null) {
        programId = if (shaderCache != null) {
            shaderCache.getOrCompileProgram("shadow_depth_shader", VERTEX_SOURCE, FRAGMENT_SOURCE)
        } else {
            GlShader.createProgram(VERTEX_SOURCE, FRAGMENT_SOURCE)
        }
        if (programId == 0) return

        aPositionLocation = GLES20.glGetAttribLocation(programId, "a_Position")
        uLightMVPMatrixLocation = GLES20.glGetUniformLocation(programId, "u_LightMVPMatrix")
    }

    fun use() {
        if (programId != 0) {
            GLES20.glUseProgram(programId)
        }
    }

    fun setLightMVPMatrix(matrix: Mat4) {
        if (uLightMVPMatrixLocation != -1) {
            GLES20.glUniformMatrix4fv(uLightMVPMatrixLocation, 1, false, matrix.values, 0)
        }
    }

    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }
}
