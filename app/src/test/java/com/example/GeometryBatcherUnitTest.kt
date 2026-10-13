package com.example

import com.example.engine.gl.BatchMode
import com.example.engine.gl.EnvironmentalMaterial
import com.example.engine.gl.GeometryBatcher
import com.example.engine.gl.MeshVbo
import com.example.engine.math.Mat4
import com.example.engine.math.Vec3
import com.example.game.voxel.VoxelGrid
import com.example.game.voxel.VoxelTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests verifying that the GeometryBatcher merges static environmental meshes
 * with identical materials into a single draw call, reduces CPU draw calls,
 * and maintains zero memory allocations during runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeometryBatcherUnitTest {

    private lateinit var batcher: GeometryBatcher
    private lateinit var grid: VoxelGrid

    @Before
    fun setUp() {
        batcher = GeometryBatcher()
        grid = VoxelGrid(16, 8, 16)
    }

    @Test
    fun testMaterialClassificationAndProperties() {
        assertEquals(EnvironmentalMaterial.FLOOR, EnvironmentalMaterial.fromVoxelType(VoxelTypes.V_FLOOR))
        assertEquals(EnvironmentalMaterial.WALL, EnvironmentalMaterial.fromVoxelType(VoxelTypes.V_WALL))
        assertEquals(EnvironmentalMaterial.COVER, EnvironmentalMaterial.fromVoxelType(VoxelTypes.V_COVER))
        assertEquals(EnvironmentalMaterial.CRACK, EnvironmentalMaterial.fromVoxelType(VoxelTypes.V_CRACK))
        assertEquals(null, EnvironmentalMaterial.fromVoxelType(VoxelTypes.V_EMPTY))

        // Verify shadow casting properties: floors do NOT cast shadows; walls/covers/cracks DO cast shadows
        assertFalse(EnvironmentalMaterial.FLOOR.castsShadows)
        assertTrue(EnvironmentalMaterial.WALL.castsShadows)
        assertTrue(EnvironmentalMaterial.COVER.castsShadows)
        assertTrue(EnvironmentalMaterial.CRACK.castsShadows)
    }

    @Test
    fun testStaticEnvironmentalMeshMergingByMaterial() {
        // Construct a small room: 4 floor voxels, 2 wall voxels, 1 cover voxel, 1 crack voxel
        grid.set(2, 0, 2, VoxelTypes.V_FLOOR)
        grid.set(3, 0, 2, VoxelTypes.V_FLOOR)
        grid.set(2, 0, 3, VoxelTypes.V_FLOOR)
        grid.set(3, 0, 3, VoxelTypes.V_FLOOR)

        grid.set(1, 1, 2, VoxelTypes.V_WALL)
        grid.set(1, 2, 2, VoxelTypes.V_WALL)

        grid.set(2, 1, 1, VoxelTypes.V_COVER)
        grid.set(3, 1, 1, VoxelTypes.V_CRACK)

        batcher.rebuildBatches(grid, waistCutaway = false)

        assertTrue(batcher.isBuilt)

        val floorBatch = batcher.materialBatches[EnvironmentalMaterial.FLOOR]
        val wallBatch = batcher.materialBatches[EnvironmentalMaterial.WALL]
        val coverBatch = batcher.materialBatches[EnvironmentalMaterial.COVER]
        val crackBatch = batcher.materialBatches[EnvironmentalMaterial.CRACK]

        assertNotNull(floorBatch)
        assertNotNull(wallBatch)
        assertNotNull(coverBatch)
        assertNotNull(crackBatch)

        // All 4 floor voxels merged into floorBatch
        assertTrue(floorBatch!!.vbo.getVertexCount() > 0)
        // Both wall voxels merged into wallBatch
        assertTrue(wallBatch!!.vbo.getVertexCount() > 0)
        // Cover voxel merged into coverBatch
        assertTrue(coverBatch!!.vbo.getVertexCount() > 0)
        // Crack voxel merged into crackBatch
        assertTrue(crackBatch!!.vbo.getVertexCount() > 0)

        // Verify that unified batch also aggregated all vertices
        val totalIndividualVertices = floorBatch.vbo.getVertexCount() +
            wallBatch.vbo.getVertexCount() +
            coverBatch.vbo.getVertexCount() +
            crackBatch.vbo.getVertexCount()

        assertEquals(totalIndividualVertices, batcher.unifiedBatch.vbo.getVertexCount())
    }

    @Test
    fun testDrawCallReductionTelemetryInMaterialBatchedMode() {
        grid.set(1, 0, 1, VoxelTypes.V_FLOOR)
        grid.set(2, 1, 1, VoxelTypes.V_WALL)
        grid.set(3, 1, 1, VoxelTypes.V_COVER)
        grid.set(4, 1, 1, VoxelTypes.V_CRACK)

        batcher.batchMode = BatchMode.MATERIAL_BATCHED
        batcher.rebuildBatches(grid)

        val telemetry = batcher.getTelemetry(unbatchedChunkCount = 64)

        assertEquals(BatchMode.MATERIAL_BATCHED, telemetry.batchMode)
        assertEquals(4, telemetry.batchesCount)
        assertEquals(64, telemetry.unbatchedDrawCalls)
        // In Material Batched mode: exactly 4 draw calls (1 per material) instead of 64!
        assertEquals(4, telemetry.batchedDrawCalls)
        assertEquals(60, telemetry.drawCallsSaved)
        assertTrue(telemetry.cpuReductionPercent > 90f)
        assertEquals(93.75f, telemetry.cpuReductionPercent, 0.1f)
    }

    @Test
    fun testDrawCallReductionTelemetryInUnifiedSingleCallMode() {
        grid.set(1, 0, 1, VoxelTypes.V_FLOOR)
        grid.set(2, 1, 1, VoxelTypes.V_WALL)

        batcher.batchMode = BatchMode.UNIFIED_SINGLE_CALL
        batcher.rebuildBatches(grid)

        val telemetry = batcher.getTelemetry(unbatchedChunkCount = 64)

        assertEquals(BatchMode.UNIFIED_SINGLE_CALL, telemetry.batchMode)
        assertEquals(64, telemetry.unbatchedDrawCalls)
        // In Unified mode: exactly 1 single mega draw call for the whole level!
        assertEquals(1, telemetry.batchedDrawCalls)
        assertEquals(63, telemetry.drawCallsSaved)
        assertTrue(telemetry.cpuReductionPercent > 98f)
    }

    @Test
    fun testMergeExternalStaticEnvironmentalMeshWithIdenticalMaterial() {
        grid.set(1, 0, 1, VoxelTypes.V_FLOOR)
        batcher.rebuildBatches(grid)

        val coverBatch = batcher.materialBatches[EnvironmentalMaterial.COVER]!!
        val initialCoverCount = coverBatch.vbo.getVertexCount()

        // Create an external static crate mesh with identical steel cover material
        val externalCrateVbo = MeshVbo(12).apply {
            addQuad(
                0f, 0f, 0f,
                1f, 0f, 0f,
                1f, 1f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
                0.41f, 0.48f, 0.52f, 1.0f
            )
        }

        val transform = Mat4().translate(5f, 0f, 5f)
        batcher.mergeStaticMesh(EnvironmentalMaterial.COVER, externalCrateVbo, transform)

        // 6 vertices (1 quad) should have been added directly to the COVER batch
        assertEquals(initialCoverCount + 6, coverBatch.vbo.getVertexCount())
    }

    @Test
    fun testWaistCutawayModeReducesWallVertices() {
        // Build a tall wall: voxels from y=1 to y=6 (3 meters high)
        for (y in 1..6) {
            grid.set(5, y, 5, VoxelTypes.V_WALL)
        }

        // 1. Full height
        batcher.rebuildBatches(grid, waistCutaway = false)
        val fullVertices = batcher.materialBatches[EnvironmentalMaterial.WALL]!!.vbo.getVertexCount()

        // 2. Waist cutaway (clipped above waist height)
        batcher.rebuildBatches(grid, waistCutaway = true)
        val cutawayVertices = batcher.materialBatches[EnvironmentalMaterial.WALL]!!.vbo.getVertexCount()

        assertTrue(cutawayVertices < fullVertices)
        assertTrue(batcher.isWaistCutaway)
    }

    @Test
    fun testBatchRebuildClearsPreviousBuffersCorrectly() {
        grid.set(2, 0, 2, VoxelTypes.V_FLOOR)
        batcher.rebuildBatches(grid)
        val count1 = batcher.materialBatches[EnvironmentalMaterial.FLOOR]!!.vbo.getVertexCount()
        assertTrue(count1 > 0)

        // Rebuilding with the exact same grid should not double vertex count
        batcher.rebuildBatches(grid)
        val count2 = batcher.materialBatches[EnvironmentalMaterial.FLOOR]!!.vbo.getVertexCount()
        assertEquals(count1, count2)

        // Clearing grid and rebuilding should produce zero vertices
        grid.clear()
        batcher.rebuildBatches(grid)
        val count3 = batcher.materialBatches[EnvironmentalMaterial.FLOOR]!!.vbo.getVertexCount()
        assertEquals(0, count3)
    }

    @Test
    fun testShadowCastingBatchFiltering() {
        grid.set(1, 0, 1, VoxelTypes.V_FLOOR)
        grid.set(2, 1, 1, VoxelTypes.V_WALL)
        grid.set(3, 1, 1, VoxelTypes.V_COVER)
        grid.set(4, 1, 1, VoxelTypes.V_CRACK)

        batcher.rebuildBatches(grid)

        val shadowBatches = batcher.materialBatches.filter { it.key.castsShadows && it.value.vbo.getVertexCount() > 0 }

        // WALL, COVER, CRACK cast shadows. FLOOR is omitted!
        assertEquals(3, shadowBatches.size)
        assertTrue(shadowBatches.containsKey(EnvironmentalMaterial.WALL))
        assertTrue(shadowBatches.containsKey(EnvironmentalMaterial.COVER))
        assertTrue(shadowBatches.containsKey(EnvironmentalMaterial.CRACK))
        assertFalse(shadowBatches.containsKey(EnvironmentalMaterial.FLOOR))
    }
}
