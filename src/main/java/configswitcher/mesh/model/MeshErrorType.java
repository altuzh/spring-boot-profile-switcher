package configswitcher.mesh.model;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.awt.Color;

/**
 * Categorization of GraphQL Mesh and application execution errors.
 */
public enum MeshErrorType {
    NONE("Success", "OK", AllIcons.General.InspectionsOK, new JBColor(new Color(46, 125, 50), new Color(129, 199, 132))),
    GRAPHQL_SERVER_ERROR("GraphQL Server Error", "GQL ERR", AllIcons.General.Error, JBColor.RED),
    GRAPHQL_VALIDATION_ERROR("GraphQL Validation Error", "GQL VAL", AllIcons.General.Warning, new JBColor(new Color(230, 81, 0), new Color(255, 183, 77))),
    HTTP_5XX("HTTP 5xx Server Error", "HTTP 5xx", AllIcons.General.Error, JBColor.RED),
    HTTP_4XX("HTTP 4xx Client Error", "HTTP 4xx", AllIcons.General.Warning, new JBColor(new Color(230, 81, 0), new Color(255, 183, 77))),
    NETWORK_TIMEOUT("Network Timeout", "TIMEOUT", AllIcons.General.Warning, JBColor.RED),
    APP_EXCEPTION("Application Exception", "APP EXC", AllIcons.General.Error, JBColor.RED);

    private final String displayName;
    private final String badgeText;
    private final Icon icon;
    private final JBColor color;

    MeshErrorType(@NotNull String displayName, @NotNull String badgeText, @NotNull Icon icon, @NotNull JBColor color) {
        this.displayName = displayName;
        this.badgeText = badgeText;
        this.icon = icon;
        this.color = color;
    }

    public @NotNull String getDisplayName() {
        return displayName;
    }

    public @NotNull String getBadgeText() {
        return badgeText;
    }

    public @NotNull Icon getIcon() {
        return icon;
    }

    public @NotNull JBColor getColor() {
        return color;
    }

    public boolean isError() {
        return this != NONE;
    }
}
