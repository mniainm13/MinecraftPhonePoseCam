package com.frost.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 磨砂容器：先画模糊背景（从整帧小图按本 View 位置裁剪）+ tint，再画子 View。
 * 模糊关/未到时画半透明兜底色，保证任何情况可读。
 */
public final class FrostBlurView extends FrameLayout {
    private Bitmap blur;
    private int frameW = 1, frameH = 1;
    private final float[] pos = new float[2]; // 本 View 在 GL 画面中的左上角（px）
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint();
    private final Path clip = new Path();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private float cornerDp = 20;
    private int tintColor = 0x40000000;
    private int fallbackColor = 0x99000000;

    public FrostBlurView(Context c) { super(c); init(); }
    public FrostBlurView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        cornerDp = 20 * d;
        tintPaint.setColor(tintColor);
    }

    public void setCornerDp(float dp) {
        cornerDp = dp * getResources().getDisplayMetrics().density;
        invalidate();
    }

    /** GL 线程 10Hz 回调的全帧模糊小图 + 当前画面尺寸。pos 为本 View 左上在画面中的位置。
     *  注意：多块磨砂共用同一张图，此处不 recycle，由 MainActivity 统一回收上一帧。 */
    public void setBlur(Bitmap bm, int frameW, int frameH, float leftInFrame, float topInFrame) {
        blur = bm;
        this.frameW = Math.max(1, frameW);
        this.frameH = Math.max(1, frameH);
        pos[0] = leftInFrame;
        pos[1] = topInFrame;
        postInvalidate();
    }

    public void clearBlur() {
        if (blur != null) { blur.recycle(); blur = null; }
        postInvalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w > 0 && h > 0) {
            dst.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(dst, cornerDp, cornerDp, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            if (blur != null && !blur.isRecycled()) {
                int bw = blur.getWidth(), bh = blur.getHeight();
                src.set(
                    (int) (pos[0] / frameW * bw),
                    (int) (pos[1] / frameH * bh),
                    (int) ((pos[0] + w) / frameW * bw),
                    (int) ((pos[1] + h) / frameH * bh));
                src.intersect(0, 0, bw, bh);
                canvas.drawBitmap(blur, src, dst, paint);
                canvas.drawRect(dst, tintPaint);
            } else {
                canvas.drawColor(fallbackColor);
            }
            canvas.restore();
        }
        super.dispatchDraw(canvas);
    }
}
