package com.example

import com.example.engine.gl.ShaderCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShaderCacheUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testComputeCacheKeyDeterminismAndInvalidation() {
        val cache = ShaderCache()

        val vert1 = "attribute vec4 a_Pos; void main() { gl_Position = a_Pos; }"
        val frag1 = "precision mediump float; void main() { gl_FragColor = vec4(1.0); }"

        val key1 = cache.computeCacheKey("test_shader", vert1, frag1)
        val key2 = cache.computeCacheKey("test_shader", vert1, frag1)
        assertEquals(key1, key2)

        // Altering vertex shader source must produce a different cache key (automatic cache invalidation)
        val vertModified = "attribute vec4 a_Pos; void main() { gl_Position = a_Pos * 2.0; }"
        val keyModified = cache.computeCacheKey("test_shader", vertModified, frag1)
        assertNotEquals(key1, keyModified)

        // Altering fragment shader source must also produce a different cache key
        val fragModified = "precision mediump float; void main() { gl_FragColor = vec4(0.5); }"
        val keyFragModified = cache.computeCacheKey("test_shader", vert1, fragModified)
        assertNotEquals(key1, keyFragModified)
    }

    @Test
    fun testShaderCacheDirectoryAndCleanup() {
        val cacheDir = tempFolder.newFolder("gl_cache")
        val cache = ShaderCache(customCacheDir = cacheDir)

        // Create a dummy cached file
        val dummyFile = File(File(cacheDir, "shader_cache"), "dummy_key.bin")
        dummyFile.parentFile?.mkdirs()
        dummyFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(dummyFile.exists())

        cache.clearDiskCache()
        assertFalse(dummyFile.exists())
    }

    @Test
    fun testShaderCacheTelemetryTracking() {
        val cacheDir = tempFolder.newFolder("gl_cache_telemetry")
        val cache = ShaderCache(customCacheDir = cacheDir)

        cache.setBinarySupportForTesting(false)
        assertFalse(cache.isBinarySupported())

        val telemetry = cache.getTelemetry()
        assertFalse(telemetry.binarySupported)
        assertEquals(0, telemetry.cachedToDiskCount)
        assertEquals(0, telemetry.cacheHitCount)
    }
}
