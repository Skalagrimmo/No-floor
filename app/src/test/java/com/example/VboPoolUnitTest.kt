package com.example

import com.example.engine.gl.MeshVbo
import com.example.engine.gl.VboPool
import com.example.game.voxel.ChunkMeshBuilder
import com.example.game.voxel.VoxelGrid
import com.example.game.voxel.VoxelTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VboPoolUnitTest {

    @Test
    fun testVboPoolAcquireAndRecycle() {
        val pool = VboPool(defaultMaxVertices = 1000, maxPoolSize = 10)
        assertEquals(0, pool.getPooledCount())
        assertEquals(0L, pool.getAllocationsAvoidedCount())

        // 1. Acquire from empty pool allocates fresh buffer
        val vbo1 = pool.acquire(capacity = 500)
        assertNotNull(vbo1)
        assertEquals(0, pool.getPooledCount())

        // 2. Recycle buffer into pool
        pool.recycle(vbo1)
        assertEquals(1, pool.getPooledCount())

        // 3. Next acquire reuses the exact same buffer instance
        val vbo2 = pool.acquire(capacity = 500)
        assertSame(vbo1, vbo2)
        assertEquals(0, pool.getPooledCount())
        assertEquals(1L, pool.getAllocationsAvoidedCount())
        assertTrue(pool.getMemorySavedBytes() > 0L)
    }

    @Test
    fun testVboResetOnRecycleAndAcquire() {
        val pool = VboPool(defaultMaxVertices = 500)
        val vbo = pool.acquire()

        // Populate with vertices
        vbo.addVertex(1f, 2f, 3f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)
        vbo.addVertex(4f, 5f, 6f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        assertEquals(2, vbo.getVertexCount())

        // Recycle and re-acquire
        pool.recycle(vbo)
        val recycledVbo = pool.acquire()

        // Vertex count must be reset to zero for clean re-use
        assertEquals(0, recycledVbo.getVertexCount())
    }

    @Test
    fun testVboPoolReleaseAll() {
        val pool = VboPool(defaultMaxVertices = 500)
        val vbo1 = pool.acquire()
        val vbo2 = pool.acquire()

        pool.recycle(vbo1)
        pool.recycle(vbo2)
        assertEquals(2, pool.getPooledCount())

        pool.releaseAll()
        assertEquals(0, pool.getPooledCount())
    }

    @Test
    fun testChunkMeshBuilderRecyclingIntegration() {
        val grid = VoxelGrid(width = 32, height = 8, depth = 32)
        // Add floor in one quadrant
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                grid.set(x, 0, z, VoxelTypes.V_FLOOR)
            }
        }

        val pool = VboPool(defaultMaxVertices = 8000)
        val builder = ChunkMeshBuilder(grid, chunkSize = 16, vboPool = pool)

        // Initial build acquires VBOs
        builder.buildAll()

        // Releasing chunk meshes recycles all VBOs into the pool
        builder.releaseAll()
        assertTrue(pool.getPooledCount() > 0)

        val previousPooled = pool.getPooledCount()
        val initialAvoided = pool.getAllocationsAvoidedCount()

        // Next build pops recycled VBOs from pool without fresh ByteBuffer allocations
        builder.buildAll()
        assertTrue(pool.getAllocationsAvoidedCount() > initialAvoided)

        builder.releaseAll()
        pool.releaseAll()
    }
}
