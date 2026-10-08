package eu.wohlben.qits.cli.access.report.screenshots;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code screenshots} payload, version 1 (epic qits-762, Design §1): the committed screenshot
 * baselines at the fold against those at the baseline's tag, as paths and blob ids. No image bytes:
 * the view reads those from qits-githost's raw door, by {@code repositoryId}, revision and path. Every
 * record here is written and read back by Jackson, hence the registrations.
 *
 * @param repositoryId the githost storage id ({@code QITS_CI_REPO_ID}); null when the step does not
 *                     say, and the view then lists paths without images
 * @param headSha      the fold's commit ({@code QITS_CI_SHA})
 * @param baseline     what was compared with; null without a baseline or when its tag could not be had
 * @param conventions  the conventions that rendered in this step
 * @param totals       always complete, whatever {@code entries} holds
 * @param renderer     the renderer record's change; null without a baseline or without a record
 * @param entries      NEW, CHANGED, REMOVED, then by path; at most {@value ScreenshotsReportKind#MAX_ENTRIES}
 * @param truncated    true when more entries existed than are listed
 */
@RegisterForReflection
public record ScreenshotsReport(String repositoryId, String headSha, BaselineSide baseline,
                                List<Convention> conventions, Totals totals, Renderer renderer,
                                List<Entry> entries, boolean truncated) {

    public static final String NEW = "NEW";
    public static final String CHANGED = "CHANGED";
    public static final String REMOVED = "REMOVED";

    public ScreenshotsReport {
        conventions = List.copyOf(conventions);
        entries = List.copyOf(entries);
    }

    /**
     * @param version   the baseline's version, which is also its tag
     * @param commitSha the tag peeled to its commit: the {@code rev} the view reads "before" at
     */
    @RegisterForReflection
    public record BaselineSide(String version, String commitSha) {
    }

    @RegisterForReflection
    public record Convention(String id, String tool) {
    }

    /**
     * @param screenshots the files at the fold
     * @param added       {@code new} on the wire: only at the fold
     * @param changed     on both sides, with another blob
     * @param removed     only at the baseline
     * @param unchanged   on both sides, with the same blob; counted, never listed
     */
    @RegisterForReflection
    public record Totals(int screenshots, @JsonProperty("new") int added, int changed, int removed, int unchanged) {
    }

    /**
     * @param path    the record at the fold, else at the baseline
     * @param changed true when a key's value differs between the two sides
     * @param entries the keys whose values differ, by key; values at most
     *                {@value ScreenshotsReportKind#MAX_RENDERER_VALUE} characters
     */
    @RegisterForReflection
    public record Renderer(String path, boolean changed, List<RendererEntry> entries) {

        public Renderer {
            entries = List.copyOf(entries);
        }
    }

    /** One key: {@code before} is null when it was added, {@code after} when it was removed. */
    @RegisterForReflection
    public record RendererEntry(String key, String before, String after) {
    }

    /**
     * One baseline image that is not unchanged.
     *
     * @param status      {@code NEW}, {@code CHANGED} or {@code REMOVED}
     * @param path        from the repository's root
     * @param convention  the convention that identified it
     * @param beforeBlob  the blob at the baseline; null for NEW
     * @param afterBlob   the blob at the fold; null for REMOVED
     * @param movedFrom   on a NEW entry: the REMOVED path with the very same blob
     * @param movedTo     on a REMOVED entry: the NEW path with the very same blob
     */
    @RegisterForReflection
    public record Entry(String status, String path, String convention, String spec, String name, String browser,
                        String platform, String beforeBlob, String afterBlob, Long beforeBytes, Long afterBytes,
                        String movedFrom, String movedTo) {
    }
}
