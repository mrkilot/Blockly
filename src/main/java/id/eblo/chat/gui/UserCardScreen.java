package id.eblo.chat.gui;

import id.eblo.chat.ChatMessage;
import id.eblo.chat.ChatStore;
import id.eblo.chat.Config;
import id.eblo.chat.MessageFormatter;
import id.eblo.chat.net.Moderation;
import id.eblo.chat.net.UserLookup;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Chatterino-style user card: account info, roles, recent messages, quick actions. */
public class UserCardScreen extends Screen {
    private final Screen parent;
    private final String channel;
    private final String login;
    private volatile UserLookup.Info info;
    private Button ignoreBtn;
    private boolean banMenu;
    private EditBox customBox;
    private String customText = "";
    private volatile Component status = Component.empty();

    public UserCardScreen(Screen parent, String channel, String login) {
        super(Component.translatable("gui.eblochat.usercard.title"));
        this.parent = parent;
        this.channel = channel;
        this.login = login.toLowerCase();
        UserLookup.fetch(this.login).thenAccept(i -> this.info = i);
    }

    private Component ignoreLabel() {
        return Component.translatable(Config.d.ignoredUsers.contains(login) ? "gui.eblochat.usercard.unignore" : "gui.eblochat.usercard.ignore");
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 48;
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.copy_name"), b -> {
            this.minecraft.keyboardHandler.setClipboard(login);
            b.setMessage(Component.translatable("gui.eblochat.usercard.copied"));
        }).bounds(cx - 155, y, 100, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.copy_logs"), b -> {
            openLink("https://logs.ivr.fi/?channel=" + (channel.startsWith("@") ? login : channel) + "&username=" + login);
            b.setMessage(Component.translatable("gui.eblochat.usercard.opened"));
        }).bounds(cx - 50, y, 100, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.copy_link"), b -> {
            openLink("https://twitch.tv/" + login);
            b.setMessage(Component.translatable("gui.eblochat.usercard.opened"));
        }).bounds(cx + 55, y, 100, 18).build());
        ignoreBtn = addRenderableWidget(Button.builder(ignoreLabel(), b -> {
            if (!Config.d.ignoredUsers.remove(login)) Config.d.ignoredUsers.add(login);
            Config.save();
            b.setMessage(ignoreLabel());
        }).bounds(cx - 155, y + 22, 100, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.ban"), b -> {
            banMenu = !banMenu;
            if (customBox != null) customText = customBox.getValue();
            rebuildWidgets();
        }).bounds(cx - 50, y + 22, 100, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.usercard.back"), b -> onClose())
                .bounds(cx + 55, y + 22, 100, 18).build());

        customBox = null;
        if (banMenu) {
            int my = y - 48;
            String[] labels = {"1m", "10m", "1h", "1d", "7d", ""};
            int[] secs = {60, 600, 3600, 86400, 604800, 0};
            for (int k = 0; k < 6; k++) {
                final int sec = secs[k];
                Component label = labels[k].isEmpty() ? Component.translatable("gui.eblochat.ban.perm") : Component.literal(labels[k]);
                addRenderableWidget(Button.builder(label, b -> doBan(sec)).bounds(cx - 155 + k * 52, my, 50, 18).build());
            }
            customBox = new EditBox(this.font, cx - 155, my + 22, 110, 18, Component.empty());
            customBox.setMaxLength(12);
            customBox.setValue(customText);
            addRenderableWidget(customBox);
            addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.ban.ok"), b -> {
                int sec = Moderation.parseDuration(customBox.getValue());
                if (sec < 0) status = Component.translatable("gui.eblochat.ban.invalid");
                else doBan(sec);
            }).bounds(cx - 41, my + 22, 62, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.eblochat.ban.unban"), b -> doUnban())
                    .bounds(cx + 25, my + 22, 130, 18).build());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        int x = this.width / 2 - 155;
        int y = 14;
        g.text(this.font, Component.literal(login).withColor(0x9146FF), x, y, 0xFFFFFFFF, true);
        y += 14;

        UserLookup.Info i = this.info;
        List<Component> rows = new ArrayList<>();
        if (i == null) {
            rows.add(Component.translatable("gui.eblochat.usercard.loading"));
        } else if (!i.ok) {
            rows.add(Component.translatable("gui.eblochat.usercard.failed", i.error));
        } else {
            rows.add(Component.translatable("gui.eblochat.usercard.name", i.display));
            rows.add(Component.translatable("gui.eblochat.usercard.id", i.id));
            rows.add(Component.translatable("gui.eblochat.usercard.created", i.created));
            if (i.followers >= 0) rows.add(Component.translatable("gui.eblochat.usercard.followers", i.followers));
            List<String> roles = new ArrayList<>();
            if (i.staff) roles.add("staff");
            if (i.partner) roles.add("partner");
            if (i.affiliate) roles.add("affiliate");
            if (!roles.isEmpty()) rows.add(Component.translatable("gui.eblochat.usercard.roles", String.join(", ", roles)));
            rows.add(Component.translatable(i.live ? "gui.eblochat.usercard.live" : "gui.eblochat.usercard.offline"));
            if (i.banned) rows.add(Component.translatable("gui.eblochat.usercard.banned"));
            if (!i.bio.isBlank()) rows.add(Component.literal(i.bio));
        }
        for (Component c : rows) {
            for (FormattedCharSequence s : this.font.split(c, 310)) {
                g.text(this.font, s, x, y, 0xFFDDDDDD, true);
                y += 10;
            }
        }

        // recent messages from this user in the selected channel
        y += 6;
        g.text(this.font, Component.translatable("gui.eblochat.usercard.history"), x, y, 0xFFAAAAAA, true);
        y += 12;
        List<ChatMessage> src = ChatStore.list(channel);
        List<ChatMessage> mine = new ArrayList<>();
        for (int k = src.size() - 1; k >= 0 && mine.size() < 8; k--) {
            ChatMessage m = src.get(k);
            if (!m.system && m.login.equalsIgnoreCase(login)) mine.add(0, m);
        }
        int limit = this.height - (banMenu ? 134 : 72);
        for (ChatMessage m : mine) {
            if (y + 10 > limit) break;
            List<FormattedCharSequence> ls = this.font.split(MessageFormatter.prefix(m, false).append(MessageFormatter.body(m)), 310);
            if (!ls.isEmpty()) g.text(this.font, ls.get(0), x, y, 0xFFFFFFFF, true);
            y += 10;
        }
        if (banMenu) {
            g.text(this.font, Component.translatable("gui.eblochat.ban.title"), this.width / 2 - 155, this.height - 108, 0xFFFFAA55, true);
        }
        g.text(this.font, status, this.width / 2 - 155, this.height - (banMenu ? 122 : 60), 0xFFFFFFFF, true);
    }

    private String targetId() {
        UserLookup.Info i = this.info;
        if (i == null || !i.ok || i.id.isEmpty()) {
            status = Component.translatable("gui.eblochat.ban.no_data");
            return null;
        }
        return i.id;
    }

    private void report(String err, Component ok) {
        if (err == null) status = ok;
        else status = err.startsWith("gui.eblochat") ? Component.translatable(err) : Component.literal(err);
    }

    private void doBan(int seconds) {
        String id = targetId();
        if (id == null) return;
        status = Component.translatable("gui.eblochat.ban.working");
        Component ok = Component.translatable("gui.eblochat.ban.done", login,
                seconds > 0 ? ChatStore.formatDuration(seconds) : Component.translatable("gui.eblochat.ban.perm").getString());
        Moderation.ban(channel, id, seconds).thenAccept(err -> report(err, ok));
    }

    private void doUnban() {
        String id = targetId();
        if (id == null) return;
        status = Component.translatable("gui.eblochat.ban.working");
        Component ok = Component.translatable("gui.eblochat.ban.unbanned", login);
        Moderation.unban(channel, id).thenAccept(err -> report(err, ok));
    }

    /** Opens a URL in the system browser and also copies it to the clipboard as a fallback. */
    private void openLink(String url) {
        this.minecraft.keyboardHandler.setClipboard(url);
        Thread t = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                else if (os.contains("mac")) new ProcessBuilder("open", url).start();
                else new ProcessBuilder("xdg-open", url).start();
            } catch (Throwable ignored) {
            }
        }, "Blockly-OpenLink");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
