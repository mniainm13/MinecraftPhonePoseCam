package dev.phonecam.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Simple JSON config under config/phonecam.json */
public final class PhoneCamConfig {
    public int udpPort = 42424;
    public String bindAddress = "0.0.0.0";
    public float positionScale = 1.0f;
    public float smoothAlpha = 0.65f;
    public int dataTimeoutMs = 500;
    public boolean trackingEnabledOnStart = false;
    public float zoomMin = 0.25f;
    public float zoomMax = 4.0f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PhoneCamConfig instance;

    public static PhoneCamConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("phonecam.json");
    }

    private static PhoneCamConfig load() {
        Path p = file();
        if (Files.exists(p)) {
            try {
                return GSON.fromJson(Files.readString(p), PhoneCamConfig.class);
            } catch (Exception e) {
                // fall through to default
            }
        }
        PhoneCamConfig c = new PhoneCamConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
