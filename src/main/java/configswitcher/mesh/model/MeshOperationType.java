package configswitcher.mesh.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum MeshOperationType {
    QUERY("QUERY"),
    MUTATION("MUTATION"),
    SUBSCRIPTION("SUBSCRIPTION"),
    UNKNOWN("UNKNOWN");

    private final String displayName;

    MeshOperationType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    private static final Pattern OP_PATTERN = Pattern.compile(
            "(?:^|\\b)(query|mutation|subscription)\\b[^{]*\\{",
            Pattern.CASE_INSENSITIVE
    );

    @NotNull
    public static MeshOperationType fromQueryText(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return UNKNOWN;
        }
        String cleaned = query.replaceAll("(?m)^\\s*#.*$", "").trim();
        Matcher matcher = OP_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            String op = matcher.group(1).toLowerCase();
            return switch (op) {
                case "mutation" -> MUTATION;
                case "query" -> QUERY;
                case "subscription" -> SUBSCRIPTION;
                default -> UNKNOWN;
            };
        }
        // If it starts directly with '{' it is an anonymous query
        if (cleaned.startsWith("{")) {
            return QUERY;
        }
        // Fallback for truncated queries or queries without braces in logs
        String lower = cleaned.toLowerCase();
        if (lower.startsWith("mutation")) {
            return MUTATION;
        }
        if (lower.startsWith("query")) {
            return QUERY;
        }
        if (lower.startsWith("subscription")) {
            return SUBSCRIPTION;
        }
        return UNKNOWN;
    }

    @NotNull
    public static MeshOperationType fromToken(@Nullable String token) {
        if (token == null) return UNKNOWN;
        String lower = token.trim().toLowerCase();
        if (lower.startsWith("mutation")) return MUTATION;
        if (lower.startsWith("query")) return QUERY;
        if (lower.startsWith("subscription")) return SUBSCRIPTION;
        return UNKNOWN;
    }
}
