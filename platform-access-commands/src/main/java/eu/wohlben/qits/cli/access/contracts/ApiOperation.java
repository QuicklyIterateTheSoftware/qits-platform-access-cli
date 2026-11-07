package eu.wohlben.qits.cli.access.contracts;

import java.util.Locale;

/**
 * One operation of an OpenAPI document: a method under a path.
 *
 * @param operationId its operationId, or null when the document gives none
 * @param method      upper case: {@code GET}
 * @param path        the document's path template, without a server's prefix: {@code /work/{id}}
 */
public record ApiOperation(String operationId, String method, String path) {

    /** Its operationId when it has one, else {@code METHOD /path}. */
    public String name() {
        return operationId != null ? operationId : method.toUpperCase(Locale.ROOT) + " " + path;
    }
}
