package dev.phonecam.camera;

import dev.phonecam.PhoneCamClient;
import dev.phonecam.net.PosePacket;

/**
 * Pose buffer + render-time interpolation.
 * Phone sends ~60Hz; MC renders often faster — interpolate between last two packets.
 */
public final class CameraController {
    private static volatile boolean enabled;
    private static volatile boolean hasData;

    private static volatile float phoneYaw;
    private static volatile float phonePitch;
    private static volatile float phoneRoll;
    private static volatile float phonePosX;
    private static volatile float phonePosY;
    private static volatile float phonePosZ;
    private static volatile float phoneZoom = 1.0f;

    private static volatile float calibYaw;
    private static volatile float calibPitch;
    private static volatile float calibRoll;
    private static volatile boolean calibrated;

    private static volatile long lastPacketAt;

    // --- interpolation buffer (network thread writes, render thread reads) ---
    private static final class Sample {
        long tNs;
        float yaw, pitch, roll, x, y, z, zoom;
    }

    private static final Object bufLock = new Object();
    private static final Sample prev = new Sample();
    private static final Sample curr = new Sample();
    private static boolean bufInit;

    // Light exponential toward interpolated target (kills leftover jitter)
    private static final Object smoothLock = new Object();
    private static float smYaw, smPitch, smRoll, smX, smY, smZ;
    private static boolean smInit;
    private static long lastSmoothNs;
    private static float smoothAlpha = 0.55f;

    /** Extra ms to extrapolate past latest packet (compensates render vs packet phase). */
    private static final float EXTRAP_MS = 8f;

    public static void setSmoothAlpha(float a) {
        smoothAlpha = Math.max(0.05f, Math.min(1f, a));
    }

    public static float getSmoothAlpha() {
        return smoothAlpha;
    }

    private CameraController() {
    }

    public static void onPosePacket(PosePacket packet) {
        long now = System.nanoTime();
        synchronized (bufLock) {
            if (bufInit) {
                prev.tNs = curr.tNs;
                prev.yaw = curr.yaw;
                prev.pitch = curr.pitch;
                prev.roll = curr.roll;
                prev.x = curr.x;
                prev.y = curr.y;
                prev.z = curr.z;
                prev.zoom = curr.zoom;
            }
            curr.tNs = now;
            curr.yaw = packet.yaw;
            curr.pitch = packet.pitch;
            curr.roll = packet.roll;
            curr.x = packet.posX;
            curr.y = packet.posY;
            curr.z = packet.posZ;
            curr.zoom = packet.zoom <= 0 ? 1.0f : packet.zoom;
            bufInit = true;
        }
        phoneYaw = packet.yaw;
        phonePitch = packet.pitch;
        phoneRoll = packet.roll;
        phonePosX = packet.posX;
        phonePosY = packet.posY;
        phonePosZ = packet.posZ;
        float z = packet.zoom;
        // Only sanitize invalid values; range is owned by phone zoomMin/Max (no FOV clamp).
        phoneZoom = (z <= 0f || Float.isNaN(z) || Float.isInfinite(z)) ? 1.0f : z;
        curr.zoom = phoneZoom;
        lastPacketAt = System.currentTimeMillis();
        hasData = true;
    }

    /** Interpolate/extrapolate toward "now + EXTRAP_MS" using last two samples. */
    private static void tickSmooth() {
        long now = System.nanoTime();
        float ty, tp, tr, tx, tyy, tz;
        synchronized (bufLock) {
            if (!bufInit) {
                ty = viewYaw();
                tp = viewPitch();
                tr = viewRoll();
                tx = phonePosX;
                tyy = phonePosY;
                tz = phonePosZ;
            } else if (prev.tNs == 0 || curr.tNs == prev.tNs) {
                // only one sample — use it
                ty = curr.yaw;
                tp = curr.pitch;
                tr = curr.roll;
                tx = curr.x;
                tyy = curr.y;
                tz = curr.z;
            } else {
                float spanNs = curr.tNs - prev.tNs;
                // aim slightly ahead of latest packet for render latency
                float targetNs = now + (long) (EXTRAP_MS * 1_000_000f);
                float t = (targetNs - prev.tNs) / spanNs;
                t = clamp(t, 0f, 1.35f); // allow mild extrapolation
                ty = lerpAngle(prev.yaw, curr.yaw, t);
                tp = prev.pitch + (curr.pitch - prev.pitch) * t;
                tr = lerpAngle(prev.roll, curr.roll, t);
                tx = prev.x + (curr.x - prev.x) * t;
                tyy = prev.y + (curr.y - prev.y) * t;
                tz = prev.z + (curr.z - prev.z) * t;
            }
        }

        // Convert phone-abs → view-relative (calibration)
        float vy = viewYawFrom(ty);
        float vp = viewPitchFrom(tp);
        float vr = viewRollFrom(tr);

        synchronized (smoothLock) {
            if (!smInit) {
                smYaw = vy;
                smPitch = vp;
                smRoll = vr;
                smX = tx;
                smY = tyy;
                smZ = tz;
                smInit = true;
                lastSmoothNs = now;
                return;
            }
            float dt = (now - lastSmoothNs) * 1e-9f;
            lastSmoothNs = now;

            float dpx = tx - smX;
            float dpy = tyy - smY;
            float dpz = tz - smZ;
            float posJump = (float) Math.sqrt(dpx * dpx + dpy * dpy + dpz * dpz);
            float dyaw = vy - smYaw;
            while (dyaw > 180f) dyaw -= 360f;
            while (dyaw < -180f) dyaw += 360f;
            if (posJump > 0.35f || Math.abs(dyaw) > 25f || Math.abs(vp - smPitch) > 20f) {
                smYaw = vy;
                smPitch = vp;
                smRoll = vr;
                smX = tx;
                smY = tyy;
                smZ = tz;
                return;
            }

            float a = 1f - (float) Math.pow(1.0 - smoothAlpha, Math.max(dt * 60.0, 0.01));
            smYaw += a * dyaw;
            smPitch += a * (vp - smPitch);
            smRoll += a * (vr - smRoll);
            smX += a * dpx;
            smY += a * dpy;
            smZ += a * dpz;
        }
    }

    private static float lerpAngle(float a, float b, float t) {
        float d = b - a;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return a + d * t;
    }

    /** One coherent smoothed pose sample per frame (yaw/pitch/roll/pos). */
    public static final class Frame {
        public float yaw, pitch, roll, x, y, z;
    }

    /**
     * Tick smoothing once and snapshot all axes together so CameraMixin
     * never mixes frames (avoids axis tearing when only yaw called tickSmooth).
     */
    public static Frame smoothFrame() {
        tickSmooth();
        Frame f = new Frame();
        synchronized (smoothLock) {
            f.yaw = smYaw;
            f.pitch = smPitch;
            f.roll = smRoll;
            f.x = smX;
            f.y = smY;
            f.z = smZ;
        }
        return f;
    }

    /**
     * Disconnect fade: 1.0 while fresh, linear to 0 between 500–1500ms stale,
     * 0 after 1500ms (caller should restore vanilla camera).
     */
    public static float disconnectFade() {
        long age = dataAgeMs();
        if (age < 0) return 0f;
        if (age <= 500L) return 1f;
        if (age >= 1500L) return 0f;
        return 1f - (age - 500f) / 1000f;
    }

    @Deprecated
    public static float smoothYaw() {
        tickSmooth();
        return smYaw;
    }

    @Deprecated
    public static float smoothPitch() {
        return smPitch;
    }

    @Deprecated
    public static float smoothRoll() {
        return smRoll;
    }

    @Deprecated
    public static float smoothPosX() {
        return smX;
    }

    @Deprecated
    public static float smoothPosY() {
        return smY;
    }

    @Deprecated
    public static float smoothPosZ() {
        return smZ;
    }

    public static void resetSmooth() {
        synchronized (smoothLock) {
            smInit = false;
        }
        synchronized (bufLock) {
            bufInit = false;
            prev.tNs = 0;
        }
    }

    public static void toggle() {
        enabled = !enabled;
        if (enabled) resetSmooth();
        PhoneCamClient.LOGGER.info("PhoneCam tracking {}", enabled ? "ON" : "OFF");
    }

    public static boolean isEnabled() {
        return enabled && hasData;
    }

    public static boolean isTrackingEnabled() {
        return enabled;
    }

    public static void calibrate() {
        calibYaw = phoneYaw;
        calibPitch = phonePitch;
        calibRoll = phoneRoll;
        calibrated = true;
        resetSmooth();
    }

    public static long dataAgeMs() {
        return hasData ? System.currentTimeMillis() - lastPacketAt : -1;
    }

    private static float viewYawFrom(float phoneYawVal) {
        if (!calibrated) return phoneYawVal;
        float d = phoneYawVal - calibYaw;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    private static float viewPitchFrom(float phonePitchVal) {
        if (!calibrated) return phonePitchVal;
        return clamp(phonePitchVal - calibPitch, -90f, 90f);
    }

    private static float viewRollFrom(float phoneRollVal) {
        if (!calibrated) return phoneRollVal;
        float d = phoneRollVal - calibRoll;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    public static float viewYaw() {
        return viewYawFrom(phoneYaw);
    }

    public static float viewPitch() {
        return viewPitchFrom(phonePitch);
    }

    public static float viewRoll() {
        return viewRollFrom(phoneRoll);
    }

    public static float posX() {
        return phonePosX;
    }

    public static float posY() {
        return phonePosY;
    }

    public static float posZ() {
        return phonePosZ;
    }

    public static float zoom() {
        return phoneZoom;
    }

    public static String hudLine() {
        if (!enabled) return "PhoneCam OFF";
        if (!hasData) return "PhoneCam ON — no data";
        return "PhoneCam ON yaw=%.1f pitch=%.1f zoom=%.2f age=%dms"
                .formatted(viewYaw(), viewPitch(), phoneZoom, dataAgeMs());
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
