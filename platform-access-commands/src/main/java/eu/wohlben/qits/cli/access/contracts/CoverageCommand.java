package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.report.contracts.GoldenMasterIndexParser;
import eu.wohlben.qits.cli.access.report.contracts.RecordedOperation;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code qits contracts coverage}: every operation a java service serves must have a golden master
 * (qits-1940). qits-ci runs it in the QA step of every java-service release, after the repository's
 * own script, so it reads the {@code docs/openapi.yml} that script's suite just wrote, and hands the
 * uncovered operations to the {@code golden-master-coverage} release report through {@code --out}.
 * <p>
 * An input that cannot be read is exit 1 and writes no {@code --out} file at all (one already there is
 * removed): an empty file would read as a clean pass, and a missing one is "not reported".
 * Reads local files only, so it touches no session and no platform.
 */
@TuiCommand(interaction = Interaction.CI_ONLY)
@CommandLine.Command(name = "coverage", mixinStandardHelpOptions = true,
        description = {"Name every operation of a service's exported OpenAPI document that no golden master "
                        + "records: the platform's rule is at least one golden master per operation.",
                "An operation is covered when a call in the golden-master index has its operationId, or its "
                        + "method and a path that is one of the document's server urls followed by the "
                        + "operation's path. A {name} in either stands for one non-empty segment; the call's "
                        + "query and one trailing slash are ignored. Prints one `uncovered:` line per operation "
                        + "that is not, in document order, then `golden-master coverage: <covered>/<total> "
                        + "operations covered`."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits contracts coverage",
                "  qits contracts coverage --out .qits-ci/uncovered-operations.tsv",
                "  qits contracts coverage --no-golden-masters",
                "",
                "- An operation is named by its operationId, or `METHOD /path` (the document's path template) when "
                        + "it has none.",
                "- --out writes one line per uncovered operation, `<name>\\t<METHOD>\\t<path>`, and an empty file "
                        + "when every operation is covered. The golden-master-coverage release report reads it "
                        + "through QITS_CI_UNCOVERED_OPERATIONS.",
                "- The document is YAML, or JSON when its name ends in .json; only OpenAPI 3 is read. Only "
                        + "formatVersion 1 of the index is read.",
                "- Reads local files only: it needs no sign-in and calls no service."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Every operation is covered, or the document declares none.",
                "1:An input is missing (`no exported OpenAPI at <path>`, `no golden-master index at <path>`) or does "
                        + "not parse, or --out could not be written. --out is then not written at all.",
                "2:Used wrongly.",
                "3:At least one operation has no golden master; each is named on stdout."})
public class CoverageCommand extends PlatformCommand {

    /** At least one operation is uncovered. */
    static final int UNCOVERED = 3;

    @CommandLine.Option(names = "--openapi", paramLabel = "<file>", defaultValue = "docs/openapi.yml",
            description = "The service's exported OpenAPI document, relative to the working directory. Default: "
                    + "docs/openapi.yml.")
    String openapi;

    @CommandLine.Option(names = "--index", paramLabel = "<file>", defaultValue = "golden-masters/index.json",
            description = "The golden-master index, relative to the working directory. Default: "
                    + "golden-masters/index.json.")
    String index;

    @CommandLine.Option(names = "--out", paramLabel = "<file>",
            description = "Also write the uncovered operations here as TSV, replacing the file.")
    String out;

    @CommandLine.Option(names = "--no-golden-masters",
            description = "The repository declares no golden masters: every operation is uncovered, and no index "
                    + "is read.")
    boolean noGoldenMasters;

    @Override
    protected int execute(CliContext context) throws CliFailure {
        Path outFile = out == null ? null : Path.of(out);
        try {
            OpenApiDocument document = document();
            List<RecordedOperation> recorded = noGoldenMasters ? List.of() : recorded();
            List<ApiOperation> uncovered = GoldenMasterMatch.uncovered(document, recorded);
            if (outFile != null) {
                write(outFile, uncovered);
            }
            for (ApiOperation operation : uncovered) {
                context.out().println("uncovered: " + operation.name() + (operation.operationId() == null ? ""
                        : " (" + operation.method() + " " + operation.path() + ")"));
            }
            int total = document.operations().size();
            context.out().println(uncovered.isEmpty() ? "golden-master coverage: all " + total + " operations covered"
                    : "golden-master coverage: " + (total - uncovered.size()) + "/" + total + " operations covered"
                    + (noGoldenMasters ? " (no golden masters declared)" : ""));
            return uncovered.isEmpty() ? 0 : UNCOVERED;
        } catch (CliFailure failure) {
            if (outFile != null) {
                try {
                    Files.deleteIfExists(outFile);
                } catch (IOException ignored) {
                    // the failure already says what is wrong; a file left behind is the composer's to clean
                }
            }
            throw failure;
        }
    }

    private OpenApiDocument document() throws CliFailure {
        Path file = Path.of(openapi);
        if (!Files.isRegularFile(file)) {
            throw new CliFailure("no exported OpenAPI at " + openapi, CliFailure.FAILED);
        }
        try {
            return OpenApiDocument.read(file);
        } catch (IOException | RuntimeException failed) {
            throw new CliFailure("Could not read the OpenAPI document " + openapi + ": " + failed.getMessage(),
                    CliFailure.FAILED);
        }
    }

    private List<RecordedOperation> recorded() throws CliFailure {
        Path file = Path.of(index);
        if (!Files.isRegularFile(file)) {
            throw new CliFailure("no golden-master index at " + index, CliFailure.FAILED);
        }
        try {
            return GoldenMasterIndexParser.operations(file);
        } catch (IOException | RuntimeException failed) {
            throw new CliFailure("Could not read the golden-master index " + index + ": " + failed.getMessage(),
                    CliFailure.FAILED);
        }
    }

    private static void write(Path file, List<ApiOperation> uncovered) throws CliFailure {
        StringBuilder tsv = new StringBuilder();
        for (ApiOperation operation : uncovered) {
            tsv.append(operation.name()).append('\t').append(operation.method()).append('\t')
                    .append(operation.path()).append('\n');
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, tsv, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new CliFailure("Could not write " + file + ": " + failed.getMessage(), CliFailure.FAILED);
        }
    }
}
