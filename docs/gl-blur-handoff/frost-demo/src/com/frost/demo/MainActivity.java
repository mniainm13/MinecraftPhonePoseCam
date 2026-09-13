package com.frost.demo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 全屏 SurfaceView 动态场景 + 底栏胶囊 + 设置卡片，全部磨砂。 */
public final class MainActivity extends Activity {
    private static final String TAG = "FrostBlur";

    private GLSurfaceView glView;
    private SceneRenderer renderer;
    private FrostBlurView capsule;
    private FrostBlurView card;
    private int[] glOffset = new int[2];
    private Bitmap lastBm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new SceneRenderer(this);
        glView.setRenderer(renderer);
        root.addView(glView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        float d = getResources().getDisplayMetrics().density;

        // —— 设置卡片（右上） ——
        card = new FrostBlurView(this);
        card.setCornerDp(16);
        LinearLayout cardBox = new LinearLayout(this);
        cardBox.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * d);
        cardBox.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("画面设置");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        TextView sub = new TextView(this);
        sub.setText("磨砂背景 · 10fps 低频更新");
        sub.setTextColor(0xB3FFFFFF);
        sub.setTextSize(12);
        final Button toggle = new Button(this);
        toggle.setText("模糊：强");
        toggle.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                int lv = renderer.getLevel();
                int next = lv == 2 ? 1 : lv == 1 ? 0 : 2; // 强→弱→关→强
                renderer.setLevel(next);
                toggle.setText(next == 2 ? "模糊：强" : next == 1 ? "模糊：弱" : "模糊：关");
                if (next == 0) {
                    capsule.clearBlur();
                    card.clearBlur();
                }
                Log.d(TAG, "blur level=" + next);
            }
        });
        cardBox.addView(title);
        cardBox.addView(sub);
        cardBox.addView(toggle);
        final Button fps = new Button(this);
        fps.setText("帧率：10fps");
        fps.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                long cur = renderer.getIntervalMs();
                // 100→66→50→33ms，即 10→15→20→30fps 循环
                long next = cur == 100 ? 66 : cur == 66 ? 50 : cur == 50 ? 33 : 100;
                renderer.setIntervalMs(next);
                int f = (int) Math.round(1000.0 / next);
                fps.setText("帧率：" + f + "fps");
                Log.d(TAG, "blur interval=" + next + "ms");
            }
        });
        cardBox.addView(fps);
        card.addView(cardBox);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            (int) (220 * d), FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP | Gravity.END;
        cardLp.setMargins(0, (int) (16 * d), (int) (16 * d), 0);
        root.addView(card, cardLp);

        // —— 底栏胶囊（变焦/模式/校准/全屏） ——
        capsule = new FrostBlurView(this);
        capsule.setCornerDp(28);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String[] items = {"变焦", "模式", "校准", "全屏"};
        for (String s : items) {
            TextView tv = new TextView(this);
            tv.setText(s);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(15);
            int hp = (int) (18 * d), vp = (int) (12 * d);
            tv.setPadding(hp, vp, hp, vp);
            row.addView(tv);
        }
        capsule.addView(row);
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT);
        capLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        capLp.setMargins(0, 0, 0, (int) (28 * d));
        root.addView(capsule, capLp);

        setContentView(root);

        renderer.setBlurListener(new SceneRenderer.BlurListener() {
            @Override
            public void onBlur(final Bitmap bm, final int frameW, final int frameH) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pushBlur(bm, frameW, frameH);
                    }
                });
            }
        });
    }

    /** 把同一张全帧模糊图按各自位置分发给两个磨砂容器；上一帧两处都不用了再回收。 */
    private void pushBlur(Bitmap bm, int frameW, int frameH) {
        glView.getLocationOnScreen(glOffset);
        int[] p = new int[2];
        capsule.getLocationOnScreen(p);
        capsule.setBlur(bm, frameW, frameH,
            p[0] - glOffset[0], p[1] - glOffset[1]);
        card.getLocationOnScreen(p);
        card.setBlur(bm, frameW, frameH,
            p[0] - glOffset[0], p[1] - glOffset[1]);
        Bitmap old = lastBm;
        lastBm = bm;
        if (old != null && old != bm && !old.isRecycled()) old.recycle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }

    @Override
    protected void onPause() {
        glView.onPause();
        super.onPause();
    }
}
