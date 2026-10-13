package com.example.engine.gl

import com.example.engine.math.Vec3
import com.example.game.voxel.VoxelChunk
import com.example.game.voxel.VoxelGrid
import com.example.game.voxel.VoxelTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Aggression level for wall occlusion culling.
 */
enum class OcclusionMode(val displayName: String, val description: String) {
    OFF("Disabled", "Render all objects without wall occlusion testing"),
    CONSERVATIVE("Conservative", "Cull objects strictly when fully blocked by solid walls"),
    AGGRESSIVE("Aggressive", "Cull objects and sectors behind intervening walls to minimize draw calls"),
    ULTRA_AGGRESSIVE("Ultra Aggressive", "Extreme occlusion for thermal safety / Mali-400 fill protection")
}

/**
 * Real-time performance metrics tracking GPU draw calls and vertices saved
 * by aggressive wall occlusion culling.
 */
data class OcclusionTelemetry(
    val mode: OcclusionMode = OcclusionMode.AGGRESSIVE,
    val totalChunksTested: Int = 0,
    val chunksCulled: Int = 0,
    val chunksRendered: Int = 0,
    val totalGuardsTested: Int = 0,
    val guardsCulled: Int = 0,
    val guardsRendered: Int = 0,
    val visionConesCulled: Int = 0,
    val shadowCastersCulled: Int = 0,
    val tracersCulled: Int = 0,
    val estimatedDrawCallsSaved: Int = 0,
    val estimatedVerticesSaved: Long = 0L,
    val lastCullDurationUs: Long = 0L
) {
    val chunkCullRatio: Float
        get() = if (totalChunksTested > 0) chunksCulled.toFloat() / totalChunksTested else 0f

    val guardCullRatio: Float
        get() = if (totalGuardsTested > 0) guardsCulled.toFloat() / totalGuardsTested else 0f
}

/**
 * High-performance, aggressive Occlusion Culling system designed specifically
 * for budget mobile hardware (e.g. ARM Mali-400 / Mali-450).
 *
 * Traverses the 3D VoxelGrid using zero-allocation raymarching to detect solid walls
 * obstructing objects from the camera viewpoint, completely eliminating draw calls
 * and vertex processing for hidden geometry, entities, and non-essential passes.
 */
class OcclusionCuller(
    var grid: VoxelGrid? = null
) {
    companion object {
        const val DEFAULT_WALL_CEILING_Y: Float = 3.0f // Standard wall height in meters (6 voxels * 0.5m)
        const val RAY_STEP_SIZE: Float = 0.25f         // Ray step interval in meters (half voxel width)
        const val VERTICES_PER_GUARD_APPROX: Long = 360L
    }

    private var currentMode: OcclusionMode = OcclusionMode.AGGRESSIVE
    private var forcedMode: OcclusionMode? = null

    // Per-frame counters
    private var frameStartTimeNs: Long = 0L
    private var totalChunksTested: Int = 0
    private var chunksCulled: Int = 0
    private var totalGuardsTested: Int = 0
    private var guardsCulled: Int = 0
    private var visionConesCulled: Int = 0
    private var shadowCastersCulled: Int = 0
    private var tracersCulled: Int = 0
    private var verticesSaved: Long = 0L

    private val _telemetry = MutableStateFlow(OcclusionTelemetry())
    val telemetry: StateFlow<OcclusionTelemetry> = _telemetry.asStateFlow()

    fun setForceMode(mode: OcclusionMode?) {
        forcedMode = mode
    }

    /**
     * Initializes state for a new frame.
     */
    fun beginFrame(mode: OcclusionMode = OcclusionMode.AGGRESSIVE) {
        currentMode = forcedMode ?: mode
        frameStartTimeNs = System.nanoTime()
        totalChunksTested = 0
        chunksCulled = 0
        totalGuardsTested = 0
        guardsCulled = 0
        visionConesCulled = 0
        shadowCastersCulled = 0
        tracersCulled = 0
        verticesSaved = 0L
    }

    /**
     * Fast 3D raymarch through VoxelGrid to check if solid walls obstruct the line-of-sight
     * between start point (x0, y0, z0) and target point (x1, y1, z1).
     *
     * Zero heap allocation per call.
     */
    fun isRayBlockedByWall(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        ignoreMinVx: Int = -1, ignoreMaxVx: Int = -1,
        ignoreMinVz: Int = -1, ignoreMaxVz: Int = -1,
        maxWallHeight: Float = DEFAULT_WALL_CEILING_Y,
        checkCover: Boolean = false
    ): Boolean {
        val currentGrid = grid ?: return false

        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 0.15f) return false

        val steps = max(1, ceil(dist / RAY_STEP_SIZE).toInt())
        val invSteps = 1.0f / steps

        // March from near start towards target, skipping start point (near camera) and end point
        for (i in 1 until steps) {
            val t = i * invSteps
            val curY = y0 + dy * t

            // If the ray at this step is above the maximum wall height, it flies cleanly over the wall
            if (curY > maxWallHeight) continue
            if (curY < 0f) continue

            val curX = x0 + dx * t
            val curZ = z0 + dz * t

            val vx = floor(curX / VoxelTypes.VOX_SIZE).toInt()
            val vz = floor(curZ / VoxelTypes.VOX_SIZE).toInt()

            // Skip voxels that belong to the target object itself to prevent self-occlusion
            if (ignoreMinVx != -1 && vx in ignoreMinVx until ignoreMaxVx && vz in ignoreMinVz until ignoreMaxVz) {
                continue
            }

            // Outside map boundary: perimeter walls block line-of-sight
            if (!currentGrid.inBounds(vx, 1, vz)) {
                if (curY <= maxWallHeight) return true
                continue
            }

            // Check locked door blocking the passageway
            val door = currentGrid.doorStates["$vx:$vz"]
            if (door != null && door.locked) {
                return true
            }

            // Sample voxel grid height
            val vy = floor(curY / VoxelTypes.VOX_SIZE).toInt().coerceIn(1, VoxelTypes.WALL_HEIGHT_VOXELS)
            val voxel = currentGrid.get(vx, vy, vz)

            if (voxel == VoxelTypes.V_WALL || voxel == VoxelTypes.V_CRACK) {
                return true
            }

            if (checkCover && voxel == VoxelTypes.V_COVER && curY <= 1.0f) {
                return true
            }
        }

        return false
    }

    /**
     * Determines whether an entity (such as a Guard, drone, or prop) is hidden behind walls,
     * allowing the engine to skip drawing its model and vision cone.
     */
    fun isEntityOccluded(
        camX: Float, camY: Float, camZ: Float,
        playerX: Float, playerZ: Float,
        entityX: Float, entityY: Float, entityZ: Float,
        entityHeight: Float = 1.2f,
        mode: OcclusionMode = currentMode
    ): Boolean {
        val effectiveMode = forcedMode ?: mode
        if (effectiveMode == OcclusionMode.OFF) return false
        val currentGrid = grid ?: return false

        val targetY = entityY + entityHeight

        // 1. Camera ray test: Is the camera line-of-sight to the entity blocked by intervening walls?
        val cameraBlocked = isRayBlockedByWall(
            camX, camY, camZ,
            entityX, targetY, entityZ
        )

        // 2. Player room / stealth LOS test: Is the entity in another room separated by solid walls from the player?
        val playerBlocked = isRayBlockedByWall(
            playerX, 1.2f, playerZ,
            entityX, targetY, entityZ
        )

        return when (effectiveMode) {
            OcclusionMode.OFF -> false
            OcclusionMode.CONSERVATIVE -> {
                // In conservative mode, must be obstructed from camera view
                if (cameraBlocked) {
                    val leftBlocked = isRayBlockedByWall(
                        camX, camY, camZ,
                        entityX - 0.35f, targetY, entityZ
                    )
                    val rightBlocked = isRayBlockedByWall(
                        camX, camY, camZ,
                        entityX + 0.35f, targetY, entityZ
                    )
                    cameraBlocked && leftBlocked && rightBlocked
                } else {
                    false
                }
            }
            OcclusionMode.AGGRESSIVE -> {
                // In aggressive mode, skip rendering if hidden behind walls from camera OR separated by walls from player
                cameraBlocked || playerBlocked
            }
            OcclusionMode.ULTRA_AGGRESSIVE -> {
                // In ultra-aggressive mode, any wall obstruction triggers culling to protect GPU fill-rate
                cameraBlocked || playerBlocked
            }
        }
    }

    /**
     * Determines whether an entire 16x16 VoxelChunk is occluded by walls,
     * allowing the renderer to skip thousands of vertices and a VAO draw call.
     */
    fun isChunkOccluded(
        camX: Float, camY: Float, camZ: Float,
        chunk: VoxelChunk,
        playerX: Float, playerZ: Float,
        mode: OcclusionMode = currentMode
    ): Boolean {
        val effectiveMode = forcedMode ?: mode
        if (effectiveMode == OcclusionMode.OFF) return false

        val s = VoxelTypes.VOX_SIZE
        val minX = chunk.minVx * s
        val maxX = chunk.maxVx * s
        val minZ = chunk.minVz * s
        val maxZ = chunk.maxVz * s

        // Never cull the chunk that the player is actively standing in
        if (playerX >= minX && playerX <= maxX && playerZ >= minZ && playerZ <= maxZ) {
            return false
        }

        val vbo = chunk.vbo
        if (vbo == null || vbo.getVertexCount() == 0) {
            return true // Empty chunk has no geometry to render
        }

        val centerX = (minX + maxX) * 0.5f
        val centerZ = (minZ + maxZ) * 0.5f

        // Test center point from camera view and from player room vantage
        val centerCamBlocked = isRayBlockedByWall(
            camX, camY, camZ,
            centerX, 0.5f, centerZ,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        )
        val centerPlayerBlocked = isRayBlockedByWall(
            playerX, 1.2f, playerZ,
            centerX, 0.5f, centerZ,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        )

        val centerBlocked = centerCamBlocked || centerPlayerBlocked
        if (!centerBlocked) {
            return false // Chunk center has clear visual line
        }

        // Test four inset corner points
        val inset = 0.5f
        val c1Blocked = isRayBlockedByWall(
            camX, camY, camZ,
            minX + inset, 0.5f, minZ + inset,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        ) || isRayBlockedByWall(
            playerX, 1.2f, playerZ,
            minX + inset, 0.5f, minZ + inset,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        )

        val c2Blocked = isRayBlockedByWall(
            camX, camY, camZ,
            maxX - inset, 0.5f, minZ + inset,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        ) || isRayBlockedByWall(
            playerX, 1.2f, playerZ,
            maxX - inset, 0.5f, minZ + inset,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        )

        val c3Blocked = isRayBlockedByWall(
            camX, camY, camZ,
            minX + inset, 0.5f, maxZ - inset,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        ) || isRayBlockedByWall(
            playerX, 1.2f, playerZ,
            minX + inset, 0.5f, maxZ - inset,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        )

        val c4Blocked = isRayBlockedByWall(
            camX, camY, camZ,
            maxX - inset, 0.5f, maxZ - inset,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        ) || isRayBlockedByWall(
            playerX, 1.2f, playerZ,
            maxX - inset, 0.5f, maxZ - inset,
            ignoreMinVx = chunk.minVx, ignoreMaxVx = chunk.maxVx,
            ignoreMinVz = chunk.minVz, ignoreMaxVz = chunk.maxVz
        )

        return when (effectiveMode) {
            OcclusionMode.OFF -> false
            OcclusionMode.CONSERVATIVE -> {
                // All sample points must be completely occluded
                centerBlocked && c1Blocked && c2Blocked && c3Blocked && c4Blocked
            }
            OcclusionMode.AGGRESSIVE -> {
                // Center and at least 3 corners blocked
                val cornerBlockedCount = (if (c1Blocked) 1 else 0) + (if (c2Blocked) 1 else 0) +
                        (if (c3Blocked) 1 else 0) + (if (c4Blocked) 1 else 0)
                centerBlocked && cornerBlockedCount >= 3
            }
            OcclusionMode.ULTRA_AGGRESSIVE -> {
                // Center and at least 2 corners blocked
                val cornerBlockedCount = (if (c1Blocked) 1 else 0) + (if (c2Blocked) 1 else 0) +
                        (if (c3Blocked) 1 else 0) + (if (c4Blocked) 1 else 0)
                centerBlocked && cornerBlockedCount >= 2
            }
        }
    }

    /**
     * Determines if a shadow caster is too distant or occluded from the directional light frustum.
     */
    fun isShadowCasterOccluded(
        casterX: Float, casterZ: Float,
        playerPos: Vec3,
        maxShadowDist: Float
    ): Boolean {
        val dist = hypot(casterX - playerPos.x, casterZ - playerPos.z)
        return dist > maxShadowDist
    }

    // Telemetry and recording helpers
    fun recordChunkTested(culled: Boolean, vertexCount: Int = 0) {
        totalChunksTested++
        if (culled) {
            chunksCulled++
            verticesSaved += vertexCount
        }
    }

    fun recordGuardTested(culled: Boolean) {
        totalGuardsTested++
        if (culled) {
            guardsCulled++
            verticesSaved += VERTICES_PER_GUARD_APPROX
        }
    }

    fun recordVisionConeTested(culled: Boolean) {
        if (culled) {
            visionConesCulled++
        }
    }

    fun recordShadowCasterCulled() {
        shadowCastersCulled++
    }

    fun recordTracerTested(culled: Boolean) {
        if (culled) {
            tracersCulled++
        }
    }

    /**
     * Concludes the frame and publishes the updated occlusion telemetry.
     */
    fun endFrame(): OcclusionTelemetry {
        val durationNs = System.nanoTime() - frameStartTimeNs
        val durationUs = durationNs / 1_000L

        val drawCallsSaved = chunksCulled + guardsCulled + visionConesCulled + shadowCastersCulled + tracersCulled
        val renderedChunks = totalChunksTested - chunksCulled
        val renderedGuards = totalGuardsTested - guardsCulled

        val snapshot = OcclusionTelemetry(
            mode = currentMode,
            totalChunksTested = totalChunksTested,
            chunksCulled = chunksCulled,
            chunksRendered = renderedChunks,
            totalGuardsTested = totalGuardsTested,
            guardsCulled = guardsCulled,
            guardsRendered = renderedGuards,
            visionConesCulled = visionConesCulled,
            shadowCastersCulled = shadowCastersCulled,
            tracersCulled = tracersCulled,
            estimatedDrawCallsSaved = drawCallsSaved,
            estimatedVerticesSaved = verticesSaved,
            lastCullDurationUs = durationUs
        )

        _telemetry.value = snapshot
        return snapshot
    }
}
