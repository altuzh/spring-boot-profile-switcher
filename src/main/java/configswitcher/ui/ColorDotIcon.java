package configswitcher.ui;

import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Renders a crisp, anti-aliased colored circular badge icon
 * for representing application startup stages and health.
 */
public class ColorDotIcon implements Icon {

    private final int size;
    private final Color color;

    public ColorDotIcon(int size, @NotNull Color color) {
        this.size = Math.max(6, size);
        this.color = color;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            int diameter = size - 2;
            int yOffset = y + (getIconHeight() - diameter) / 2;
            g2.fillOval(x + 1, yOffset, diameter, diameter);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
