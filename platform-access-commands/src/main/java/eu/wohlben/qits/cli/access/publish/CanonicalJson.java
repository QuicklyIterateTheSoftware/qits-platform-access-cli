package eu.wohlben.qits.cli.access.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An npm manifest as the content hash reads it: {@code package.json} without its top-level
 * {@code version}, written canonically. Object keys sorted by {@link String#compareTo}, recursively;
 * no whitespace; strings escaped as {@code \"}, {@code \\}, {@code \b}, {@code \f}, {@code \n},
 * {@code \r}, {@code \t}, other U+0000 to U+001F as {@code \\u00xx} (lowercase hex) and everything
 * else literal UTF-8; numbers as Jackson's {@code JsonNode.toString()}.
 *
 * <p>The version is dropped because it is the one field every release changes and the one thing an
 * unchanged package must not be told apart by.
 */
final class CanonicalJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CanonicalJson() {
    }

    /** The manifest's canonical bytes, minus {@code version}. */
    static byte[] manifestWithoutVersion(byte[] packageJson) {
        JsonNode tree;
        try {
            tree = JSON.readTree(packageJson);
        } catch (IOException e) {
            throw CliException.policy("package.json is not JSON: " + e.getMessage());
        }
        if (!(tree instanceof ObjectNode object)) {
            throw CliException.policy("package.json is not a JSON object");
        }
        ObjectNode copy = object.deepCopy();
        copy.remove("version");
        return write(copy).getBytes(StandardCharsets.UTF_8);
    }

    static String write(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                keys.add(field.getKey());
            }
            keys.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                string(keys.get(i), out);
                out.append(':');
                write(node.get(keys.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            string(node.textValue(), out);
        } else {
            // Numbers, booleans and null: Jackson's own spelling, which is the definition.
            out.append(node.toString());
        }
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
