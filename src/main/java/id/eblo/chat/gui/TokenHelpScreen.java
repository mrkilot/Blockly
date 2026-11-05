package id.eblo.chat.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Отдельное окно с инструкцией по получению OAuth-токена Twitch. */
public class TokenHelpScreen extends Screen {
    private final Screen parent;

    public TokenHelpScreen(Screen parent) {
        super(Component.translatable("gui.eblochat.tokenhelp.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 50;

        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.tokenhelp.copy"), b -> {
            this.minecraft.keyboardHandler.setClipboard("https://twitchtokengenerator.com/");
        }).bounds(cx - 150, y, 300, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.tokenhelp.back"), b -> {
            this.minecraft.setScreen(parent);
        }).bounds(cx - 150, y + 26, 300, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);

        int cx = this.width / 2;
        int y = 40;

        g.text(this.font, this.title, cx - this.font.width(this.title) / 2, y, 0xFFFFFFFF, true);
        y += 26;

        // Блок 1: строки 1-3
        String[] block1 = {
                "1. Откройте https://twitchtokengenerator.com/",
                "2. Выберите User Access Token -> Generate User Access Token.",
                "3. Укажите скопы: chat:read, chat:edit, moderator:manage:banned_users.",
        };

        // Блок 2: строки 4-6
        String[] block2 = {
                "4. Нажмите Connect Twitch, войдите в аккаунт и авторизуйте.",
                "5. Скопируйте Access Token и вставьте в поле OAuth-token в настройках мода.",
                "6. Также укажите свой Twitch nick.",
        };

        for (String line : block1) {
            g.text(this.font, Component.literal(line), cx - this.font.width(line) / 2, y, 0xFFDDDDDD, true);
            y += 16;
        }

        y += 10;

        for (String line : block2) {
            g.text(this.font, Component.literal(line), cx - this.font.width(line) / 2, y, 0xFFDDDDDD, true);
            y += 16;
        }

        y += 14;

        String warn = "Токен даёт полный доступ к аккаунту Twitch - не делитесь им!";
        g.text(this.font, Component.literal(warn), cx - this.font.width(warn) / 2, y, 0xFFFFAAAA, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
