package eu.wohlben.qits.cli.access.report.screenshots;

import java.nio.file.Path;
import java.util.List;

/** The one list of {@link ScreenshotConvention}s. A new convention is one more class here. */
public final class ScreenshotConventions {

    private ScreenshotConventions() {
    }

    /** Every convention, deciding "rendered here" by the renderer image's real provenance record. */
    public static List<ScreenshotConvention> standard() {
        return standard(VitestBrowserScreenshots.PROVENANCE);
    }

    /** Every convention, with the renderer image's provenance record looked for at {@code rendererProvenance}. */
    public static List<ScreenshotConvention> standard(Path rendererProvenance) {
        return List.of(new VitestBrowserScreenshots(rendererProvenance));
    }
}
