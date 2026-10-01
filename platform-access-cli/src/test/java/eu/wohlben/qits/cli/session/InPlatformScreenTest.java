package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.tui.TuiApp;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.run.CommandRunner;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The screen's half of InPlatformRefusalsTest, which lives with the commands: the commands that
 * open a browser, as the screen shows them inside the platform and outside it.
 */
class InPlatformScreenTest {

    @Test
    void theScreenShowsTheBrowserCommandsAsDecorationInside() {
        CommandNode root = CommandNode.of(new CommandLine(new AccessCli()).getCommandSpec());
        TuiApp inside = new TuiApp(root, "qits tui", new CommandRunner("/bin/true")).inPlatform(true);
        List<String> names = inside.view().rows().stream().map(row -> row.name()).toList();
        assertThat(inside.view().rows()).filteredOn(row -> row.name().equals("login"))
                .singleElement().satisfies(row -> assertThat(row.dim()).isTrue());
        assertThat(names.indexOf("login")).isGreaterThan(names.indexOf("ci"));

        TuiApp outside = new TuiApp(root, "qits tui", new CommandRunner("/bin/true"));
        assertThat(outside.view().rows()).filteredOn(row -> row.name().equals("login"))
                .singleElement().satisfies(row -> assertThat(row.dim()).isFalse());
    }
}
