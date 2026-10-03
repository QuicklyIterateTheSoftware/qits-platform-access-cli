package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every root the publish commands talk to, composed from {@code QITS_DOMAIN} alone. The expected
 * strings are the ones qits-ci's {@code StepAddressPlane} handed a step as {@code
 * QITS_ARTIFACTS_URL}, {@code QITS_DOCS_URL}, {@code QITS_NPM_REGISTRY_URL}, {@code
 * QITS_MAVEN_REGISTRY_URL} and {@code QITS_MAVEN_PROXY_URL} (trailing slash aside, which the CLI
 * always trimmed), so a step that still carries those variables and one that does not reach the
 * same addresses.
 */
class StoreTest {

  @Test
  void everyRootIsAPathUnderThePublicHostsOfTheDomain() {
    Store store = Store.from(env("QITS_DOMAIN", "example.org"));

    assertEquals(
        "https://registry.qits.example.org/artifacts/sboms/docker/qits/qits-ci/-/1",
        store.sbomDocument("docker", "qits/qits-ci", "1"));
    assertEquals(
        "https://registry.qits.example.org/artifacts/docs/docs/@apidocs/qits-ci/-/1",
        store.docsBundle("@apidocs/qits-ci", "1"));
    assertEquals(
        "https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/1",
        store.daemonBinary("qits-ci-daemon", "1"));
    assertEquals("https://registry.qits.example.org/artifacts/npm/npm", store.npmRegistry());
    assertEquals("https://registry.qits.example.org/artifacts/maven/maven", store.mavenRegistry());
    assertEquals(
        List.of(
            "https://registry.qits.example.org/artifacts/maven/maven",
            "https://mirror.qits.example.org/mirror/maven/central"),
        store.mavenReadRoots());
  }

  @Test
  void noDomainIsTheLiveEstateNeverAnInternalAddress() {
    assertEquals(
        "https://registry.qits.wohlben.eu/artifacts/npm/npm", Store.from(env()).npmRegistry());
    assertEquals(
        "https://registry.qits.wohlben.eu/artifacts/npm/npm",
        Store.from(env("QITS_DOMAIN", "")).npmRegistry());
  }

  @Test
  void theDomainIsFoldedTheWayQitsCiFoldsIt() {
    assertEquals(
        "https://registry.qits.wohlben.eu/artifacts/npm/npm",
        Store.from(env("QITS_DOMAIN", " .Wohlben.EU. ")).npmRegistry());
  }

  @Test
  void theOldAddressVariablesAreNotRead() {
    Store store =
        Store.from(
            env(
                "QITS_ARTIFACTS_URL", "http://dev-qits-artifacts:8080",
                "QITS_DOCS_URL", "http://dev-qits-artifacts:8080/artifacts/docs/docs",
                "QITS_NPM_REGISTRY_URL", "http://dev-qits-artifacts:8080/artifacts/npm/npm/",
                "QITS_MAVEN_REGISTRY_URL", "http://dev-qits-artifacts:8080/artifacts/maven/maven"));

    assertEquals("https://registry.qits.wohlben.eu/artifacts/npm/npm", store.npmRegistry());
    assertEquals(
        "https://registry.qits.wohlben.eu/artifacts/docs/docs/s/-/1", store.docsBundle("s", "1"));
  }

  private static Env env(String... pairs) {
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      values.put(pairs[i], pairs[i + 1]);
    }
    return new Env(values);
  }
}
