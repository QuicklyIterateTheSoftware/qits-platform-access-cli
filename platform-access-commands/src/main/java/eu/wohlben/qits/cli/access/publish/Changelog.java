package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.access.changelog.AssociatedTickets;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * One release's {@code CHANGELOG.md}, as text: no I/O, no service, so every line of the format is a
 * unit test on a string. {@link ChangelogCommand} gathers the parts and publishes what this writes.
 * <p>
 * The format is a contract with its readers, the last line most of all: {@code associated tickets:}
 * is what a dependant's bump message reads back ({@link AssociatedTickets}), so it is always there,
 * bare when the release resolved no ticket.
 */
final class Changelog {

    /** A commit as listed: oldest first, the request's own merge commits already left out. */
    record Commit(String shortHash, String subject, String author) {
    }

    /** A ticket a subject named. {@code title} null is an id the service does not know. */
    record Ticket(String qualifiedId, String title, String link) {

        boolean resolved() {
            return title != null;
        }
    }

    /** A gate report's highlight, in the order the reports are to be listed. */
    record Highlight(String kind, String severity, String text) {
    }

    /** What the document is about. {@code occurredAt} may be null: the line then leaves the time out. */
    record Release(String repositoryName, String version, String releaseRequestId, Instant occurredAt) {
    }

    private Changelog() {
    }

    /** The whole document, ending in exactly one line break. */
    static String render(Release release, List<Commit> commits, List<Ticket> tickets, List<Highlight> highlights) {
        List<String> blocks = new ArrayList<>();
        blocks.add("# " + oneLine(release.repositoryName()) + " " + oneLine(release.version()));
        blocks.add("Release request `" + oneLine(release.releaseRequestId()) + "`"
                + (release.occurredAt() == null ? "" : ", released " + released(release.occurredAt())) + ".");

        StringBuilder commitBlock = new StringBuilder("## Commits\n\n");
        if (commits.isEmpty()) {
            commitBlock.append("None.");
        } else {
            List<String> lines = new ArrayList<>();
            for (Commit commit : commits) {
                lines.add("- `" + oneLine(commit.shortHash()) + "` " + oneLine(commit.subject())
                        + " (" + oneLine(commit.author()) + ")");
            }
            commitBlock.append(String.join("\n", lines));
        }
        blocks.add(commitBlock.toString());

        StringBuilder ticketBlock = new StringBuilder("## Tickets\n\n");
        List<String> resolved = new ArrayList<>();
        if (tickets.isEmpty()) {
            ticketBlock.append("None.");
        } else {
            List<String> lines = new ArrayList<>();
            for (Ticket ticket : tickets) {
                if (ticket.resolved()) {
                    lines.add("- [`" + ticket.qualifiedId() + "`](" + ticket.link() + ") " + oneLine(ticket.title()));
                    resolved.add(ticket.qualifiedId());
                } else {
                    lines.add("- `" + ticket.qualifiedId() + "` (no such work item)");
                }
            }
            ticketBlock.append(String.join("\n", lines));
        }
        blocks.add(ticketBlock.toString());

        if (!highlights.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Highlight highlight : highlights) {
                lines.add("- **" + oneLine(highlight.kind()) + "** " + oneLine(highlight.severity()) + ": "
                        + oneLine(highlight.text()));
            }
            blocks.add("## Report highlights\n\n" + String.join("\n", lines));
        }

        blocks.add(AssociatedTickets.line(resolved));
        return String.join("\n\n", blocks) + "\n";
    }

    /** ISO-8601 in UTC, to the second: {@code 2026-10-12T09:15:02Z}. */
    static String released(Instant at) {
        return DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * A value on one line: a list item that broke over a line would end the list, and a title or a
     * highlight is free text somebody wrote.
     */
    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n]+", " ").strip();
    }
}
