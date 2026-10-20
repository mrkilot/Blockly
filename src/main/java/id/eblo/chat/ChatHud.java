package id.eblo.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Always-on chat window drawn on the HUD (like a minimap widget). Position, size, text scale and fade-out are
 * set in {@link id.eblo.chat.gui.HudEditScreen} (position presets + sliders). The main chat screen is still used for typing and tabs.
 */
public final class ChatHud {
    public static final int LH = 9;
    public static final int MIN_W = 80, MIN_H = 40;
    private static final long FADE_MS = 1500;   // length of the fade-out itself

    private record Cached(int rev, int width, boolean showChannel, int emoVer, List<List<ChatLayout.Seg>> lines) {}

    private static final Map<ChatMessage, Cached> CACHE = new HashMap<>();

    private ChatHud() {}

    public static final int ANCHORS = 8;
    private static final int MARGIN = 4, HOTBAR_GAP = 40;

    /** Screen-space rectangle {x, y, w, h} of the widget for the chosen anchor, clamped to the window. */
    public static int[] rect(int screenW, int screenH) {
        Config.Data d = Config.d;
        int w = Math.max(MIN_W, Math.min(d.hudW, screenW));
        int h = Math.max(MIN_H, Math.min(d.hudH, screenH));
        int a = Math.max(0, Math.min(ANCHORS - 1, d.hudAnchor));
        int col = switch (a) { case 0, 3, 5 -> 0; case 1, 6 -> 1; default -> 2; };   // left / centre / right
        int row = switch (a) { case 0, 1, 2 -> 0; case 3, 4 -> 1; default -> 2; };   // top / middle / bottom
        int x = col == 0 ? MARGIN : col == 1 ? (screenW - w) / 2 : screenW - w - MARGIN;
        int y = row == 0 ? MARGIN : row == 1 ? (screenH - h) / 2 : screenH - h - HOTBAR_GAP;
        x = Math.max(0, Math.min(x, screenW - w));
        y = Math.max(0, Math.min(y, screenH - h));
        return new int[]{x, y, w, h};
    }

    /** Called from the HUD element every frame. */
    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.d.hudEnabled || mc.player == null || mc.options.hideGui) return;
        if (mc.screen instanceof id.eblo.chat.gui.EbloChatScreen) return; // the full window is already open
        try {
            draw(g, mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), false);
        } catch (Exception e) {
            EbloChatClient.LOGGER.debug("HUD draw failed", e);
        }
    }

    /** 1 = fully visible, 0 = gone. Never fades in preview / edit mode. */
    private static float fade(ChatMessage m, long now, boolean preview) {
        int sec = Config.d.hudFade;
        if (sec <= 0 || preview) return 1F;
        long age = now - m.shown - sec * 1000L;
        if (age <= 0) return 1F;
        return Math.max(0F, 1F - age / (float) FADE_MS);
    }

    private static int argb(float alpha, int rgb) {
        return (Math.round(Math.max(0F, Math.min(1F, alpha)) * 255F) << 24) | (rgb & 0xFFFFFF);
    }

    /** Draws the widget; {@code preview} = edit mode (no fade-out, placeholder text when empty). */
    public static void draw(GuiGraphicsExtractor g, Minecraft mc, int sw, int sh, boolean preview) {
        Font font = mc.font;
        int[] r = rect(sw, sh);
        int x = r[0], y = r[1], w = r[2], h = r[3];

        float sc = Math.max(0.5F, Math.min(3F, Config.d.hudScale / 100F));
        int pad = 3;
        int wrapW = Math.max(20, Math.round((w - pad * 2) / sc));          // wrap width in text units
        int visible = Math.max(1, (int) ((h - pad * 2) / (LH * sc)));
        boolean showChannel = Config.d.hudAllChannels && Config.d.channels.size() > 1;
        long now = System.currentTimeMillis();

        List<ChatMessage> msgs = ChatStore.recent(visible + 5, Config.d.hudAllChannels);
        List<List<ChatLayout.Seg>> lines = new ArrayList<>();
        List<ChatMessage> owners = new ArrayList<>();
        List<Float> alphas = new ArrayList<>();
        float best = 0F;
        for (int i = msgs.size() - 1; i >= 0 && lines.size() < visible; i--) {
            ChatMessage m = msgs.get(i);
            float a = fade(m, now, preview);
            if (a <= 0.02F) continue;                 // fully faded (and so is everything older)
            Cached c = layout(font, m, wrapW, showChannel);
            for (int j = c.lines.size() - 1; j >= 0 && lines.size() < visible; j--) {
                lines.add(c.lines.get(j));
                owners.add(m);
                alphas.add(a);
            }
            best = Math.max(best, a);
        }

        boolean fading = Config.d.hudFade > 0 && !preview;
        float boxA = fading ? best : 1F;
        if (boxA > 0.02F) {
            g.fill(x, y, x + w, y + h, argb(boxA * Config.d.hudOpacity / 100F, 0));
        }
        if (lines.isEmpty()) {
            if (preview) g.text(font, Component.translatable("gui.eblochat.hud.preview"), x + pad, y + pad, 0xFFAAAAAA, true);
            return;
        }

        g.enableScissor(x, y, x + w, y + h);
        g.pose().pushMatrix();
        g.pose().translate(x + pad, y + h - pad);
        g.pose().scale(sc, sc);
        int padU = Math.round(pad / sc);
        for (int k = 0; k < lines.size(); k++) {
            int ly = -LH * (k + 1);
            ChatMessage m = owners.get(k);
            float a = alphas.get(k);
            int bg = m.mention ? 0xFF8800 : m.highlight ? 0x00AAFF : m.event ? 0x9146FF : -1;
            if (bg >= 0) {
                float base = m.mention ? 0.33F : 0.27F;
                g.fill(-padU, ly - 1, wrapW + padU, ly + LH - 1, argb(base * a, bg));
            }
            if (a > 0.02F) ChatLayout.drawRow(g, font, lines.get(k), 0, ly, argb(a, 0xFFFFFF));
        }
        g.pose().popMatrix();
        g.disableScissor();
    }

    private static Cached layout(Font font, ChatMessage m, int wrapW, boolean showChannel) {
        Cached c = CACHE.get(m);
        int ev = id.eblo.chat.net.Emotes.version();
        if (c != null && c.rev == m.rev && c.width == wrapW && c.showChannel == showChannel && c.emoVer == ev) return c;
        c = new Cached(m.rev, wrapW, showChannel, ev, ChatLayout.layout(font, m, wrapW, showChannel).rows());
        if (CACHE.size() > 600) CACHE.clear();
        CACHE.put(m, c);
        return c;
    }
}
