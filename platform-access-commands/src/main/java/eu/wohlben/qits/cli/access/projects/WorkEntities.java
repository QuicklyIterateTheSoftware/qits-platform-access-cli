package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.Table;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * What the {@code qits work} commands share: the full state a transition starts from, RFC 7396's
 * merge, what a target schema has no slot for, and the tables. Every value a person sees goes
 * through {@link SafeText}.
 */
final class WorkEntities {

    /**
     * The properties of a row that a transition carries, in the names the transition door reads.
     * {@code membership} is built from {@code parent} and {@code position}. These are the door's
     * field names, not a schema: which of them a target archetype takes is the served schema's call,
     * and whatever it does not list is dropped.
     */
    static final List<String> CARRIED = List.of("title", "description", "status", "ticketType", "impetus", "assignee",
            "supersededBy", "repositoryId", "implementingAt", "implementedAt", "dependsOn", "acceptanceCriteria");

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private WorkEntities() {
    }

    /** The UUID of an entity named by its id or its qualified id; a qualified id is looked up. */
    static String uuid(ProjectsApi api, String entity) throws CliFailure, InterruptedException {
        return UUID.matcher(entity).matches() ? entity : text(api.entity(entity), "id");
    }

    /** The row as the full state the transition door takes: what it carries now, nothing else. */
    static ObjectNode currentState(JsonNode row) {
        ObjectNode state = JsonNodeFactory.instance.objectNode();
        for (String field : CARRIED) {
            JsonNode value = row.get(field);
            if (value != null && !value.isNull()) {
                state.set(field, value.deepCopy());
            }
        }
        JsonNode parent = row.get("parent");
        if (parent != null && !parent.isNull()) {
            ObjectNode membership = state.putObject("membership");
            membership.set("parent", parent.deepCopy());
            JsonNode position = row.get("position");
            if (position != null && !position.isNull()) {
                membership.set("position", position.deepCopy());
            }
        }
        return state;
    }

    /** RFC 7396: the patch applied to a copy of the target. Null removes, an object merges, anything else replaces. */
    static JsonNode mergePatch(JsonNode target, JsonNode patch) {
        if (!patch.isObject()) {
            return patch.deepCopy();
        }
        ObjectNode result = target != null && target.isObject() ? ((ObjectNode) target).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> field : patch.properties()) {
            if (field.getValue().isNull()) {
                result.remove(field.getKey());
            } else {
                result.set(field.getKey(), mergePatch(result.get(field.getKey()), field.getValue()));
            }
        }
        return result;
    }

    /** The properties of {@code state} the schema lists no slot for, in the state's order. */
    static List<String> unslotted(JsonNode schema, JsonNode state) throws CliFailure {
        JsonNode properties = properties(schema);
        List<String> none = new ArrayList<>();
        state.fieldNames().forEachRemaining(name -> {
            if (!properties.has(name)) {
                none.add(name);
            }
        });
        return none;
    }

    /**
     * The schema's required properties the state does not carry. An object property the state has
     * is looked into one level ({@code membership.parent}); one it lacks is named with what it
     * requires, so the answer says what to write.
     */
    static List<String> missing(JsonNode schema, JsonNode state) throws CliFailure {
        JsonNode properties = properties(schema);
        List<String> missing = new ArrayList<>();
        for (JsonNode required : schema.path("required")) {
            String name = required.asText();
            JsonNode inner = properties.path(name).path("required");
            JsonNode value = state.get(name);
            if (value == null || value.isNull()) {
                if (inner.isArray() && !inner.isEmpty()) {
                    inner.forEach(r -> missing.add(SafeText.line(name + "." + r.asText())));
                } else {
                    missing.add(SafeText.line(name));
                }
            } else if (inner.isArray() && value.isObject()) {
                inner.forEach(r -> {
                    if (!value.hasNonNull(r.asText())) {
                        missing.add(SafeText.line(name + "." + r.asText()));
                    }
                });
            }
        }
        return missing;
    }

    private static JsonNode properties(JsonNode schema) throws CliFailure {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            throw new CliFailure("Nothing was sent. The service's schema lists no properties, so what the target "
                    + "archetype takes cannot be told.", CliFailure.FAILED);
        }
        return properties;
    }

    /** The registry's entry for one archetype; null when it has none. */
    static JsonNode declared(JsonNode registry, String archetype) {
        for (JsonNode entry : registry.path("archetypes")) {
            if (archetype.equals(text(entry, "archetype"))) {
                return entry;
            }
        }
        return null;
    }

    static List<String> texts(JsonNode array) {
        List<String> all = new ArrayList<>();
        array.forEach(v -> all.add(SafeText.line(v.asText())));
        return all;
    }

    /** The entities of a list answer, {@code {"entities":[…]}}. */
    static List<JsonNode> list(JsonNode answer) {
        List<JsonNode> all = new ArrayList<>();
        answer.path("entities").forEach(e -> {
            if (e.isObject()) {
                all.add(e);
            }
        });
        return all;
    }

    /** A write's answer: as JSON, or the one row the item has in a list. */
    static void printAnswer(PrintStream out, boolean json, JsonNode answer) throws CliFailure {
        if (json) {
            SafeJson.print(out, answer);
            return;
        }
        printRows(out, "", List.of(answer));
    }

    /** ID (qualified), ARCHETYPE, STATUS, BLOCKED when one is, TITLE, UPDATED. */
    static void printRows(PrintStream out, String indent, List<JsonNode> entities) {
        boolean anyBlocked = entities.stream().anyMatch(WorkEntities::blocked);
        List<String> headers = new ArrayList<>(List.of("ID", "ARCHETYPE", "STATUS"));
        if (anyBlocked) {
            headers.add("BLOCKED");
        }
        headers.add("TITLE");
        headers.add("UPDATED");
        Table.print(out, indent, headers, entities.stream().map(e -> {
            List<String> row = new ArrayList<>(List.of(cell(label(e), 40), cell(text(e, "archetype"), 9),
                    // 11, so that IMPLEMENTED, the longest status, is not cut
                    cell(text(e, "status"), 11)));
            if (anyBlocked) {
                row.add(blocked(e) ? "yes" : "-");
            }
            row.add(cell(text(e, "title"), 70));
            row.add(Table.time(text(e, "updatedAt")));
            return row;
        }).toList());
    }

    /** One item in full: its fields, its description, its thread and its children. */
    static void printDetails(PrintStream out, JsonNode e, List<JsonNode> comments, List<JsonNode> children) {
        out.println(SafeText.line(text(e, "archetype")) + " " + SafeText.line(label(e)) + "  ("
                + SafeText.line(text(e, "id")) + ")");
        List<List<String>> rows = new ArrayList<>();
        rows.add(row("title", text(e, "title")));
        rows.add(row("status", text(e, "status")));
        if (e.has("blocked") && !e.get("blocked").isNull()) {
            rows.add(row("blocked", blocked(e) ? "yes" : "no"));
        }
        optional(rows, e, "ticket type", "ticketType");
        optional(rows, e, "impetus", "impetus");
        optional(rows, e, "assignee", "assignee");
        optional(rows, e, "parent", "parent");
        optional(rows, e, "position", "position");
        optional(rows, e, "depends on", "dependsOn");
        optional(rows, e, "repository", "repositoryId");
        optional(rows, e, "superseded by", "supersededBy");
        if (!text(e, "implementedAt").isEmpty()) {
            rows.add(List.of("implemented", Table.time(text(e, "implementedAt"))));
        }
        rows.add(row("created by", text(e, "createdBy")));
        rows.add(List.of("created", Table.time(text(e, "createdAt"))));
        rows.add(List.of("updated", Table.time(text(e, "updatedAt"))));
        Table.print(out, "  ", null, rows);
        out.println("Description:");
        String description = text(e, "description");
        if (description.isBlank()) {
            out.println("  (none)");
        } else {
            lines(out, "  ", description);
        }
        acceptanceCriteria(out, e);
        if (comments.isEmpty()) {
            out.println("Comments: none.");
        } else {
            out.println("Comments (" + comments.size() + "):");
            for (JsonNode comment : comments) {
                out.println("  " + Table.time(text(comment, "createdAt")) + "  " + cell(text(comment, "author"), 80));
                lines(out, "    ", text(comment, "body"));
            }
        }
        if (children.isEmpty()) {
            out.println("Children: none.");
        } else {
            out.println("Children (" + children.size() + "):");
            printRows(out, "  ", children);
        }
    }

    /** Acceptance criteria, one numbered Markdown line each; nothing printed when the archetype carries none. */
    private static void acceptanceCriteria(PrintStream out, JsonNode e) {
        JsonNode criteria = e.path("acceptanceCriteria");
        if (!criteria.isArray() || criteria.isEmpty()) {
            return;
        }
        out.println("Acceptance criteria:");
        int n = 1;
        for (JsonNode item : criteria) {
            out.println("  " + n + ". " + SafeText.line(item.asText()));
            n++;
        }
    }

    private static void optional(List<List<String>> rows, JsonNode e, String name, String field) {
        String value = text(e, field);
        if (!value.isEmpty()) {
            rows.add(row(name, value));
        }
    }

    /** The qualified id, which is what a person types; the id when the service sent none. */
    private static String label(JsonNode e) {
        String qualified = text(e, "qualifiedId");
        return qualified.isEmpty() ? text(e, "id") : qualified;
    }

    /** Blocked means the phase the item's status belongs to cannot proceed. Only tickets carry it. */
    private static boolean blocked(JsonNode e) {
        return e.path("blocked").asBoolean(false);
    }

    /** The text a line at a time, each line cleaned on its own and indented. */
    private static void lines(PrintStream out, String indent, String text) {
        for (String line : text.split("\\R", -1)) {
            out.println((indent + SafeText.line(line)).stripTrailing());
        }
    }

    private static List<String> row(String name, String value) {
        return List.of(name, cell(value, 600));
    }

    private static String cell(String value, int max) {
        return Table.cell(SafeText.line(value), max);
    }
}
