package com.frost.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLSurfaceView（本身就是 SurfaceView）渲染动态场景，
 * 旁路：scene → D0(1/2) → D1(1/4) → U0(1/2) → OUT(1/4)，
 * 10Hz 把 OUT 读回 Bitmap 供胶囊/卡片当磨砂背景。
 */
public final class SceneRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "FrostBlur";

    /** 模糊节拍档位：更新间隔 ms（对应 10/15/20/30fps），卡片上可切 */
    private volatile long intervalMs = 100;
    public void setIntervalMs(long ms) { intervalMs = ms; }
    public long getIntervalMs() { return intervalMs; }

    public interface BlurListener {
        void onBlur(Bitmap bm, int viewW, int viewH);
    }

    private final Context ctx;
    private final KawaseFilter kf = new KawaseFilter();
    private volatile BlurListener listener;
    private volatile int level = 2; // 0=关 1=弱 2=强

    private int viewW, viewH;
    private int sceneTex;
    private long startNs;
    private long lastBlurMs;
    private float timeSec;

    // FBO 链：D0(1/2) D1(1/4) U0(1/2) OUT(1/4)。
    // D0 直接由场景 shader 在半分辨率画出（= 下采样），屏幕每帧照常直画，
    // 模糊节拍外零额外全屏开销。
    private final int[] fbo = new int[4];
    private final int[] tex = new int[4];
    private final int[] fw = new int[4];
    private final int[] fh = new int[4];

    private double avgMs = -1;
    private int blurSeq;
    private int overCount;

    public SceneRenderer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setBlurListener(BlurListener l) { listener = l; }
    public void setLevel(int lv) { level = lv; }
    public int getLevel() { return level; }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        kf.init();
        sceneTex = loadTexture();
        for (int i = 0; i < 4; i++) { fbo[i] = 0; tex[i] = 0; }
        startNs = System.nanoTime();
        lastBlurMs = 0;
        avgMs = -1;
        blurSeq = 0;
        overCount = 0;
        GLES20.glClearColor(0f, 0f, 0f, 1f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        viewW = w;
        viewH = h;
        buildChain(w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        timeSec = (System.nanoTime() - startNs) / 1e9f;

        // 主路径：场景直画屏幕（与 v1.0 同开销）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        kf.blit(sceneTex, true, timeSec, 0, 0);

        // 旁路：低频模糊
        long now = System.currentTimeMillis();
        BlurListener l = listener;
        if (l != null && level > 0 && now - lastBlurMs >= intervalMs) {
            lastBlurMs = now;
            long t0 = System.nanoTime();
            runBlurChain();
            Bitmap bm = readOut();
            double ms = (System.nanoTime() - t0) / 1e6;
            avgMs = avgMs < 0 ? ms : avgMs * 0.85 + ms * 0.15;
            blurSeq++;
            Log.d(TAG, "blur seq=" + blurSeq
                + " avg=" + String.format("%.1f", avgMs)
                + "ms radius=" + level
                + " out=" + fw[3] + "x" + fh[3]);
            if (bm != null) l.onBlur(bm, viewW, viewH);
            // 自动降级：跳过前 30 帧预热抖动，连续 3 次超 8ms 才降档
            if (avgMs > 8 && level > 1 && blurSeq > 30) {
                if (++overCount >= 3) {
                    level = 1;
                    overCount = 0;
                    Log.d(TAG, "blur auto-degrade to radius=1");
                }
            } else {
                overCount = 0;
            }
        }
    }

    private void runBlurChain() {
        float k = level == 2 ? 1.0f : 0.5f;
        // D0：场景 shader 直画半分辨率（动画帧采样 + 下采样一次完成）
        bindTarget(0);
        kf.blit(sceneTex, true, timeSec, 0, 0);
        // D0 → D1(1/4)
        bindTarget(1);
        kf.blit(tex[0], false, 0, 2.0f * k / fw[1], 2.0f * k / fh[1]);
        // D1 → U0(1/2) 上采样+糊
        bindTarget(2);
        kf.blit(tex[1], false, 0, 1.0f * k / fw[2], 1.0f * k / fh[2]);
        // U0 → OUT(1/4)
        bindTarget(3);
        kf.blit(tex[2], false, 0, 1.0f * k / fw[3], 1.0f * k / fh[3]);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void bindTarget(int i) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
        GLES20.glViewport(0, 0, fw[i], fh[i]);
    }

    private Bitmap readOut() {
        int w = fw[3], h = fh[3];
        if (w <= 0 || h <= 0) return null;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[3]);
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        int[] px = new int[w * h];
        buf.asIntBuffer().get(px);
        // GL 左下原点 → Bitmap 左上：上下翻转；RGBA→ARGB 位移
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                int r = (p) & 0xFF, g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF, a = (p >>> 24);
                out[(h - 1 - y) * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    private void buildChain(int w, int h) {
        int[] divs = {2, 4, 2, 4};
        int[] fb = new int[4];
        int[] tx = new int[4];
        GLES20.glGenFramebuffers(4, fb, 0);
        GLES20.glGenTextures(4, tx, 0);
        for (int i = 0; i < 4; i++) {
            if (fbo[i] != 0) GLES20.glDeleteFramebuffers(1, fbo, i);
            if (tex[i] != 0) GLES20.glDeleteTextures(1, tex, i);
            fbo[i] = fb[i];
            tex[i] = tx[i];
            fw[i] = Math.max(1, w / divs[i]);
            fh[i] = Math.max(1, h / divs[i]);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw[i], fh[i],
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[i], 0);
            int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "fbo incomplete: " + st);
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private int loadTexture() {
        Bitmap bm = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.bg_scene);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        bm.recycle();
        return t[0];
    }
}
