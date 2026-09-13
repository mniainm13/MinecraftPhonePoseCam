package dev.phonecam.app.ui

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Fullscreen quad + Kawase 4-tap + OES video scene shader. */
class KawaseFilter {
    companion object {
        private const val TAG = "FrostBlur"
    }

    private val vert = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main(){ vUV = aUV; gl_Position = vec4(aPos, 0.0, 1.0); }
    """.trimIndent()

    private val fragKawase = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D sTex;
        uniform vec2 uOffset;
        void main(){
          vec4 c = vec4(0.0);
          c += texture2D(sTex, vUV + uOffset * vec2( 1.0,  1.0));
          c += texture2D(sTex, vUV + uOffset * vec2(-1.0,  1.0));
          c += texture2D(sTex, vUV + uOffset * vec2( 1.0, -1.0));
          c += texture2D(sTex, vUV + uOffset * vec2(-1.0, -1.0));
          gl_FragColor = c * 0.25;
        }
    """.trimIndent()

    /** OES: optional 90° source rotate, center-crop, user rotate, then tex matrix. */
    private val fragVideoOes = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUV;
        uniform samplerExternalOES sTex;
        uniform mat4 uTexMatrix;
        uniform vec4 uCrop;
        uniform float uRot;
        uniform float uSrcRot90; // +1 = +90°, -1 = -90° (180° flip), 0 = off
        void main(){
          vec2 frameUV = mix(uCrop.xy, uCrop.zw, vUV);
          if (abs(uSrcRot90) > 0.5) {
            vec2 c = vec2(0.5, 0.5);
            vec2 p = frameUV - c;
            // +1: (x,y)->(y,-x); -1: (x,y)->(-y,x)
            frameUV = (uSrcRot90 > 0.0)
              ? vec2(p.y, -p.x) + c
              : vec2(-p.y, p.x) + c;
          }
          vec2 c2 = (uCrop.xy + uCrop.zw) * 0.5;
          vec2 q = frameUV - c2;
          float s = sin(uRot);
          float co = cos(uRot);
          q = vec2(q.x * co - q.y * s, q.x * s + q.y * co);
          frameUV = q + c2;
          vec2 texUV = (uTexMatrix * vec4(frameUV, 0.0, 1.0)).xy;
          vec4 col = texture2D(sTex, texUV);
          gl_FragColor = vec4(col.rgb, 1.0);
        }
    """.trimIndent()

    var progKawase = 0
        private set
    var progVideo = 0
        private set
    var aPosK = 0; var aUvK = 0; var sTexK = 0; var offK = 0
    var aPosV = 0; var aUvV = 0; var sTexV = 0; var matV = 0; var cropV = 0; var rotV = 0; var srcRot90V = 0

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        )).also { it.position(0) }

    fun init() {
        progKawase = link(vert, fragKawase)
        progVideo = link(vert, fragVideoOes)
        aPosK = GLES20.glGetAttribLocation(progKawase, "aPos")
        aUvK = GLES20.glGetAttribLocation(progKawase, "aUV")
        sTexK = GLES20.glGetUniformLocation(progKawase, "sTex")
        offK = GLES20.glGetUniformLocation(progKawase, "uOffset")
        aPosV = GLES20.glGetAttribLocation(progVideo, "aPos")
        aUvV = GLES20.glGetAttribLocation(progVideo, "aUV")
        sTexV = GLES20.glGetUniformLocation(progVideo, "sTex")
        matV = GLES20.glGetUniformLocation(progVideo, "uTexMatrix")
        cropV = GLES20.glGetUniformLocation(progVideo, "uCrop")
        rotV = GLES20.glGetUniformLocation(progVideo, "uRot")
        srcRot90V = GLES20.glGetUniformLocation(progVideo, "uSrcRot90")
    }

    fun blitTex2d(tex: Int, offX: Float, offY: Float) {
        GLES20.glUseProgram(progKawase)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPosK)
        GLES20.glVertexAttribPointer(aPosK, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(aUvK)
        GLES20.glVertexAttribPointer(aUvK, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(sTexK, 0)
        GLES20.glUniform2f(offK, offX, offY)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosK)
        GLES20.glDisableVertexAttribArray(aUvK)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun blitOes(oesTex: Int, texMatrix: FloatArray, crop: FloatArray, rotRad: Float, srcRot90: Float) {
        GLES20.glUseProgram(progVideo)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPosV)
        GLES20.glVertexAttribPointer(aPosV, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(aUvV)
        GLES20.glVertexAttribPointer(aUvV, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(sTexV, 0)
        GLES20.glUniformMatrix4fv(matV, 1, false, texMatrix, 0)
        if (cropV >= 0) GLES20.glUniform4f(cropV, crop[0], crop[1], crop[2], crop[3])
        if (rotV >= 0) GLES20.glUniform1f(rotV, rotRad)
        if (srcRot90V >= 0) GLES20.glUniform1f(srcRot90V, srcRot90)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosV)
        GLES20.glDisableVertexAttribArray(aUvV)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "shader fail: " + GLES20.glGetShaderInfoLog(s))
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun link(v: String, f: String): Int {
        val p = GLES20.glCreateProgram()
        val vs = shader(GLES20.GL_VERTEX_SHADER, v)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, f)
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "link fail: " + GLES20.glGetProgramInfoLog(p))
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }
}
