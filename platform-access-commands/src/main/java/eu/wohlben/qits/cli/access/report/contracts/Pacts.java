package eu.wohlben.qits.cli.access.report.contracts;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reading one pact document, whichever specification wrote it: v4 ({@code interactions[]}, each with a
 * {@code type}), v3 ({@code interactions[]} with {@code providerStates[]}, and {@code messages[]}) and
 * v2 or older ({@code providerState} as one string).
 */
final class Pacts {

    private Pacts() {
    }

    /** The pact's declared specification version, {@code 4.0}; null when it declares none. */
    static String specification(JsonNode pact) {
        JsonNode metadata = pact.path("metadata");
        for (String field : List.of("pactSpecification", "pact-specification")) {
            JsonNode version = metadata.path(field).path("version");
            if (version.isValueNode() && !version.asText().isBlank()) {
                return version.asText();
            }
        }
        JsonNode old = metadata.path("pactSpecificationVersion");
        return old.isValueNode() && !old.asText().isBlank() ? old.asText() : null;
    }

    /**
     * {@code pact-v4}, {@code pact-v3} or {@code pact-v2}: the declared major version, or, without one,
     * what the interactions look like.
     */
    static int major(JsonNode pact, String specification) {
        if (specification != null) {
            String digits = specification.strip().replaceFirst("^[vV]", "");
            int dot = digits.indexOf('.');
            try {
                return Integer.parseInt(dot < 0 ? digits : digits.substring(0, dot));
            } catch (NumberFormatException notANumber) {
                // fall through to the shape
            }
        }
        for (JsonNode interaction : pact.path("interactions")) {
            if (interaction.has("type")) {
                return 4;
            }
            if (interaction.has("providerStates")) {
                return 3;
            }
        }
        return pact.has("messages") ? 3 : 2;
    }

    /** The consumer's or the provider's name; null when the pact names none. */
    static String name(JsonNode pact, String party) {
        JsonNode name = pact.path(party).path("name");
        return name.isTextual() && !name.asText().isBlank() ? name.asText() : null;
    }

    /** Every interaction and message of the pact, unordered. */
    static List<InteractionEntry> interactions(JsonNode pact, String verified) {
        List<InteractionEntry> found = new ArrayList<>();
        for (JsonNode interaction : pact.path("interactions")) {
            if (interaction.isObject()) {
                found.add(interaction(interaction, verified));
            }
        }
        for (JsonNode message : pact.path("messages")) {
            if (message.isObject()) {
                found.add(entry(message, InteractionEntry.ASYNC_MESSAGE, verified));
            }
        }
        return found;
    }

    /** One element of {@code interactions[]}; its {@code type} says what it is, and HTTP without one. */
    static InteractionEntry interaction(JsonNode interaction, String verified) {
        JsonNode type = interaction.path("type");
        return entry(interaction, type.isTextual() && !type.asText().isBlank() ? type.asText() : InteractionEntry.HTTP,
                verified);
    }

    private static InteractionEntry entry(JsonNode interaction, String type, String verified) {
        boolean http = type.equals(InteractionEntry.HTTP);
        boolean message = !http && !interaction.has("request");
        String method = null;
        String path = null;
        Integer status = null;
        if (http) {
            JsonNode request = interaction.path("request");
            method = request.path("method").isTextual() ? request.path("method").asText().toUpperCase(Locale.ROOT) : null;
            path = request.path("path").isTextual() ? request.path("path").asText() : null;
            JsonNode code = interaction.path("response").path("status");
            status = code.canConvertToInt() ? code.asInt() : null;
        }
        return new InteractionEntry(text(interaction.path("description")), states(interaction), type, method, path,
                status, ContentHash.of(interaction, message), verified);
    }

    /** The names of the interaction's provider states: {@code providerStates[].name}, or v2's {@code providerState}. */
    static List<String> states(JsonNode interaction) {
        List<String> names = new ArrayList<>();
        for (JsonNode state : interaction.path("providerStates")) {
            JsonNode name = state.isTextual() ? state : state.path("name");
            if (name.isTextual()) {
                names.add(name.asText());
            }
        }
        for (String single : List.of("providerState", "provider_state")) {
            JsonNode state = interaction.path(single);
            if (names.isEmpty() && state.isTextual() && !state.asText().isBlank()) {
                names.add(state.asText());
            }
        }
        return names;
    }

    private static String text(JsonNode node) {
        return node.isValueNode() ? node.asText() : "";
    }
}
