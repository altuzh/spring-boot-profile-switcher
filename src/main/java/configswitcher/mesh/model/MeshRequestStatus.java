package configswitcher.mesh.model;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Color;

public enum MeshRequestStatus {
    PENDING("PENDING", "Pending response", AllIcons.Process.ProgressPauseSmall, new JBColor(new Color(120, 120, 120), new Color(160, 160, 160))),
    SUCCESS("OK", "Successful response", AllIcons.General.InspectionsOK, new JBColor(new Color(46, 125, 50), new Color(129, 199, 132))),
    WARN("WARN", "Warning event", AllIcons.General.Warning, new JBColor(new Color(230, 81, 0), new Color(255, 183, 77))),
    ERROR("ERR", "Failed with error", AllIcons.General.Error, new JBColor(new Color(198, 40, 40), new Color(239, 83, 80))),
    TIMEOUT("TIMEOUT", "Request timed out", AllIcons.General.Warning, new JBColor(new Color(239, 108, 0), new Color(255, 183, 77)));

    private final String label;
    private final String description;
    private final Icon icon;
    private final Color color;

    MeshRequestStatus(@NotNull String label, @NotNull String description, @NotNull Icon icon, @NotNull Color color) {
        this.label = label;
        this.description = description;
        this.icon = icon;
        this.color = color;
    }

    public @NotNull String getLabel() {
        return label;
    }

    public @NotNull String getDescription() {
        return description;
    }

    public @NotNull Icon getIcon() {
        return icon;
    }

    public @NotNull Color getColor() {
        return color;
    }

    public boolean isTerminal() {
        return this != PENDING;
    }
}
