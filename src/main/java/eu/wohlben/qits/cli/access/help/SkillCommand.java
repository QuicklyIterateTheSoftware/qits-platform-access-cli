package eu.wohlben.qits.cli.access.help;

import eu.wohlben.qits.cli.access.platform.HelpText;
import picocli.CommandLine;

import java.io.PrintWriter;

@CommandLine.Command(name = "skill", mixinStandardHelpOptions = true,
        description = "Print the help of every command as a SKILL.md for an agent: the platform rules, and each "
                + "command with its options, examples and exit codes.",
        footerHeading = HelpText.EXAMPLES,
        footer = "  mkdir -p ~/.claude/skills/qits && qits help skill > ~/.claude/skills/qits/SKILL.md",
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = "0:Printed.")
public class SkillCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        PrintWriter out = spec.commandLine().getOut();
        out.print(SkillDocument.render(spec.root()));
        out.flush();
    }
}
