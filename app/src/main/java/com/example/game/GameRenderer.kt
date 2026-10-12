package com.example.game

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.example.engine.gl.MeshVbo
import com.example.engine.gl.PostProcessShader
import com.example.engine.gl.ShaderCache
import com.example.engine.gl.VaoManager
import com.example.engine.gl.VboPool
import com.example.engine.gl.WorldShader
import com.example.engine.math.Mat4
import com.example.engine.math.Vec3
import com.example.engine.thermal.ThermalTier
import com.example.game.entities.GuardState
import com.example.game.voxel.ChunkMeshBuilder
import com.example.game.voxel.VoxelTypes
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * OpenGL ES Renderer optimized specifically for ARM Mali-400 GPU architecture.
 * Implements Vertex Array Object (VAO) acceleration to dramatically reduce CPU-to-GPU state overhead,
 * low draw-call batching, and thermal frame rate throttling.
 */
class GameRenderer(val engine: GameEngine) : GLSurfaceView.Renderer {

    private val worldShader = WorldShader()
    private val postProcessShader = PostProcessShader()
    val shaderCache = ShaderCache(engine.context)
    val vaoManager = VaoManager()
    val vboPool = VboPool()
    private var meshBuilder: ChunkMeshBuilder? = null

    // Reusable Character / Prop Mesh VBOs and VAOs
    private var characterVbo: MeshVbo? = null
    private var characterVao: Int = 0
    private var coneVbo: MeshVbo? = null
    private var coneVao: Int = 0
    private var lineVbo: MeshVbo? = null

    private var screenWidth: Int = 1280
    private var screenHeight: Int = 720
    private var viewSizeMeters: Float = 14f

    private val projMatrix = Mat4()
    private val viewMatrix = Mat4()
    private val modelMatrix = Mat4()
    private val mvpMatrix = Mat4()

    private var lastTimeNs: Long = System.nanoTime()
    private var alertPulseTimer: Float = 0f
    @Volatile
    private var meshRebuildPending: Boolean = true

    init {
        engine.onMeshNeedsRebuild = {
            meshRebuildPending = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.043f, 0.067f, 0.090f, 1.0f) // #0B1117 tactical navy
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        shaderCache.init()
        worldShader.init(shaderCache)
        postProcessShader.init(shaderCache)
        vaoManager.init()

        initStaticMeshes()

        meshRebuildPending = true
    }

    private fun initStaticMeshes() {
        // Simple Operative Prism/Model VBO
        characterVbo = MeshVbo(36).apply {
            val s = 0.35f
            val h = 1.35f
            // Front
            addQuad(-s, 0f, s, s, 0f, s, s, h, s, -s, h, s, 0f, 0f, 1f, 0.25f, 0.35f, 0.45f)
            // Back
            addQuad(s, 0f, -s, -s, 0f, -s, -s, h, -s, s, h, -s, 0f, 0f, -1f, 0.25f, 0.35f, 0.45f)
            // Left
            addQuad(-s, 0f, -s, -s, 0f, s, -s, h, s, -s, h, -s, -1f, 0f, 0f, 0.20f, 0.30f, 0.40f)
            // Right
            addQuad(s, 0f, s, s, 0f, -s, s, h, -s, s, h, s, 1f, 0f, 0f, 0.20f, 0.30f, 0.40f)
            // Top
            addQuad(-s, h, -s, -s, h, s, s, h, s, s, h, -s, 0f, 1f, 0f, 0.35f, 0.82f, 0.79f)
            upload()
        }

        if (vaoManager.isVaoSupported() && characterVbo != null) {
            characterVao = vaoManager.createVao(
                characterVbo!!,
                worldShader.aPositionLocation,
                worldShader.aNormalLocation,
                worldShader.aColorLocation
            )
        }

        // Vision Cone Fan Mesh VBO
        coneVbo = MeshVbo(64).apply {
            val seg = 16
            val fov = 1.2f
            for (i in 0 until seg) {
                val a0 = -fov * 0.5f + (fov * i) / seg
                val a1 = -fov * 0.5f + (fov * (i + 1)) / seg
                addVertex(0f, 0.05f, 0f, 0f, 1f, 0f, 0.91f, 0.72f, 0.29f, 0.3f)
                addVertex(sin(a0), 0.05f, cos(a0), 0f, 1f, 0f, 0.91f, 0.72f, 0.29f, 0.1f)
                addVertex(sin(a1), 0.05f, cos(a1), 0f, 1f, 0f, 0.91f, 0.72f, 0.29f, 0.1f)
            }
            upload()
        }

        if (vaoManager.isVaoSupported() && coneVbo != null) {
            coneVao = vaoManager.createVao(
                coneVbo!!,
                worldShader.aPositionLocation,
                worldShader.aNormalLocation,
                worldShader.aColorLocation
            )
        }

        // Reusable Line VBO for lasers and tracers (dynamic lines, updated frequently)
        lineVbo = MeshVbo(128)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        GLES20.glViewport(0, 0, width, height)

        val aspect = width.toFloat() / max(1f, height.toFloat())
        val halfW = viewSizeMeters * aspect
        val halfH = viewSizeMeters

        projMatrix.copyFrom(Mat4.ortho(-halfW, halfW, -halfH, halfH, 0.1f, 150f))
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = (now - lastTimeNs) / 1_000_000_000.0f
        lastTimeNs = now
        val safeDt = dt.coerceIn(0.001f, 0.05f)

        alertPulseTimer += safeDt * 4.0f
        var drawCalls = 0

        // Clear Color and Depth
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 1. Setup Camera View Matrix
        val player = engine.player
        val camDistance = 28f
        val camHeight = 36f
        val az = engine.cameraAzimuth
        val eyeX = player.pos.x + sin(az) * camDistance
        val eyeY = camHeight
        val eyeZ = player.pos.z + cos(az) * camDistance

        viewMatrix.copyFrom(
            Mat4.lookAt(
                eyeX = eyeX, eyeY = eyeY, eyeZ = eyeZ,
                centerX = player.pos.x, centerY = 0f, centerZ = player.pos.z,
                upX = 0f, upY = 1f, upZ = 0f
            )
        )

        // 2. Prepare World Shader
        worldShader.use()

        val alertPulse = if (engine.alertLevel > 0) (0.5f + 0.5f * sin(alertPulseTimer)) * (engine.alertLevel / 3.0f) else 0f
        val focusMode = if (player.isFocusing) 1.0f else 0.0f

        worldShader.setEnvironment(
            lightDir = Vec3(-0.4f, 0.8f, 0.3f).normalize(),
            lightColor = Vec3(0.9f, 0.95f, 1.0f),
            ambientColor = Vec3(0.22f, 0.28f, 0.34f),
            playerPos = player.pos,
            playerLightColor = Vec3(0.37f, 0.82f, 0.79f), // Neon cyan personal light
            playerLightRadius = 9.0f,
            alertPulse = alertPulse,
            focusMode = focusMode,
            fogColor = Vec3(0.043f, 0.067f, 0.090f),
            fogStart = 16f,
            fogEnd = 40f
        )

        // 3. Rebuild chunk meshes if requested or if grid changed (guaranteed on GL thread)
        if (meshRebuildPending || meshBuilder == null || meshBuilder?.grid !== engine.grid) {
            meshRebuildPending = false
            meshBuilder?.releaseAll(vaoManager)
            meshBuilder = ChunkMeshBuilder(engine.grid, chunkSize = 16, vboPool = vboPool).apply {
                buildAll(vaoManager, worldShader.aPositionLocation, worldShader.aNormalLocation, worldShader.aColorLocation)
            }
        }

        // 4. Render Voxel Chunks using VAO Manager
        val currentTier = engine.thermalGovernor.telemetry.value.tier
        val maxChunkRenderDistance = if (currentTier == ThermalTier.SEVERE || currentTier == ThermalTier.CRITICAL) 32f else 54f

        meshBuilder?.let { builder ->
            for (chunk in builder.chunks) {
                val chunkCenterX = (chunk.minVx + chunk.maxVx) * 0.5f * VoxelTypes.VOX_SIZE
                val chunkCenterZ = (chunk.minVz + chunk.maxVz) * 0.5f * VoxelTypes.VOX_SIZE
                val distToPlayer = player.pos.distance(Vec3(chunkCenterX, 0f, chunkCenterZ))

                // Frustum / distance culling
                if (distToPlayer > maxChunkRenderDistance) continue

                val vbo = chunk.vbo ?: continue

                modelMatrix.identity()
                val mvp = Mat4.multiply(projMatrix, Mat4.multiply(viewMatrix, modelMatrix))
                worldShader.setModelMatrix(modelMatrix)
                worldShader.setMVPMatrix(mvp)

                vaoManager.draw(
                    vaoId = chunk.vaoId,
                    vbo = vbo,
                    posLoc = worldShader.aPositionLocation,
                    normLoc = worldShader.aNormalLocation,
                    colorLoc = worldShader.aColorLocation
                )
                drawCalls++
            }
        }

        // 4. Render Characters (Player and Guards) using VAO Manager
        characterVbo?.let { vbo ->
            // Render Player
            modelMatrix.identity()
                .translate(player.pos.x, 0f, player.pos.z)
                .rotate(Math.toDegrees(player.yaw.toDouble()).toFloat(), 0f, 1f, 0f)

            if (player.isCrouching) {
                modelMatrix.scale(1.0f, 0.75f, 1.0f)
            }

            worldShader.setModelMatrix(modelMatrix)
            worldShader.setMVPMatrix(Mat4.multiply(projMatrix, Mat4.multiply(viewMatrix, modelMatrix)))
            vaoManager.draw(characterVao, vbo, worldShader.aPositionLocation, worldShader.aNormalLocation, worldShader.aColorLocation)
            drawCalls++

            // Render Guards
            for (g in engine.guards) {
                if (g.isDown) continue
                modelMatrix.identity()
                    .translate(g.pos.x, 0f, g.pos.z)
                    .rotate(Math.toDegrees(g.yaw.toDouble()).toFloat(), 0f, 1f, 0f)

                if (g.def.isFlying) {
                    modelMatrix.translate(0f, 1.8f, 0f)
                }

                worldShader.setModelMatrix(modelMatrix)
                worldShader.setMVPMatrix(Mat4.multiply(projMatrix, Mat4.multiply(viewMatrix, modelMatrix)))
                vaoManager.draw(characterVao, vbo, worldShader.aPositionLocation, worldShader.aNormalLocation, worldShader.aColorLocation)
                drawCalls++
            }
        }

        // 5. Render Guard Vision Cones (Alpha Blending) with VAO
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        coneVbo?.let { cVbo ->
            for (g in engine.guards) {
                if (g.isDown) continue
                val isDrone = g.def.isFlying
                val show = isDrone || player.isFocusing || g.isAlerted || g.suspicion > 10f
                if (!show) continue

                val facing = if (isDrone) g.scanYaw else g.yaw
                modelMatrix.identity()
                    .translate(g.pos.x, 0.05f, g.pos.z)
                    .rotate(Math.toDegrees(facing.toDouble()).toFloat(), 0f, 1f, 0f)
                    .scale(g.def.viewRange, 1f, g.def.viewRange)

                worldShader.setModelMatrix(modelMatrix)
                worldShader.setMVPMatrix(Mat4.multiply(projMatrix, Mat4.multiply(viewMatrix, modelMatrix)))
                vaoManager.draw(coneVao, cVbo, worldShader.aPositionLocation, worldShader.aNormalLocation, worldShader.aColorLocation)
                drawCalls++
            }
        }

        // 6. Render Tracers and Sniper Lasers
        lineVbo?.let { lVbo ->
            lVbo.clear()
            for (t in engine.tracers) {
                val c = t.color
                lVbo.addVertex(t.start.x, t.start.y, t.start.z, 0f, 1f, 0f, c[0], c[1], c[2], c[3])
                lVbo.addVertex(t.end.x, t.end.y, t.end.z, 0f, 1f, 0f, c[0], c[1], c[2], c[3])
            }

            // Sniper aiming lasers
            for (g in engine.guards) {
                if (!g.isDown && g.def.isSniper && g.state == GuardState.COMBAT) {
                    val lx = g.pos.x + sin(g.yaw) * 20f
                    val lz = g.pos.z + cos(g.yaw) * 20f
                    lVbo.addVertex(g.pos.x, 1.2f, g.pos.z, 0f, 1f, 0f, 0.95f, 0.15f, 0.15f, 0.8f)
                    lVbo.addVertex(lx, 1.2f, lz, 0f, 1f, 0f, 0.95f, 0.15f, 0.15f, 0.8f)
                }
            }

            if (lVbo.getVertexCount() > 0) {
                lVbo.upload()
                modelMatrix.identity()
                worldShader.setModelMatrix(modelMatrix)
                worldShader.setMVPMatrix(Mat4.multiply(projMatrix, Mat4.multiply(viewMatrix, modelMatrix)))
                lVbo.bindAndDraw(worldShader.aPositionLocation, worldShader.aNormalLocation, worldShader.aColorLocation, primitiveMode = GLES20.GL_LINES)
                drawCalls++
            }
        }

        GLES20.glDisable(GLES20.GL_BLEND)

        // 7. Fullscreen Post-Processing Shader (Scanlines, Vignette, Hurt Flash, Focus Tint)
        postProcessShader.render(
            width = screenWidth,
            height = screenHeight,
            damageFlash = player.hurtFlash,
            focusMode = focusMode,
            timeSeconds = alertPulseTimer
        )
        drawCalls++

        // 8. Thermal Frame Rate Governor Regulation
        engine.thermalGovernor.onFrameRendered(drawCalls)
    }

    fun release() {
        vaoManager.releaseAll()
        meshBuilder?.releaseAll(vaoManager)
        vboPool.releaseAll()
        characterVbo?.release()
        coneVbo?.release()
        lineVbo?.release()
        worldShader.release()
        postProcessShader.release()
        shaderCache.releaseAll()
    }
}
