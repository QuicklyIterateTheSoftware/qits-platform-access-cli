package eu.wohlben.qits.cli.access.report.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.report.ReportJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What tells a changed interaction from an unchanged one: {@code sha256:} and the hex SHA-256 of the
 * canonical JSON (keys sorted, no whitespace) of what the interaction exchanges, {@code {request,
 * response}} for HTTP and a synchronous message, {@code {contents, metadata}} for an asynchronous one.
 * What a pact library writes beside the exchange ({@code key}, {@code comments}, {@code pending},
 * {@code _id}, its own name under {@code metadata}) is left out, so a library bump is not a change.
 */
public final class ContentHash {

    /** Interaction fields that say nothing about the exchange itself. */
    static final Set<String> IGNORED = Set.of("key", "comments", "pending", "_id");

    /** The pact libraries' own entries under a {@code metadata} object. */
    static final Set<String> IGNORED_METADATA = Set.of("pactJvm", "pactJs", "pact-jvm", "pact-js", "pactRust");

    private ContentHash() {
    }

    /** The hash of one interaction, as a pact file holds it. */
    public static String of(JsonNode interaction, boolean message) {
        ObjectNode exchanged = JsonNodeFactory.instance.objectNode();
        for (String field : message ? List.of("contents", "metadata") : List.of("request", "response")) {
            JsonNode value = interaction.get(field);
            exchanged.set(field, value == null ? JsonNodeFactory.instance.nullNode() : value);
        }
        return "sha256:" + sha256(canonical(exchanged));
    }

    /** {@code node} with every object's keys sorted and the ignored fields gone, written with no whitespace. */
    static String canonical(JsonNode node) {
        try {
            return ReportJson.MAPPER.writeValueAsString(sorted(node, false));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonNode sorted(JsonNode node, boolean metadata) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String name = field.getKey();
                if (IGNORED.contains(name) || metadata && IGNORED_METADATA.contains(name)) {
                    continue;
                }
                names.add(name);
            }
            names.sort(null);
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                copy.set(name, sorted(node.get(name), name.equals("metadata")));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                copy.add(sorted(element, false));
            }
            return copy;
        }
        return node;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
