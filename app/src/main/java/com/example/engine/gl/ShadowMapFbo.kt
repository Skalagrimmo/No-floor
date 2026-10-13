package com.example.engine.gl

import android.opengl.GLES20
import android.util.Log

/**
 * Encapsulates OpenGL ES Framebuffer Object (FBO), packed RGBA color texture,
 * and depth renderbuffer for low-resolution shadow map rendering on ARM Mali-400.
 */
class ShadowMapFbo(initialResolution: Int = 512) {

    companion object {
        private const val TAG = "ShadowMapFbo"
    }

    var resolution: Int = initialResolution; private set
    var fboId: Int = 0; private set
    var textureId: Int = 0; private set
    var depthBufferId: Int = 0; private set
    var isReady: Boolean = false; private set

    fun setReadyForTesting(ready: Boolean) {
        isReady = ready
    }

    init {
        createBuffers(initialResolution)
    }

    fun updateResolution(newResolution: Int) {
        if (resolution == newResolution && isReady) return
        release()
        createBuffers(newResolution)
    }

    private fun createBuffers(size: Int) {
        resolution = size

        // 1. Generate FBO
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        // 2. Generate Color Texture for packed depth
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            size,
            size,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 3. Generate Depth Renderbuffer
        val depthBuffers = IntArray(1)
        GLES20.glGenRenderbuffers(1, depthBuffers, 0)
        depthBufferId = depthBuffers[0]

        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthBufferId)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, size, size)

        // 4. Attach to FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureId,
            0
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER,
            depthBufferId
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer incomplete, status: $status")
            isReady = false
        } else {
            isReady = true
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
    }

    fun bind() {
        if (!isReady) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, resolution, resolution)
        GLES20.glClearColor(1.0f, 1.0f, 1.0f, 1.0f) // 1.0 depth = far plane
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    }

    fun unbind(origScreenWidth: Int, origScreenHeight: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, origScreenWidth, origScreenHeight)
    }

    fun release() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (depthBufferId != 0) {
            GLES20.glDeleteRenderbuffers(1, intArrayOf(depthBufferId), 0)
            depthBufferId = 0
        }
        isReady = false
    }
}
