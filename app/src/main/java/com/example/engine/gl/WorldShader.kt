package com.example.engine.gl

import android.opengl.GLES20
import com.example.engine.math.Mat4
import com.example.engine.math.Vec3

/**
 * Primary 3D shader for voxel environments, props, characters, and dynamic lighting.
 * Specially optimized for Mali-400 series GPUs with mediump float precision and minimal branching.
 */
class WorldShader {

    companion object {
        const val VERTEX_SOURCE = """
            precision mediump float;

            uniform mat4 u_MVPMatrix;
            uniform mat4 u_ModelMatrix;
            uniform vec3 u_PlayerPos;
            uniform float u_FogStart;
            uniform float u_FogEnd;

            attribute vec4 a_Position;
            attribute vec3 a_Normal;
            attribute vec4 a_Color;

            varying vec4 v_Color;
            varying vec3 v_Normal;
            varying vec3 v_WorldPos;
            varying float v_FogFactor;

            void main() {
                vec4 worldPos = u_ModelMatrix * a_Position;
                v_WorldPos = worldPos.xyz;
                
                // Normal transform (assuming uniform scale)
                v_Normal = normalize((u_ModelMatrix * vec4(a_Normal, 0.0)).xyz);
                v_Color = a_Color;
                
                gl_Position = u_MVPMatrix * a_Position;
                
                // Camera distance for depth fog
                float dist = length(worldPos.xyz - u_PlayerPos);
                v_FogFactor = clamp((dist - u_FogStart) / (u_FogEnd - u_FogStart), 0.0, 1.0);
            }
        """

        const val FRAGMENT_SOURCE = """
            precision mediump float;

            uniform vec3 u_LightDir;
            uniform vec3 u_LightColor;
            uniform vec3 u_AmbientColor;
            uniform vec3 u_PlayerPos;
            uniform vec3 u_PlayerLightColor;
            uniform float u_PlayerLightRadius;
            uniform float u_AlertPulse;
            uniform float u_FocusMode;
            uniform vec3 u_FogColor;

            varying vec4 v_Color;
            varying vec3 v_Normal;
            varying vec3 v_WorldPos;
            varying float v_FogFactor;

            void main() {
                vec3 norm = normalize(v_Normal);
                
                // 1. Directional Sun/Key Light
                float diff = max(dot(norm, u_LightDir), 0.0);
                vec3 dirLight = diff * u_LightColor;
                
                // 2. Personal Operative / Tactical Flashlight
                vec3 toPlayer = u_PlayerPos - v_WorldPos;
                float playerDist = length(toPlayer);
                float playerAtten = clamp(1.0 - (playerDist / u_PlayerLightRadius), 0.0, 1.0);
                playerAtten = playerAtten * playerAtten;
                vec3 pointLight = u_PlayerLightColor * playerAtten * max(dot(norm, normalize(toPlayer)), 0.2);
                
                // 3. Ambient and combined illumination
                vec3 totalLight = u_AmbientColor + dirLight + pointLight;
                vec3 finalColor = v_Color.rgb * totalLight;
                
                // 4. Alert / Alarm Red Strobing Effect
                if (u_AlertPulse > 0.01) {
                    finalColor += vec3(0.35, 0.05, 0.05) * u_AlertPulse;
                }
                
                // 5. Tactical Focus Mode (Cyber-Vision Night Enhancement)
                if (u_FocusMode > 0.01) {
                    float edge = 1.0 - max(dot(norm, vec3(0.0, 1.0, 0.0)), 0.0);
                    vec3 focusColor = mix(finalColor, vec3(0.1, 0.35, 0.35) + vec3(0.3, 0.8, 0.8) * edge, 0.4);
                    finalColor = mix(finalColor, focusColor, u_FocusMode);
                }
                
                // 6. Depth Distance Fog
                finalColor = mix(finalColor, u_FogColor, v_FogFactor);
                
                gl_FragColor = vec4(finalColor, v_Color.a);
            }
        """
    }

    private var programId: Int = 0

    // Attribute locations
    var aPositionLocation: Int = -1; private set
    var aNormalLocation: Int = -1; private set
    var aColorLocation: Int = -1; private set

    // Uniform locations
    private var uMVPMatrixLocation: Int = -1
    private var uModelMatrixLocation: Int = -1
    private var uLightDirLocation: Int = -1
    private var uLightColorLocation: Int = -1
    private var uAmbientColorLocation: Int = -1
    private var uPlayerPosLocation: Int = -1
    private var uPlayerLightColorLocation: Int = -1
    private var uPlayerLightRadiusLocation: Int = -1
    private var uAlertPulseLocation: Int = -1
    private var uFocusModeLocation: Int = -1
    private var uFogColorLocation: Int = -1
    private var uFogStartLocation: Int = -1
    private var uFogEndLocation: Int = -1

    fun init(shaderCache: ShaderCache? = null) {
        programId = if (shaderCache != null) {
            shaderCache.getOrCompileProgram("world_shader", VERTEX_SOURCE, FRAGMENT_SOURCE)
        } else {
            GlShader.createProgram(VERTEX_SOURCE, FRAGMENT_SOURCE)
        }
        if (programId == 0) return

        aPositionLocation = GLES20.glGetAttribLocation(programId, "a_Position")
        aNormalLocation = GLES20.glGetAttribLocation(programId, "a_Normal")
        aColorLocation = GLES20.glGetAttribLocation(programId, "a_Color")

        uMVPMatrixLocation = GLES20.glGetUniformLocation(programId, "u_MVPMatrix")
        uModelMatrixLocation = GLES20.glGetUniformLocation(programId, "u_ModelMatrix")
        uLightDirLocation = GLES20.glGetUniformLocation(programId, "u_LightDir")
        uLightColorLocation = GLES20.glGetUniformLocation(programId, "u_LightColor")
        uAmbientColorLocation = GLES20.glGetUniformLocation(programId, "u_AmbientColor")
        uPlayerPosLocation = GLES20.glGetUniformLocation(programId, "u_PlayerPos")
        uPlayerLightColorLocation = GLES20.glGetUniformLocation(programId, "u_PlayerLightColor")
        uPlayerLightRadiusLocation = GLES20.glGetUniformLocation(programId, "u_PlayerLightRadius")
        uAlertPulseLocation = GLES20.glGetUniformLocation(programId, "u_AlertPulse")
        uFocusModeLocation = GLES20.glGetUniformLocation(programId, "u_FocusMode")
        uFogColorLocation = GLES20.glGetUniformLocation(programId, "u_FogColor")
        uFogStartLocation = GLES20.glGetUniformLocation(programId, "u_FogStart")
        uFogEndLocation = GLES20.glGetUniformLocation(programId, "u_FogEnd")
    }

    fun use() {
        GLES20.glUseProgram(programId)
    }

    fun setMVPMatrix(matrix: Mat4) {
        GLES20.glUniformMatrix4fv(uMVPMatrixLocation, 1, false, matrix.values, 0)
    }

    fun setModelMatrix(matrix: Mat4) {
        GLES20.glUniformMatrix4fv(uModelMatrixLocation, 1, false, matrix.values, 0)
    }

    fun setEnvironment(
        lightDir: Vec3,
        lightColor: Vec3,
        ambientColor: Vec3,
        playerPos: Vec3,
        playerLightColor: Vec3,
        playerLightRadius: Float,
        alertPulse: Float,
        focusMode: Float,
        fogColor: Vec3,
        fogStart: Float,
        fogEnd: Float
    ) {
        GLES20.glUniform3f(uLightDirLocation, lightDir.x, lightDir.y, lightDir.z)
        GLES20.glUniform3f(uLightColorLocation, lightColor.x, lightColor.y, lightColor.z)
        GLES20.glUniform3f(uAmbientColorLocation, ambientColor.x, ambientColor.y, ambientColor.z)
        GLES20.glUniform3f(uPlayerPosLocation, playerPos.x, playerPos.y, playerPos.z)
        GLES20.glUniform3f(uPlayerLightColorLocation, playerLightColor.x, playerLightColor.y, playerLightColor.z)
        GLES20.glUniform1f(uPlayerLightRadiusLocation, playerLightRadius)
        GLES20.glUniform1f(uAlertPulseLocation, alertPulse)
        GLES20.glUniform1f(uFocusModeLocation, focusMode)
        GLES20.glUniform3f(uFogColorLocation, fogColor.x, fogColor.y, fogColor.z)
        GLES20.glUniform1f(uFogStartLocation, fogStart)
        GLES20.glUniform1f(uFogEndLocation, fogEnd)
    }

    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }
}
