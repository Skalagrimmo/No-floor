package com.example.game.voxel

import com.example.engine.gl.MeshVbo
import com.example.engine.gl.VaoManager
import com.example.engine.gl.VboPool

data class VoxelChunk(
    val chunkX: Int,
    val chunkZ: Int,
    val minVx: Int,
    val maxVx: Int,
    val minVz: Int,
    val maxVz: Int,
    var vbo: MeshVbo? = null,
    var vaoId: Int = 0,
    var isWaistCutaway: Boolean = false
)

/**
 * Builds optimized OpenGL VBO meshes from 3D VoxelGrid chunks.
 * Integrates Vertex Array Object (VAO) acceleration, ambient face shading,
 * and VBO buffer pooling to minimize memory allocations and GC overhead.
 */
class ChunkMeshBuilder(
    val grid: VoxelGrid,
    val chunkSize: Int = 16,
    val vboPool: VboPool = VboPool()
) {

    val chunksX = grid.width / chunkSize
    val chunksZ = grid.depth / chunkSize
    val chunks = mutableListOf<VoxelChunk>()

    init {
        for (cz in 0 until chunksZ) {
            for (cx in 0 until chunksX) {
                chunks.add(
                    VoxelChunk(
                        chunkX = cx,
                        chunkZ = cz,
                        minVx = cx * chunkSize,
                        maxVx = (cx + 1) * chunkSize,
                        minVz = cz * chunkSize,
                        maxVz = (cz + 1) * chunkSize
                    )
                )
            }
        }
    }

    fun buildChunkMesh(
        chunk: VoxelChunk,
        waistCutaway: Boolean = false,
        vaoManager: VaoManager? = null,
        posLoc: Int = -1,
        normLoc: Int = -1,
        colorLoc: Int = -1
    ) {
        if (chunk.vaoId != 0 && vaoManager != null) {
            vaoManager.deleteVao(chunk.vaoId)
            chunk.vaoId = 0
        }

        // Reuse existing chunk VBO or acquire recycled buffer from VboPool (Zero GC allocation!)
        val vbo = chunk.vbo ?: vboPool.acquire(capacity = 8000)
        vbo.clear()

        val s = VoxelTypes.VOX_SIZE
        val waistH = if (waistCutaway) VoxelTypes.COVER_HEIGHT_VOXELS + 1 else VoxelTypes.WALL_HEIGHT_VOXELS

        for (y in 0 until grid.height) {
            for (z in chunk.minVz until chunk.maxVz) {
                for (x in chunk.minVx until chunk.maxVx) {
                    val v = grid.get(x, y, z)
                    if (v == VoxelTypes.V_EMPTY) continue

                    // In waist cutaway mode, clip tall walls down to waist level
                    if (waistCutaway && y >= waistH && (v == VoxelTypes.V_WALL || v == VoxelTypes.V_CRACK)) {
                        continue
                    }

                    val color = when (v) {
                        VoxelTypes.V_FLOOR -> VoxelTypes.COLOR_FLOOR
                        VoxelTypes.V_WALL -> VoxelTypes.COLOR_WALL
                        VoxelTypes.V_CRACK -> VoxelTypes.COLOR_CRACK
                        VoxelTypes.V_COVER -> VoxelTypes.COLOR_COVER
                        else -> VoxelTypes.COLOR_WALL
                    }

                    val p0x = x * s
                    val p1x = (x + 1) * s
                    val p0y = y * s
                    val p1y = (y + 1) * s
                    val p0z = z * s
                    val p1z = (z + 1) * s

                    // 1. Top Face (+Y)
                    if (grid.get(x, y + 1, z) == VoxelTypes.V_EMPTY || (waistCutaway && y + 1 >= waistH)) {
                        vbo.addQuad(
                            p0x, p1y, p0z,
                            p0x, p1y, p1z,
                            p1x, p1y, p1z,
                            p1x, p1y, p0z,
                            0f, 1f, 0f,
                            color[0] * 1.1f, color[1] * 1.1f, color[2] * 1.1f, color[3]
                        )
                    }

                    // 2. North Face (-Z)
                    if (grid.get(x, y, z - 1) == VoxelTypes.V_EMPTY) {
                        vbo.addQuad(
                            p1x, p0y, p0z,
                            p0x, p0y, p0z,
                            p0x, p1y, p0z,
                            p1x, p1y, p0z,
                            0f, 0f, -1f,
                            color[0] * 0.85f, color[1] * 0.85f, color[2] * 0.85f, color[3]
                        )
                    }

                    // 3. South Face (+Z)
                    if (grid.get(x, y, z + 1) == VoxelTypes.V_EMPTY) {
                        vbo.addQuad(
                            p0x, p0y, p1z,
                            p1x, p0y, p1z,
                            p1x, p1y, p1z,
                            p0x, p1y, p1z,
                            0f, 0f, 1f,
                            color[0] * 0.95f, color[1] * 0.95f, color[2] * 0.95f, color[3]
                        )
                    }

                    // 4. West Face (-X)
                    if (grid.get(x - 1, y, z) == VoxelTypes.V_EMPTY) {
                        vbo.addQuad(
                            p0x, p0y, p0z,
                            p0x, p0y, p1z,
                            p0x, p1y, p1z,
                            p0x, p1y, p0z,
                            -1f, 0f, 0f,
                            color[0] * 0.75f, color[1] * 0.75f, color[2] * 0.75f, color[3]
                        )
                    }

                    // 5. East Face (+X)
                    if (grid.get(x + 1, y, z) == VoxelTypes.V_EMPTY) {
                        vbo.addQuad(
                            p1x, p0y, p1z,
                            p1x, p0y, p0z,
                            p1x, p1y, p0z,
                            p1x, p1y, p1z,
                            1f, 0f, 0f,
                            color[0] * 0.8f, color[1] * 0.8f, color[2] * 0.8f, color[3]
                        )
                    }
                }
            }
        }

        vbo.upload()
        chunk.vbo = vbo
        chunk.isWaistCutaway = waistCutaway

        if (vaoManager != null && vaoManager.isVaoSupported() && vbo.getVertexCount() > 0) {
            chunk.vaoId = vaoManager.createVao(vbo, posLoc, normLoc, colorLoc)
        }
    }

    fun buildAll(
        vaoManager: VaoManager? = null,
        posLoc: Int = -1,
        normLoc: Int = -1,
        colorLoc: Int = -1
    ) {
        for (c in chunks) {
            buildChunkMesh(c, waistCutaway = false, vaoManager, posLoc, normLoc, colorLoc)
        }
    }

    fun releaseAll(vaoManager: VaoManager? = null) {
        for (c in chunks) {
            if (c.vaoId != 0 && vaoManager != null) {
                vaoManager.deleteVao(c.vaoId)
                c.vaoId = 0
            }
            c.vbo?.let { vbo ->
                vboPool.recycle(vbo)
                c.vbo = null
            }
        }
    }
}
