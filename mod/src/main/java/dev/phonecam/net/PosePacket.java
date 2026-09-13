package dev.phonecam.net;

/**
 * Latest pose snapshot from the phone (or mock sender).
 * Yaw/pitch are absolute degrees in Minecraft convention after PhoneCam transforms.
 * pos is relative translation in meters from the calibration anchor.
 */
public final class PosePacket {
    public final long timestampMs;
    public final float yaw;
    public final float pitch;
    public final float roll;
    public final float posX;
    public final float posY;
    public final float posZ;
    public final float zoom;
    public final String mode;

    public PosePacket(
            long timestampMs,
            float yaw,
            float pitch,
            float roll,
            float posX,
            float posY,
            float posZ,
            float zoom,
            String mode) {
        this.timestampMs = timestampMs;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.zoom = zoom;
        this.mode = mode == null ? "look" : mode;
    }

    @Override
    public String toString() {
        return "PosePacket{yaw=%.2f pitch=%.2f roll=%.2f pos=(%.3f,%.3f,%.3f) zoom=%.2f mode=%s}"
                .formatted(yaw, pitch, roll, posX, posY, posZ, zoom, mode);
    }
}
