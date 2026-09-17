package eu.wohlben.qits.cli.access.help;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The commands' own help, arranged as a SKILL.md for an agent. The picocli annotations are the one
 * source; this class only arranges them. The result depends on the annotations alone (no terminal
 * width, no time, no path, a fixed order), so a test can hold it against the committed file.
 * <p>
 * Footer lines that start with two spaces are examples and become code blocks; other lines are
 * text. The top command's footer is the platform rules.
 */
public final class SkillDocument {

    private SkillDocument() {
    }

    public static String render(CommandSpec root) {
        StringBuilder md = new StringBuilder();
        List<String> about = paragraphs(root.usageMessage().description());
        md.append("---\n");
        md.append("name: ").append(root.name()).append('\n');
        md.append("description: ").append(yamlString(about.isEmpty() ? "" : about.getFirst())).append('\n');
        md.append("---\n\n");
        md.append("# ").append(root.name()).append("\n\n");
        about.stream().skip(1).forEach(p -> md.append(p).append("\n\n"));
        footer(md, root, "## ");
        exitCodes(md, root, "## ");
        for (CommandSpec command : commands(root)) {
            section(md, command);
        }
        return md.toString().stripTrailing() + "\n";
    }

    /** Every visible command under {@code parent}, depth first, in the order they are registered. */
    private static List<CommandSpec> commands(CommandSpec parent) {
        List<CommandSpec> all = new ArrayList<>();
        Set<CommandSpec> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CommandLine sub : parent.subcommands().values()) {
            CommandSpec spec = sub.getCommandSpec();
            if (spec.usageMessage().hidden() || !seen.add(spec)) {
                continue;
            }
            all.add(spec);
            all.addAll(commands(spec));
        }
        return all;
    }

    private static void section(StringBuilder md, CommandSpec command) {
        md.append("## ").append(command.qualifiedName()).append("\n\n");
        paragraphs(command.usageMessage().description()).forEach(p -> md.append(p).append("\n\n"));
        // A group only names its commands; the commands under it carry the options.
        if (command.subcommands().isEmpty()) {
            md.append("```\n").append(synopsis(command)).append("\n```\n\n");
            arguments(md, command);
        }
        footer(md, command, "### ");
        exitCodes(md, command, "### ");
    }

    private static String synopsis(CommandSpec command) {
        StringBuilder line = new StringBuilder(command.qualifiedName());
        for (OptionSpec option : options(command)) {
            String one = option.longestName() + (takesValue(option) ? " " + option.paramLabel() : "")
                    + (option.isMultiValue() ? "..." : "");
            line.append(' ').append(option.required() ? one : "[" + one + "]");
        }
        for (PositionalParamSpec parameter : command.positionalParameters()) {
            line.append(' ').append(parameter.required() ? parameter.paramLabel() : "[" + parameter.paramLabel() + "]");
        }
        return line.toString();
    }

    private static void arguments(StringBuilder md, CommandSpec command) {
        List<String[]> rows = new ArrayList<>();
        for (PositionalParamSpec parameter : command.positionalParameters()) {
            if (!parameter.hidden()) {
                rows.add(new String[] {parameter.paramLabel(), text(parameter.renderedDescription())});
            }
        }
        for (OptionSpec option : options(command)) {
            String label = String.join(", ", option.names()) + (takesValue(option) ? " " + option.paramLabel() : "")
                    + (option.isMultiValue() ? "..." : "");
            rows.add(new String[] {label, (option.required() ? "Required. " : "") + text(option.renderedDescription())});
        }
        if (rows.isEmpty()) {
            return;
        }
        md.append("| Name | What it does |\n|---|---|\n");
        for (String[] row : rows) {
            md.append("| `").append(cell(row[0])).append("` | ").append(cell(row[1])).append(" |\n");
        }
        md.append('\n');
    }

    /** The options a person gives: required first, then by name. -h and -V are left out. */
    private static List<OptionSpec> options(CommandSpec command) {
        return command.options().stream()
                .filter(o -> !o.usageHelp() && !o.versionHelp() && !o.hidden())
                .sorted(Comparator.comparing((OptionSpec o) -> !o.required())
                        .thenComparing(o -> o.longestName().replaceFirst("^-+", "")))
                .toList();
    }

    private static boolean takesValue(OptionSpec option) {
        return option.arity().max() > 0;
    }

    private static void footer(StringBuilder md, CommandSpec command, String heading) {
        List<String> lines = lines(command.usageMessage().footer());
        if (lines.stream().allMatch(String::isBlank)) {
            return;
        }
        String title = lines(new String[] {command.usageMessage().footerHeading()}).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("Notes:");
        md.append(heading).append(title.endsWith(":") ? title.substring(0, title.length() - 1) : title).append("\n\n");
        blocks(md, lines);
    }

    /** Examples (two spaces in front) as code blocks, other lines as text, a blank line between the two. */
    private static void blocks(StringBuilder md, List<String> lines) {
        boolean inCode = false;
        boolean open = false;
        for (String line : lines) {
            if (line.isBlank()) {
                if (inCode) {
                    md.append("```\n");
                    inCode = false;
                }
                if (open) {
                    md.append('\n');
                    open = false;
                }
                continue;
            }
            boolean example = line.startsWith("  ");
            if (example != inCode) {
                if (inCode) {
                    md.append("```\n");
                }
                if (open) {
                    md.append('\n');
                }
                if (example) {
                    md.append("```\n");
                }
                inCode = example;
            }
            md.append(example ? line.substring(2) : line.strip()).append('\n');
            open = true;
        }
        if (inCode) {
            md.append("```\n");
        }
        if (open) {
            md.append('\n');
        }
    }

    private static void exitCodes(StringBuilder md, CommandSpec command, String heading) {
        Map<String, String> codes = command.usageMessage().exitCodeList();
        if (codes.isEmpty()) {
            return;
        }
        md.append(heading).append("Exit codes\n\n");
        codes.forEach((code, meaning) -> md.append("- `").append(code.strip()).append("` ")
                .append(flat(format(meaning))).append('\n'));
        md.append('\n');
    }

    /** Each non-empty description entry is one paragraph, on one line. */
    private static List<String> paragraphs(String[] description) {
        List<String> result = new ArrayList<>();
        for (String entry : description) {
            String paragraph = flat(format(entry));
            if (!paragraph.isEmpty()) {
                result.add(paragraph);
            }
        }
        return result;
    }

    private static List<String> lines(String[] texts) {
        List<String> result = new ArrayList<>();
        for (String text : texts) {
            Collections.addAll(result, format(text).split("\\R", -1));
        }
        return result;
    }

    private static String text(String[] description) {
        return flat(format(String.join(" ", description)));
    }

    /** What picocli prints: it formats every text, so {@code %n} is a line break. */
    private static String format(String text) {
        if (text == null) {
            return "";
        }
        try {
            return String.format(Locale.ROOT, text);
        } catch (IllegalFormatException notAFormat) {
            return text;
        }
    }

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String cell(String text) {
        return text.replace("|", "\\|");
    }

    private static String yamlString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
