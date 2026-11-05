package id.eblo.chat;

import com.mojang.blaze3d.platform.InputConstants;
import id.eblo.chat.gui.EbloChatScreen;
import id.eblo.chat.gui.HudEditScreen;
import id.eblo.chat.net.Emotes;
import id.eblo.chat.net.TwitchChat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EbloChatClient implements ClientModInitializer {
    public static final String MOD_ID = "eblochat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping openKey, hudKey;

    @Override
    public void onInitializeClient() {
        Config.load();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eblochat.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                new KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "general"))));

        hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.eblochat.hud_edit",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                new KeyMapping.Category(Identifier.fromNamespaceAndPath(MOD_ID, "general"))));

        // always-on chat widget on the HUD (moved / resized in HudEditScreen)
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "chat_hud"),
                (graphics, tickCounter) -> ChatHud.render(graphics));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (hudKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new HudEditScreen(null));
            }
            while (openKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new EbloChatScreen());
            }
        });

        Emotes.init();
        TwitchChat.start();
        for (String ch : Config.d.channels) TwitchChat.join(ch); // preloads history; IRC JOIN happens on connect
        LOGGER.info("EbloChat initialised ({} channels)", Config.d.channels.size());
    }
}
