package com.example.engine.gl

import java.util.ArrayDeque

/**
 * High-performance Vertex Buffer Object (VBO) Pooling System.
 *
 * Recycles existing MeshVbo instances and their direct native ByteBuffers during runtime,
 * completely eliminating garbage collection pressure, native heap fragmentation, and redundant
 * glGenBuffers/glDeleteBuffers calls on memory-constrained budget devices (e.g. 2GB RAM / Mali-400).
 */
class VboPool(
    val defaultMaxVertices: Int = 8000,
    val maxPoolSize: Int = 128
) {
    private val pool = ArrayDeque<MeshVbo>()
    private var totalAllocationsAvoided: Long = 0L
    private var totalBytesSaved: Long = 0L

    /**
     * Acquires a recycled MeshVbo from the pool or allocates a new one if pool is empty.
     */
    @Synchronized
    fun acquire(capacity: Int = defaultMaxVertices): MeshVbo {
        val iterator = pool.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.maxVertices >= capacity) {
                iterator.remove()
                candidate.clear()
                totalAllocationsAvoided++
                totalBytesSaved += (candidate.maxVertices * MeshVbo.STRIDE_BYTES).toLong()
                return candidate
            }
        }

        // None available in pool; allocate a fresh one
        return MeshVbo(capacity)
    }

    /**
     * Recycles a MeshVbo back into the pool for immediate reuse.
     */
    @Synchronized
    fun recycle(vbo: MeshVbo?) {
        if (vbo == null) return
        if (pool.size < maxPoolSize) {
            vbo.clear()
            pool.push(vbo)
        } else {
            vbo.release()
        }
    }

    /**
     * Clears all pooled VBOs and releases their GPU resources.
     */
    @Synchronized
    fun releaseAll() {
        while (!pool.isEmpty()) {
            val vbo = pool.pop()
            vbo.release()
        }
    }

    @Synchronized
    fun getPooledCount(): Int = pool.size

    @Synchronized
    fun getAllocationsAvoidedCount(): Long = totalAllocationsAvoided

    @Synchronized
    fun getMemorySavedBytes(): Long = totalBytesSaved
}
