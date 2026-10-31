package eu.wohlben.qits.cli.access.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.maintenance.MaintenanceApi;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.publish.Store;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * {@code qits artifacts fetch deployed-pacts} (ticket qits-1765): the pact of every consumer version
 * a provider still has to serve, unpacked where the provider's pact loader reads them.
 * <p>
 * qits-maintenance makes the join (what serves where, which repository built it, which pact package
 * each version was built against), so this command asks it once and then only downloads. It fails
 * closed: a provider gate that verified fewer pacts than are deployed would pass for the wrong
 * reason, so any refusal, any failed download and any jar without the pact is a non-zero exit.
 */
@TuiCommand(interaction = Interaction.LOCAL, effect = Effect.READ)
@CommandLine.Command(name = "deployed-pacts", mixinStandardHelpOptions = true,
        description = {"Download the consumer pacts a provider has to keep honouring: for each consumer version "
                        + "that is deployed or kept as a rollback target, the pact package it was built against.",
                "qits-maintenance names the packages (GET /maintenance/api/deployed-pacts); each one's jar comes "
                        + "from qits-artifacts' hosted maven repository, and its pacts/*_<provider>.json files are "
                        + "written to <out>/<consumer repository>/<pact version>/. One line per pact: `fetched "
                        + "<consumer> pact <version> for deployed <v1>, <v2>`, then the count."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits artifacts fetch deployed-pacts --provider qits-projects-service --out target/deployed-pacts",
                "  qits artifacts fetch deployed-pacts --provider qits-projects-service --out /tmp/pacts -o json",
                "",
                "- --out is created when it is missing. A consumer version's directory is replaced as a whole; one "
                        + "the platform no longer names is left as it is, so give a fresh --out to get exactly what "
                        + "serves now.",
                "- No deployed consumer is not an error: --out is created and the command says so.",
                "- In a CI step the run's token (QITS_TOKEN) reads both services; elsewhere the session or the "
                        + "workspace's token does.",
                "- -o json prints qits-maintenance's answer with `files` (the paths written) on each pact and `out`."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Every named pact was written, or no consumer of the provider is deployed.",
                "1:qits-maintenance refused (503: qits-deployments cannot say what serves), a jar could not be "
                        + "downloaded, a jar holds no pacts/*_<provider>.json, or --out could not be written.",
                HelpText.USAGE})
public class DeployedPactsCommand extends PlatformCommand {

    @CommandLine.Option(names = "--provider", paramLabel = "<repository>", required = true,
            description = "The provider's repository name, as its pact packages name it: qits-projects-service.")
    String provider;

    @CommandLine.Option(names = "--out", paramLabel = "<dir>", required = true,
            description = "Where the pacts are written, created when missing.")
    String out;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json",
            description = "table (the default): one line per pact and a count. json: the service's answer with "
                    + "the files written.")
    String output;

    @CommandLine.Option(names = "--maintenance-url", paramLabel = "<url>",
            description = "The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, "
                    + "else derived from the idp address or QITS_DOMAIN.")
    String maintenanceUrl;

    @CommandLine.Option(names = "--artifacts-url", paramLabel = "<url>",
            description = "qits-artifacts' base URL, without /artifacts. Default: QITS_ARTIFACTS_URL, else "
                    + "https://registry.qits.<domain>.")
    String artifactsUrl;

    /** One pact package, as qits-maintenance named it. */
    record Pact(String consumerRepository, String groupId, String artifactId, String pactVersion,
                List<String> deployedVersions, ObjectNode answered) {

        String coordinate() {
            return groupId + ":" + artifactId + ":" + pactVersion;
        }
    }

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        boolean json = json(output);
        String wanted = provider == null ? "" : provider.strip();
        if (!PactJars.plainSegment(wanted)) {
            throw new CliFailure("--provider is a repository name, such as qits-projects-service, not '"
                    + SafeText.line(provider) + "'.", CliFailure.USAGE);
        }
        Path dir = directory(out);
        String idpUrl = context.idpUrl();
        PlatformClient client = new PlatformClient(context.credential());
        MaintenanceApi maintenance = new MaintenanceApi(client,
                PlatformUrls.maintenance(maintenanceUrl, context.env(), idpUrl));
        PactJars jars = new PactJars(client,
                PlatformUrls.artifacts(artifactsUrl, context.env(), idpUrl) + Store.MAVEN_HOSTED_PATH);

        JsonNode answer;
        try {
            answer = maintenance.deployedPacts(wanted);
        } catch (CliFailure failed) {
            if (failed.exitCode() != CliFailure.FAILED) {
                throw failed;
            }
            throw new CliFailure("Cannot list the deployed consumer pacts of " + wanted + ", so none was fetched: "
                    + failed.getMessage(), CliFailure.FAILED);
        }
        List<Pact> pacts = pacts(answer);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new CliFailure("Cannot create " + dir + ": " + e.getMessage(), CliFailure.FAILED);
        }

        ArrayNode written = SafeText.JSON.createArrayNode();
        for (Pact pact : pacts) {
            List<Path> files = fetch(jars, pact, wanted, dir);
            ObjectNode entry = pact.answered().deepCopy();
            ArrayNode paths = entry.putArray("files");
            files.forEach(f -> paths.add(f.toString()));
            written.add(entry);
            if (!json) {
                context.out().println("fetched " + SafeText.line(pact.consumerRepository()) + " pact "
                        + SafeText.line(pact.pactVersion()) + " for deployed "
                        + SafeText.line(String.join(", ", pact.deployedVersions())));
            }
        }

        if (json) {
            ObjectNode printed = answer.deepCopy();
            printed.set("pacts", written);
            printed.put("out", dir.toString());
            try {
                context.out().println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(printed));
            } catch (IOException impossible) {
                throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
            }
        } else if (pacts.isEmpty()) {
            context.out().println("no deployed consumer pacts for " + wanted);
        } else {
            context.out().println("fetched " + pacts.size() + (pacts.size() == 1 ? " pact" : " pacts") + " for "
                    + wanted + " into " + dir);
        }
        return 0;
    }

    private static Path directory(String value) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure("Name the directory: --out <dir>.", CliFailure.USAGE);
        }
        try {
            return Path.of(value.strip());
        } catch (InvalidPathException e) {
            throw new CliFailure("--out '" + value + "' is not a usable path.", CliFailure.USAGE);
        }
    }

    /** The answer's pacts, each field checked: a name here becomes a path on disk and a URL. */
    static List<Pact> pacts(JsonNode answer) throws CliFailure {
        JsonNode list = answer.path("pacts");
        if (!list.isArray()) {
            throw new CliFailure("qits-maintenance answered without a pacts list.", CliFailure.FAILED);
        }
        List<Pact> pacts = new ArrayList<>();
        for (JsonNode node : list) {
            if (!(node instanceof ObjectNode entry)) {
                throw new CliFailure("qits-maintenance answered a pact that is not an object.", CliFailure.FAILED);
            }
            String consumer = segment(entry, "consumerRepository");
            String version = segment(entry, "pactVersion");
            String artifactId = segment(entry, "artifactId");
            String groupId = entry.path("groupId").asText("");
            for (String part : groupId.split("\\.", -1)) {
                if (!PactJars.plainSegment(part)) {
                    throw new CliFailure("qits-maintenance answered a groupId that is not a maven group: '"
                            + SafeText.line(groupId) + "'.", CliFailure.FAILED);
                }
            }
            List<String> deployed = new ArrayList<>();
            entry.path("deployedVersions").forEach(v -> deployed.add(v.asText("")));
            pacts.add(new Pact(consumer, groupId, artifactId, version, List.copyOf(deployed), entry));
        }
        return pacts;
    }

    private static String segment(JsonNode entry, String field) throws CliFailure {
        String value = entry.path(field).asText("");
        if (!PactJars.plainSegment(value)) {
            throw new CliFailure("qits-maintenance answered a pact whose " + field + " is not a plain name: '"
                    + SafeText.line(value) + "'.", CliFailure.FAILED);
        }
        return value;
    }

    /**
     * One package: its jar downloaded to a scratch file, its {@code pacts/*_<provider>.json} read whole,
     * and only then the consumer version's directory replaced, so a bad jar leaves no half of itself.
     */
    private static List<Path> fetch(PactJars jars, Pact pact, String provider, Path out)
            throws CliFailure, InterruptedException {
        Map<String, byte[]> found = new LinkedHashMap<>();
        Path jar;
        try {
            jar = Files.createTempFile(out, ".deployed-pact-", ".jar");
        } catch (IOException e) {
            throw new CliFailure("Cannot write in " + out + ": " + e.getMessage(), CliFailure.FAILED);
        }
        try {
            try {
                jars.download(pact.groupId(), pact.artifactId(), pact.pactVersion(), jar);
            } catch (CliFailure failed) {
                throw new CliFailure("Cannot download " + pact.coordinate() + ": " + failed.getMessage(),
                        CliFailure.FAILED);
            }
            found.putAll(pactFiles(jar, provider, pact));
        } finally {
            try {
                Files.deleteIfExists(jar);
            } catch (IOException ignored) {
                // A scratch file left behind is untidy, not wrong.
            }
        }
        if (found.isEmpty()) {
            throw new CliFailure(pact.coordinate() + " holds no pacts/*_" + provider + ".json.", CliFailure.FAILED);
        }
        Path target = out.resolve(pact.consumerRepository()).resolve(pact.pactVersion());
        List<Path> written = new ArrayList<>();
        try {
            clear(target);
            Files.createDirectories(target);
            for (Map.Entry<String, byte[]> file : found.entrySet()) {
                Path path = target.resolve(file.getKey());
                Files.write(path, file.getValue());
                written.add(path);
            }
        } catch (IOException e) {
            throw new CliFailure("Cannot write " + target + ": " + e.getMessage(), CliFailure.FAILED);
        }
        return written;
    }

    /** The jar's top-level {@code pacts/<consumer>_<provider>.json} entries, by file name. */
    static Map<String, byte[]> pactFiles(Path jar, String provider, Pact pact) throws CliFailure {
        String suffix = "_" + provider + ".json";
        Map<String, byte[]> found = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("pacts/")) {
                    continue;
                }
                String file = name.substring("pacts/".length());
                if (file.contains("/") || !file.endsWith(suffix) || file.length() == suffix.length()
                        || !PactJars.plainSegment(file)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    found.put(file, in.readAllBytes());
                }
            }
        } catch (ZipException e) {
            throw new CliFailure(pact.coordinate() + " is not a readable jar: " + e.getMessage(), CliFailure.FAILED);
        } catch (IOException e) {
            throw new CliFailure("Cannot read " + pact.coordinate() + ": " + e.getMessage(), CliFailure.FAILED);
        }
        return found;
    }

    /** The consumer version's directory, gone: what was there came from an earlier run. */
    private static void clear(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
