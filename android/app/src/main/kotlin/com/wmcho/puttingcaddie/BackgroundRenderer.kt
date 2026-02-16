package com.wmcho.puttingcaddie

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Frame
import com.google.ar.core.Coordinates2d
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {
    private var textureId = -1

    fun getTextureId(): Int = textureId
    private var backgroundShader = -1
    private var backgroundTextureId = -1
    private var backgroundMesh = -1
    private var texMatrixLocation = -1

    private var userRotationDeg = 0.0f
    private var mirrorHorizontal = false
    private var mirrorVertical = false
    private val texMatrix3 = FloatArray(9)

    fun setZoomLevel(zoom: Float) {
        // Deprecated path: kept for API compatibility but no longer used.
    }

    /**
     * lie-caddy-v1 style user adjustment: rotate/mirror preview at texture(UV) level.
     * rotationDeg: [-180..180] (positive rotates clockwise on screen; implemented as UV rotate -deg)
     */
    fun setUserAdjust(rotationDeg: Float, mirrorH: Boolean, mirrorV: Boolean) {
        userRotationDeg = rotationDeg
        mirrorHorizontal = mirrorH
        mirrorVertical = mirrorV
    }

    // Backward compatible setters (used by older code paths, kept for safety)
    fun setRotationAngle(angleDegrees: Float) = setUserAdjust(angleDegrees, mirrorHorizontal, mirrorVertical)
    fun setMirrorHorizontal(mirror: Boolean) = setUserAdjust(userRotationDeg, mirror, mirrorVertical)
    fun setMirrorVertical(mirror: Boolean) = setUserAdjust(userRotationDeg, mirrorHorizontal, mirror)

    private val backgroundVertices = floatArrayOf(
        -1.0f, -1.0f, 0.0f, 0.0f,
        1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f, 1.0f, 0.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 1.0f
    )

    // NDC quad coords for transformCoordinates2d (x,y per vertex).
    private val ndcQuadCoords: FloatBuffer =
        ByteBuffer.allocateDirect(2 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    // Updated per display geometry change.
    private val cameraTexCoords: FloatBuffer =
        ByteBuffer.allocateDirect(2 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat3 uTexMatrix;
        void main() {
            gl_Position = aPosition;
            vec3 t = uTexMatrix * vec3(aTexCoord.xy, 1.0);
            vTexCoord = t.xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            // IMPORTANT:
            // ARCore background UVs may be outside [0,1] to implement center-crop.
            // Some devices ignore wrap modes for samplerExternalOES, so clamp explicitly.
            vec2 uv = clamp(vTexCoord, 0.0, 1.0);
            gl_FragColor = texture2D(sTexture, uv);
        }
    """.trimIndent()

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )

        backgroundShader = createShaderProgram(vertexShaderCode, fragmentShaderCode)
        backgroundTextureId = GLES20.glGetUniformLocation(backgroundShader, "sTexture")
        texMatrixLocation = GLES20.glGetUniformLocation(backgroundShader, "uTexMatrix")

        val vertices =
            ByteBuffer.allocateDirect(backgroundVertices.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        vertices.put(backgroundVertices)
        vertices.position(0)

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        backgroundMesh = buffers[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, backgroundMesh)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            vertices.capacity() * 4,
            vertices,
            GLES20.GL_STATIC_DRAW
        )
    }

    fun draw(frame: Frame) {
        // Update UVs when display geometry changes (ARCore canonical mapping: fill-screen with crop).
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                ndcQuadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                cameraTexCoords
            )
            cameraTexCoords.position(0)
            updateInterleavedTexCoords(cameraTexCoords)
        }

        GLES20.glUseProgram(backgroundShader)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(backgroundTextureId, 0)
        updateTexMatrix()
        GLES20.glUniformMatrix3fv(texMatrixLocation, 1, false, texMatrix3, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, backgroundMesh)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, 8)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun createShaderProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link program: ${GLES20.glGetProgramInfoLog(program)}")
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    /**
     * Build 2D affine matrix for UV transform around center.
     * GLSL mat3 (column-major):
     * [ a c tx
     *   b d ty
     *   0 0 1 ]
     */
    private fun updateTexMatrix() {
        // lie_caddy_v1: user adjustment at texture level
        val m = UvAdjust.buildTexMatrix3(userRotationDeg, mirrorHorizontal, mirrorVertical)
        System.arraycopy(m, 0, texMatrix3, 0, 9)
    }

    private fun updateInterleavedTexCoords(texCoords: FloatBuffer) {
        // Rebuild interleaved buffer: (x,y,u,v) for 4 vertices
        val out = FloatArray(16)
        // positions from backgroundVertices, replace u,v
        for (i in 0 until 4) {
            out[i * 4] = backgroundVertices[i * 4]
            out[i * 4 + 1] = backgroundVertices[i * 4 + 1]
            out[i * 4 + 2] = texCoords.get(i * 2)
            out[i * 4 + 3] = texCoords.get(i * 2 + 1)
        }
        val fb = ByteBuffer.allocateDirect(out.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(out)
        fb.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, backgroundMesh)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, out.size * 4, fb)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

