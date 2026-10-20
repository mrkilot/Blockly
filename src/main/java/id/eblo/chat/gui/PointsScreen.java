package id.eblo.chat.gui;

import id.eblo.chat.Config;
import id.eblo.chat.net.PointsClaimer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Opt-in switch for the channel-points bonus auto-claim. */
public class PointsScreen extends Screen {
    private final Screen parent;
    private EditBox token;
    private SecretField tokenEye;

    public PointsScreen(Screen parent) {
        super(Component.translatable("gui.eblochat.points.title"));
        this.parent = parent;
    }

    private static Component toggleLabel() {
        return Component.translatable("gui.eblochat.points.autoclaim").append(": ")
                .append(Component.translatable(Config.d.autoClaim ? "gui.eblochat.on" : "gui.eblochat.off"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(toggleLabel(), b -> {
            Config.d.autoClaim = !Config.d.autoClaim;
            b.setMessage(toggleLabel());
        }).bounds(cx - 100, 28, 200, 20).build());

        token = new EditBox(this.font, cx - 100, 70, 176, 18, Component.translatable("gui.eblochat.points.token"));
        token.setMaxLength(100);
        token.setValue(Config.d.webToken);
        // never show the token on screen (streams / screenshots) unless the eye button is toggled
        addRenderableWidget(token);
        tokenEye = SecretField.attach(token, cx + 78, 70, this::addRenderableWidget);

        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.done"), b -> onClose())
                .bounds(cx - 75, this.height - 28, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        if (tokenEye != null) tokenEye.draw(g);
        int cx = this.width / 2;
        g.text(this.font, this.title, cx - this.font.width(this.title) / 2, 10, 0xFFFFFFFF, true);
        g.text(this.font, Component.translatable("gui.eblochat.points.token"), cx - 100, 58, 0xFFAAAAAA, true);

        int y = 98;
        for (String key : new String[]{"gui.eblochat.points.info1", "gui.eblochat.points.info2",
                "gui.eblochat.points.warn1", "gui.eblochat.points.warn2"}) {
            boolean warn = key.contains("warn");
            List<FormattedCharSequence> lines = this.font.split(Component.translatable(key), Math.min(this.width - 20, 360));
            for (FormattedCharSequence l : lines) {
                g.text(this.font, l, cx - Math.min(this.width - 20, 360) / 2, y, warn ? 0xFFFFAA55 : 0xFFAAAAAA, true);
                y += 10;
            }
            y += 4;
        }
        String st = PointsClaimer.status();
        Component line = Component.translatable("gui.eblochat.points.status", PointsClaimer.claimed(), st.isEmpty() ? "-" : st);
        g.text(this.font, line, cx - this.font.width(line) / 2, this.height - 46, 0xFF88DDFF, true);
    }

    @Override
    public void onClose() {
        Config.d.webToken = token.getValue().trim();
        Config.save();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
