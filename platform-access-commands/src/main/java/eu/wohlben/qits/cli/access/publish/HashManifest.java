package eu.wohlben.qits.cli.access.publish;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The canonical text a content hash is the sha256 of. Its shape is the v1 definition, verbatim:
 *
 * <pre>
 * qits-content-hash\tv1
 * type\t&lt;maven|npm&gt;
 * f\t&lt;sha256 hex of the entry bytes&gt;\t&lt;path&gt;   one per content entry, sorted by path
 * s\t&lt;none|cyclonedx&gt;
 * c\t&lt;identity&gt;\t&lt;version&gt;\t&lt;hashes&gt;            SBOM components, sorted with the edges
 * e\t&lt;from&gt;\t&lt;to&gt;                                SBOM edges
 * </pre>
 *
 * <p>UTF-8, LF, every line ending in {@code \n}. Each section is a set, so a duplicate line
 * collapses. Sorting is by UTF-8 bytes, unsigned: the {@code f} lines by their path, the {@code c}
 * and {@code e} lines together by the whole line.
 */
final class HashManifest {

    /** Unsigned UTF-8 byte order, which is not {@link String#compareTo} once a path leaves ASCII. */
    static final Comparator<String> UTF8_ORDER =
            (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    private final String type;
    private final List<String[]> files = new ArrayList<>();
    private final List<String> include;
    private String sbomKind = "none";
    private final TreeSet<String> sbomLines = new TreeSet<>(UTF8_ORDER);

    HashManifest(String type, List<String> include) {
        this.type = type;
        this.include = List.copyOf(include);
    }

    /** One content entry. A path a line could not carry is refused, never escaped. */
    void file(String path, byte[] bytes) {
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || c == 0) {
                throw CliException.policy("the entry '" + path.replace("\n", "\\n").replace("\t", "\\t")
                        .replace("\r", "\\r").replace("\0", "\\0")
                        + "' carries a tab, a line break or a NUL, which the content hash refuses rather than escapes");
            }
        }
        files.add(new String[] {path, hex(sha256(bytes))});
    }

    /** The SBOM half: {@code cyclonedx} and its canonical lines, or nothing for {@code s\tnone}. */
    void sbom(List<String> lines) {
        sbomKind = "cyclonedx";
        sbomLines.addAll(lines);
    }

    /** The manifest text. */
    String text() {
        List<String[]> kept = files;
        if (!include.isEmpty()) {
            List<PathMatcher> matchers = include.stream()
                    .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                    .toList();
            kept = files.stream()
                    .filter(f -> matchers.stream().anyMatch(m -> m.matches(Path.of(f[0]))))
                    .toList();
            if (kept.isEmpty()) {
                throw CliException.policy("--include " + String.join(", ", include) + " matches no entry of the "
                        + "package; a hash over nothing would call every release unchanged");
            }
        }
        TreeSet<String> fileLines = new TreeSet<>(Comparator
                .comparing((String line) -> line.substring(line.indexOf('\t', 2) + 1), UTF8_ORDER)
                .thenComparing(UTF8_ORDER));
        for (String[] f : kept) {
            fileLines.add("f\t" + f[1] + "\t" + f[0]);
        }
        StringBuilder text = new StringBuilder();
        text.append("qits-content-hash\t").append(ContentHash.VERSION).append('\n');
        text.append("type\t").append(type).append('\n');
        fileLines.forEach(line -> text.append(line).append('\n'));
        text.append("s\t").append(sbomKind).append('\n');
        sbomLines.forEach(line -> text.append(line).append('\n'));
        return text.toString();
    }

    /** {@code v1:sha256:<hex>} of {@link #text()}. */
    String value() {
        return ContentHash.PREFIX + hex(sha256(text().getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JDK has no SHA-256", e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }
}
