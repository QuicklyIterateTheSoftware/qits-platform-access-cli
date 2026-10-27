package eu.wohlben.qits.cli.access.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The CLI's own blocks in {@code ~/.npmrc} and {@code ~/.m2/settings.xml}. Pure text in, text out.
 * <p>
 * Everything outside the blocks stays as it was, byte for byte, unless {@code removeStale} asks to
 * drop the old {@code *.dev.localhost} registries and foreign entries that use the ids or keys the
 * blocks own. Without it, such entries stay and a warning names them.
 */
public final class ManagedBlocks {

    static final String BEGIN = ">>> qits registry-login: managed by the qits CLI, edits here are replaced >>>";
    static final String END = "<<< qits registry-login <<<";

    /** The mirror the repositories' {@code qits-maven} repository resolves through. */
    public static final String MIRROR_ID = "qits-maven-host";
    /** The repository id the poms declare. Its server also gets the header, for a build without the mirror. */
    public static final String REPOSITORY_ID = "qits-maven";
    private static final Set<String> MAVEN_IDS = Set.of(MIRROR_ID, REPOSITORY_ID);

    private static final String STALE_HOST = ".dev.localhost";

    private ManagedBlocks() {
    }

    /** The new text and what the caller should tell the person. */
    public record Result(String text, List<String> warnings) {
    }

    // ---------------------------------------------------------------- npm

    /**
     * @param keys scheme-less registry prefixes, {@code //registry.qits.example/artifacts/npm/npm/}
     */
    public static Result npmrc(String existing, List<String> keys, String token, boolean removeStale) {
        String text = existing == null ? "" : existing;
        List<String> warnings = new ArrayList<>();
        List<String> lines = new ArrayList<>(text.lines().toList());

        int begin = -1;
        int end = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (begin < 0 && line.equals("# " + BEGIN)) {
                begin = i;
            } else if (begin >= 0 && line.equals("# " + END)) {
                end = i;
                break;
            }
        }
        List<String> block = new ArrayList<>();
        block.add("# " + BEGIN);
        keys.forEach(key -> block.add(key + ":_authToken=" + token));
        block.add("# " + END);

        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (begin >= 0 && end >= 0 && i >= begin && i <= end) {
                if (i == begin) {
                    out.addAll(block);
                }
                continue;
            }
            String line = lines.get(i);
            String trimmed = line.strip();
            boolean comment = trimmed.startsWith("#") || trimmed.startsWith(";");
            boolean stale = !comment && trimmed.contains(STALE_HOST);
            boolean ours = !comment && keys.stream().anyMatch(trimmed::startsWith);
            if (removeStale && (stale || ours)) {
                continue;
            }
            if (stale) {
                warnings.add("~/.npmrc has a line for an old *.dev.localhost registry; --remove-stale drops it.");
            } else if (ours) {
                warnings.add("~/.npmrc has a line outside the qits block for a registry the block sets; "
                        + "--remove-stale drops it.");
            }
            out.add(line);
        }
        if (begin < 0 || end < 0) {
            if (!out.isEmpty() && !out.getLast().isBlank()) {
                out.add("");
            }
            out.addAll(block);
        }
        return new Result(String.join("\n", out) + "\n", warnings.stream().distinct().toList());
    }

    // ---------------------------------------------------------------- Maven

    private static final Pattern MIRROR = Pattern.compile("[ \\t]*<mirror>.*?</mirror>[ \\t]*\\R?", Pattern.DOTALL);
    private static final Pattern SERVER = Pattern.compile("[ \\t]*<server>.*?</server>[ \\t]*\\R?", Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("<id>\\s*([^<]*?)\\s*</id>");
    private static final Pattern URL = Pattern.compile("<url>\\s*([^<]*?)\\s*</url>");

    public static Result mavenSettings(String existing, String url, String token, boolean removeStale) {
        String text = existing == null || existing.isBlank() ? SKELETON : existing;
        List<String> warnings = new ArrayList<>();
        if (!Pattern.compile("<settings[\\s>]").matcher(masked(text)).find()) {
            throw new IllegalArgumentException("it has no <settings> element");
        }
        text = removeBlocks(text);

        // Foreign entries: stale ones (*.dev.localhost) and ones that use the ids the blocks own.
        List<String> staleMirrorIds = new ArrayList<>();
        text = filter(text, MIRROR, element -> {
            String id = first(ID, element);
            boolean stale = first(URL, element).contains(STALE_HOST);
            boolean ours = MAVEN_IDS.contains(id);
            if (stale) {
                staleMirrorIds.add(id);
            }
            if ((stale || ours) && !removeStale) {
                warnings.add("~/.m2/settings.xml has a mirror `" + id + "` outside the qits block"
                        + (stale ? " for an old *.dev.localhost registry" : "") + "; --remove-stale drops it.");
            }
            return removeStale && (stale || ours);
        });
        text = filter(text, SERVER, element -> {
            String id = first(ID, element);
            boolean drop = MAVEN_IDS.contains(id) || staleMirrorIds.contains(id);
            if (drop && !removeStale) {
                warnings.add("~/.m2/settings.xml has a server `" + id + "` outside the qits block; "
                        + "--remove-stale drops it.");
            }
            return removeStale && drop;
        });

        String mirror = """
                <mirror>
                  <id>%s</id>
                  <mirrorOf>%s</mirrorOf>
                  <url>%s</url>
                </mirror>
                """.formatted(MIRROR_ID, REPOSITORY_ID, xml(url));
        StringBuilder servers = new StringBuilder();
        for (String id : List.of(MIRROR_ID, REPOSITORY_ID)) {
            servers.append("""
                    <server>
                      <id>%s</id>
                      <configuration>
                        <httpHeaders>
                          <property>
                            <name>Authorization</name>
                            <value>Bearer %s</value>
                          </property>
                        </httpHeaders>
                      </configuration>
                    </server>
                    """.formatted(id, xml(token)));
        }
        text = insert(text, "mirrors", mirror);
        text = insert(text, "servers", servers.toString());
        return new Result(text, warnings.stream().distinct().toList());
    }

    private static final String SKELETON = """
            <?xml version="1.0" encoding="UTF-8"?>
            <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
            </settings>
            """;

    /** Drops every managed block, with its indentation and line end. */
    private static String removeBlocks(String text) {
        Pattern block = Pattern.compile("[ \\t]*<!-- " + Pattern.quote(BEGIN) + " -->.*?<!-- "
                + Pattern.quote(END) + " -->[ \\t]*\\R?", Pattern.DOTALL);
        return block.matcher(text).replaceAll("");
    }

    /** Puts {@code body} first inside {@code <section>}, making the section when there is none. */
    private static String insert(String text, String section, String body) {
        String masked = masked(text);
        Matcher open = Pattern.compile("<" + section + "\\s*(/?)>").matcher(masked);
        if (open.find()) {
            String indent = indentOf(text, open.start());
            String inner = indent + "  ";
            String block = "\n" + wrap(body, inner);
            if (!open.group(1).isEmpty()) {
                return text.substring(0, open.start()) + "<" + section + ">" + block + indent + "</" + section + ">"
                        + text.substring(open.end());
            }
            int lineEnd = text.indexOf('\n', open.end());
            boolean restOfLineBlank = lineEnd >= 0 && text.substring(open.end(), lineEnd).isBlank();
            int at = restOfLineBlank ? lineEnd : open.end();
            String insertion = restOfLineBlank ? block.substring(0, block.length() - 1) : block;
            return text.substring(0, at) + insertion + text.substring(at);
        }
        Matcher close = Pattern.compile("</settings\\s*>").matcher(masked);
        if (!close.find()) {
            throw new IllegalArgumentException("it has no </settings> end tag");
        }
        int lineStart = text.lastIndexOf('\n', close.start()) + 1;
        boolean ownLine = text.substring(lineStart, close.start()).isBlank();
        int at = ownLine ? lineStart : close.start();
        String created = "  <" + section + ">\n" + wrap(body, "    ") + "  </" + section + ">\n";
        return text.substring(0, at) + (ownLine ? "" : "\n") + created + text.substring(at);
    }

    private static String wrap(String body, String indent) {
        StringBuilder out = new StringBuilder(indent).append("<!-- ").append(BEGIN).append(" -->\n");
        body.lines().forEach(line -> out.append(indent).append(line).append('\n'));
        return out.append(indent).append("<!-- ").append(END).append(" -->\n").toString();
    }

    private static String indentOf(String text, int position) {
        int lineStart = text.lastIndexOf('\n', position - 1) + 1;
        String before = text.substring(lineStart, position);
        return before.isBlank() ? before : "  ";
    }

    private interface Drop {
        boolean test(String element);
    }

    /** Removes the elements {@code drop} names. Elements inside comments are never seen. */
    private static String filter(String text, Pattern element, Drop drop) {
        Matcher m = element.matcher(masked(text));
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (drop.test(text.substring(m.start(), m.end()))) {
                out.append(text, last, m.start());
                last = m.end();
            }
        }
        return out.append(text.substring(last)).toString();
    }

    private static String first(Pattern pattern, String element) {
        Matcher m = pattern.matcher(element);
        return m.find() ? m.group(1) : "";
    }

    /** The text with every comment blanked out, same length, so a match's offsets fit the original. */
    static String masked(String text) {
        StringBuilder out = new StringBuilder(text);
        int from = 0;
        while (true) {
            int start = text.indexOf("<!--", from);
            if (start < 0) {
                return out.toString();
            }
            int end = text.indexOf("-->", start + 4);
            end = end < 0 ? text.length() : end + 3;
            for (int i = start; i < end; i++) {
                if (out.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                }
            }
            from = end;
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- redaction

    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("(_authToken\\s*=\\s*)\\S+"),
            Pattern.compile("(_auth\\s*=\\s*)\\S+"),
            Pattern.compile("(_password\\s*=\\s*)\\S+"),
            Pattern.compile("(Bearer\\s+)[^\\s<\"']+"),
            Pattern.compile("(<password>)[^<]*"));

    /** One line with every credential replaced by {@code <token>}. */
    public static String redact(String line) {
        String out = line;
        for (Pattern secret : SECRETS) {
            out = secret.matcher(out).replaceAll("$1<token>");
        }
        return out;
    }
}
