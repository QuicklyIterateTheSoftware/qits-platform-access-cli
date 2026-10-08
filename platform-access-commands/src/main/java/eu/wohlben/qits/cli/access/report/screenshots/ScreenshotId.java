package eu.wohlben.qits.cli.access.report.screenshots;

/**
 * What a baseline image shows, as its convention reads it off the path.
 *
 * @param spec     the spec file that takes it, from the repository's root:
 *                 {@code src/app/layout/shell/shell.layout.browser.spec.ts}
 * @param name     the screenshot's name in that spec: {@code narrow-open}
 * @param browser  {@code chromium}, {@code firefox} or {@code webkit}; null when the file name does not say
 * @param platform {@code linux}, {@code darwin} or {@code win32}; null when the file name does not say
 */
public record ScreenshotId(String spec, String name, String browser, String platform) {
}
