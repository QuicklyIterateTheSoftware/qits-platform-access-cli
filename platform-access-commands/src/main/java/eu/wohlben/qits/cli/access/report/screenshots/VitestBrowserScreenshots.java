package eu.wohlben.qits.cli.access.report.screenshots;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vitest browser mode's baselines: a {@code __screenshots__} directory next to the spec, one directory
 * per spec file in it, and {@code <name>-<browser>-<platform>.png} in that:
 * {@code src/app/layout/shell/__screenshots__/shell.layout.browser.spec.ts/narrow-open-chromium-linux.png}.
 * <p>
 * Rendered in a step that runs on the renderer image ({@code /etc/qits-renderer-provenance}) or that
 * left {@code @qits/angular}'s run record behind. Only the app archetype's {@code node-browser-base}
 * step has either, which is what makes its two {@code release-request:} steps report once.
 */
public final class VitestBrowserScreenshots implements ScreenshotConvention {

    public static final String ID = "vitest-browser";
    public static final String TOOL = "vitest";

    /** The renderer image's record of what renders: playwright, chromium, the fonts. */
    public static final Path PROVENANCE = Path.of("/etc/qits-renderer-provenance");

    /** {@code screenshotReferences()} writes this when a browser run ends, relative to the project. */
    static final String RUN_RECORD = "node_modules/.cache/@qits/angular/screenshot-references.json";

    /** The committed copy of the provenance record, which {@code scripts/check-renderer.mjs} keeps. */
    static final String RENDERER_RECORD = "testing/browser/renderer.txt";

    /** {@code (<dir>/)__screenshots__/<spec file>/<file>.png}, the spec file directly under the directory. */
    private static final Pattern PATH = Pattern.compile("^(?:(.*)/)?__screenshots__/([^/]+)/([^/]+)\\.png$");

    private static final Pattern STEM = Pattern.compile("^(.*)-(chromium|firefox|webkit)-(linux|darwin|win32)$");

    private final Path provenance;

    public VitestBrowserScreenshots() {
        this(PROVENANCE);
    }

    /** @param provenance where the renderer image's provenance record is looked for */
    public VitestBrowserScreenshots(Path provenance) {
        this.provenance = provenance;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String tool() {
        return TOOL;
    }

    @Override
    public boolean renderedHere(Path root) {
        return Files.exists(provenance) || Files.exists(root.resolve(RUN_RECORD));
    }

    @Override
    public Optional<ScreenshotId> identify(String repoPath) {
        if (repoPath == null) {
            return Optional.empty();
        }
        Matcher path = PATH.matcher(repoPath);
        if (!path.matches()) {
            return Optional.empty();
        }
        String directory = path.group(1);
        String spec = directory == null ? path.group(2) : directory + "/" + path.group(2);
        String stem = path.group(3);
        Matcher split = STEM.matcher(stem);
        if (split.matches()) {
            return Optional.of(new ScreenshotId(spec, split.group(1), split.group(2), split.group(3)));
        }
        return Optional.of(new ScreenshotId(spec, stem, null, null));
    }

    /** The one path ending in {@code testing/browser/renderer.txt}; none when there are several. */
    @Override
    public Optional<String> rendererRecord(Set<String> repoPaths) {
        List<String> found = repoPaths.stream()
                .filter(p -> p.equals(RENDERER_RECORD) || p.endsWith("/" + RENDERER_RECORD))
                .toList();
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }
}
