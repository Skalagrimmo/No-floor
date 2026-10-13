package com.example

import com.example.engine.gl.MeshVbo
import com.example.engine.gl.OcclusionCuller
import com.example.engine.gl.OcclusionMode
import com.example.engine.math.Vec3
import com.example.game.voxel.DoorDef
import com.example.game.voxel.VoxelChunk
import com.example.game.voxel.VoxelGrid
import com.example.game.voxel.VoxelTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OcclusionCullerUnitTest {

    private lateinit var grid: VoxelGrid
    private lateinit var culler: OcclusionCuller

    @Before
    fun setUp() {
        grid = VoxelGrid(width = 64, height = 16, depth = 64)
        culler = OcclusionCuller(grid)
    }

    @Test
    fun testRayBlockedByDirectSolidWall() {
        // Place a solid wall at grid column (10, 10) up to standard wall height (6 voxels = 3.0m)
        for (h in 1..VoxelTypes.WALL_HEIGHT_VOXELS) {
            grid.set(10, h, 10, VoxelTypes.V_WALL)
        }

        // World coordinates: grid * 0.5m -> (10, 10) is at world X=5.0m, Z=5.0m
        // Ray passing horizontally through the wall at altitude 1.2m
        val blocked = culler.isRayBlockedByWall(
            x0 = 0.0f, y0 = 1.2f, z0 = 5.25f,
            x1 = 10.0f, y1 = 1.2f, z1 = 5.25f
        )
        assertTrue("Ray intersecting wall below ceiling height must be blocked", blocked)

        // Ray passing ABOVE the wall height (> 3.0m) should clear cleanly
        val clearAbove = culler.isRayBlockedByWall(
            x0 = 0.0f, y0 = 4.0f, z0 = 5.25f,
            x1 = 10.0f, y1 = 4.0f, z1 = 5.25f
        )
        assertFalse("Ray flying above wall height must not be blocked", clearAbove)
    }

    @Test
    fun testRayClearAcrossOpenFloor() {
        // Floor at height 0 only, no walls
        for (x in 0..20) {
            for (z in 0..20) {
                grid.set(x, 0, z, VoxelTypes.V_FLOOR)
            }
        }

        val blocked = culler.isRayBlockedByWall(
            x0 = 1.0f, y0 = 1.0f, z0 = 1.0f,
            x1 = 8.0f, y1 = 1.0f, z1 = 8.0f
        )
        assertFalse("Ray across open floor without walls must be unblocked", blocked)
    }

    @Test
    fun testLockedDoorBlocksRayAndUnlocks() {
        val gx = 12
        val gz = 12
        val door = DoorDef("door_sec_1", gx * 0.5f, gz * 0.5f, axis = 'x', locked = true)
        grid.doorStates["$gx:$gz"] = door

        // Ray through locked door
        val blockedLocked = culler.isRayBlockedByWall(
            x0 = 4.0f, y0 = 1.2f, z0 = 6.25f,
            x1 = 8.0f, y1 = 1.2f, z1 = 6.25f
        )
        assertTrue("Locked security door must occlude line-of-sight", blockedLocked)

        // Unlock the door
        door.locked = false
        val blockedUnlocked = culler.isRayBlockedByWall(
            x0 = 4.0f, y0 = 1.2f, z0 = 6.25f,
            x1 = 8.0f, y1 = 1.2f, z1 = 6.25f
        )
        assertFalse("Unlocked passage door must allow line-of-sight", blockedUnlocked)
    }

    @Test
    fun testEntityOcclusionBehindWall() {
        // Place solid wall barrier at world X=10m, Z=10m to 14m
        for (vz in 20..28) {
            for (h in 1..VoxelTypes.WALL_HEIGHT_VOXELS) {
                grid.set(20, h, vz, VoxelTypes.V_WALL)
            }
        }

        val camX = 5.0f
        val camY = 36.0f
        val camZ = 35.0f

        val playerX = 6.0f
        val playerZ = 12.0f

        // Guard standing directly behind the wall barrier (world X = 12m, Z = 12m)
        val hiddenGuardX = 12.0f
        val hiddenGuardZ = 12.0f

        val isOccluded = culler.isEntityOccluded(
            camX = camX, camY = camY, camZ = camZ,
            playerX = playerX, playerZ = playerZ,
            entityX = hiddenGuardX, entityY = 0f, entityZ = hiddenGuardZ,
            mode = OcclusionMode.AGGRESSIVE
        )
        assertTrue("Guard behind 3m wall barrier must be culled", isOccluded)

        // Guard in front of wall in direct camera line of sight (world X = 7m, Z = 12m)
        val visibleGuardX = 7.0f
        val visibleGuardZ = 12.0f

        val isVisible = culler.isEntityOccluded(
            camX = camX, camY = camY, camZ = camZ,
            playerX = playerX, playerZ = playerZ,
            entityX = visibleGuardX, entityY = 0f, entityZ = visibleGuardZ,
            mode = OcclusionMode.AGGRESSIVE
        )
        assertFalse("Guard with clear line of sight to camera must not be culled", isVisible)
    }

    @Test
    fun testChunkOcclusionExcludesPlayerCurrentChunk() {
        val playerChunk = VoxelChunk(
            chunkX = 1,
            chunkZ = 1,
            minVx = 16,
            maxVx = 32,
            minVz = 16,
            maxVz = 32
        )
        // Player is at world X=10m, Z=10m (grid vx = 20, vz = 20 inside chunk 1,1)
        val playerX = 10.0f
        val playerZ = 10.0f

        val culled = culler.isChunkOccluded(
            camX = 10.0f, camY = 36.0f, camZ = 38.0f,
            chunk = playerChunk,
            playerX = playerX, playerZ = playerZ,
            mode = OcclusionMode.AGGRESSIVE
        )
        assertFalse("Player's active chunk must never be culled", culled)
    }

    @Test
    fun testChunkOcclusionBehindWall() {
        val distantChunk = VoxelChunk(
            chunkX = 3,
            chunkZ = 3,
            minVx = 48,
            maxVx = 64,
            minVz = 48,
            maxVz = 64
        )
        val fakeVbo = MeshVbo(10)
        fakeVbo.addQuad(24f, 0f, 24f, 24f, 0f, 32f, 32f, 0f, 32f, 32f, 0f, 24f, 0f, 1f, 0f, 1f, 1f, 1f, 1f)
        distantChunk.vbo = fakeVbo

        // Solid dividing wall between player and distant chunk at grid vz=35
        for (vx in 0 until 64) {
            for (h in 1..VoxelTypes.WALL_HEIGHT_VOXELS) {
                grid.set(vx, h, 35, VoxelTypes.V_WALL)
            }
        }

        val playerX = 5.0f
        val playerZ = 5.0f
        val culled = culler.isChunkOccluded(
            camX = 5.0f, camY = 36.0f, camZ = 20.0f,
            chunk = distantChunk,
            playerX = playerX, playerZ = playerZ,
            mode = OcclusionMode.AGGRESSIVE
        )
        assertTrue("Chunk behind solid wall barrier must be culled", culled)
    }

    @Test
    fun testOcclusionModeDisabledRendersAll() {
        // Place wall
        for (h in 1..VoxelTypes.WALL_HEIGHT_VOXELS) {
            grid.set(10, h, 10, VoxelTypes.V_WALL)
        }

        val isOccludedOff = culler.isEntityOccluded(
            camX = 0f, camY = 36f, camZ = 20f,
            playerX = 0f, playerZ = 5f,
            entityX = 15f, entityY = 0f, entityZ = 5f,
            mode = OcclusionMode.OFF
        )
        assertFalse("When mode is OFF, entities must never be culled", isOccludedOff)
    }

    @Test
    fun testTelemetryRecordingAndDrawCallsSaved() {
        culler.beginFrame(OcclusionMode.AGGRESSIVE)

        // Record chunks: 8 tested, 5 culled (e.g. 500 verts each)
        for (i in 0 until 5) {
            culler.recordChunkTested(culled = true, vertexCount = 500)
        }
        for (i in 0 until 3) {
            culler.recordChunkTested(culled = false, vertexCount = 500)
        }

        // Record guards: 6 tested, 4 culled
        for (i in 0 until 4) {
            culler.recordGuardTested(culled = true)
        }
        for (i in 0 until 2) {
            culler.recordGuardTested(culled = false)
        }

        // Record 4 vision cones culled
        for (i in 0 until 4) {
            culler.recordVisionConeTested(culled = true)
        }

        // Record 2 shadow casters culled
        culler.recordShadowCasterCulled()
        culler.recordShadowCasterCulled()

        val snapshot = culler.endFrame()

        assertEquals(8, snapshot.totalChunksTested)
        assertEquals(5, snapshot.chunksCulled)
        assertEquals(3, snapshot.chunksRendered)

        assertEquals(6, snapshot.totalGuardsTested)
        assertEquals(4, snapshot.guardsCulled)
        assertEquals(2, snapshot.guardsRendered)

        assertEquals(4, snapshot.visionConesCulled)
        assertEquals(2, snapshot.shadowCastersCulled)

        // Estimated draw calls saved = 5 (chunks) + 4 (guards) + 4 (vision cones) + 2 (shadow casters) = 15
        assertEquals(15, snapshot.estimatedDrawCallsSaved)

        // Estimated vertices saved = 5 * 500 (chunks) + 4 * 360 (guards) = 2500 + 1440 = 3940
        assertEquals(3940L, snapshot.estimatedVerticesSaved)

        assertTrue("Chunk cull ratio must reflect 5/8", snapshot.chunkCullRatio > 0.6f)
        assertTrue("Guard cull ratio must reflect 4/6", snapshot.guardCullRatio > 0.6f)
    }
}
