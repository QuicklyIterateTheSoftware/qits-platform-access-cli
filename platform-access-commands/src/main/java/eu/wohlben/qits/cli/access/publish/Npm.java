package eu.wohlben.qits.cli.access.publish;

import java.util.Map;

/**
 * The npm half: what to do before publishing, and what to do after it.

 *
 * <p>Nothing here runs {@code npm}. The publish itself is {@link NpmPublisher} now (epic qits-620),
 * which builds the tarball and calls {@link #distTag} after it; {@code plan} stays until the
 * build-only npm-library archetype is live everywhere, and then goes.
 */
final class Npm {

  private final Http http;
  private final Store store;
  private final Console console;

  Npm(Http http, Store store, Console console) {
    this.http = http;
    this.store = store;
    this.console = console;
  }

  // --- plan --------------------------------------------------------------------------------------

  /**
   * Decide, in one word on stdout, what a release step should do with {@code package@version}:
   *
   * <ul>
   *   <li>{@code skip} — the registry already holds this exact version. Versions are immutable, so
   *       publishing again would be refused; a re-fired run must go green rather than fight.
   *   <li>{@code publish-replay} — this version is <em>below</em> the registry's {@code latest}. A
   *       bootstrap replays every version the estate still pins onto a registry that may already hold
   *       a newer one, and npm refuses to move {@code latest} backwards. Such a publish takes a
   *       throwaway tag ({@code npm publish --tag replay}) and leaves the real tags alone.
   *   <li>{@code publish} — the ordinary case.
   * </ul>
   *
   * <p>A package with nothing published answers 404 and that is {@code publish}, not a failure — npm
   * reads the same 404 the same way. <b>A transport failure is not that</b>, and this is the one
   * sharpening over the shell it replaces: {@code latest=$(npm view … || true)} turned an unreachable
   * registry into an empty {@code latest}, which reads as "publish", which on a replay would try to
   * claim {@code latest}. Here an unanswerable question exits 2 and the step stops.
   */
  int plan(String packageName, String version) {
    String url = store.npmPackument(packageName);
    Http.Response response = http.get(url);
    if (response.status() == 404) {
      console.info(packageName + " has no published version yet");
      console.answer("publish");
      return ExitCode.OK;
    }
    if (response.status() != 200) {
      throw refusal("the npm packument for " + packageName, url, response);
    }

    Map<String, Object> packument = Json.parseObject(response.body(), "the npm packument");
    Map<String, Object> versions = Json.object(packument, "versions");
    if (versions != null && versions.containsKey(version)) {
      console.info(packageName + "@" + version + " is already published");
      console.answer("skip");
      return ExitCode.OK;
    }

    Map<String, Object> tags = Json.object(packument, "dist-tags");
    String latest = Json.string(tags, "latest");
    VersionOrder order = VersionOrder.compare(version, latest);
    if (order == VersionOrder.BELOW) {
      console.info(
          version
              + " is below latest "
              + latest
              + " — a replay: it takes a throwaway tag and leaves the real ones alone");
      console.answer("publish-replay");
      return ExitCode.OK;
    }
    console.info(
        latest == null
            ? packageName + " has no latest tag; " + version + " is a release"
            : version + " is " + (order == VersionOrder.SAME ? "level with" : "above") + " latest " + latest);
    console.answer("publish");
    return ExitCode.OK;
  }

  // --- dist-tag ----------------------------------------------------------------------------------

  /**
   * Point a dist-tag at a version.
   *
   * <p><b>Always run, never guarded.</b> This is one of the policy's idempotent repairs: moving a tag
   * onto the version it already names costs one request and answers 200, whereas guarding it behind
   * "did the publish happen this time" is what left {@code @qits/ui-components@main} a release behind
   * with nothing saying so. The registry refuses only a <em>backwards</em> move of {@code latest},
   * and that refusal is a real answer worth failing on rather than swallowing.
   *
   * <p>The body is the version as a JSON string — {@code "1.2.3"}, quotes included — which is what
   * {@code npm dist-tag add} sends and what the route reads.
   */
  int distTag(String packageName, String version, String tag) {
    String url = store.npmDistTag(packageName, tag);
    Http.Response response = http.putText(url, Json.quote(version), "application/json", Map.of());
    if (response.status() == 200) {
      console.info("the " + tag + " dist-tag now names " + packageName + "@" + version);
      return ExitCode.OK;
    }
    throw refusal(
        "pointing the " + tag + " dist-tag at " + packageName + "@" + version, url, response);
  }

  private static CliException refusal(String what, String url, Http.Response response) {
    String message =
        what
            + " answered HTTP "
            + response.status()
            + " ("
            + url
            + ")"
            + (response.excerpt().isEmpty() ? "" : ": " + response.excerpt());
    return response.status() >= 500
        ? CliException.transport(message)
        : CliException.policy(message);
  }
}
