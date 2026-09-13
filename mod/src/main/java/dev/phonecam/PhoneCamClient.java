package dev.phonecam;

import dev.phonecam.camera.CameraController;
import dev.phonecam.config.PhoneCamConfig;
import dev.phonecam.config.PhoneCamConfigScreen;
import dev.phonecam.net.PoseReceiver;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PhoneCamClient implements ClientModInitializer {
    public static final String MOD_ID = "phonecam";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static PoseReceiver receiver;
    private KeyBinding toggleKey;
    private KeyBinding calibrateKey;
    private KeyBinding configKey;

    @Override
    public void onInitializeClient() {
        PhoneCamConfig cfg = PhoneCamConfig.get();
        receiver = new PoseReceiver(cfg.udpPort, CameraController::onPosePacket);
        receiver.start();
        CameraController.setSmoothAlpha(cfg.smoothAlpha);

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phonecam.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category
        ));

        calibrateKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phonecam.calibrate",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                category
        ));

        configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.phonecam.config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                CameraController.toggle();
                boolean on = CameraController.isTrackingEnabled();
                // Level the player look so phone pitch is the only vertical source
                if (on && client.player != null) {
                    client.player.setPitch(0f);
                }
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal(on
                                    ? "[PhoneCam] ON — " + receiver.status()
                                    : "[PhoneCam] OFF"),
                            true);
                }
            }
            while (calibrateKey.wasPressed()) {
                CameraController.calibrate();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("[PhoneCam] Calibrated"), true);
                }
            }
            while (configKey.wasPressed()) {
                client.setScreen(new PhoneCamConfigScreen(client.currentScreen));
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> receiver.stop());

        if (cfg.trackingEnabledOnStart) {
            CameraController.toggle();
        }

        // Level pitch shortly after join when auto-track is on
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (cfg.trackingEnabledOnStart
                    && CameraController.isTrackingEnabled()
                    && client.player != null
                    && client.player.age == 1) {
                client.player.setPitch(0f);
            }
        });

        LOGGER.info("PhoneCam listening on UDP :{}", cfg.udpPort);
    }

    /** Called after config screen save — port change requires game restart for now. */
    public static void applyConfig() {
        PhoneCamConfig cfg = PhoneCamConfig.get();
        CameraController.setSmoothAlpha(cfg.smoothAlpha);
        LOGGER.info("PhoneCam config applied: udp={} smooth={}", cfg.udpPort, cfg.smoothAlpha);
    }

    public static PoseReceiver receiver() {
        return receiver;
    }
}
