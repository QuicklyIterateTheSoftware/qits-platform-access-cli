package eu.wohlben.qits.cli.access.report;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code coverage} payload, version 1: line coverage of the whole tree and of the lines the fold
 * changed against its baseline. Every record here is written and read back by Jackson, hence the
 * registrations. Paths are relative to the step's root, with forward slashes, as the diff names them.
 *
 * @param sources       the tools that fed it, one per language and tool
 * @param total         every coverable line of every parsed file
 * @param baselineTotal the baseline's total; null without a baseline, or when the baseline has no
 *                      coverage report
 * @param diff          the changed lines' coverage; null without a baseline, or when the changed lines
 *                      could not be had
 * @param files         one line per file with a coverable line, in path order
 */
@RegisterForReflection
public record Coverage(List<Source> sources, Total total, BaselineTotal baselineTotal, Diff diff,
                       List<FileCoverage> files) {

    /** One tool in one language: {@code java}/{@code jacoco}, {@code typescript}/{@code vitest-coverage}. */
    @RegisterForReflection
    public record Source(String language, String tool) {
    }

    /** {@code percent} is null when no line is coverable. */
    @RegisterForReflection
    public record Total(int linesCovered, int linesTotal, Double percent) {
    }

    /** The baseline's {@link Total#percent()}, and which version it was. */
    @RegisterForReflection
    public record BaselineTotal(String version, Double percent) {
    }

    /**
     * Diff coverage.
     *
     * @param linesChanged only the coverable lines the fold added or changed
     * @param linesCovered those of them that are covered
     * @param percent      null when no changed line is coverable
     * @param uncovered    the rest, per file in path order, as inclusive line ranges
     */
    @RegisterForReflection
    public record Diff(String baselineVersion, int linesChanged, int linesCovered, Double percent,
                       List<Uncovered> uncovered) {
    }

    /** {@code ranges} are {@code [first, last]} pairs, 1-based and inclusive, ascending. */
    @RegisterForReflection
    public record Uncovered(String file, List<List<Integer>> ranges) {
    }

    @RegisterForReflection
    public record FileCoverage(String file, int linesCovered, int linesTotal) {
    }

    Coverage withBaselineTotal(BaselineTotal baseline) {
        return new Coverage(sources, total, baseline, diff, files);
    }
}
