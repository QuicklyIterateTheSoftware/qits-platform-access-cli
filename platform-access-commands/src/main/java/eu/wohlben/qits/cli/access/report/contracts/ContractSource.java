package eu.wohlben.qits.cli.access.report.contracts;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One file the report was read from.
 *
 * @param file   relative to the step's root, with forward slashes
 * @param format {@code pact-v4}, {@code pact-v3}, {@code pact-v2}, {@code pact-jvm-json-report} or
 *               {@code golden-master-index-v1}
 */
@RegisterForReflection
public record ContractSource(String language, String tool, String file, String format) {
}
