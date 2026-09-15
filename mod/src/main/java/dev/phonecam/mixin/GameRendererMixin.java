package dev.phonecam.mixin;

import dev.phonecam.camera.CameraController;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Apply phone zoom by scaling FOV. zoom 1.0 = vanilla; higher zoom narrows FOV.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void phonecam$applyZoom(
            Camera camera,
            float tickDelta,
            boolean changingFov,
            CallbackInfoReturnable<Float> cir) {
        if (!CameraController.isTrackingEnabled()) {
            return;
        }
        if (CameraController.disconnectFade() <= 0f) {
            return;
        }
        float zoom = CameraController.zoom();
        if (zoom <= 0f || Math.abs(zoom - 1f) < 1e-3f) {
            return;
        }
        float fov = cir.getReturnValue();
        // zoom 1 → 1x, zoom 2 → 0.5x FOV, zoom 0.5 → wider.
        // No hard FOV floor: phone zoomMin/zoomMax owns the range.
        cir.setReturnValue(fov / zoom);
    }
}
