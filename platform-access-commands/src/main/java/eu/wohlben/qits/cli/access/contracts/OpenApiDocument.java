package eu.wohlben.qits.cli.access.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The operations an OpenAPI 3 document declares, and the prefixes its servers put in front of them.
 * YAML (what Quarkus' smallrye-openapi writes to {@code docs/openapi.yml}) or, by its extension, JSON;
 * either is read as a tree, never bound, so the native image needs no reflection for it.
 *
 * @param servers    each server's path prefix, without a trailing {@code /}; {@code ""} is no prefix. A
 *                   document without servers has the one, {@code ""}
 * @param operations every {@code paths.<path>.<method>}, in document order
 */
public record OpenApiDocument(List<String> servers, List<ApiOperation> operations) {

    /** The methods a path item may hold operations under; its other members are not operations. */
    static final List<String> METHODS = List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ORIGIN = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/]*");

    public OpenApiDocument {
        servers = List.copyOf(servers);
        operations = List.copyOf(operations);
    }

    /**
     * @throws IOException when the file does not parse, or is not an OpenAPI 3 document
     */
    public static OpenApiDocument read(Path file) throws IOException {
        boolean json = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
        JsonNode document = (json ? JSON : YAML).readTree(file.toFile());
        if (document == null || !document.isObject()) {
            throw new IOException("not an OpenAPI document: not an object");
        }
        JsonNode version = document.path("openapi");
        if (!version.isTextual() || !version.asText().startsWith("3.")) {
            throw new IOException("not an OpenAPI 3 document: openapi is " + (version.isMissingNode() ? "missing"
                    : version.asText()));
        }
        return new OpenApiDocument(servers(document.path("servers")), operations(document.path("paths")));
    }

    private static List<String> servers(JsonNode servers) {
        List<String> prefixes = new ArrayList<>();
        for (JsonNode server : servers) {
            JsonNode url = server.path("url");
            if (!url.isTextual()) {
                continue;
            }
            String prefix = ORIGIN.matcher(url.asText().strip()).replaceFirst("");
            prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
            if (!prefixes.contains(prefix)) {
                prefixes.add(prefix);
            }
        }
        return prefixes.isEmpty() ? List.of("") : prefixes;
    }

    private static List<ApiOperation> operations(JsonNode paths) throws IOException {
        if (paths.isMissingNode() || paths.isNull()) {
            return List.of();
        }
        if (!paths.isObject()) {
            throw new IOException("not an OpenAPI document: paths is not an object");
        }
        List<ApiOperation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> member : path.getValue().properties()) {
                String method = member.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method) || !member.getValue().isObject()) {
                    continue;
                }
                JsonNode id = member.getValue().path("operationId");
                operations.add(new ApiOperation(id.isTextual() && !id.asText().isBlank() ? id.asText() : null,
                        method.toUpperCase(Locale.ROOT), path.getKey()));
            }
        }
        return operations;
    }
}
