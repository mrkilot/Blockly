package id.eblo.chat;

import id.eblo.chat.net.Emotes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a chat message out into wrapped rows of segments: styled text pieces and emote images. Shared by the main
 * chat window and the HUD. Messages without emotes use the vanilla text splitter.
 */
public final class ChatLayout {
    public static final int EMOTE_H = 10;       // emote height in text units (a line is 9 high)
    private static final int EMOTE_MAX_W = 36;

    /** One piece of a row: either text or an emote, at x (text units) from the row start. */
    public record Seg(int x, FormattedCharSequence text, Emotes.Emote emote) {}

    public record Result(List<List<Seg>> rows, int prefixW, int nameW) {}

    private ChatLayout() {}

    private static int emoteWidth(Emotes.Emote e) {
        Emotes.Img img = Emotes.image(e);
        if (img == null) return EMOTE_H;
        return Math.max(4, Math.min(EMOTE_MAX_W, Math.round(EMOTE_H * img.w() / (float) img.h())));
    }

    public static Result layout(Font font, ChatMessage m, int wrapW, boolean showChannel) {
        int pw = font.width(MessageFormatter.prefix(m, showChannel));
        int nw = m.system ? 0 : font.width(MessageFormatter.name(m));
        if (m.system || !Config.d.showEmotes) return plain(font, m, wrapW, showChannel, pw, nw);

        String[] words = m.text.split(" ");
        Emotes.Emote[] em = new Emotes.Emote[words.length];
        boolean any = false;
        for (int i = 0; i < words.length; i++) {
            em[i] = Emotes.resolve(m, words[i]);
            any |= em[i] != null;
        }
        if (!any) return plain(font, m, wrapW, showChannel, pw, nw);

        Style bodyStyle = MessageFormatter.body(m).getStyle();
        MutableComponent head = MessageFormatter.prefix(m, showChannel);
        head.append(MessageFormatter.name(m)).append(Component.literal(m.action ? " " : ": "));
        int hw = font.width(head);
        if (hw > wrapW) return plain(font, m, wrapW, showChannel, pw, nw);

        int space = font.width(" ");
        List<List<Seg>> rows = new ArrayList<>();
        List<Seg> cur = new ArrayList<>();
        cur.add(new Seg(0, head.getVisualOrderText(), null));
        int x = hw;
        boolean firstInRow = true;
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) continue;
            FormattedCharSequence seq = null;
            int w;
            if (em[i] != null) {
                w = emoteWidth(em[i]);
            } else {
                seq = Component.literal(words[i]).setStyle(bodyStyle).getVisualOrderText();
                w = font.width(seq);
            }
            int gap = firstInRow ? 0 : space;
            if (x > 0 && x + gap + w > wrapW) {
                rows.add(cur);
                cur = new ArrayList<>();
                x = 0;
                gap = 0;
            }
            cur.add(new Seg(x + gap, seq, em[i]));
            x += gap + w;
            firstInRow = false;
        }
        rows.add(cur);
        return new Result(rows, pw, nw);
    }

    private static Result plain(Font font, ChatMessage m, int wrapW, boolean showChannel, int pw, int nw) {
        List<List<Seg>> rows = new ArrayList<>();
        for (FormattedCharSequence line : font.split(MessageFormatter.full(m, showChannel), wrapW)) {
            rows.add(List.of(new Seg(0, line, null)));
        }
        return new Result(rows, pw, nw);
    }

    /** Draws one row at (x, y) with the given ARGB colour (alpha is honoured for text and emotes). */
    public static void drawRow(GuiGraphicsExtractor g, Font font, List<Seg> row, int x, int y, int argb) {
        for (Seg s : row) {
            if (s.text() != null) {
                g.text(font, s.text(), x + s.x(), y, argb, true);
            } else if (s.emote() != null) {
                Emotes.Img img = Emotes.image(s.emote());
                if (img == null) continue;
                int dw = Math.max(4, Math.min(EMOTE_MAX_W, Math.round(EMOTE_H * img.w() / (float) img.h())));
                int tint = (argb & 0xFF000000) | 0x00FFFFFF;
                g.blit(RenderPipelines.GUI_TEXTURED, img.id(), x + s.x(), y - 1, 0F, 0F, dw, EMOTE_H,
                        img.w(), img.h(), img.w(), img.h(), tint);
            }
        }
    }
}
