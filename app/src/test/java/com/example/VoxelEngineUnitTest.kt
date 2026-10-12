package com.example

import com.example.game.voxel.ComplexGen
import com.example.game.voxel.VoxelGrid
import com.example.game.voxel.VoxelTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoxelEngineUnitTest {

    @Test
    fun testProceduralComplexGeneration() {
        val levels = ComplexGen.generateComplex(seed = 12345L, levelCount = 3)
        assertEquals(3, levels.size)

        for (level in levels) {
            assertTrue(level.rooms.isNotEmpty())
            assertTrue(level.doors.isNotEmpty())
            assertTrue(level.guards.isNotEmpty())
            assertTrue(level.spawnX > 0f)
            assertTrue(level.spawnZ > 0f)
        }

        // Last level should have the vault core
        assertTrue(levels[2].vaultX != null)
        assertTrue(levels[2].vaultZ != null)
    }

    @Test
    fun testVoxelCollisionAndRaycasting() {
        val grid = VoxelGrid(width = 32, height = 8, depth = 32)
        grid.clear()

        // Place floor in room area
        for (z in 5..15) {
            for (x in 5..15) {
                grid.set(x, 0, z, VoxelTypes.V_FLOOR)
            }
        }

        // Place a solid wall at x = 10, z = 10
        for (h in 1..4) {
            grid.set(10, h, 10, VoxelTypes.V_WALL)
        }

        // Point inside wall should be blocked
        val worldX = 10.2f * VoxelTypes.VOX_SIZE
        val worldZ = 10.2f * VoxelTypes.VOX_SIZE
        assertTrue(grid.isBlocked(worldX, worldZ, radius = 0.2f))

        // Open floor should not be blocked
        val openX = 7.0f * VoxelTypes.VOX_SIZE
        val openZ = 7.0f * VoxelTypes.VOX_SIZE
        assertFalse(grid.isBlocked(openX, openZ, radius = 0.2f))

        // Line of sight across the wall should be false
        val p1x = 6.0f * VoxelTypes.VOX_SIZE
        val p1z = 10.0f * VoxelTypes.VOX_SIZE
        val p2x = 14.0f * VoxelTypes.VOX_SIZE
        val p2z = 10.0f * VoxelTypes.VOX_SIZE
        assertFalse(grid.hasLineOfSight(p1x, p1z, p2x, p2z, lowCoverCheck = false))

        // Line of sight in open space should be true
        val p3x = 6.0f * VoxelTypes.VOX_SIZE
        val p3z = 6.0f * VoxelTypes.VOX_SIZE
        val p4x = 6.0f * VoxelTypes.VOX_SIZE
        val p4z = 14.0f * VoxelTypes.VOX_SIZE
        assertTrue(grid.hasLineOfSight(p3x, p3z, p4x, p4z, lowCoverCheck = false))
    }
}
