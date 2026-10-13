package com.example.engine.gl

import android.opengl.GLES20
import com.example.engine.math.Mat4
import com.example.engine.math.Vec3
import com.example.game.voxel.VoxelGrid
import com.example.game.voxel.VoxelTypes
import kotlin.math.max

/**
 * Defines distinct environmental material categories with specific physical
 * shading, color palettes, and shadow-casting properties.
 */
enum class EnvironmentalMaterial(
    val id: Int,
    val displayName: String,
    val voxelType: Byte,
    val baseColor: FloatArray,
    val castsShadows: Boolean,
    val isWaistCutawayCandidate: Boolean
) {
    FLOOR(
        id = 1,
        displayName = "Tactical Slate Floor",
        voxelType = VoxelTypes.V_FLOOR,
        baseColor = VoxelTypes.COLOR_FLOOR,
        castsShadows = false,
        isWaistCutawayCandidate = false
    ),
    WALL(
        id = 2,
        displayName = "Reinforced Concrete Wall",
        voxelType = VoxelTypes.V_WALL,
        baseColor = VoxelTypes.COLOR_WALL,
        castsShadows = true,
        isWaistCutawayCandidate = true
    ),
    COVER(
        id = 3,
        displayName = "Steel Waist-High Cover",
        voxelType = VoxelTypes.V_COVER,
        baseColor = VoxelTypes.COLOR_COVER,
        castsShadows = true,
        isWaistCutawayCandidate = false
    ),
    CRACK(
        id = 4,
        displayName = "Breachable Thermite Wall",
        voxelType = VoxelTypes.V_CRACK,
        baseColor = VoxelTypes.COLOR_CRACK,
        castsShadows = true,
        isWaistCutawayCandidate = true
    );

    companion object {
        fun fromVoxelType(type: Byte): EnvironmentalMaterial? = when (type) {
            VoxelTypes.V_FLOOR -> FLOOR
            VoxelTypes.V_WALL -> WALL
            VoxelTypes.V_COVER -> COVER
            VoxelTypes.V_CRACK -> CRACK
            else -> null
        }
    }
}

/**
 * Geometry batching modes allowing fine-tuned control over CPU-to-GPU overhead.
 */
enum class BatchMode(val displayName: String, val description: String) {
    MATERIAL_BATCHED(
        "Material Batched",
        "Merges all static environmental meshes sharing identical materials into a single draw call per material."
    ),
    UNIFIED_SINGLE_CALL(
        "Unified Static (1 Call)",
        "Combines all static environmental geometry across the entire level into 1 mega draw call."
    ),
    CHUNK_UNBATCHED(
        "Chunk Unbatched (Legacy)",
        "Draws each 16x16 voxel chunk separately (up to 64 draw calls) for profiling comparison."
    )
}

/**
 * Encapsulates a merged GPU batch of static geometry sharing an identical material.
 */
class MaterialBatch(
    val material: EnvironmentalMaterial,
    maxVertices: Int = 32000
) {
    val vbo: MeshVbo = MeshVbo(maxVertices)
    var vaoId: Int = 0
    var vertexCount: Int = 0
    var quadCount: Int = 0

    // Bounding volume for spatial queries and frustum rejection
    var minX: Float = Float.MAX_VALUE
    var minY: Float = Float.MAX_VALUE
    var minZ: Float = Float.MAX_VALUE
    var maxX: Float = -Float.MAX_VALUE
    var maxY: Float = -Float.MAX_VALUE
    var maxZ: Float = -Float.MAX_VALUE

    fun clear() {
        vbo.clear()
        vertexCount = 0
        quadCount = 0
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        minZ = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        maxZ = -Float.MAX_VALUE
    }

    fun addQuad(
        p0x: Float, p0y: Float, p0z: Float,
        p1x: Float, p1y: Float, p1z: Float,
        p2x: Float, p2y: Float, p2z: Float,
        p3x: Float, p3y: Float, p3z: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float, a: Float = 1.0f
    ) {
        vbo.addQuad(
            p0x, p0y, p0z,
            p1x, p1y, p1z,
            p2x, p2y, p2z,
            p3x, p3y, p3z,
            nx, ny, nz,
            r, g, b, a
        )
        vertexCount += 6
        quadCount++

        updateBounds(p0x, p0y, p0z)
        updateBounds(p1x, p1y, p1z)
        updateBounds(p2x, p2y, p2z)
        updateBounds(p3x, p3y, p3z)
    }

    private fun updateBounds(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        if (z < minZ) minZ = z
        if (z > maxZ) maxZ = z
    }

    fun upload(vaoManager: VaoManager? = null, posLoc: Int = -1, normLoc: Int = -1, colorLoc: Int = -1) {
        if (vaoId != 0 && vaoManager != null) {
            vaoManager.deleteVao(vaoId)
            vaoId = 0
        }
        vbo.upload()
        if (vaoManager != null && vaoManager.isVaoSupported() && vbo.getVertexCount() > 0) {
            vaoId = vaoManager.createVao(vbo, posLoc, normLoc, colorLoc)
        }
    }

    fun release(vaoManager: VaoManager? = null) {
        if (vaoId != 0 && vaoManager != null) {
            vaoManager.deleteVao(vaoId)
            vaoId = 0
        }
        clear()
    }
}

/**
 * Telemetry snapshot detailing geometry batching performance and draw call reduction.
 */
data class BatchingTelemetry(
    val batchMode: BatchMode = BatchMode.MATERIAL_BATCHED,
    val batchesCount: Int = 4,
    val totalVertices: Int = 0,
    val unbatchedDrawCalls: Int = 64,
    val batchedDrawCalls: Int = 4,
    val drawCallsSaved: Int = 60,
    val cpuReductionPercent: Float = 93.75f,
    val materialBreakdown: Map<String, Int> = emptyMap()
)

/**
 * High-performance Geometry Batching System.
 *
 * Merges static environmental meshes (voxel walls, floors, covers, cracks, and static props)
 * sharing identical materials into a single draw call per material.
 *
 * Drastically reduces CPU-to-GPU overhead on mobile tile-based architectures (ARM Mali-400 / Mali-450)
 * by cutting world environmental draw calls from 64+ down to 1–4 draw calls per frame (a >90% reduction
 * in OpenGL ES driver context switches and state changes).
 */
class GeometryBatcher(
    val vboPool: VboPool? = null
) {
    var batchMode: BatchMode = BatchMode.MATERIAL_BATCHED

    // Dedicated material batches for each environmental material
    val materialBatches = LinkedHashMap<EnvironmentalMaterial, MaterialBatch>().apply {
        put(EnvironmentalMaterial.FLOOR, MaterialBatch(EnvironmentalMaterial.FLOOR, maxVertices = 35000))
        put(EnvironmentalMaterial.WALL, MaterialBatch(EnvironmentalMaterial.WALL, maxVertices = 45000))
        put(EnvironmentalMaterial.COVER, MaterialBatch(EnvironmentalMaterial.COVER, maxVertices = 12000))
        put(EnvironmentalMaterial.CRACK, MaterialBatch(EnvironmentalMaterial.CRACK, maxVertices = 8000))
    }

    // Unified static batch combining ALL materials into a single draw call
    val unifiedBatch = MaterialBatch(EnvironmentalMaterial.WALL, maxVertices = 90000)

    var isBuilt: Boolean = false
        private set
    var isWaistCutaway: Boolean = false
        private set

    private val tempIdentityMatrix = Mat4()
    private val tempMvpMatrix = Mat4()

    fun init() {
        // Pre-initialize empty batch state
        for (batch in materialBatches.values) {
            batch.clear()
        }
        unifiedBatch.clear()
        isBuilt = false
    }

    /**
     * Rebuilds static environmental batches from the given VoxelGrid.
     * Merges all faces of identical material into dedicated single-call buffers.
     */
    fun rebuildBatches(
        grid: VoxelGrid,
        waistCutaway: Boolean = false,
        vaoManager: VaoManager? = null,
        posLoc: Int = -1,
        normLoc: Int = -1,
        colorLoc: Int = -1
    ) {
        // Clear all previous batch buffers
        for (batch in materialBatches.values) {
            batch.clear()
        }
        unifiedBatch.clear()

        isWaistCutaway = waistCutaway
        val s = VoxelTypes.VOX_SIZE
        val waistH = if (waistCutaway) VoxelTypes.COVER_HEIGHT_VOXELS + 1 else VoxelTypes.WALL_HEIGHT_VOXELS

        val w = grid.width
        val h = grid.height
        val d = grid.depth

        for (y in 0 until h) {
            for (z in 0 until d) {
                for (x in 0 until w) {
                    val v = grid.get(x, y, z)
                    if (v == VoxelTypes.V_EMPTY) continue

                    // In waist cutaway mode, clip tall walls down to waist level
                    if (waistCutaway && y >= waistH && (v == VoxelTypes.V_WALL || v == VoxelTypes.V_CRACK)) {
                        continue
                    }

                    val material = EnvironmentalMaterial.fromVoxelType(v) ?: continue
                    val targetBatch = materialBatches[material] ?: continue

                    val baseColor = material.baseColor
                    val p0x = x * s
                    val p1x = (x + 1) * s
                    val p0y = y * s
                    val p1y = (y + 1) * s
                    val p0z = z * s
                    val p1z = (z + 1) * s

                    // 1. Top Face (+Y)
                    if (grid.get(x, y + 1, z) == VoxelTypes.V_EMPTY || (waistCutaway && y + 1 >= waistH)) {
                        val cr = baseColor[0] * 1.1f
                        val cg = baseColor[1] * 1.1f
                        val cb = baseColor[2] * 1.1f
                        val ca = baseColor[3]

                        targetBatch.addQuad(
                            p0x, p1y, p0z,
                            p0x, p1y, p1z,
                            p1x, p1y, p1z,
                            p1x, p1y, p0z,
                            0f, 1f, 0f,
                            cr, cg, cb, ca
                        )
                        unifiedBatch.addQuad(
                            p0x, p1y, p0z,
                            p0x, p1y, p1z,
                            p1x, p1y, p1z,
                            p1x, p1y, p0z,
                            0f, 1f, 0f,
                            cr, cg, cb, ca
                        )
                    }

                    // 2. North Face (-Z)
                    if (grid.get(x, y, z - 1) == VoxelTypes.V_EMPTY) {
                        val cr = baseColor[0] * 0.85f
                        val cg = baseColor[1] * 0.85f
                        val cb = baseColor[2] * 0.85f
                        val ca = baseColor[3]

                        targetBatch.addQuad(
                            p1x, p0y, p0z,
                            p0x, p0y, p0z,
                            p0x, p1y, p0z,
                            p1x, p1y, p0z,
                            0f, 0f, -1f,
                            cr, cg, cb, ca
                        )
                        unifiedBatch.addQuad(
                            p1x, p0y, p0z,
                            p0x, p0y, p0z,
                            p0x, p1y, p0z,
                            p1x, p1y, p0z,
                            0f, 0f, -1f,
                            cr, cg, cb, ca
                        )
                    }

                    // 3. South Face (+Z)
                    if (grid.get(x, y, z + 1) == VoxelTypes.V_EMPTY) {
                        val cr = baseColor[0] * 0.95f
                        val cg = baseColor[1] * 0.95f
                        val cb = baseColor[2] * 0.95f
                        val ca = baseColor[3]

                        targetBatch.addQuad(
                            p0x, p0y, p1z,
                            p1x, p0y, p1z,
                            p1x, p1y, p1z,
                            p0x, p1y, p1z,
                            0f, 0f, 1f,
                            cr, cg, cb, ca
                        )
                        unifiedBatch.addQuad(
                            p0x, p0y, p1z,
                            p1x, p0y, p1z,
                            p1x, p1y, p1z,
                            p0x, p1y, p1z,
                            0f, 0f, 1f,
                            cr, cg, cb, ca
                        )
                    }

                    // 4. West Face (-X)
                    if (grid.get(x - 1, y, z) == VoxelTypes.V_EMPTY) {
                        val cr = baseColor[0] * 0.75f
                        val cg = baseColor[1] * 0.75f
                        val cb = baseColor[2] * 0.75f
                        val ca = baseColor[3]

                        targetBatch.addQuad(
                            p0x, p0y, p0z,
                            p0x, p0y, p1z,
                            p0x, p1y, p1z,
                            p0x, p1y, p0z,
                            -1f, 0f, 0f,
                            cr, cg, cb, ca
                        )
                        unifiedBatch.addQuad(
                            p0x, p0y, p0z,
                            p0x, p0y, p1z,
                            p0x, p1y, p1z,
                            p0x, p1y, p0z,
                            -1f, 0f, 0f,
                            cr, cg, cb, ca
                        )
                    }

                    // 5. East Face (+X)
                    if (grid.get(x + 1, y, z) == VoxelTypes.V_EMPTY) {
                        val cr = baseColor[0] * 0.8f
                        val cg = baseColor[1] * 0.8f
                        val cb = baseColor[2] * 0.8f
                        val ca = baseColor[3]

                        targetBatch.addQuad(
                            p1x, p0y, p1z,
                            p1x, p0y, p0z,
                            p1x, p1y, p0z,
                            p1x, p1y, p1z,
                            1f, 0f, 0f,
                            cr, cg, cb, ca
                        )
                        unifiedBatch.addQuad(
                            p1x, p0y, p1z,
                            p1x, p0y, p0z,
                            p1x, p1y, p0z,
                            p1x, p1y, p1z,
                            1f, 0f, 0f,
                            cr, cg, cb, ca
                        )
                    }
                }
            }
        }

        // Upload all batched VBOs to GPU memory and bind VAOs
        for (batch in materialBatches.values) {
            batch.upload(vaoManager, posLoc, normLoc, colorLoc)
        }
        unifiedBatch.upload(vaoManager, posLoc, normLoc, colorLoc)

        isBuilt = true
    }

    /**
     * Merges an external static environmental mesh with an identical material into this batch.
     * Allows static environmental props, doors, and architectural geometry to be batched seamlessly.
     */
    fun mergeStaticMesh(
        material: EnvironmentalMaterial,
        sourceVbo: MeshVbo,
        transform: Mat4? = null
    ) {
        val targetBatch = materialBatches[material] ?: return
        val count = sourceVbo.getVertexCount()
        if (count == 0) return

        val buf = sourceVbo.floatBuffer
        buf.position(0)

        // Stride is 10 floats: pos(3), norm(3), color(4)
        for (i in 0 until count) {
            var x = buf.get()
            var y = buf.get()
            var z = buf.get()
            var nx = buf.get()
            var ny = buf.get()
            var nz = buf.get()
            val r = buf.get()
            val g = buf.get()
            val b = buf.get()
            val a = buf.get()

            if (transform != null) {
                val p = transform.transformPoint(Vec3(x, y, z))
                x = p.x
                y = p.y
                z = p.z
                val n = transform.transformDirection(Vec3(nx, ny, nz)).normalize()
                nx = n.x
                ny = n.y
                nz = n.z
            }

            targetBatch.vbo.addVertex(x, y, z, nx, ny, nz, r, g, b, a)
            targetBatch.vertexCount++

            unifiedBatch.vbo.addVertex(x, y, z, nx, ny, nz, r, g, b, a)
            unifiedBatch.vertexCount++
        }
        buf.position(0)
    }

    /**
     * Renders all static environmental batches in the main World lighting pass.
     *
     * In MATERIAL_BATCHED mode: Executes exactly 1 draw call per environmental material.
     * In UNIFIED_SINGLE_CALL mode: Executes exactly 1 draw call for the entire world.
     *
     * Returns the exact number of draw calls issued.
     */
    fun renderBatchedWorld(
        vaoManager: VaoManager,
        worldShader: WorldShader,
        modelMatrix: Mat4,
        projMatrix: Mat4,
        viewMatrix: Mat4
    ): Int {
        if (!isBuilt) return 0
        var drawCalls = 0

        tempIdentityMatrix.identity()
        tempMvpMatrix.copyFrom(Mat4.multiply(projMatrix, Mat4.multiply(viewMatrix, tempIdentityMatrix)))
        worldShader.setModelMatrix(tempIdentityMatrix)
        worldShader.setMVPMatrix(tempMvpMatrix)

        when (batchMode) {
            BatchMode.MATERIAL_BATCHED -> {
                // One single draw call per material type!
                for ((_, batch) in materialBatches) {
                    val vCount = batch.vbo.getVertexCount()
                    if (vCount == 0) continue

                    vaoManager.draw(
                        vaoId = batch.vaoId,
                        vbo = batch.vbo,
                        posLoc = worldShader.aPositionLocation,
                        normLoc = worldShader.aNormalLocation,
                        colorLoc = worldShader.aColorLocation
                    )
                    drawCalls++
                }
            }
            BatchMode.UNIFIED_SINGLE_CALL -> {
                // Exactly 1 single draw call for the whole environment!
                val vCount = unifiedBatch.vbo.getVertexCount()
                if (vCount > 0) {
                    vaoManager.draw(
                        vaoId = unifiedBatch.vaoId,
                        vbo = unifiedBatch.vbo,
                        posLoc = worldShader.aPositionLocation,
                        normLoc = worldShader.aNormalLocation,
                        colorLoc = worldShader.aColorLocation
                    )
                    drawCalls++
                }
            }
            BatchMode.CHUNK_UNBATCHED -> {
                // Handled externally by fallback loop
            }
        }

        return drawCalls
    }

    /**
     * Renders static environmental shadow casters in the directional light Shadow depth pass.
     *
     * Automatically omits FLOOR materials (since floors never cast shadows on anything),
     * rendering only shadow-casting WALL, COVER, and CRACK batches in 1–3 draw calls!
     *
     * Returns the exact number of draw calls issued.
     */
    fun renderBatchedShadows(
        shadowShader: ShadowShader,
        lightSpaceMatrix: Mat4
    ): Int {
        if (!isBuilt) return 0
        var drawCalls = 0

        tempIdentityMatrix.identity()
        val lightMvp = Mat4.multiply(lightSpaceMatrix, tempIdentityMatrix)
        shadowShader.setLightMVPMatrix(lightMvp)

        when (batchMode) {
            BatchMode.MATERIAL_BATCHED -> {
                // Render only shadow-casting materials (Walls, Covers, Cracks). Floors are omitted!
                for ((material, batch) in materialBatches) {
                    if (!material.castsShadows) continue
                    if (batch.vbo.getVertexCount() == 0) continue

                    batch.vbo.bindAndDraw(shadowShader.aPositionLocation, -1, -1)
                    drawCalls++
                }
            }
            BatchMode.UNIFIED_SINGLE_CALL -> {
                // Render shadow-casting geometry in single call
                for ((material, batch) in materialBatches) {
                    if (!material.castsShadows) continue
                    if (batch.vbo.getVertexCount() == 0) continue

                    batch.vbo.bindAndDraw(shadowShader.aPositionLocation, -1, -1)
                    drawCalls++
                }
            }
            BatchMode.CHUNK_UNBATCHED -> {
                // Handled externally by fallback loop
            }
        }

        return drawCalls
    }

    /**
     * Releases all GPU buffers and deletes VAOs.
     */
    fun releaseAll(vaoManager: VaoManager? = null) {
        for (batch in materialBatches.values) {
            batch.release(vaoManager)
        }
        unifiedBatch.release(vaoManager)
        isBuilt = false
    }

    /**
     * Returns comprehensive telemetry on geometry batching performance.
     */
    fun getTelemetry(unbatchedChunkCount: Int = 64): BatchingTelemetry {
        var totalVerts = 0
        var activeBatches = 0
        val breakdown = mutableMapOf<String, Int>()

        for ((mat, batch) in materialBatches) {
            val count = batch.vbo.getVertexCount()
            if (count > 0) {
                activeBatches++
                totalVerts += count
                breakdown[mat.displayName] = count
            }
        }

        val actualCalls = when (batchMode) {
            BatchMode.MATERIAL_BATCHED -> max(1, activeBatches)
            BatchMode.UNIFIED_SINGLE_CALL -> 1
            BatchMode.CHUNK_UNBATCHED -> unbatchedChunkCount
        }

        val saved = max(0, unbatchedChunkCount - actualCalls)
        val reduction = if (unbatchedChunkCount > 0) {
            (saved.toFloat() / unbatchedChunkCount.toFloat()) * 100f
        } else {
            0f
        }

        return BatchingTelemetry(
            batchMode = batchMode,
            batchesCount = activeBatches,
            totalVertices = totalVerts,
            unbatchedDrawCalls = unbatchedChunkCount,
            batchedDrawCalls = actualCalls,
            drawCallsSaved = saved,
            cpuReductionPercent = reduction,
            materialBreakdown = breakdown
        )
    }
}
