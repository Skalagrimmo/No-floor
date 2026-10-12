package com.example

import com.example.engine.gl.MeshVbo
import com.example.engine.gl.VaoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaoManagerUnitTest {

    @Test
    fun testVaoManagerInitialState() {
        val vaoManager = VaoManager()
        assertFalse(vaoManager.isVaoSupported())
        assertEquals(0, vaoManager.getActiveVaoCount())
        assertEquals(0L, vaoManager.getSavedStateChangesCount())
    }

    @Test
    fun testVaoFallbackWhenUnsupported() {
        val vaoManager = VaoManager()
        vaoManager.setSupportedForTesting(false)

        val vbo = MeshVbo(10)
        vbo.addVertex(0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f)

        // When unsupported, createVao should return 0 safely
        val vaoId = vaoManager.createVao(vbo, posLoc = 0, normLoc = 1, colorLoc = 2)
        assertEquals(0, vaoId)
        assertEquals(0, vaoManager.getActiveVaoCount())

        // Drawing with vaoId = 0 delegates to standard fallback without exceptions
        vaoManager.draw(vaoId, vbo, posLoc = 0, normLoc = 1, colorLoc = 2)
        assertEquals(0L, vaoManager.getSavedStateChangesCount())
    }

    @Test
    fun testVaoLifecycleAndTracking() {
        val vaoManager = VaoManager()
        vaoManager.setSupportedForTesting(true)
        assertTrue(vaoManager.isVaoSupported())

        val vbo = MeshVbo(10)
        vbo.addVertex(1f, 2f, 3f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)

        // Delete of non-existent vao should be safe
        vaoManager.deleteVao(0)
        assertEquals(0, vaoManager.getActiveVaoCount())

        // Release all on empty should be safe
        vaoManager.releaseAll()
        assertEquals(0, vaoManager.getActiveVaoCount())
    }
}
