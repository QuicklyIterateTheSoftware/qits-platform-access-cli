package eu.wohlben.qits.cli.access.report;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code stale-outputs} payload, version 1 (qits-1920): the {@code check-outputs:} kinds the QA
 * step found stale, each with the command that regenerates it. Written and read back by Jackson, hence
 * the registrations.
 *
 * @param kinds one per stale output kind, in the order the step checked them
 */
@RegisterForReflection
public record StaleOutputs(List<Kind> kinds) {

    public StaleOutputs {
        kinds = List.copyOf(kinds);
    }

    /**
     * @param kind       the output kind, as {@code release.yml} names it under {@code check-outputs:}:
     *                   {@code cli-tapes}
     * @param regenerate the command that regenerates it at the release request
     */
    @RegisterForReflection
    public record Kind(String kind, String regenerate) {
    }
}
