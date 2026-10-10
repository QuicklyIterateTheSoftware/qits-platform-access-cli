package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The condition syntax of {@code qits wait --when}, read into the selection's {@code when} groups
 * (qits-1153, W8). One {@code --when} is one group: its conditions must all hold, and an event that
 * fits any group matches.
 * <p>
 * Forms: {@code path=value} (equal), {@code path^=value} (starts with), {@code path?} (present),
 * {@code !path} (absent). A path is dot-separated payload fields; {@code []} after a field means any
 * element of that array ({@code entities[].status=VERIFIED}). A value holding spaces is quoted.
 * <p>
 * The syntax and the limits are checked here so a mistyped group stops with a usage error rather
 * than a wait that never fires. The service checks them again. The grammar, the limits and the wire
 * shape are qits-eventstream-javalib's ({@code eu.wohlben.qits.eventstream.selection}); this copy
 * exists because the CLI is a native binary that does not depend on that library. Keep the two in
 * step: what one refuses, the other refuses.
 * <p>
 * On the wire the ops are lowercase, a value is always a string, and absent is {@code exists} with
 * {@code false}: {@code {"path":"phase","op":"exists","value":false}}.
 */
public final class WhenGrammar {

    public enum Op { EXACT, PREFIX, EXISTS, ABSENT }

    /** The library's limits (EventSelection.MAX_*). */
    static final int MAX_GROUPS = 8;
    static final int MAX_CONDITIONS = 8;
    static final int MAX_PATH_SEGMENTS = 8;
    static final int MAX_PATH_LENGTH = 256;
    static final int MAX_VALUE_LENGTH = 1024;
    static final int MAX_EVENT_LENGTH = 128;

    /** One condition as the wire has it. {@code value} is null for EXISTS and ABSENT. */
    public record Condition(String path, Op op, String value) {

        /** The condition as it is written on the command line. */
        public String written() {
            return switch (op) {
                case EXACT -> path + "=" + quoted(value);
                case PREFIX -> path + "^=" + quoted(value);
                case EXISTS -> path + "?";
                case ABSENT -> "!" + path;
            };
        }
    }

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(\\[])?");
    private static final String OPERATOR_CHARACTERS = "=^?!\"";
    private static final String FORMS = "path=value, path^=value, path? or !path";

    private WhenGrammar() {
    }

    /** One {@code --when}: its conditions, at least one. */
    public static List<Condition> parse(String group) throws CliFailure {
        List<String> tokens = tokens(group == null ? "" : group);
        if (tokens.isEmpty()) {
            throw new CliFailure("--when is empty. Give conditions, for example --when 'runId=<id> status=SUCCESS'.",
                    CliFailure.USAGE);
        }
        if (tokens.size() > MAX_CONDITIONS) {
            throw new CliFailure("--when '" + group.strip() + "' has " + tokens.size() + " conditions; the most is "
                    + MAX_CONDITIONS + ".", CliFailure.USAGE);
        }
        List<Condition> conditions = new ArrayList<>();
        for (String token : tokens) {
            conditions.add(condition(token));
        }
        return List.copyOf(conditions);
    }

    /** {@code [[{path, op, value?}, …], …]}: the groups as the wire has them. */
    public static ArrayNode json(List<List<Condition>> groups) {
        ArrayNode when = JsonNodeFactory.instance.arrayNode();
        for (List<Condition> group : groups) {
            ArrayNode conditions = when.addArray();
            for (Condition condition : group) {
                ObjectNode node = conditions.addObject();
                node.put("path", condition.path());
                switch (condition.op()) {
                    case EXACT -> node.put("op", "exact").put("value", condition.value());
                    case PREFIX -> node.put("op", "prefix").put("value", condition.value());
                    case EXISTS -> node.put("op", "exists").put("value", true);
                    case ABSENT -> node.put("op", "exists").put("value", false);
                }
            }
        }
        return when;
    }

    /** A group the service answered, as it is written on the command line; null when it is not one. */
    public static String written(JsonNode group) {
        if (group == null || !group.isArray()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode node : group) {
            String path = node.path("path").asText("");
            JsonNode raw = node.path("value");
            String value = raw.isNull() || raw.isMissingNode() ? null : raw.asText();
            String op = node.path("op").asText("").toLowerCase(java.util.Locale.ROOT);
            Condition condition = switch (op) {
                case "exact" -> value == null ? null : new Condition(path, Op.EXACT, value);
                case "prefix" -> value == null ? null : new Condition(path, Op.PREFIX, value);
                case "exists" -> new Condition(path, raw.isBoolean() && !raw.asBoolean() ? Op.ABSENT : Op.EXISTS, null);
                case "absent" -> new Condition(path, Op.ABSENT, null);
                default -> null;
            };
            parts.add(condition != null ? condition.written()
                    : path + " " + node.path("op").asText("?") + (value == null ? "" : " " + quoted(value)));
        }
        return String.join(" ", parts);
    }

    /** Splits on spaces outside double quotes. The quotes stay; {@link #value} reads them. */
    static List<String> tokens(String group) throws CliFailure {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        boolean inQuotes = false;
        for (int i = 0; i < group.length(); i++) {
            char c = group.charAt(i);
            if (inQuotes) {
                current.append(c);
                if (c == '\\' && i + 1 < group.length()) {
                    current.append(group.charAt(++i));
                } else if (c == '"') {
                    inQuotes = false;
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }
            inToken = true;
            current.append(c);
            inQuotes = c == '"';
        }
        if (inQuotes) {
            throw usage(current.toString(), "has a quote that is not closed.");
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    static Condition condition(String token) throws CliFailure {
        if (token.startsWith("!")) {
            String name = token.substring(1);
            if (!name.isEmpty() && name.chars().anyMatch(c -> OPERATOR_CHARACTERS.indexOf(c) >= 0)) {
                throw usage(token, "is not a condition. !path takes a path alone, and asks that it is absent.");
            }
            return new Condition(path(token, name), Op.ABSENT, null);
        }
        int end = 0;
        while (end < token.length() && OPERATOR_CHARACTERS.indexOf(token.charAt(end)) < 0) {
            end++;
        }
        String path = path(token, token.substring(0, end));
        String rest = token.substring(end);
        if (rest.isEmpty()) {
            throw usage(token, "has no operator. Write " + FORMS + ".");
        }
        if (rest.equals("?")) {
            return new Condition(path, Op.EXISTS, null);
        }
        if (rest.startsWith("?")) {
            throw usage(token, "has text after the ?. path? stands alone: it asks that the field is present.");
        }
        Op op;
        String raw;
        if (rest.startsWith("^=")) {
            op = Op.PREFIX;
            raw = rest.substring(2);
        } else if (rest.startsWith("=") && !rest.startsWith("==")) {
            op = Op.EXACT;
            raw = rest.substring(1);
        } else {
            throw usage(token, "has an operator qits does not know. Write " + FORMS + ".");
        }
        if (raw.isEmpty()) {
            throw usage(token, "has an empty value. To match an empty string, write " + path + "=\"\".");
        }
        String value = value(token, raw);
        if (op == Op.PREFIX && value.isEmpty()) {
            throw usage(token, "asks for an empty prefix, which every value has. Write " + path + "? instead.");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw usage(token, "has a value longer than " + MAX_VALUE_LENGTH + " characters.");
        }
        return new Condition(path, op, value);
    }

    private static String path(String token, String path) throws CliFailure {
        if (path.isEmpty()) {
            throw usage(token, "names no path. Write " + FORMS + ", for example runId=<id> or entities[].status=VERIFIED.");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw usage(token, "has a path longer than " + MAX_PATH_LENGTH + " characters.");
        }
        String[] segments = path.split("\\.", -1);
        if (segments.length > MAX_PATH_SEGMENTS) {
            throw usage(token, "has a path of " + segments.length + " segments; the most is " + MAX_PATH_SEGMENTS + ".");
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw usage(token, "has the path '" + path + "', which is not one. A path is field names joined by "
                        + "dots; [] after a field means any element of that array: entities[].status.");
            }
        }
        return path;
    }

    /** The value after the operator: as written, or the inside of a double-quoted value. */
    private static String value(String token, String raw) throws CliFailure {
        if (!raw.startsWith("\"")) {
            if (raw.indexOf('"') >= 0) {
                throw usage(token, "has a quote inside the value. Quote the whole value: label=\"two words\".");
            }
            return raw;
        }
        StringBuilder value = new StringBuilder();
        int i = 1;
        for (; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length() && (raw.charAt(i + 1) == '"' || raw.charAt(i + 1) == '\\')) {
                value.append(raw.charAt(++i));
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        if (i >= raw.length()) {
            throw usage(token, "has a quote that is not closed.");
        }
        if (i != raw.length() - 1) {
            throw usage(token, "has text after the closing quote.");
        }
        return value.toString();
    }

    /** A value as written: bare when it can be, else in double quotes. */
    static String quoted(String value) {
        if (!value.isEmpty() && value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\\')) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static CliFailure usage(String token, String problem) {
        return new CliFailure("--when: '" + token + "' " + problem, CliFailure.USAGE);
    }
}
