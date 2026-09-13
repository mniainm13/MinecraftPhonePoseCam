package dev.phonecam.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Optional Mod Menu hook — only loaded if Mod Menu is installed. */
public final class PhoneCamModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return PhoneCamConfigScreen::new;
    }
}
