package com.example.engine.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * High-performance Vertex Buffer Object (VBO) for mobile GPUs like Mali-400.
 * Pre-allocated in native memory; zero garbage collection pressure during runtime.
 * Buffer ID allocation is deferred to upload/bind to guarantee safe EGL thread context execution.
 */
class MeshVbo(val maxVertices: Int) {

    companion object {
        const val FLOATS_PER_VERTEX = 10 // pos(3) + norm(3) + color(4)
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val POSITION_OFFSET_BYTES = 0
        const val NORMAL_OFFSET_BYTES = 3 * 4
        const val COLOR_OFFSET_BYTES = 6 * 4
    }

    private var vboId: Int = 0
    private var vertexCount: Int = 0

    val floatBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(maxVertices * STRIDE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun getVboId(): Int {
        if (vboId == 0) {
            val buffers = IntArray(1)
            GLES20.glGenBuffers(1, buffers, 0)
            vboId = buffers[0]
        }
        return vboId
    }

    fun addVertex(
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float, a: Float = 1.0f
    ) {
        if (vertexCount >= maxVertices) return
        floatBuffer.put(x)
        floatBuffer.put(y)
        floatBuffer.put(z)
        floatBuffer.put(nx)
        floatBuffer.put(ny)
        floatBuffer.put(nz)
        floatBuffer.put(r)
        floatBuffer.put(g)
        floatBuffer.put(b)
        floatBuffer.put(a)
        vertexCount++
    }

    fun addQuad(
        p0x: Float, p0y: Float, p0z: Float,
        p1x: Float, p1y: Float, p1z: Float,
        p2x: Float, p2y: Float, p2z: Float,
        p3x: Float, p3y: Float, p3z: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float, a: Float = 1.0f
    ) {
        // Two triangles: p0-p1-p2 and p0-p2-p3
        addVertex(p0x, p0y, p0z, nx, ny, nz, r, g, b, a)
        addVertex(p1x, p1y, p1z, nx, ny, nz, r, g, b, a)
        addVertex(p2x, p2y, p2z, nx, ny, nz, r, g, b, a)

        addVertex(p0x, p0y, p0z, nx, ny, nz, r, g, b, a)
        addVertex(p2x, p2y, p2z, nx, ny, nz, r, g, b, a)
        addVertex(p3x, p3y, p3z, nx, ny, nz, r, g, b, a)
    }

    fun upload() {
        if (vboId == 0) {
            val buffers = IntArray(1)
            GLES20.glGenBuffers(1, buffers, 0)
            vboId = buffers[0]
        }
        if (vboId == 0) return

        floatBuffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            vertexCount * STRIDE_BYTES,
            floatBuffer,
            GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun bindAndDraw(
        posLoc: Int,
        normLoc: Int,
        colorLoc: Int,
        primitiveMode: Int = GLES20.GL_TRIANGLES
    ) {
        if (vertexCount == 0 || vboId == 0) return

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)

        if (posLoc != -1) {
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, POSITION_OFFSET_BYTES)
        }

        if (normLoc != -1) {
            GLES20.glEnableVertexAttribArray(normLoc)
            GLES20.glVertexAttribPointer(normLoc, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, NORMAL_OFFSET_BYTES)
        }

        if (colorLoc != -1) {
            GLES20.glEnableVertexAttribArray(colorLoc)
            GLES20.glVertexAttribPointer(colorLoc, 4, GLES20.GL_FLOAT, false, STRIDE_BYTES, COLOR_OFFSET_BYTES)
        }

        GLES20.glDrawArrays(primitiveMode, 0, vertexCount)

        if (posLoc != -1) GLES20.glDisableVertexAttribArray(posLoc)
        if (normLoc != -1) GLES20.glDisableVertexAttribArray(normLoc)
        if (colorLoc != -1) GLES20.glDisableVertexAttribArray(colorLoc)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun clear() {
        floatBuffer.clear()
        vertexCount = 0
    }

    fun release() {
        if (vboId != 0) {
            val buffers = intArrayOf(vboId)
            GLES20.glDeleteBuffers(1, buffers, 0)
            vboId = 0
        }
        vertexCount = 0
    }

    fun getVertexCount(): Int = vertexCount
}
