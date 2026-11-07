package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.access.report.contracts.RecordedOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which operations of an OpenAPI document a golden-master index records (qits-1940). An operation is
 * covered when a recorded call
 * <ol>
 *     <li>has its operationId, or</li>
 *     <li>has its method (in any case) and a path, its query left off, that is one of the document's
 *     server prefixes followed by the operation's path template.</li>
 * </ol>
 * A path is compared segment by segment: a {@code {name}} in the server's url or the document's path
 * stands for one non-empty segment (or the part of one it takes up), everything else is literal. A
 * template in the recorded path is literal too, so {@code {id}} there is covered by a {@code {x}} in
 * the document and by nothing else. One trailing {@code /} is ignored on either side, except on
 * {@code /} itself.
 * <p>
 * Only the document's own servers are prefixes; a document without servers has the empty one. A
 * recorded {@code /foo} does not cover {@code /api/foo} served under {@code /api}: it is a call that
 * never reached that operation.
 */
public final class GoldenMasterMatch {

    private static final Pattern VARIABLE = Pattern.compile("\\{[^/{}]*}");

    private GoldenMasterMatch() {
    }

    /** The document's operations that no recorded call covers, in document order. */
    public static List<ApiOperation> uncovered(OpenApiDocument document, List<RecordedOperation> recorded) {
        List<ApiOperation> uncovered = new ArrayList<>();
        for (ApiOperation operation : document.operations()) {
            if (recorded.stream().noneMatch(call -> covers(call, operation, document.servers()))) {
                uncovered.add(operation);
            }
        }
        return uncovered;
    }

    /** Whether {@code call} covers {@code operation} under one of {@code servers}' prefixes. */
    static boolean covers(RecordedOperation call, ApiOperation operation, List<String> servers) {
        if (operation.operationId() != null && operation.operationId().equals(call.operationId())) {
            return true;
        }
        if (call.method() == null || call.path() == null || !call.method().equalsIgnoreCase(operation.method())) {
            return false;
        }
        String path = normalise(withoutQuery(call.path()));
        for (String server : servers) {
            if (template(normalise(server + operation.path())).matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /** A template as a pattern: each {@code {name}} one or more characters of one segment. */
    static Pattern template(String template) {
        StringBuilder pattern = new StringBuilder();
        Matcher variable = VARIABLE.matcher(template);
        int literal = 0;
        while (variable.find()) {
            pattern.append(Pattern.quote(template.substring(literal, variable.start()))).append("[^/]+");
            literal = variable.end();
        }
        return Pattern.compile(pattern.append(Pattern.quote(template.substring(literal))).toString());
    }

    private static String withoutQuery(String path) {
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        int end = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
        return end < 0 ? path : path.substring(0, end);
    }

    private static String normalise(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
