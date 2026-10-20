package id.eblo.chat.gui;

import id.eblo.chat.ChatHud;
import id.eblo.chat.ChatStore;
import id.eblo.chat.Config;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Settings for the HUD chat widget: pick one of 8 positions and set the size with sliders; the widget behind
 * this screen updates live. Everything is saved when the screen is closed.
 */
public class HudEditScreen extends Screen {
    private static final int[] FADE_STEPS = {0, 5, 10, 20, 30, 60};
    private static final int[] SCALE_STEPS = {75, 100, 125, 150, 200};

    /** Integer slider with a live label. */
    private static final class IntSlider extends AbstractSliderButton {
        private final int min, max;
        private final IntConsumer set;
        private final IntFunction<Component> label;

        IntSlider(int x, int y, int w, int min, int max, int value, IntFunction<Component> label, IntConsumer set) {
            super(x, y, w, 20, label.apply(value), max == min ? 0 : (value - min) / (double) (max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.set = set;
        }

        private int current() {
            return min + (int) Math.round(this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(label.apply(current()));
        }

        @Override
        protected void applyValue() {
            set.accept(current());
        }
    }

    private final Screen parent;

    public HudEditScreen(Screen parent) {
        super(Component.translatable("gui.eblochat.hud.title"));
        this.parent = parent;
    }

    private static int next(int[] steps, int cur) {
        for (int v : steps) if (v > cur) return v;
        return steps[0];
    }

    private static Component onOff(String key, boolean on) {
        return Component.translatable(key).append(": ").append(Component.translatable(on ? "gui.eblochat.on" : "gui.eblochat.off"));
    }

    private static Component posLabel() {
        return Component.translatable("gui.eblochat.hud.pos", Component.translatable("gui.eblochat.hud.pos." + Config.d.hudAnchor));
    }

    private static Component sourceLabel() {
        return Component.translatable(Config.d.hudAllChannels ? "gui.eblochat.hud.all" : "gui.eblochat.hud.selected");
    }

    private static Component fadeLabel() {
        return Config.d.hudFade <= 0
                ? Component.translatable("gui.eblochat.hud.fade_off")
                : Component.translatable("gui.eblochat.hud.fade", Config.d.hudFade + "s");
    }

    @Override
    protected void init() {
        Config.Data d = Config.d;
        int cx = this.width / 2, lx = cx - 155, rx = cx + 5, bw = 150, step = 24;
        int y = 30;

        // left column: where and how big
        addRenderableWidget(Button.builder(posLabel(), b -> {
            d.hudAnchor = (d.hudAnchor + 1) % ChatHud.ANCHORS;
            b.setMessage(posLabel());
        }).bounds(lx, y, bw, 20).build());
        addRenderableWidget(new IntSlider(lx, y + step, bw, ChatHud.MIN_W, Math.max(ChatHud.MIN_W, this.width - 8), d.hudW,
                v -> Component.translatable("gui.eblochat.hud.width", v), v -> d.hudW = v));
        addRenderableWidget(new IntSlider(lx, y + step * 2, bw, ChatHud.MIN_H, Math.max(ChatHud.MIN_H, this.height - 8), d.hudH,
                v -> Component.translatable("gui.eblochat.hud.height", v), v -> d.hudH = v));
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.hud.font", d.hudScale), b -> {
            d.hudScale = next(SCALE_STEPS, d.hudScale);
            b.setMessage(Component.translatable("gui.eblochat.hud.font", d.hudScale));
        }).bounds(lx, y + step * 3, bw, 20).build());

        // right column: look and behaviour
        addRenderableWidget(Button.builder(onOff("gui.eblochat.hud.enabled", d.hudEnabled), b -> {
            d.hudEnabled = !d.hudEnabled;
            b.setMessage(onOff("gui.eblochat.hud.enabled", d.hudEnabled));
        }).bounds(rx, y, bw, 20).build());
        addRenderableWidget(Button.builder(sourceLabel(), b -> {
            d.hudAllChannels = !d.hudAllChannels;
            b.setMessage(sourceLabel());
        }).bounds(rx, y + step, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.hud.opacity", d.hudOpacity), b -> {
            d.hudOpacity = d.hudOpacity >= 100 ? 0 : d.hudOpacity + 20;
            b.setMessage(Component.translatable("gui.eblochat.hud.opacity", d.hudOpacity));
        }).bounds(rx, y + step * 2, bw, 20).build());
        addRenderableWidget(Button.builder(fadeLabel(), b -> {
            d.hudFade = next(FADE_STEPS, d.hudFade);
            b.setMessage(fadeLabel());
        }).bounds(rx, y + step * 3, bw, 20).build());

        // bottom: reset + done
        int by = this.height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.hud.reset"), b -> {
            d.hudAnchor = 5;
            d.hudW = 230;
            d.hudH = 110;
            d.hudScale = 100;
            d.hudFade = 0;
            d.hudOpacity = 40;
            rebuildWidgets();
        }).bounds(lx, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(rx, by, bw, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // while the HUD is switched off the HUD element draws nothing, so show a preview of the box here
        if (!Config.d.hudEnabled) ChatHud.draw(g, this.minecraft, this.width, this.height, true);

        int[] r = ChatHud.rect(this.width, this.height);
        int x = r[0], y = r[1], w = r[2], h = r[3];
        if (Config.d.hudEnabled && ChatStore.recent(1, Config.d.hudAllChannels).isEmpty()) {
            g.text(this.font, Component.translatable("gui.eblochat.hud.preview"), x + 3, y + 3, 0x66FFFFFF, false);
        }
        int border = 0xFF9146FF; // outline so the box is visible even when empty / transparent
        g.fill(x - 1, y - 1, x + w + 1, y, border);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, border);
        g.fill(x - 1, y, x, y + h, border);
        g.fill(x + w, y, x + w + 1, y + h, border);

        g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 8, 0xFFFFFFFF, true);
        Component hint = Component.translatable("gui.eblochat.hud.hint");
        g.text(this.font, hint, this.width / 2 - this.font.width(hint) / 2, 19, 0xFFAAAAAA, true);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
