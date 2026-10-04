package configswitcher.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Modern context help component that displays a blue circular '?' icon
 * with interactive HTML tooltips containing descriptions and instructions.
 */
public class HelpLabel extends JLabel {

    public HelpLabel(@Nullable String tooltipText) {
        setIcon(new BlueQuestionIcon());
        updateTooltip(tooltipText);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(JBUI.Borders.empty(0, 3));
    }

    public void updateTooltip(@Nullable String tooltipText) {
        if (tooltipText == null || tooltipText.isBlank()) {
            setToolTipText(null);
            return;
        }
        setToolTipText(formatTooltip(tooltipText));
    }

    private static String formatTooltip(String text) {
        if (text.startsWith("<html>")) return text;
        return "<html><body style='width: 320px; font-family: sans-serif; font-size: 11px; padding: 3px;'>"
                + text.replace("\n", "<br>")
                + "</body></html>";
    }

    private static class BlueQuestionIcon implements Icon {
        private static final int BASE_SIZE = 15;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                boolean isDark = !JBColor.isBright();
                Color circleBg = isDark ? new Color(0x18, 0x36, 0x54) : new Color(0xE3, 0xF2, 0xFD);
                Color circleBorder = isDark ? new Color(0x38, 0xB2, 0xE4) : new Color(0x19, 0x76, 0xD2);
                Color textColor = isDark ? new Color(0x80, 0xD8, 0xFF) : new Color(0x0D, 0x47, 0xA1);

                int size = JBUI.scale(BASE_SIZE);
                int offset = 1;

                // Circular background
                g2.setColor(circleBg);
                g2.fillOval(x + offset, y + offset, size - 2 * offset, size - 2 * offset);

                // Border
                g2.setColor(circleBorder);
                g2.setStroke(new BasicStroke(JBUI.scale(1.2f)));
                g2.drawOval(x + offset, y + offset, size - 2 * offset, size - 2 * offset);

                // Centered '?' symbol
                g2.setColor(textColor);
                Font font = new Font(Font.SANS_SERIF, Font.BOLD, JBUI.scaleFontSize(10));
                g2.setFont(font);
                FontMetrics fm = g2.getFontMetrics();
                int textX = x + (size - fm.stringWidth("?")) / 2;
                int textY = y + ((size - fm.getHeight()) / 2) + fm.getAscent();
                g2.drawString("?", textX, textY);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return JBUI.scale(BASE_SIZE);
        }

        @Override
        public int getIconHeight() {
            return JBUI.scale(BASE_SIZE);
        }
    }
}
