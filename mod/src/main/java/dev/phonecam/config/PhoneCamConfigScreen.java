package dev.phonecam.config;

import dev.phonecam.PhoneCamClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable native MC config. Footer (save/cancel) is always visible.
 * Content scrolls with mouse wheel; no cloth-config dependency.
 */
public final class PhoneCamConfigScreen extends Screen {
    private static final int FIELD_W = 280;
    private static final int TITLE_H = 12;
    private static final int DESC_H = 10;
    private static final int INPUT_H = 18;
    private static final int BLOCK_GAP = 8;
    private static final int FOOTER_H = 28;
    private static final int TOP_PAD = 30;

    private final Screen parent;

    private TextFieldWidget portField;
    private TextFieldWidget bindField;
    private TextFieldWidget scaleField;
    private TextFieldWidget smoothField;
    private TextFieldWidget timeoutField;
    private TextFieldWidget zoomMinField;
    private TextFieldWidget zoomMaxField;
    private ButtonWidget autoBtn;
    private boolean autoTrack;

    /** Widgets that scroll with content (not footer). */
    private final List<net.minecraft.client.gui.Element> contentWidgets = new ArrayList<>();
    /** Unscrolled content height. */
    private int contentHeight;
    private int scrollY;

    public PhoneCamConfigScreen(Screen parent) {
        super(Text.literal("PhoneCam 设置"));
        this.parent = parent;
    }

    private int viewportHeight() {
        return Math.max(40, this.height - TOP_PAD - FOOTER_H - 8);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - viewportHeight());
    }

    private void applyScroll() {
        scrollY = MathHelper.clamp(scrollY, 0, maxScroll());
        // Reposition is done in initLayout via y0 - scrollY; just rebuild.
        clearAndInit();
    }

    private TextWidget titleLabel(String s, int x, int y, int w) {
        TextWidget t = new TextWidget(x, y, w, TITLE_H, Text.literal(s), this.textRenderer);
        t.setTextColor(0xE0E0E0);
        return t;
    }

    private TextWidget descLabel(String s, int x, int y, int w) {
        TextWidget t = new TextWidget(x, y, w, DESC_H, Text.literal(s), this.textRenderer);
        t.setTextColor(0x808080);
        return t;
    }

    private <T extends net.minecraft.client.gui.widget.ClickableWidget> T addContent(T widget) {
        this.addDrawableChild(widget);
        contentWidgets.add(widget);
        return widget;
    }

    /** title + desc + input at unscrolled y; returns next unscrolled y. */
    private int addField(String title, String desc, TextFieldWidget[] out, String value, int left, int y0, int w) {
        int y = y0 - scrollY;
        addContent(titleLabel(title, left, y, w));
        addContent(descLabel(desc, left, y + TITLE_H, w));
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, left, y + TITLE_H + DESC_H, w, INPUT_H, Text.literal(title));
        f.setText(value);
        addContent(f);
        out[0] = f;
        return y0 + TITLE_H + DESC_H + INPUT_H + BLOCK_GAP;
    }

    @Override
    protected void init() {
        contentWidgets.clear();
        PhoneCamConfig cfg = PhoneCamConfig.get();
        int left = this.width / 2 - FIELD_W / 2;

        // If first init, seed field values from config (clearAndInit would wipe)
        String port = portField != null ? portField.getText() : String.valueOf(cfg.udpPort);
        String bind = bindField != null ? bindField.getText() : cfg.bindAddress;
        String scale = scaleField != null ? scaleField.getText() : String.valueOf(cfg.positionScale);
        String smooth = smoothField != null ? smoothField.getText() : String.valueOf(cfg.smoothAlpha);
        String timeout = timeoutField != null ? timeoutField.getText() : String.valueOf(cfg.dataTimeoutMs);
        String zmin = zoomMinField != null ? zoomMinField.getText() : String.valueOf(cfg.zoomMin);
        String zmax = zoomMaxField != null ? zoomMaxField.getText() : String.valueOf(cfg.zoomMax);
        if (autoBtn == null) {
            autoTrack = cfg.trackingEnabledOnStart;
        }

        int y0 = TOP_PAD;
        TextFieldWidget[] tmp = new TextFieldWidget[1];

        y0 = addField("UDP 端口",
                "手机 App 发位姿的目标端口，默认 42424。改后需重启游戏。",
                tmp, port, left, y0, FIELD_W);
        portField = tmp[0];

        y0 = addField("绑定地址",
                "监听哪块网卡。0.0.0.0 = 全部网卡（推荐）。",
                tmp, bind, left, y0, FIELD_W);
        bindField = tmp[0];

        y0 = addField("位置尺度 (方块 / 米)",
                "手机移动 1 米 = 游戏内多少方块。调大位移更远。",
                tmp, scale, left, y0, FIELD_W);
        scaleField = tmp[0];

        y0 = addField("平滑 alpha (0.05 – 1.0)",
                "镜头跟随强度。小=更稳但滞后，大=跟手但可能抖。默认 0.65。",
                tmp, smooth, left, y0, FIELD_W);
        smoothField = tmp[0];

        y0 = addField("数据超时 (ms)",
                "超过该时长没收到位姿包，自动回原视角，防卡镜。",
                tmp, timeout, left, y0, FIELD_W);
        timeoutField = tmp[0];

        y0 = addField("缩放下限",
                "仅作记录；实际范围以手机设置为准。Mod 不再钳制 FOV。",
                tmp, zmin, left, y0, FIELD_W);
        zoomMinField = tmp[0];

        y0 = addField("缩放上限",
                "仅作记录；实际范围以手机设置为准。Mod 不再钳制 FOV。",
                tmp, zmax, left, y0, FIELD_W);
        zoomMaxField = tmp[0];

        int by = y0 - scrollY;
        autoBtn = ButtonWidget.builder(
                Text.literal("启动自动跟踪：" + (autoTrack ? "开" : "关")),
                b -> {
                    autoTrack = !autoTrack;
                    b.setMessage(Text.literal("启动自动跟踪：" + (autoTrack ? "开" : "关")));
                }
        ).dimensions(left, by, FIELD_W, INPUT_H).build();
        addContent(autoBtn);
        addContent(descLabel(
                "进游戏是否直接开跟踪；也可游戏内按 F8 手动开关。",
                left, by + INPUT_H + 2, FIELD_W));

        contentHeight = y0 + INPUT_H + 2 + DESC_H + 4;
        scrollY = MathHelper.clamp(scrollY, 0, maxScroll());

        // Fixed footer
        int fy = this.height - FOOTER_H - 6;
        int half = FIELD_W / 2 - 4;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("保存"), b -> saveAndClose())
                .dimensions(left, fy, half, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("取消"), b -> {
            if (client != null) client.setScreen(parent);
        }).dimensions(left + half + 8, fy, half, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll() <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int step = (int) (-verticalAmount * 24);
        int next = MathHelper.clamp(scrollY + step, 0, maxScroll());
        if (next != scrollY) {
            scrollY = next;
            clearAndInit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void saveAndClose() {
        PhoneCamConfig cfg = PhoneCamConfig.get();
        try {
            cfg.udpPort = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ignored) {
        }
        cfg.bindAddress = bindField.getText().trim();
        try {
            cfg.positionScale = Float.parseFloat(scaleField.getText().trim());
        } catch (NumberFormatException ignored) {
        }
        try {
            cfg.smoothAlpha = Float.parseFloat(smoothField.getText().trim());
        } catch (NumberFormatException ignored) {
        }
        try {
            cfg.dataTimeoutMs = Integer.parseInt(timeoutField.getText().trim());
        } catch (NumberFormatException ignored) {
        }
        try {
            float zmin = Float.parseFloat(zoomMinField.getText().trim());
            float zmax = Float.parseFloat(zoomMaxField.getText().trim());
            if (zmin > 0 && zmax > zmin) {
                cfg.zoomMin = zmin;
                cfg.zoomMax = zmax;
            }
        } catch (NumberFormatException ignored) {
        }
        cfg.trackingEnabledOnStart = autoTrack;
        cfg.save();
        PhoneCamClient.applyConfig();
        if (client != null) client.setScreen(parent);
    }

    /**
     * 1.21.2+ Screen.renderBackground applies a blur. Text fields blur again →
     * "Can only blur once per frame". Draw a plain dark fill instead.
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);

        int left = this.width / 2 - FIELD_W / 2;
        int top = TOP_PAD - 2;
        int bottom = this.height - FOOTER_H - 2;

        // clip content so fields don't draw over title/footer
        context.enableScissor(0, top, this.width, bottom);
        for (net.minecraft.client.gui.Element e : contentWidgets) {
            if (e instanceof net.minecraft.client.gui.Drawable d) {
                d.render(context, mouseX, mouseY, delta);
            }
        }
        context.disableScissor();

        // scrollbar hint
        if (maxScroll() > 0) {
            int trackX = left + FIELD_W + 6;
            int trackH = bottom - top;
            int thumbH = Math.max(20, trackH * viewportHeight() / Math.max(1, contentHeight));
            int thumbY = top + (trackH - thumbH) * scrollY / Math.max(1, maxScroll());
            context.fill(trackX, top, trackX + 3, bottom, 0x40202020);
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF8A9BB8);
        }

        // footer bar
        context.fill(0, this.height - FOOTER_H - 10, this.width, this.height, 0xC0141C2C);
        for (net.minecraft.client.gui.Element e : this.children()) {
            if (!contentWidgets.contains(e) && e instanceof net.minecraft.client.gui.Drawable d) {
                d.render(context, mouseX, mouseY, delta);
            }
        }

        // scroll hint
        if (maxScroll() > 0) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("滚轮滚动 · " + (scrollY * 100 / Math.max(1, maxScroll())) + "%"),
                    this.width / 2, this.height - FOOTER_H + 2, 0x808080);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
