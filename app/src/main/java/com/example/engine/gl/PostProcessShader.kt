package com.example.engine.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Fullscreen HUD and Post-Process Shader for screen-space effects:
 * Scanlines, CRT vignette, damage flash, and tactical night-vision.
 * Highly optimized for Mali-400 GPU fill-rate limits.
 */
class PostProcessShader {

    companion object {
        const val VERTEX_SOURCE = """
            precision mediump float;

            attribute vec2 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;

            void main() {
                v_TexCoord = a_TexCoord;
                gl_Position = vec4(a_Position, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SOURCE = """
            precision mediump float;

            uniform float u_DamageFlash;
            uniform float u_FocusMode;
            uniform float u_Time;
            uniform vec2 u_Resolution;

            varying vec2 v_TexCoord;

            void main() {
                vec2 uv = v_TexCoord;
                vec4 effectColor = vec4(0.0);
                
                // 1. CRT Screen Vignette
                vec2 vigCoord = (uv - 0.5) * 2.0;
                float vignette = clamp(1.0 - dot(vigCoord, vigCoord) * 0.45, 0.0, 1.0);
                float darkBorder = (1.0 - vignette) * 0.7;
                
                // 2. Tactical Scanlines (subtle high-tech HUD feel)
                float scanline = sin(uv.y * u_Resolution.y * 1.5) * 0.04;
                
                // 3. Red Damage Flash
                if (u_DamageFlash > 0.01) {
                    effectColor.rgb += vec3(0.8, 0.05, 0.05) * u_DamageFlash * 0.6;
                    effectColor.a = max(effectColor.a, u_DamageFlash * 0.5);
                }
                
                // 4. Night-vision / Focus Mode Tint & Pulse
                if (u_FocusMode > 0.01) {
                    float pulse = 0.5 + 0.5 * sin(u_Time * 4.0);
                    vec3 cyberTint = vec3(0.05, 0.25, 0.3) * (0.8 + 0.2 * pulse);
                    effectColor.rgb += cyberTint * u_FocusMode;
                    effectColor.a = max(effectColor.a, u_FocusMode * 0.25);
                }
                
                // Vignette shadow overlay
                effectColor.rgb = mix(effectColor.rgb, vec3(0.02, 0.05, 0.08), darkBorder);
                effectColor.a = clamp(effectColor.a + darkBorder * 0.4 + scanline, 0.0, 0.85);
                
                gl_FragColor = effectColor;
            }
        """

        private val QUAD_VERTICES = floatArrayOf(
            // x, y, u, v
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )
    }

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uDamageFlashLoc: Int = -1
    private var uFocusModeLoc: Int = -1
    private var uTimeLoc: Int = -1
    private var uResolutionLoc: Int = -1

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    fun init(shaderCache: ShaderCache? = null) {
        programId = if (shaderCache != null) {
            shaderCache.getOrCompileProgram("post_process_shader", VERTEX_SOURCE, FRAGMENT_SOURCE)
        } else {
            GlShader.createProgram(VERTEX_SOURCE, FRAGMENT_SOURCE)
        }
        if (programId == 0) return

        aPositionLoc = GLES20.glGetAttribLocation(programId, "a_Position")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "a_TexCoord")

        uDamageFlashLoc = GLES20.glGetUniformLocation(programId, "u_DamageFlash")
        uFocusModeLoc = GLES20.glGetUniformLocation(programId, "u_FocusMode")
        uTimeLoc = GLES20.glGetUniformLocation(programId, "u_Time")
        uResolutionLoc = GLES20.glGetUniformLocation(programId, "u_Resolution")
    }

    fun render(width: Int, height: Int, damageFlash: Float, focusMode: Float, timeSeconds: Float) {
        if (programId == 0) return

        // Enable alpha blending for overlay effects
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        GLES20.glUseProgram(programId)

        GLES20.glUniform1f(uDamageFlashLoc, damageFlash)
        GLES20.glUniform1f(uFocusModeLoc, focusMode)
        GLES20.glUniform1f(uTimeLoc, timeSeconds)
        GLES20.glUniform2f(uResolutionLoc, width.toFloat(), height.toFloat())

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, quadBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }
}
