package com.example.game

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.example.engine.gl.BatchMode
import com.example.engine.gl.BatchingTelemetry
import com.example.engine.gl.GeometryBatcher
import com.example.engine.gl.MeshVbo
import com.example.engine.gl.OcclusionCuller
import com.example.engine.gl.OcclusionMode
import com.example.engine.gl.PostProcessShader
import com.example.engine.gl.ShaderCache
import com.example.engine.gl.ShadowMapManager
import com.example.engine.gl.ShadowShader
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
 * low draw-call batching, dynamic thermal frame rate throttling, and an adaptive low-res PCF shadow map system.
 */
class GameRenderer(val engine: GameEngine) : GLSurfaceView.Renderer {

    private val worldShader = WorldShader()
    private val postProcessShader = PostProcessShader()
    val shadowMapManager = ShadowMapManager()
    val shaderCache = ShaderCache(engine.context)
    val vaoManager = VaoManager()
    val vboPool = VboPool()
    val occlusionCuller = OcclusionCuller()
    val geometryBatcher = GeometryBatcher(vboPool = vboPool)
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
        shaderCache.precompile(
            mapOf(
                "world_shader" to Pair(WorldShader.VERTEX_SOURCE, WorldShader.FRAGMENT_SOURCE),
                "post_process_shader" to Pair(PostProcessShader.VERTEX_SOURCE, PostProcessShader.FRAGMENT_SOURCE),
                "shadow_depth_shader" to Pair(ShadowShader.VERTEX_SOURCE, ShadowShader.FRAGMENT_SOURCE)
            )
        )
        worldShader.init(shaderCache)
        postProcessShader.init(shaderCache)
        shadowMapManager.init(shaderCache)
        vaoManager.init()

        initStaticMeshes()

        meshRebuildPending = true
        occlusionCuller.grid = engine.grid
        geometryBatcher.init()
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

        val frameBudgetManager = engine.thermalGovernor.frameBudgetManager
        frameBudgetManager.beginFrame(engine.thermalGovernor.getTargetFps())
        val budget = frameBudgetManager.currentBudgetState

        // 1. Rebuild chunk meshes and static material batches if requested or if grid changed
        if (meshRebuildPending || meshBuilder == null || meshBuilder?.grid !== engine.grid) {
            meshRebuildPending = false
            meshBuilder?.releaseAll(vaoManager)
            meshBuilder = ChunkMeshBuilder(engine.grid, chunkSize = 16, vboPool = vboPool).apply {
                buildAll(vaoManager, worldShader.aPositionLocation, worldShader.aNormalLocation, worldShader.aColorLocation)
            }
            geometryBatcher.rebuildBatches(
                grid = engine.grid,
                waistCutaway = false,
                vaoManager = vaoManager,
                posLoc = worldShader.aPositionLocation,
                normLoc = worldShader.aNormalLocation,
                colorLoc = worldShader.aColorLocation
            )
        }

        val player = engine.player
        val currentTier = engine.thermalGovernor.telemetry.value.tier
        val lightDir = Vec3(-0.4f, 0.8f, 0.3f).normalize()

        occlusionCuller.grid = engine.grid
        val cullingMode = when {
            budget.isHighHeat || currentTier == ThermalTier.SEVERE || currentTier == ThermalTier.CRITICAL ->
                OcclusionMode.ULTRA_AGGRESSIVE
            currentTier == ThermalTier.LIGHT || currentTier == ThermalTier.MODERATE ->
                OcclusionMode.AGGRESSIVE
            else ->
                OcclusionMode.AGGRESSIVE
        }
        occlusionCuller.beginFrame(cullingMode)

        // 2. Evaluate Thermal Quality and update Directional Light Frustum
        shadowMapManager.evaluateThermalQuality(currentTier)
        shadowMapManager.updateLightFrustum(player.pos, lightDir)
        val shadowSettings = shadowMapManager.currentSettings

        // Lower shadow texture resolution according to Frame Budget Manager
        if (shadowSettings.resolution != budget.shadowMapResolution) {
            shadowMapManager.fbo?.updateResolution(budget.shadowMapResolution)
        }

        val canRenderShadows = budget.shadowPassEnabled && shadowMapManager.fbo?.isReady == true

        // ---------------------------------------------------------------------
        // PASS 1: Low-Resolution Directional Shadow Map Pass (if active)
        // ---------------------------------------------------------------------
        if (canRenderShadows) {
            val shadowFbo = shadowMapManager.fbo!!
            shadowFbo.bind()

            val shadowShader = shadowMapManager.shader
            shadowShader.use()

            // Shadow caster distance scaled for thermal performance
            val maxShadowDist = if (currentTier == ThermalTier.SEVERE || budget.isHighHeat) 24f else 40f

            // Render environmental shadow casters (batched by material or chunk fallback)
            if (geometryBatcher.batchMode != BatchMode.CHUNK_UNBATCHED && geometryBatcher.isBuilt) {
                drawCalls += geometryBatcher.renderBatchedShadows(
                    shadowShader = shadowShader,
                    lightSpaceMatrix = shadowMapManager.lightSpaceMatrix
                )
            } else {
                meshBuilder?.let { builder ->
                    for (chunk in builder.chunks) {
                        val chunkCenterX = (chunk.minVx + chunk.maxVx) * 0.5f * VoxelTypes.VOX_SIZE
                        val chunkCenterZ = (chunk.minVz + chunk.maxVz) * 0.5f * VoxelTypes.VOX_SIZE
                        if (occlusionCuller.isShadowCasterOccluded(chunkCenterX, chunkCenterZ, player.pos, maxShadowDist)) {
                            occlusionCuller.recordShadowCasterCulled()
                            continue
                        }

                        val vbo = chunk.vbo ?: continue
                        if (vbo.getVertexCount() == 0) continue

                        modelMatrix.identity()
                        val lightMvp = Mat4.multiply(shadowMapManager.lightSpaceMatrix, modelMatrix)
                        shadowShader.setLightMVPMatrix(lightMvp)

                        vbo.bindAndDraw(shadowShader.aPositionLocation, -1, -1)
                        drawCalls++
                    }
                }
            }

            // Render Player shadow caster
            characterVbo?.let { vbo ->
                modelMatrix.identity()
                    .translate(player.pos.x, 0f, player.pos.z)
                    .rotate(Math.toDegrees(player.yaw.toDouble()).toFloat(), 0f, 1f, 0f)

                if (player.isCrouching) {
                    modelMatrix.scale(1.0f, 0.75f, 1.0f)
                }

                shadowShader.setLightMVPMatrix(Mat4.multiply(shadowMapManager.lightSpaceMatrix, modelMatrix))
                vbo.bindAndDraw(shadowShader.aPositionLocation, -1, -1)
                drawCalls++

                // Render Guard shadow casters
                for (g in engine.guards) {
                    if (g.isDown) continue
                    if (occlusionCuller.isShadowCasterOccluded(g.pos.x, g.pos.z, player.pos, maxShadowDist)) {
                        occlusionCuller.recordShadowCasterCulled()
                        continue
                    }

                    modelMatrix.identity()
                        .translate(g.pos.x, 0f, g.pos.z)
                        .rotate(Math.toDegrees(g.yaw.toDouble()).toFloat(), 0f, 1f, 0f)

                    if (g.def.isFlying) {
                        modelMatrix.translate(0f, 1.8f, 0f)
                    }

                    shadowShader.setLightMVPMatrix(Mat4.multiply(shadowMapManager.lightSpaceMatrix, modelMatrix))
                    vbo.bindAndDraw(shadowShader.aPositionLocation, -1, -1)
                    drawCalls++
                }
            }

            shadowFbo.unbind(screenWidth, screenHeight)
        }

        // ---------------------------------------------------------------------
        // PASS 2: Main World Render Pass
        // ---------------------------------------------------------------------
        // Clear Color and Depth for screen display
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 3. Setup Camera View Matrix
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

        // 4. Configure World Shader and PCF Shadow Parameters
        worldShader.use()
        worldShader.setLightSpaceMatrix(shadowMapManager.lightSpaceMatrix)

        if (canRenderShadows) {
            worldShader.setShadowParameters(
                textureUnit = 0,
                textureId = shadowMapManager.fbo!!.textureId,
                mapSize = budget.shadowMapResolution.toFloat(),
                bias = shadowSettings.bias,
                strength = shadowSettings.strength,
                quality = budget.pcfKernelQuality.toFloat()
            )
        } else {
            worldShader.setShadowParameters(
                textureUnit = 0,
                textureId = shadowMapManager.fbo?.textureId ?: 0,
                mapSize = 128f,
                bias = 0.01f,
                strength = 0.0f,
                quality = 0.0f
            )
        }

        val alertPulse = if (engine.alertLevel > 0) (0.5f + 0.5f * sin(alertPulseTimer)) * (engine.alertLevel / 3.0f) else 0f
        val focusMode = if (player.isFocusing) 1.0f else 0.0f

        worldShader.setEnvironment(
            lightDir = lightDir,
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

        // 5. Render Environmental Geometry (Batched by Material or Chunk Fallback)
        if (geometryBatcher.batchMode != BatchMode.CHUNK_UNBATCHED && geometryBatcher.isBuilt) {
            drawCalls += geometryBatcher.renderBatchedWorld(
                vaoManager = vaoManager,
                worldShader = worldShader,
                modelMatrix = modelMatrix,
                projMatrix = projMatrix,
                viewMatrix = viewMatrix
            )
        } else {
            val maxChunkRenderDistance = if (currentTier == ThermalTier.SEVERE || currentTier == ThermalTier.CRITICAL) 32f else 54f

            meshBuilder?.let { builder ->
                for (chunk in builder.chunks) {
                    val vbo = chunk.vbo ?: continue
                    val vertexCount = vbo.getVertexCount()
                    if (vertexCount == 0) continue

                    val chunkCenterX = (chunk.minVx + chunk.maxVx) * 0.5f * VoxelTypes.VOX_SIZE
                    val chunkCenterZ = (chunk.minVz + chunk.maxVz) * 0.5f * VoxelTypes.VOX_SIZE
                    val distToPlayer = player.pos.distance(Vec3(chunkCenterX, 0f, chunkCenterZ))

                    // Frustum / distance culling
                    if (distToPlayer > maxChunkRenderDistance) {
                        occlusionCuller.recordChunkTested(culled = true, vertexCount)
                        continue
                    }

                    // Aggressive Wall Occlusion Culling: skip rendering chunks hidden behind intervening walls
                    if (occlusionCuller.isChunkOccluded(eyeX, eyeY, eyeZ, chunk, player.pos.x, player.pos.z, mode = cullingMode)) {
                        occlusionCuller.recordChunkTested(culled = true, vertexCount)
                        continue
                    }
                    occlusionCuller.recordChunkTested(culled = false, vertexCount)

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
        }

        // 6. Render Characters (Player and Guards) with Wall Occlusion Culling
        characterVbo?.let { vbo ->
            // Render Player (always rendered)
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

            // Render Guards (skip guards hidden behind walls)
            for (g in engine.guards) {
                if (g.isDown) continue

                // Aggressive Wall Occlusion: skip drawing guards blocked by solid walls
                if (occlusionCuller.isEntityOccluded(eyeX, eyeY, eyeZ, player.pos.x, player.pos.z, g.pos.x, 0f, g.pos.z, mode = cullingMode)) {
                    occlusionCuller.recordGuardTested(culled = true)
                    continue
                }
                occlusionCuller.recordGuardTested(culled = false)

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

        // 7. Render Guard Vision Cones (Alpha Blending) with VAO (skip occluded guards)
        if (budget.visionConesPassEnabled) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            coneVbo?.let { cVbo ->
                for (g in engine.guards) {
                    if (g.isDown) continue
                    val isDrone = g.def.isFlying
                    val show = isDrone || player.isFocusing || g.isAlerted || g.suspicion > 10f
                    if (!show) continue

                    // Aggressive Occlusion: skip vision cones for guards hidden behind walls
                    if (occlusionCuller.isEntityOccluded(eyeX, eyeY, eyeZ, player.pos.x, player.pos.z, g.pos.x, 0f, g.pos.z, mode = cullingMode)) {
                        occlusionCuller.recordVisionConeTested(culled = true)
                        continue
                    }
                    occlusionCuller.recordVisionConeTested(culled = false)

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

            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // 8. Render Tracers and Sniper Lasers (governed by FrameBudgetManager and OcclusionCuller)
        if (budget.dynamicTracersPassEnabled) {
            lineVbo?.let { lVbo ->
                lVbo.clear()
                for (t in engine.tracers) {
                    val c = t.color
                    lVbo.addVertex(t.start.x, t.start.y, t.start.z, 0f, 1f, 0f, c[0], c[1], c[2], c[3])
                    lVbo.addVertex(t.end.x, t.end.y, t.end.z, 0f, 1f, 0f, c[0], c[1], c[2], c[3])
                }

                // Sniper aiming lasers (skip if sniper is hidden behind walls)
                for (g in engine.guards) {
                    if (!g.isDown && g.def.isSniper && g.state == GuardState.COMBAT) {
                        if (occlusionCuller.isEntityOccluded(eyeX, eyeY, eyeZ, player.pos.x, player.pos.z, g.pos.x, 0f, g.pos.z, mode = cullingMode)) {
                            continue
                        }
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
        }

        // 9. Fullscreen Post-Processing Shader (Non-essential pass: disabled during high heat to save Mali fill-rate)
        if (budget.postProcessPassEnabled) {
            postProcessShader.render(
                width = screenWidth,
                height = screenHeight,
                damageFlash = player.hurtFlash,
                focusMode = focusMode,
                timeSeconds = alertPulseTimer
            )
            drawCalls++
        }

        // 10. Thermal Frame Rate Governor, Occlusion Telemetry, and Budget Measurement
        val cullingTelemetry = occlusionCuller.endFrame()
        val batchingTelemetry = geometryBatcher.getTelemetry(unbatchedChunkCount = meshBuilder?.chunks?.size ?: 64)
        frameBudgetManager.endFrame(drawCalls)
        engine.thermalGovernor.onFrameRendered(drawCalls, cullingTelemetry, batchingTelemetry)
    }

    fun release() {
        vaoManager.releaseAll()
        meshBuilder?.releaseAll(vaoManager)
        geometryBatcher.releaseAll(vaoManager)
        vboPool.releaseAll()
        characterVbo?.release()
        coneVbo?.release()
        lineVbo?.release()
        shadowMapManager.release()
        worldShader.release()
        postProcessShader.release()
        shaderCache.releaseAll()
    }
}
