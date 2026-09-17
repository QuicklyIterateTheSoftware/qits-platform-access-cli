package eu.wohlben.qits.cli.access.help;

import eu.wohlben.qits.cli.access.AccessCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL.md at the repository root is the commands' help, rendered. This test renders it again and
 * fails when the committed file differs, so a help change and its SKILL.md travel in one commit.
 * With {@code -Dqits.skill.update=true} it writes the file first, the way the services'
 * OpenApiSchemaExportTest writes docs/openapi.yml.
 */
class SkillDocumentTest {

    /**
     * SKILL.md at the REPOSITORY root, resolved one directory up from this module's basedir.
     *
     * <p>The reactor split of 2026-09-17 moved the module's basedir under the repository root — the
     * sources went to {@code platform-access-cli/}, and surefire runs a module in its own basedir —
     * and SKILL.md deliberately did not follow. It is the repository's user-facing surface: README
     * and AGENTS.md name it at the top, and it is the file a person copies into
     * {@code ~/.claude/skills/qits/}. A reader should find it there, not two directories down.
     *
     * <p>One constant for both the read and the {@code -Dqits.skill.update=true} write, so the two
     * cannot drift onto different files — a write that landed on a module-local copy would make
     * this test pass against a file nobody ships. The path is deterministic: surefire's working
     * directory is always this module's basedir, from the root reactor and from {@code -pl} alike.
     */
    private static final Path COMMITTED = Path.of("..", "SKILL.md");
    private static final String REGENERATE = "SKILL.md differs from the commands' help. Write it again with "
            + "`./mvnw test -Dtest=SkillDocumentTest -Dqits.skill.update=true` "
            + "(or `platform-access-cli/target/qits help skill > SKILL.md`), "
            + "read the diff, and commit it with the help change.";

    private static String rendered() {
        return SkillDocument.render(new CommandLine(new AccessCli()).getCommandSpec());
    }

    @Test
    void theCommittedSkillIsTheHelp() throws IOException {
        String rendered = rendered();
        if (Boolean.getBoolean("qits.skill.update")) {
            Files.writeString(COMMITTED, rendered, StandardCharsets.UTF_8);
        }
        String committed = Files.exists(COMMITTED) ? Files.readString(COMMITTED, StandardCharsets.UTF_8) : "";
        assertThat(committed).as(REGENERATE).isEqualTo(rendered);
    }

    @Test
    void theSkillIsStableAsciiAndLeavesTheHiddenCommandOut() {
        String rendered = rendered();

        assertThat(rendered()).isEqualTo(rendered);
        assertThat(rendered.chars()).as("ASCII only, so every terminal and locale prints the same bytes")
                .allMatch(c -> c < 128);
        assertThat(rendered).startsWith("---\nname: qits\ndescription: \"Use for any work on the qits platform from a "
                + "terminal:");
        assertThat(rendered).contains("\n## Platform rules\n").contains("\n## qits release-request join\n")
                .contains("| `--request <id>` | Required. ").contains("\n### Exit codes\n")
                .doesNotContain("## qits help").doesNotContain("`-h, --help`").doesNotContain("\r");
    }
}
