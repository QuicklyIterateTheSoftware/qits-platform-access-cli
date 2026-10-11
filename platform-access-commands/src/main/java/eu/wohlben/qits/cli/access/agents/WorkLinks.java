package eu.wohlben.qits.cli.access.agents;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Work ids such as {@code qits-111} as links to the landing app, in text Claude shows.
 * <p>
 * An id is linked only where it stands alone. A branch ({@code ticket/qits-1152}), a longer name
 * ({@code qits-1152-agent-worktrees}), a link that is already there ({@code [qits-7](…)}), inline
 * code and fenced code blocks stay as they are.
 */
public final class WorkLinks {

    /** How a link is written. */
    public enum Style {
        /** {@code [qits-111](url)}. */
        MARKDOWN,
        /** A terminal hyperlink (OSC 8): the id alone on screen, the url behind it. */
        OSC8;

        /** {@code markdown} or {@code osc8}; anything else is null. */
        public static Style of(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "markdown" -> MARKDOWN;
                case "osc8" -> OSC8;
                default -> null;
            };
        }
    }

    /** One work id and the project slug it belongs to: {@code qits-111} of {@code qits}. */
    public record WorkId(String slug, String id) {
    }

    /** The text as shown, and whether a code fence is still open at its end. */
    public record Result(String text, boolean inFence) {
    }

    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");

    private final Pattern id;
    private final String landing;
    private final Style style;

    /**
     * @param slugs   the project slugs whose ids are linked
     * @param landing the landing app's base URL, without a trailing slash
     */
    public WorkLinks(Collection<String> slugs, String landing, Style style) {
        List<String> quoted = new ArrayList<>();
        // Longest first, so `qits-ci-5` is never read as `qits` followed by something else.
        slugs.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).distinct()
                .sorted((a, b) -> b.length() - a.length()).forEach(s -> quoted.add(Pattern.quote(s)));
        this.id = quoted.isEmpty() ? null : Pattern.compile(
                "(?<![\\w/.\\-\\[])(" + String.join("|", quoted) + ")-(\\d+)(?![\\w\\-]|\\]\\()",
                Pattern.UNICODE_CHARACTER_CLASS);
        this.landing = landing;
        this.style = style;
    }

    /**
     * @param inFence whether a code fence was open where this text starts (a fence can span batches)
     */
    public Result link(String text, boolean inFence) {
        StringBuilder out = new StringBuilder(text.length() + 64);
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            String line = text.substring(start, end);
            start = end;
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                out.append(line);
            } else if (inFence || id == null) {
                out.append(line);
            } else {
                linkLine(line, out);
            }
        }
        return new Result(out.toString(), inFence);
    }

    /**
     * Every id in {@code text}, in order, by the same rules as {@link #link}, but in code too: the
     * status line reads tool output, which is not markdown.
     */
    public List<WorkId> ids(String text) {
        List<WorkId> found = new ArrayList<>();
        if (id != null) {
            Matcher m = id.matcher(text);
            while (m.find()) {
                found.add(new WorkId(m.group(1), m.group()));
            }
        }
        return found;
    }

    /** Links one line outside a fence, leaving its inline code alone. */
    private void linkLine(String line, StringBuilder out) {
        Matcher code = INLINE_CODE.matcher(line);
        int at = 0;
        while (code.find()) {
            linkText(line.substring(at, code.start()), out);
            out.append(code.group());
            at = code.end();
        }
        linkText(line.substring(at), out);
    }

    private void linkText(String text, StringBuilder out) {
        Matcher m = id.matcher(text);
        int at = 0;
        while (m.find()) {
            out.append(text, at, m.start()).append(link(m.group(1), m.group()));
            at = m.end();
        }
        out.append(text, at, text.length());
    }

    /** One id as a link in this style. */
    public String link(String slug, String workId) {
        String url = landing + "/projects/" + slug + "/work/detail/" + workId;
        return style == Style.OSC8
                ? "\u001b]8;;" + url + "\u001b\\" + workId + "\u001b]8;;\u001b\\"
                : "[" + workId + "](" + url + ")";
    }
}
