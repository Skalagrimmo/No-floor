package com.example.engine.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class ShaderCacheTelemetry(
    val binarySupported: Boolean = false,
    val binaryFormatsCount: Int = 0,
    val cachedToDiskCount: Int = 0,
    val cacheHitCount: Int = 0,
    val compileFallbackCount: Int = 0,
    val activeProgramsCount: Int = 0
)

/**
 * High-performance Shader Cache System for mobile devices (e.g. Mali-400 / ARM).
 *
 * Pre-compiles and binary-saves GLSL programs during startup / initial loading screen,
 * eliminating runtime JIT compilation pauses and stuttering during gameplay.
 *
 * Automatically invalidates disk cache if shader sources change or GPU driver updates.
 */
class ShaderCache(
    private val context: Context? = null,
    val customCacheDir: File? = null
) {
    companion object {
        private const val TAG = "ShaderCache"
        private const val MAGIC_HEADER = 0x474C5042 // 'GLPB'
    }

    private var isInitialized = false
    private var binarySupported = false
    private var binaryFormatsCount = 0

    private val activePrograms = mutableMapOf<String, Int>()
    private var cacheHitCount = 0
    private var compileFallbackCount = 0
    private var cachedToDiskCount = 0

    private val cacheDirectory: File? by lazy {
        val baseDir = customCacheDir ?: context?.cacheDir
        if (baseDir != null) {
            val dir = File(baseDir, "shader_cache")
            if (!dir.exists()) dir.mkdirs()
            dir
        } else null
    }

    fun init() {
        if (isInitialized) return

        try {
            val versionStr = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            val isGles3 = versionStr.contains("OpenGL ES 3.") || versionStr.contains("OpenGL ES-CM 3.")

            if (isGles3) {
                val numFormats = IntArray(1)
                GLES20.glGetIntegerv(GLES30.GL_NUM_PROGRAM_BINARY_FORMATS, numFormats, 0)
                binaryFormatsCount = numFormats[0]
                binarySupported = (binaryFormatsCount > 0)
                Log.i(TAG, "GLES 3.0 Program Binary support: $binarySupported ($binaryFormatsCount formats)")
            } else {
                binarySupported = false
                Log.i(TAG, "GLES 2.0 context: Binary program saving disabled, using memory pre-compilation cache")
            }
        } catch (e: Throwable) {
            binarySupported = false
            Log.w(TAG, "Error probing GL_PROGRAM_BINARY_FORMATS: ${e.message}")
        }

        isInitialized = true
    }

    fun setBinarySupportForTesting(supported: Boolean) {
        binarySupported = supported
        isInitialized = true
    }

    fun isBinarySupported(): Boolean = binarySupported

    /**
     * Loads a pre-compiled program from disk binary cache, or compiles from source,
     * validates and caches binary to disk.
     */
    fun getOrCompileProgram(
        name: String,
        vertexSource: String,
        fragmentSource: String
    ): Int {
        // 1. Check in-memory program cache
        activePrograms[name]?.let { existingId ->
            if (existingId != 0 && GLES20.glIsProgram(existingId)) {
                return existingId
            }
        }

        val cacheKey = computeCacheKey(name, vertexSource, fragmentSource)
        var programId = 0

        // 2. Try loading from binary disk cache
        if (binarySupported && cacheDirectory != null) {
            programId = loadProgramFromBinaryFile(cacheKey)
            if (programId != 0) {
                cacheHitCount++
                activePrograms[name] = programId
                // Warm up program validation
                GLES20.glValidateProgram(programId)
                return programId
            }
        }

        // 3. Fallback: Compile from GLSL source
        compileFallbackCount++
        programId = GlShader.createProgram(vertexSource, fragmentSource)
        if (programId == 0) {
            Log.e(TAG, "Failed to compile shader program '$name'")
            return 0
        }

        // Pre-warm program pipeline state
        GLES20.glValidateProgram(programId)

        // 4. Save binary to disk cache if supported
        if (binarySupported && cacheDirectory != null) {
            saveProgramToBinaryFile(programId, cacheKey)
        }

        activePrograms[name] = programId
        return programId
    }

    private fun loadProgramFromBinaryFile(cacheKey: String): Int {
        val cacheFile = File(cacheDirectory, "$cacheKey.bin")
        if (!cacheFile.exists() || cacheFile.length() < 12) return 0

        try {
            FileInputStream(cacheFile).use { fis ->
                val headerBytes = ByteArray(12)
                if (fis.read(headerBytes) != 12) return 0

                val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.nativeOrder())
                val magic = headerBuf.int
                val format = headerBuf.int
                val payloadLen = headerBuf.int

                if (magic != MAGIC_HEADER || payloadLen <= 0 || payloadLen > 5_000_000) return 0

                val binaryData = ByteArray(payloadLen)
                var bytesRead = 0
                while (bytesRead < payloadLen) {
                    val read = fis.read(binaryData, bytesRead, payloadLen - bytesRead)
                    if (read == -1) break
                    bytesRead += read
                }
                if (bytesRead != payloadLen) return 0

                val directBuf = ByteBuffer.allocateDirect(payloadLen)
                    .order(ByteOrder.nativeOrder())
                directBuf.put(binaryData)
                directBuf.position(0)

                val progId = GLES20.glCreateProgram()
                if (progId == 0) return 0

                GLES30.glProgramBinary(progId, format, directBuf, payloadLen)

                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(progId, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] == 0) {
                    Log.w(TAG, "Cached program binary failed linking for key $cacheKey; discarding stale cache")
                    GLES20.glDeleteProgram(progId)
                    cacheFile.delete()
                    return 0
                }

                Log.i(TAG, "Successfully loaded binary cached shader program for key $cacheKey ($payloadLen bytes)")
                return progId
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Exception reading program binary: ${e.message}")
            cacheFile.delete()
            return 0
        }
    }

    private fun saveProgramToBinaryFile(programId: Int, cacheKey: String) {
        val cacheFile = File(cacheDirectory, "$cacheKey.bin")
        try {
            val lengthArr = IntArray(1)
            GLES30.glGetProgramiv(programId, GLES30.GL_PROGRAM_BINARY_LENGTH, lengthArr, 0)
            val binaryLen = lengthArr[0]

            if (binaryLen <= 0) return

            val directBuf = ByteBuffer.allocateDirect(binaryLen).order(ByteOrder.nativeOrder())
            val formatArr = IntArray(1)
            val writtenLenArr = IntArray(1)

            GLES30.glGetProgramBinary(programId, binaryLen, writtenLenArr, 0, formatArr, 0, directBuf)

            val actualLength = writtenLenArr[0]
            val format = formatArr[0]

            if (actualLength <= 0) return

            val bytes = ByteArray(actualLength)
            directBuf.position(0)
            directBuf.get(bytes)

            FileOutputStream(cacheFile).use { fos ->
                val headerBuf = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
                headerBuf.putInt(MAGIC_HEADER)
                headerBuf.putInt(format)
                headerBuf.putInt(actualLength)
                fos.write(headerBuf.array())
                fos.write(bytes)
            }

            cachedToDiskCount++
            Log.i(TAG, "Saved binary shader program '$cacheKey' to disk cache ($actualLength bytes, format=$format)")
        } catch (e: Throwable) {
            Log.w(TAG, "Exception saving program binary to disk: ${e.message}")
        }
    }

    fun computeCacheKey(name: String, vertexSource: String, fragmentSource: String): String {
        return try {
            val driverRenderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "renderer"
            val driverVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "v1"

            val md = MessageDigest.getInstance("SHA-256")
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(driverRenderer.toByteArray(Charsets.UTF_8))
            md.update(driverVersion.toByteArray(Charsets.UTF_8))
            md.update(vertexSource.toByteArray(Charsets.UTF_8))
            md.update(fragmentSource.toByteArray(Charsets.UTF_8))

            val digest = md.digest()
            val hexString = StringBuilder()
            for (i in 0 until minOf(16, digest.size)) {
                val hex = Integer.toHexString(0xff and digest[i].toInt())
                if (hex.length == 1) hexString.append('0')
                hexString.append(hex)
            }
            "${name}_$hexString"
        } catch (e: Exception) {
            "${name}_${(vertexSource.hashCode() xor fragmentSource.hashCode())}"
        }
    }

    /**
     * Precompiles all shaders during loading screen to eliminate runtime frame stutters.
     */
    fun precompile(shaders: Map<String, Pair<String, String>>) {
        for ((name, sources) in shaders) {
            getOrCompileProgram(name, sources.first, sources.second)
        }
    }

    fun clearDiskCache() {
        try {
            cacheDirectory?.listFiles()?.forEach { it.delete() }
            cachedToDiskCount = 0
            cacheHitCount = 0
        } catch (ignored: Exception) {
        }
    }

    fun releaseAll() {
        for (progId in activePrograms.values) {
            if (progId != 0 && GLES20.glIsProgram(progId)) {
                GLES20.glDeleteProgram(progId)
            }
        }
        activePrograms.clear()
    }

    fun getTelemetry(): ShaderCacheTelemetry = ShaderCacheTelemetry(
        binarySupported = binarySupported,
        binaryFormatsCount = binaryFormatsCount,
        cachedToDiskCount = cachedToDiskCount,
        cacheHitCount = cacheHitCount,
        compileFallbackCount = compileFallbackCount,
        activeProgramsCount = activePrograms.size
    )
}
