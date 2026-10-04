package configswitcher.mesh.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DataSetJsonConverter {

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private DataSetJsonConverter() {}

    @NotNull
    public static String toPrettyJson(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String input = raw.trim();

        // 1. Try standard JSON first if it looks like JSON
        if ((input.startsWith("{") && input.contains("\"")) || (input.startsWith("[") && input.contains("\""))) {
            try {
                JsonElement element = JsonParser.parseString(input);
                return PRETTY_GSON.toJson(element);
            } catch (Exception ignored) {
                // Fall through to DataSet parser
            }
        }

        // 2. Parse Map / DataSet toString format
        if (input.startsWith("{") || input.startsWith("[")) {
            try {
                DataSetParser parser = new DataSetParser(input);
                JsonElement element = parser.parse();
                return PRETTY_GSON.toJson(element);
            } catch (Exception ignored) {
                // If custom parsing failed, return raw trimmed input
            }
        }

        return input;
    }

    private static class DataSetParser {
        private final String src;
        private int pos = 0;

        DataSetParser(String src) {
            this.src = src;
        }

        JsonElement parse() {
            skipWhitespace();
            JsonElement res = parseValue();
            return res != null ? res : JsonNull.INSTANCE;
        }

        private JsonElement parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                return JsonNull.INSTANCE;
            }

            char c = src.charAt(pos);
            if (c == '{') {
                return parseObject();
            } else if (c == '[') {
                return parseArray();
            } else {
                return parsePrimitive();
            }
        }

        private JsonObject parseObject() {
            JsonObject obj = new JsonObject();
            pos++; // skip '{'

            while (pos < src.length()) {
                skipWhitespace();
                if (pos >= src.length()) break;
                if (src.charAt(pos) == '}') {
                    pos++; // skip '}'
                    break;
                }

                // Parse key until '='
                int keyStart = pos;
                while (pos < src.length() && src.charAt(pos) != '=' && src.charAt(pos) != '}') {
                    pos++;
                }

                if (pos >= src.length() || src.charAt(pos) == '}') {
                    // Malformed without '=', finish
                    if (pos < src.length() && src.charAt(pos) == '}') pos++;
                    break;
                }

                String key = src.substring(keyStart, pos).trim();
                pos++; // skip '='

                // Parse value
                JsonElement val = parseValue();
                obj.add(key, val);

                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++; // skip ','
                } else if (pos < src.length() && src.charAt(pos) == '}') {
                    pos++; // skip '}'
                    break;
                }
            }

            return obj;
        }

        private JsonArray parseArray() {
            JsonArray arr = new JsonArray();
            pos++; // skip '['

            while (pos < src.length()) {
                skipWhitespace();
                if (pos >= src.length()) break;
                if (src.charAt(pos) == ']') {
                    pos++; // skip ']'
                    break;
                }

                JsonElement val = parseValue();
                arr.add(val);

                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++; // skip ','
                } else if (pos < src.length() && src.charAt(pos) == ']') {
                    pos++; // skip ']'
                    break;
                }
            }

            return arr;
        }

        private JsonElement parsePrimitive() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ',' || c == '}' || c == ']') {
                    break;
                }
                pos++;
            }

            String text = src.substring(start, pos).trim();
            if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
                return JsonNull.INSTANCE;
            }
            if ("true".equalsIgnoreCase(text)) {
                return new JsonPrimitive(true);
            }
            if ("false".equalsIgnoreCase(text)) {
                return new JsonPrimitive(false);
            }

            // Check if integer
            if (text.matches("^-?\\d+$")) {
                try {
                    return new JsonPrimitive(Long.parseLong(text));
                } catch (NumberFormatException ignored) {}
            }

            // Check if floating point
            if (text.matches("^-?\\d+\\.\\d+$")) {
                try {
                    return new JsonPrimitive(Double.parseDouble(text));
                } catch (NumberFormatException ignored) {}
            }

            // Strip optional surrounding quotes if already present
            if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
                if (text.length() >= 2) {
                    text = text.substring(1, text.length() - 1);
                }
            }

            return new JsonPrimitive(text);
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
