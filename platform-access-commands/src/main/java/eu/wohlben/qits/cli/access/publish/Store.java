package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.access.idp.IdpUrl;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where qits-artifacts and qits-mirror are, and how a coordinate becomes a URL.
 *
 * <p><b>No address is spelled in a repository, and none arrives as configuration either.</b> Every
 * root is code plus the platform's public domain: {@code https://<label>.qits.$QITS_DOMAIN}, the
 * label {@code registry} for qits-artifacts (hosted npm, hosted maven, the sbom, docs, daemon and
 * content-hash stores) and {@code mirror} for qits-mirror (the npmjs and Maven Central caches). The
 * paths under each are the services' own routes, constants here exactly as qits-ci's {@code
 * StepAddressPlane} spells them, so a root composed here is byte for byte the one a step used to be
 * handed as {@code QITS_ARTIFACTS_URL}, {@code QITS_DOCS_URL} or {@code QITS_*_REGISTRY_URL}.
 *
 * <h2>The one input</h2>
 *
 * <p>{@code QITS_DOMAIN}, the bare public domain ({@code wohlben.eu}), folded the way qits-ci folds
 * it: trimmed, lower-cased, leading and trailing dots dropped. Absent, it is {@value
 * #DEFAULT_DOMAIN}, the same default {@code IdpUrl} falls back to. <b>Never an internal address</b>:
 * the public names resolve and answer from inside the platform network too (the edge hairpins), and
 * an internal fallback is exactly the class of address that stopped resolving every time a wire
 * alias moved. The public hosts answer 401 anonymously, which is why every request here goes out
 * through {@link AbstractPublishCommand#http()} with a credential.
 *
 * <p>The old {@code QITS_*_URL} variables are not read, so a step container that still carries them
 * is simply not listening to them: the addresses they named and the ones composed here are the
 * same.
 */
public final class Store {

  /** The domain a CLI with no {@code QITS_DOMAIN} talks to: the live estate's. */
  static final String DEFAULT_DOMAIN = "wohlben.eu";

  /** qits-artifacts' host label, {@code host: registry} in its deployments.yml. */
  public static final String REGISTRY_HOST = "registry";

  /** qits-mirror's host label. */
  static final String MIRROR_HOST = "mirror";

  /** qits-artifacts' hosted npm repository, the one {@code @qits/*} is published to. */
  static final String NPM_HOSTED_PATH = "/artifacts/npm/npm";

  /** qits-artifacts' hosted maven repository. */
  static final String MAVEN_HOSTED_PATH = "/artifacts/maven/maven";

  /** qits-mirror's Maven Central pull-through, where an external parent or BOM is read. */
  static final String MAVEN_CENTRAL_MIRROR_PATH = "/mirror/maven/central";

  /** qits-artifacts' docs repository, its {@code docs} namespace segment included. */
  public static final String DOCS_PATH = "/artifacts/docs/docs";

  /** The sbom store's package types. Its own whitelist, refused client-side first. */
  static final Set<String> SBOM_PACKAGE_TYPES = Set.of("npm", "maven", "docker", "daemon");

  /**
   * The two origins everything else is a path under, no trailing slash. A value of its own so the
   * suite can point both at a local stub ({@link AbstractPublishCommand#hosts}) without a production
   * variable that could do the same in a step container.
   */
  record Hosts(String registry, String mirror) {

    /** {@code https://registry.qits.<domain>} and {@code https://mirror.qits.<domain>}. */
    static Hosts of(Env env) {
      String domain = domain(env.get("QITS_DOMAIN"));
      return new Hosts(origin(REGISTRY_HOST, domain), origin(MIRROR_HOST, domain));
    }

    private static String origin(String host, String domain) {
      return "https://" + host + "." + IdpUrl.PROJECT + "." + domain;
    }

    /** Folded as qits-ci's {@code RunnerAddresses.publicOrigin} folds it; blank is the default. */
    static String domain(String value) {
      String folded =
          value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", "");
      return folded.isEmpty() ? DEFAULT_DOMAIN : folded;
    }
  }

  /**
   * {@code https://<label>.qits.<QITS_DOMAIN>}, with the domain folded and defaulted exactly as the
   * store's own two hosts are. For a CI-step command outside this package that dials another
   * service on the same public names, so the rule stays in one place: {@code qits ci report submit}
   * reaches qits-ci as label {@code ci}.
   */
  public static String publicOrigin(String label, java.util.Map<String, String> env) {
    return Hosts.origin(label, Hosts.domain(new Env(env).get("QITS_DOMAIN")));
  }

  /**
   * {@code https://qits.<QITS_DOMAIN>}: the project's own name, where the landing app serves the
   * platform's pages (a work item is {@code /projects/<slug>/work/detail/<qualifiedId>} under it).
   * Not a {@link #publicOrigin} label: {@code landing.qits.<domain>} is no application's host.
   */
  public static String projectRoot(java.util.Map<String, String> env) {
    return "https://" + IdpUrl.PROJECT + "." + Hosts.domain(new Env(env).get("QITS_DOMAIN"));
  }

  private final Hosts hosts;

  Store(Hosts hosts) {
    this.hosts = new Hosts(trim(hosts.registry()), trim(hosts.mirror()));
  }

  static Store from(Env env) {
    return new Store(Hosts.of(env));
  }

  private static String trim(String url) {
    String text = url;
    while (text.endsWith("/")) {
      text = text.substring(0, text.length() - 1);
    }
    return text;
  }

  private String origin() {
    return hosts.registry();
  }

  /** {@code /artifacts/sboms/<packageType>/<packageName>/-/<version>}. */
  String sbomDocument(String packageType, String packageName, String version) {
    requirePackageType(packageType);
    return origin()
        + "/artifacts/sboms/"
        + packageType
        + "/"
        + safe("--name", packageName)
        + "/-/"
        + version(version);
  }

  /** {@code /artifacts/daemons/<name>/<version>}. The daemon wire has no repository segment. */
  String daemonBinary(String name, String version) {
    return origin() + "/artifacts/daemons/" + safe("--name", name) + "/" + version(version);
  }

  /**
   * {@code /artifacts/docs/docs/<site>/-/<version>}. The docs wire <em>does</em> carry a repository
   * segment ({@code docs}), which the sbom and daemon wires do not.
   */
  String docsBundle(String site, String version) {
    return origin() + DOCS_PATH + "/" + safe("--site", site) + "/-/" + version(version);
  }

  /**
   * {@code /artifacts/content-hashes/<maven|npm>/<name>/-/<version|newest>}: the version's stored
   * content hash, or the newest version by version order and its hash. Both read the hosted
   * repository; the store fills in the repository segment itself.
   */
  String contentHash(String type, String name, String versionOrNewest) {
    if (!type.equals("maven") && !type.equals("npm")) {
      throw CliException.policy("unknown package type '" + type + "'; content hashes are kept for maven and npm");
    }
    return origin()
        + "/artifacts/content-hashes/"
        + type
        + "/"
        + safe("--name", name)
        + "/-/"
        + version(versionOrNewest);
  }

  /** The hosted maven repository root, where an eu.wohlben.qits artifact is published. */
  String mavenRegistry() {
    return origin() + MAVEN_HOSTED_PATH;
  }

  /**
   * Where a pom outside the reactor is read, in order: the hosted repository, then qits-mirror's
   * Maven Central cache.
   */
  List<String> mavenReadRoots() {
    return List.of(mavenRegistry(), hosts.mirror() + MAVEN_CENTRAL_MIRROR_PATH);
  }

  /**
   * {@code <maven root>/<g with dots as slashes>/<a>/<v>/<file>}. Each segment is checked on its
   * own, so a group cannot smuggle a traversal in between its dots.
   */
  String mavenFile(String groupId, String artifactId, String version, String file) {
    return mavenFileUnder(mavenRegistry(), groupId, artifactId, version, file);
  }

  /** The same path under any maven root: the hosted repository, or the proxy a BOM is read from. */
  static String mavenFileUnder(String root, String groupId, String artifactId, String version, String file) {
    StringBuilder url = new StringBuilder(trim(root));
    for (String segment : groupId.split("\\.", -1)) {
      url.append('/').append(segment(("--name"), segment));
    }
    url.append('/').append(segment("--name", artifactId));
    url.append('/').append(version(version));
    url.append('/').append(segment("--name", file));
    return url.toString();
  }

  /** One path segment: a coordinate character string with no slash in it. */
  private static String segment(String what, String value) {
    String checked = safe(what, value);
    if (checked.contains("/")) {
      throw CliException.policy(what + " '" + value + "' is not a single path segment");
    }
    return checked;
  }

  /** The hosted npm registry root, where an @qits package is published and read. */
  String npmRegistry() {
    return origin() + NPM_HOSTED_PATH;
  }

  /** The packument, which is how "is this version published" and "what is latest" are both asked. */
  String npmPackument(String packageName) {
    return npmRegistry() + "/" + safe("--package", packageName);
  }

  /**
   * {@code <registry>/-/package/<pkg>/dist-tags/<tag>}. Note the order: the repository segment is
   * already inside the registry root, and the {@code /-/} namespace comes after it.
   */
  String npmDistTag(String packageName, String tag) {
    return npmRegistry() + "/-/package/" + safe("--package", packageName) + "/dist-tags/" + safe("--tag", tag);
  }

  private static void requirePackageType(String packageType) {
    if (!SBOM_PACKAGE_TYPES.contains(packageType)) {
      throw CliException.policy(
          "unknown package type '"
              + packageType
              + "'; the sbom store serves "
              + String.join(", ", SBOM_PACKAGE_TYPES.stream().sorted().toList()));
    }
  }

  /**
   * A value on its way into a URL path, checked against the store's own charset.
   *
   * <p>Everything here arrives from a repository's pipeline configuration, which is not this
   * program's to trust: a name carrying {@code ..} or a {@code ?} would address a coordinate nobody
   * declared. The guard is the same one the shell it replaces spelled as a {@code case} pattern, and
   * it is cheap to keep for exactly the reason that one was.
   */
  private static String safe(String what, String value) {
    if (value == null || value.isEmpty()) {
      throw CliException.policy(what + " is required");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '~'
              || c == '-'
              || c == '+'
              || c == ':'
              || c == '@'
              || c == '/';
      if (!ok) {
        throw CliException.policy(
            what + " '" + value + "' carries '" + c + "', which is not a coordinate character");
      }
    }
    if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
      throw CliException.policy(what + " '" + value + "' has an empty path segment");
    }
    for (String segment : value.split("/")) {
      if (segment.equals(".") || segment.equals("..")) {
        throw CliException.policy(what + " '" + value + "' traverses");
      }
    }
    return value;
  }

  /** A version, which is one segment and never a path. */
  private static String version(String value) {
    String checked = safe("--version", value);
    if (checked.contains("/")) {
      throw CliException.policy("--version '" + value + "' is not a single path segment");
    }
    return checked;
  }

  /** Lower-cased once, so a `--type DOCKER` fails the whitelist rather than the route. */
  static String normalizeType(String type) {
    return type == null ? null : type.toLowerCase(Locale.ROOT);
  }
}
