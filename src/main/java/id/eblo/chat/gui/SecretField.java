package id.eblo.chat.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.Consumer;

/** Masks an EditBox with asterisks and adds an "eye" button that reveals / hides the text. */
final class SecretField {
    private static final Identifier EYE = Identifier.fromNamespaceAndPath("eblochat", "textures/gui/eye.png");
    private static final Identifier EYE_OFF = Identifier.fromNamespaceAndPath("eblochat", "textures/gui/eye_off.png");

    private boolean visible;
    private Button eye;

    /** Masks the box and adds the eye button. Call {@link #draw} from the screen's extractRenderState. */
    static SecretField attach(EditBox box, int x, int y, Consumer<Button> adder) {
        SecretField s = new SecretField();
        box.addFormatter((text, offset) ->
                s.visible ? FormattedCharSequence.forward(text, Style.EMPTY)
                        : FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        s.eye = Button.builder(Component.empty(), b -> s.visible = !s.visible)
                .bounds(x, y, 22, box.getHeight()).build();
        adder.accept(s.eye);
        return s;
    }

    /** Draws the eye icon on top of the button (open eye = text hidden, click to show). */
    void draw(GuiGraphicsExtractor g) {
        int x = eye.getX() + (eye.getWidth() - 13) / 2;
        int y = eye.getY() + (eye.getHeight() - 7) / 2;
        g.blit(RenderPipelines.GUI_TEXTURED, visible ? EYE_OFF : EYE, x, y, 0F, 0F, 13, 7, 13, 7, 13, 7, 0xFFFFFFFF);
    }
}
