package dev.phonecam.mixin;

import dev.phonecam.camera.CameraController;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phone pose offset on vanilla camera.
 * Position is applied relative to the entity eye (fresh each frame) so look
 * orbits from the moved camera, not a stale/orbit-around-player point.
 * On disconnect: fade 500–1500ms toward identity, then fully release.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    private Quaternionf rotation;

    @Inject(method = "update", at = @At("TAIL"))
    private void phonecam$applyPose(
            BlockView area,
            Entity focusedEntity,
            boolean thirdPerson,
            boolean inverseView,
            float tickDelta,
            CallbackInfo ci) {
        if (!CameraController.isTrackingEnabled() || focusedEntity == null) {
            return;
        }
        float fade = CameraController.disconnectFade();
        if (fade <= 0f) {
            // Fully stale — leave vanilla camera (isTrackingEnabled may still be ON).
            return;
        }

        CameraController.Frame pose = CameraController.smoothFrame();
        float baseYaw = focusedEntity.getYaw(tickDelta);
        float basePitch = focusedEntity.getPitch(tickDelta);
        float yaw = baseYaw + pose.yaw * fade;
        float pitch = MathHelper.clamp(basePitch + pose.pitch * fade, -90f, 90f);

        // Move camera first (from entity eye), then rotate around that point.
        Vec3d eye = new Vec3d(
                MathHelper.lerp(tickDelta, focusedEntity.lastX, focusedEntity.getX()),
                MathHelper.lerp(tickDelta, focusedEntity.lastY, focusedEntity.getY())
                        + focusedEntity.getEyeHeight(focusedEntity.getPose()),
                MathHelper.lerp(tickDelta, focusedEntity.lastZ, focusedEntity.getZ()));

        float dx = pose.x * fade;
        float dy = pose.y * fade;
        float dz = pose.z * fade;
        Vec3d camPos = eye;
        if (dx != 0f || dy != 0f || dz != 0f) {
            // Phone local → world using player yaw (not phone yaw)
            double rad = Math.toRadians(baseYaw);
            double worldX = eye.x + (dx * Math.cos(rad) - dz * Math.sin(rad));
            double worldY = eye.y + dy;
            double worldZ = eye.z + (dx * Math.sin(rad) + dz * Math.cos(rad));
            camPos = new Vec3d(worldX, worldY, worldZ);
        }
        this.setPos(camPos);
        this.setRotation(yaw, pitch);

        float roll = pose.roll * fade;
        if (Math.abs(roll) > 0.05f) {
            this.rotation.rotateZ((float) Math.toRadians(-roll));
        }
    }
}
