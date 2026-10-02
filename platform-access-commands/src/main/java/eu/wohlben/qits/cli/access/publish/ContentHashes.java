package eu.wohlben.qits.cli.access.publish;

import java.util.Map;
import java.util.Optional;

/**
 * The store's two content-hash reads, and the strict reading of their answers.
 *
 * <pre>
 * GET /artifacts/content-hashes/&lt;maven|npm&gt;/&lt;name&gt;/-/&lt;version&gt;   this exact version
 * GET /artifacts/content-hashes/&lt;maven|npm&gt;/&lt;name&gt;/-/newest      the highest by version order
 * </pre>
 *
 * <p>200 is {@code {ecosystem, name, version, contentHash|null}}; 404 is "no such version" (or no
 * version at all). <b>Anything else is the store failing to answer, never an answer</b>: a 5xx, a
 * 401, an unreadable body all exit 2. An error read as "absent" would publish over an outage, and
 * one read as "unchanged" would skip a release in silence.
 */
final class ContentHashes {

    /** What the store holds for one version. {@code contentHash} is {@code null} when none was recorded. */
    record Stored(String version, String contentHash) {
    }

    private final Http http;
    private final Store store;

    ContentHashes(Http http, Store store) {
        this.http = http;
        this.store = store;
    }

    /** The stored value for {@code name@version}, or empty when the version does not exist. */
    Optional<Stored> version(String type, String name, String version) {
        return read(type, name, version);
    }

    /** The newest version by version order and its value, or empty when nothing is published. */
    Optional<Stored> newest(String type, String name) {
        return read(type, name, "newest");
    }

    private Optional<Stored> read(String type, String name, String which) {
        String url = store.contentHash(type, name, which);
        Http.Response response = http.get(url);
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (response.status() != 200) {
            throw CliException.transport("the content-hash read GET " + url + " answered HTTP " + response.status()
                    + (response.excerpt().isEmpty() ? "" : ": " + response.excerpt())
                    + " — not deciding on an error");
        }
        Map<String, Object> body = Json.parseObject(response.body(), "the content-hash read of " + url);
        String version = Json.string(body, "version");
        if (version == null || version.isEmpty()) {
            throw CliException.transport("the content-hash read GET " + url + " answered 200 with no version");
        }
        Object hash = body.get("contentHash");
        if (hash != null && !(hash instanceof String)) {
            throw CliException.transport("the content-hash read GET " + url + " answered a contentHash that is not "
                    + "a string");
        }
        return Optional.of(new Stored(version, (String) hash));
    }
}
