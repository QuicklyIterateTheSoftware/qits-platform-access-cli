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

    /**
     * What the document is about. {@code releaseRequest} is the request's logical id, or its uuid when
     * the event has none. {@code occurredAt} may be null: the line then leaves the time out.
     */
    record Release(String repositoryName, String version, String releaseRequest, Instant occurredAt) {
    }

    /**
     * An earlier release that published no changelog (qits-1193): its commits are listed in this one,
     * so none of them is in no changelog at all. {@code releaseRequest} as in {@link Release}.
     */
    record Earlier(String version, String releaseRequest) {
    }

    private Changelog() {
    }

    /** The whole document of a release with no earlier release to carry. */
    static String render(Release release, List<Commit> commits, List<Ticket> tickets, List<Highlight> highlights) {
        return render(release, List.of(), commits, tickets, highlights);
    }

    /**
     * The whole document, ending in exactly one line break. {@code commits} holds the earlier
     * releases' commits too; {@code earlier} only names those releases above the list.
     */
    static String render(Release release, List<Earlier> earlier, List<Commit> commits, List<Ticket> tickets,
                         List<Highlight> highlights) {
        List<String> blocks = new ArrayList<>();
        blocks.add("# " + oneLine(release.repositoryName()) + " " + oneLine(release.version()));
        blocks.add("Release request `" + oneLine(release.releaseRequest()) + "`"
                + (release.occurredAt() == null ? "" : ", released " + released(release.occurredAt())) + ".");

        StringBuilder commitBlock = new StringBuilder("## Commits\n\n");
        if (!earlier.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Earlier unpublished : earlier) {
                names.add(oneLine(unpublished.version()) + " (`" + oneLine(unpublished.releaseRequest()) + "`)");
            }
            String joined = names.size() == 1 ? names.get(0)
                    : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
            commitBlock.append("With the commits of ").append(joined).append(", which published no changelog.\n\n");
        }
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
