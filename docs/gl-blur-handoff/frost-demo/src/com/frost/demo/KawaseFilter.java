package com.frost.demo;

import android.opengl.GLES20;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** 最小全屏四边形 + 单 Kawase 程序（down/up 共用 4-tap 核，靠 uOffset 控半径）。 */
public final class KawaseFilter {
    private static final String TAG = "FrostBlur";

    private static final String VERT =
        "attribute vec2 aPos;\n" +
        "attribute vec2 aUV;\n" +
        "varying vec2 vUV;\n" +
        "void main(){ vUV = aUV; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    // Kawase 4-tap：4 角采样平均。down 用大 offset，up 用小 offset。
    private static final String FRAG_KAWASE =
        "precision mediump float;\n" +
        "varying vec2 vUV;\n" +
        "uniform sampler2D sTex;\n" +
        "uniform vec2 uOffset;\n" +
        "void main(){\n" +
        "  vec4 c = vec4(0.0);\n" +
        "  c += texture2D(sTex, vUV + uOffset * vec2( 1.0,  1.0));\n" +
        "  c += texture2D(sTex, vUV + uOffset * vec2(-1.0,  1.0));\n" +
        "  c += texture2D(sTex, vUV + uOffset * vec2( 1.0, -1.0));\n" +
        "  c += texture2D(sTex, vUV + uOffset * vec2(-1.0, -1.0));\n" +
        "  gl_FragColor = c * 0.25;\n" +
        "}\n";

    // 模拟串流画面：底图快平移 + 波浪 + 扫光条 + 呼吸，明眼可见地动。
    private static final String FRAG_SCENE =
        "precision mediump float;\n" +
        "varying vec2 vUV;\n" +
        "uniform sampler2D sTex;\n" +
        "uniform float uTime;\n" +
        "void main(){\n" +
        "  vec2 uv = vUV;\n" +
        "  uv.x += uTime * 0.15;\n" +
        "  uv.y += uTime * 0.09;\n" +
        "  uv.x += 0.012 * sin(uv.y * 22.0 + uTime * 2.0);\n" +
        "  vec3 c = texture2D(sTex, fract(uv)).rgb;\n" +
        "  float bx = fract(uTime * 0.25);\n" +
        "  float dd = abs(fract(vUV.x - bx + 0.5) - 0.5);\n" +
        "  float bar = smoothstep(0.06, 0.0, dd);\n" +
        "  c += bar * vec3(0.9, 0.95, 1.0) * 0.9;\n" +
        "  c *= 0.92 + 0.08 * sin(uTime * 3.0);\n" +
        "  float d = distance(vUV, vec2(0.5, 0.5));\n" +
        "  c *= 1.0 - 0.45 * d * d;\n" +
        "  gl_FragColor = vec4(c, 1.0);\n" +
        "}\n";

    public int progKawase;
    public int progScene;
    public int aPosK, aUvK, sTexK, offK;
    public int aPosS, aUvS, sTexS, timeS;

    private final FloatBuffer quad;

    public KawaseFilter() {
        float[] q = {
            -1f, -1f,  0f, 0f,
             1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
             1f,  1f,  1f, 1f,
        };
        quad = ByteBuffer.allocateDirect(q.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(q);
        quad.position(0);
    }

    public void init() {
        progKawase = link(VERT, FRAG_KAWASE);
        progScene = link(VERT, FRAG_SCENE);
        aPosK = GLES20.glGetAttribLocation(progKawase, "aPos");
        aUvK = GLES20.glGetAttribLocation(progKawase, "aUV");
        sTexK = GLES20.glGetUniformLocation(progKawase, "sTex");
        offK = GLES20.glGetUniformLocation(progKawase, "uOffset");
        aPosS = GLES20.glGetAttribLocation(progScene, "aPos");
        aUvS = GLES20.glGetAttribLocation(progScene, "aUV");
        sTexS = GLES20.glGetUniformLocation(progScene, "sTex");
        timeS = GLES20.glGetUniformLocation(progScene, "uTime");
    }

    /** 把纹理 tex 画到当前绑定的 FBO/屏幕。isScene=false 时走 Kawase 核。 */
    public void blit(int tex, boolean isScene, float time, float offX, float offY) {
        int prog = isScene ? progScene : progKawase;
        int aPos = isScene ? aPosS : aPosK;
        int aUV = isScene ? aUvS : aUvK;
        GLES20.glUseProgram(prog);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        if (isScene) {
            GLES20.glUniform1i(sTexS, 0);
            GLES20.glUniform1f(timeS, time);
        } else {
            GLES20.glUniform1i(sTexK, 0);
            GLES20.glUniform2f(offK, offX, offY);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aUV);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "shader fail: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static int link(String v, String f) {
        int p = GLES20.glCreateProgram();
        int vs = shader(GLES20.GL_VERTEX_SHADER, v);
        int fs = shader(GLES20.GL_FRAGMENT_SHADER, f);
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "link fail: " + GLES20.glGetProgramInfoLog(p));
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }
}
