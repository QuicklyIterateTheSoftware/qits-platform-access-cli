package eu.wohlben.qits.cli.access.report.contracts;

/**
 * One call a golden-master index records: a state's {@code operations[]} entry, as far as {@code qits
 * contracts coverage} reads it. Never written by Jackson, so no registration.
 *
 * @param operationId the provider's operationId, or null when the recorder had none
 * @param method      the HTTP method as recorded ({@code GET}), or null
 * @param path        the path as recorded, a template or a concrete path, maybe with a query; or null
 */
public record RecordedOperation(String operationId, String method, String path) {
}
