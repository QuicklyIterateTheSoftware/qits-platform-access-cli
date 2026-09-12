package eu.wohlben.qits.cli.access.observe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The compact condition syntax of {@code qits observe --filter}, read into the groups of the wire
 * protocol (qits-observe-plan.md). One filter is one group. The server ANDs the conditions of a
 * group and ORs the groups.
 * <p>
 * The syntax is checked here, before anything goes out, so a mistyped filter stops with a usage
 * error instead of quietly matching nothing. Values are not checked, except the severity: the
 * server's word lists may grow.
 */
public final class FilterGrammar {

    /**
     * One condition as the wire has it. {@code key} is set for {@code attribute} and {@code
     * resource} only. {@code value} is a String, a Boolean ({@code exists}) or an Integer (a
     * severity number).
     */
    public record Condition(String field, String key, String op, Object value) {
    }

    private record Field(String wire, String key) {
    }

    /** The written names and their wire names. */
    private static final Map<String, String> FIELDS = Map.of(
            "kind", "kind", "service", "service", "trace", "traceId", "span", "spanId", "level", "severity",
            "body", "body", "name", "name", "status", "status", "event", "event");
    private static final String ATTRIBUTE = "attr.";
    private static final String RESOURCE = "resource.";
    private static final String OPERATOR_CHARACTERS = "=^~?<>!\"";
    private static final Set<String> SEVERITIES = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");
    private static final String FIELD_LIST =
            "kind, service, trace, span, level, body, name, status, event, attr.<key>, resource.<key>";
    private static final String FORMS = "F=V, F^=V, F~V, F?, !F or level>=V";
    private static final ObjectMapper JSON = new ObjectMapper();

    private FilterGrammar() {
    }

    /** One {@code --filter}: its conditions, or none for {@code *} (every record). */
    public static List<Condition> parse(String filter) throws CliFailure {
        List<String> tokens = tokens(filter == null ? "" : filter);
        if (tokens.isEmpty()) {
            throw new CliFailure("--filter is empty. Give conditions, for example --filter 'kind=log level>=ERROR', "
                    + "or --filter '*' for every record.", CliFailure.USAGE);
        }
        if (tokens.contains("*")) {
            if (tokens.size() > 1) {
                throw new CliFailure("--filter '" + filter.strip() + "': * stands alone. It streams every record; "
                        + "give it as a --filter of its own.", CliFailure.USAGE);
            }
            return List.of();
        }
        List<Condition> conditions = new ArrayList<>();
        for (String token : tokens) {
            conditions.add(condition(token));
        }
        return List.copyOf(conditions);
    }

    /** The text frame that replaces the connection's filters: {@code {"subscribe":[…]}}. */
    public static String subscribeFrame(List<List<Condition>> groups) {
        ObjectNode frame = JSON.createObjectNode();
        ArrayNode subscribe = frame.putArray("subscribe");
        for (List<Condition> group : groups) {
            ArrayNode conditions = subscribe.addObject().putArray("conditions");
            for (Condition condition : group) {
                ObjectNode node = conditions.addObject();
                node.put("field", condition.field());
                if (condition.key() != null) {
                    node.put("key", condition.key());
                }
                node.put("op", condition.op());
                switch (condition.value()) {
                    case Boolean b -> node.put("value", b);
                    case Integer i -> node.put("value", i);
                    default -> node.put("value", String.valueOf(condition.value()));
                }
            }
        }
        try {
            return JSON.writeValueAsString(frame);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a tree of strings always writes", impossible);
        }
    }

    /** Splits on spaces outside double quotes. The quotes stay; {@link #value} reads them. */
    static List<String> tokens(String filter) throws CliFailure {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        boolean inQuotes = false;
        for (int i = 0; i < filter.length(); i++) {
            char c = filter.charAt(i);
            if (inQuotes) {
                current.append(c);
                if (c == '\\' && i + 1 < filter.length()) {
                    current.append(filter.charAt(++i));
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
            if (!name.isEmpty() && containsAny(name, OPERATOR_CHARACTERS)) {
                throw usage(token, "is not a condition. !F takes a field alone, and asks that the field is absent.");
            }
            Field field = field(token, name);
            if (field.wire().equals("severity")) {
                throw levelTakesMinOnly(token);
            }
            return new Condition(field.wire(), field.key(), "exists", false);
        }
        int end = 0;
        while (end < token.length() && OPERATOR_CHARACTERS.indexOf(token.charAt(end)) < 0) {
            end++;
        }
        Field field = field(token, token.substring(0, end));
        String rest = token.substring(end);
        if (rest.isEmpty()) {
            throw usage(token, "has no operator. Write " + FORMS + ".");
        }
        String op;
        int length;
        if (rest.startsWith("^=")) {
            op = "prefix";
            length = 2;
        } else if (rest.startsWith(">=")) {
            op = "min";
            length = 2;
        } else if (rest.startsWith("=") && !rest.startsWith("==")) {
            op = "exact";
            length = 1;
        } else if (rest.startsWith("~")) {
            op = "contains";
            length = 1;
        } else if (rest.startsWith("?")) {
            op = "exists";
            length = 1;
        } else {
            throw usage(token, "has an operator qits does not know. Write " + FORMS + ".");
        }
        boolean level = field.wire().equals("severity");
        if (level != op.equals("min")) {
            throw level ? levelTakesMinOnly(token) : usage(token, "uses >=, which works on level only.");
        }
        if (op.equals("exists")) {
            if (rest.length() > 1) {
                throw usage(token, "has text after the ?. F? stands alone: it asks that the field is present.");
            }
            return new Condition(field.wire(), field.key(), op, true);
        }
        String value = value(token, rest.substring(length));
        if (value.isEmpty()) {
            throw usage(token, "has an empty value.");
        }
        if (level) {
            return new Condition(field.wire(), null, op, severity(token, value));
        }
        if (field.wire().equals("traceId") || field.wire().equals("spanId")) {
            // The server holds the ids as lowercase hex; a pasted uppercase id would match nothing.
            value = value.toLowerCase(Locale.ROOT);
        }
        return new Condition(field.wire(), field.key(), op, value);
    }

    private static Field field(String token, String name) throws CliFailure {
        if (name.isEmpty()) {
            throw usage(token, "names no field. Fields: " + FIELD_LIST + ".");
        }
        for (String prefix : List.of(ATTRIBUTE, RESOURCE)) {
            if (name.startsWith(prefix)) {
                String key = name.substring(prefix.length());
                boolean attribute = prefix.equals(ATTRIBUTE);
                if (key.isEmpty()) {
                    throw usage(token, "names no key. Write " + prefix + "<key>, for example "
                            + prefix + (attribute ? "exception.type" : "service.version") + ".");
                }
                return new Field(attribute ? "attribute" : "resource", key);
            }
        }
        String wire = FIELDS.get(name);
        if (wire == null) {
            throw usage(token, "names the field '" + name + "', which qits does not know. Fields: " + FIELD_LIST + ".");
        }
        return new Field(wire, null);
    }

    /** The value after the operator: as written, or the inside of a double-quoted value. */
    private static String value(String token, String raw) throws CliFailure {
        if (!raw.startsWith("\"")) {
            if (raw.indexOf('"') >= 0) {
                throw usage(token, "has a quote inside the value. Quote the whole value: body~\"connection refused\".");
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

    /** A name as the server's floor takes it (WARNING is WARN), or the number 1-24. */
    private static Object severity(String token, String value) throws CliFailure {
        String name = value.toUpperCase(Locale.ROOT);
        if (name.equals("WARNING")) {
            name = "WARN";
        }
        if (SEVERITIES.contains(name)) {
            return name;
        }
        if (value.matches("[0-9]{1,2}")) {
            int number = Integer.parseInt(value);
            if (number >= 1 && number <= 24) {
                return number;
            }
        }
        throw usage(token, "names no severity. Give TRACE, DEBUG, INFO, WARN, ERROR, FATAL or a number 1-24.");
    }

    private static CliFailure levelTakesMinOnly(String token) {
        return usage(token, "does not work on level. The level takes >= only, for example level>=ERROR.");
    }

    private static boolean containsAny(String text, String characters) {
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static CliFailure usage(String token, String reason) {
        return new CliFailure("--filter: '" + token + "' " + reason, CliFailure.USAGE);
    }
}
