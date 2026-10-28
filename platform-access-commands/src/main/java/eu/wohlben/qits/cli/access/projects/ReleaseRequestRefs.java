package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * How the commands name a release request (qits-1158). A request has two ids: its logical one,
 * {@code <repository>-rr-<n>}, which people read and type, and its uuid, the internal key. The
 * commands show the logical id and accept either, and a uuid's start too, for old habits. An older
 * service sends no {@code qualifiedId}: the commands then show the uuid as before.
 */
public final class ReleaseRequestRefs {

    /** {@code <repository>-rr-<n>}. Only the shape: the service decides which ones exist. */
    private static final Pattern QUALIFIED = Pattern.compile("(?i)\\S+-rr-\\d+");

    private ReleaseRequestRefs() {
    }

    /** True when {@code ref} has the shape of a logical id. */
    public static boolean isQualified(String ref) {
        return ref != null && QUALIFIED.matcher(ref.strip()).matches();
    }

    /** What a table shows: the logical id, else the uuid's first 8 characters. */
    public static String label(JsonNode request) {
        return label(text(request, "qualifiedId"), text(request, "id"));
    }

    /** The logical id, else the uuid's first 8 characters. Either may be blank. */
    public static String label(String qualifiedId, String id) {
        if (!qualifiedId.isBlank()) {
            return qualifiedId;
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    /** What a sentence names: the logical id, else the whole uuid. */
    public static String name(JsonNode request) {
        String qualifiedId = text(request, "qualifiedId");
        return qualifiedId.isBlank() ? text(request, "id") : qualifiedId;
    }

    /**
     * True when {@code wanted} names the request with these ids: its logical id (any case), or the
     * start of its uuid.
     */
    public static boolean matches(String qualifiedId, String id, String wanted) {
        String given = wanted.strip();
        if (!qualifiedId.isBlank() && qualifiedId.equalsIgnoreCase(given)) {
            return true;
        }
        return !id.isBlank() && id.toLowerCase(Locale.ROOT).startsWith(given.toLowerCase(Locale.ROOT));
    }

    /**
     * The one request of {@code requests} that {@code wanted} names. A logical id that fits one request
     * wins; else the uuid's start must fit exactly one. {@code whereListed} ends the refusal: the
     * command that lists the requests, or blank.
     */
    public static JsonNode find(JsonNode requests, String wanted, String repoName, String whereListed)
            throws CliFailure {
        String given = wanted.strip();
        List<JsonNode> all = new ArrayList<>();
        requests.forEach(all::add);
        for (JsonNode r : all) {
            String qualifiedId = text(r, "qualifiedId");
            if (!qualifiedId.isBlank() && qualifiedId.equalsIgnoreCase(given)) {
                return r;
            }
        }
        String start = given.toLowerCase(Locale.ROOT);
        List<JsonNode> matches = all.stream()
                .filter(r -> text(r, "id").toLowerCase(Locale.ROOT).startsWith(start))
                .toList();
        if (matches.isEmpty()) {
            throw new CliFailure("Repository " + repoName + " has no release request '" + given + "'. Give its id ("
                    + repoName + "-rr-<n>), its uuid or the start of the uuid."
                    + (whereListed.isBlank() ? "" : " " + whereListed), CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + given + "' is the start of more than one release request of " + repoName + ": "
                    + matches.stream().map(r -> describe(r)).collect(Collectors.joining(", "))
                    + ". Give its id or more of the uuid.", CliFailure.USAGE);
        }
        return matches.getFirst();
    }

    /**
     * What a request releases, on one line: its items' labels joined with "; " (qits-1673), one item
     * per branch somebody named. The platform no longer writes a summary. An older platform sends no
     * items, and a request that holds only main has none: the line is then the branches, main left
     * out while there is another.
     */
    public static String items(JsonNode request) {
        List<String> labels = new ArrayList<>();
        request.path("items").forEach(item -> {
            String label = text(item, "label");
            if (!label.isBlank()) {
                labels.add(label);
            }
        });
        if (!labels.isEmpty()) {
            return String.join("; ", labels);
        }
        java.util.LinkedHashSet<String> branches = new java.util.LinkedHashSet<>();
        request.path("sources").forEach(source -> {
            String name = text(source, "name");
            if (!name.isBlank()) {
                branches.add(name);
            }
        });
        if (branches.size() > 1) {
            branches.remove("main");
        }
        return String.join("; ", branches);
    }

    /** {@code <qualified id> <uuid> (<state>)}, or without the logical id when the service sent none. */
    private static String describe(JsonNode r) {
        String qualifiedId = text(r, "qualifiedId");
        String state = text(r, "state");
        return (qualifiedId.isBlank() ? "" : qualifiedId + " ") + text(r, "id")
                + (state.isBlank() ? "" : " (" + state + ")");
    }
}
