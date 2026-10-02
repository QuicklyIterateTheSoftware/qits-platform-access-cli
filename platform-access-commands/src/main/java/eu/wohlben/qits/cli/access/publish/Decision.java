package eu.wohlben.qits.cli.access.publish;

import java.util.Optional;

/**
 * The publish decision, one flow for maven, npm and the contract packages alike:
 *
 * <ol>
 *   <li>The package is built and its hash H computed (by the caller). H is always sent.
 *   <li><b>Is this exact version there already?</b> 200 with the same H, or with no stored hash, is
 *       a re-run: {@code published <v>}, nothing uploaded. 200 with another H is two builds of one
 *       version: exit 1. 404 goes on. Anything else: exit 2.
 *   <li>Without {@code --if-changed}: upload, {@code published <v>}.
 *   <li><b>What is the newest version's hash?</b> 404 (first publish), no stored hash (published
 *       before hashes existed; there is no backfill), another algorithm version, or a different
 *       value: changed, upload. The same value: {@code unchanged since <newest>}, nothing uploaded.
 *       Anything else: exit 2, never "unchanged" and never "changed".
 * </ol>
 *
 * <p>It errs towards publishing: every doubt that is an answer means "changed", and every doubt
 * that is not an answer stops the step.
 */
final class Decision {

    /** What happened, and the one stdout line that says so. */
    record Outcome(Kind kind, String version) {

        enum Kind {
            /** Uploaded by this run. */
            UPLOADED,
            /** Already at this version: a re-run, or a hand-deployed version with no hash. */
            ALREADY_PUBLISHED,
            /** The newest version holds the same content; nothing was uploaded. */
            UNCHANGED
        }

        boolean published() {
            return kind != Kind.UNCHANGED;
        }

        String line() {
            return kind == Kind.UNCHANGED ? "unchanged since " + version : "published " + version;
        }
    }

    private final ContentHashes hashes;
    private final Console console;

    Decision(ContentHashes hashes, Console console) {
        this.hashes = hashes;
        this.console = console;
    }

    Outcome decide(String type, String name, String version, String hash, boolean ifChanged, Runnable upload) {
        String coordinate = type + " " + name + "@" + version;
        console.info(coordinate + " hashes to " + hash);

        Optional<ContentHashes.Stored> existing = hashes.version(type, name, version);
        if (existing.isPresent()) {
            String stored = existing.get().contentHash();
            if (stored == null) {
                console.info(coordinate + " is already published, with no content hash recorded (a re-run, or a "
                        + "version deployed by hand) — not uploading it again");
                return new Outcome(Outcome.Kind.ALREADY_PUBLISHED, version);
            }
            if (stored.equals(hash)) {
                console.info(coordinate + " is already published with this content — a re-run, nothing to upload");
                return new Outcome(Outcome.Kind.ALREADY_PUBLISHED, version);
            }
            throw CliException.policy(name + "@" + version + " already holds other content: the store records "
                    + stored + " and this build hashes to " + hash + ". A version must never come to mean two "
                    + "things; release a new version, or find out why one version has two builds.");
        }

        if (!ifChanged) {
            console.info(coordinate + " is not published yet — publishing (publish: always)");
            upload.run();
            return new Outcome(Outcome.Kind.UPLOADED, version);
        }

        Optional<ContentHashes.Stored> newest = hashes.newest(type, name);
        String reason;
        if (newest.isEmpty()) {
            reason = "nothing is published yet — first publish";
        } else if (newest.get().contentHash() == null) {
            reason = "the newest version " + newest.get().version() + " has no content hash (published before "
                    + "hashes were recorded) — publishing";
        } else if (!newest.get().contentHash().startsWith(ContentHash.VERSION + ":")) {
            reason = "the newest version " + newest.get().version() + " was hashed by another algorithm version ("
                    + newest.get().contentHash() + ") — publishing";
        } else if (newest.get().contentHash().equals(hash)) {
            console.info(coordinate + ": the newest version " + newest.get().version()
                    + " holds the same content — nothing is uploaded");
            return new Outcome(Outcome.Kind.UNCHANGED, newest.get().version());
        } else {
            reason = "the content changed since " + newest.get().version() + " (" + newest.get().contentHash()
                    + ") — publishing";
        }
        console.info(coordinate + ": " + reason);
        upload.run();
        return new Outcome(Outcome.Kind.UPLOADED, version);
    }
}
