package configswitcher.mesh.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GraphQLFormatter {

    private static final Pattern OP_NAME_PATTERN = Pattern.compile(
            "\\b(?:query|mutation|subscription)\\s+([A-Za-z0-9_]+)",
            Pattern.CASE_INSENSITIVE
    );

    private GraphQLFormatter() {}

    @NotNull
    public static String format(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String raw = query.trim();
        // If it already looks formatted with multiple clean lines, let's normalize or reformat
        StringBuilder out = new StringBuilder();
        int indentLevel = 0;
        boolean inString = false;
        boolean inComment = false;
        char prev = '\0';

        char[] chars = raw.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            if (inComment) {
                out.append(c);
                if (c == '\n') {
                    inComment = false;
                    appendIndent(out, indentLevel);
                }
                prev = c;
                continue;
            }

            if (inString) {
                out.append(c);
                if (c == '"' && prev != '\\') {
                    inString = false;
                }
                prev = c;
                continue;
            }

            if (c == '#') {
                inComment = true;
                out.append(c);
                prev = c;
                continue;
            }

            if (c == '"') {
                inString = true;
                out.append(c);
                prev = c;
                continue;
            }

            switch (c) {
                case '{':
                    trimTrailingSpaces(out);
                    if (out.length() > 0 && out.charAt(out.length() - 1) != ' ' && out.charAt(out.length() - 1) != '\n') {
                        out.append(' ');
                    }
                    out.append("{\n");
                    indentLevel++;
                    appendIndent(out, indentLevel);
                    break;
                case '}':
                    trimTrailingSpaces(out);
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        out.append('\n');
                    }
                    indentLevel = Math.max(0, indentLevel - 1);
                    appendIndent(out, indentLevel);
                    out.append('}');
                    if (i + 1 < chars.length && chars[i + 1] != '}' && chars[i + 1] != '\n' && chars[i + 1] != ',') {
                        out.append('\n');
                        appendIndent(out, indentLevel);
                    }
                    break;
                case '(':
                    out.append('(');
                    break;
                case ')':
                    out.append(')');
                    break;
                case ',':
                    out.append(", ");
                    while (i + 1 < chars.length && Character.isWhitespace(chars[i + 1])) {
                        i++;
                    }
                    break;
                case ':':
                    out.append(": ");
                    while (i + 1 < chars.length && Character.isWhitespace(chars[i + 1])) {
                        i++;
                    }
                    break;
                case '\r':
                    break;
                case '\n':
                    trimTrailingSpaces(out);
                    if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        out.append('\n');
                        appendIndent(out, indentLevel);
                    }
                    while (i + 1 < chars.length && (chars[i + 1] == ' ' || chars[i + 1] == '\t' || chars[i + 1] == '\r' || chars[i + 1] == '\n')) {
                        if (chars[i + 1] == '\n') {
                            // skip redundant blank lines
                        }
                        i++;
                    }
                    break;
                case ' ':
                case '\t':
                    if (out.length() > 0) {
                        char last = out.charAt(out.length() - 1);
                        if (last != ' ' && last != '\n' && last != '(' && last != '{') {
                            out.append(' ');
                        }
                    }
                    break;
                default:
                    out.append(c);
                    break;
            }
            prev = c;
        }

        return out.toString().trim();
    }

    private static void appendIndent(StringBuilder sb, int level) {
        sb.append("  ".repeat(Math.max(0, level)));
    }

    private static void trimTrailingSpaces(StringBuilder sb) {
        while (sb.length() > 0 && (sb.charAt(sb.length() - 1) == ' ' || sb.charAt(sb.length() - 1) == '\t')) {
            sb.setLength(sb.length() - 1);
        }
    }

    @Nullable
    public static String extractOperationName(@Nullable String query) {
        if (query == null || query.isBlank()) return null;
        Matcher m = OP_NAME_PATTERN.matcher(query);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    @Nullable
    public static String extractRootField(@Nullable String query) {
        if (query == null || query.isBlank()) return null;
        int firstBrace = query.indexOf('{');
        if (firstBrace < 0) return null;

        // Skip to inside the brace
        int i = firstBrace + 1;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#') {
                // skip comment
                while (i < query.length() && query.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '}' || c == '{') break;

            // Found start of root field identifier
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < query.length() && (Character.isJavaIdentifierPart(query.charAt(i)) || query.charAt(i) == ':')) {
                    i++;
                }
                String token = query.substring(start, i);
                // If there's an alias like `myAlias: actualField`, take the alias or field
                if (token.contains(":")) {
                    String[] parts = token.split(":", 2);
                    return parts[0].trim();
                }
                return token;
            }
            i++;
        }
        return null;
    }
}
