package eu.wohlben.qits.cli.access.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A unified diff of two small texts for {@code --dry-run}, safe to paste.
 * <p>
 * Lines are compared as they are, so a token that changes shows as a changed line. What is printed
 * is the masked copy of each text (same line count, every secret shown as {@code <token>}). Context
 * comes only from the CLI's own blocks: a foreign line is printed only when it is removed or added,
 * never as context, so a hunk stops at one. Hunks with less or no context are still valid diffs.
 */
final class UnifiedDiff {

    private static final int CONTEXT = 3;

    private UnifiedDiff() {
    }

    /** One edit: the line's index in the old text and in the new one, -1 where it has none. */
    private record Op(char kind, int oldIndex, int newIndex) {
    }

    /**
     * @param shownBefore {@code before}, masked, with the same lines
     * @param shownAfter  {@code after}, masked, with the same lines
     * @param ownAfter    the indexes of {@code after}'s lines that belong to the CLI's blocks
     * @return empty when the texts are equal
     */
    static String of(String path, String before, String after, String shownBefore, String shownAfter,
                     Set<Integer> ownAfter) {
        if (before.equals(after)) {
            return "";
        }
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<String> showA = lines(shownBefore);
        List<String> showB = lines(shownAfter);
        if (showA.size() != a.size() || showB.size() != b.size()) {
            throw new IllegalStateException("a masked text must keep its lines");
        }
        List<Op> ops = ops(a, b);

        // A context line may be printed only when it is one of ours.
        boolean[] printable = new boolean[ops.size()];
        for (int k = 0; k < ops.size(); k++) {
            Op op = ops.get(k);
            printable[k] = op.kind() != ' ' || ownAfter.contains(op.newIndex());
        }

        StringBuilder out = new StringBuilder("--- ").append(path).append('\n')
                .append("+++ ").append(path).append('\n');
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind() == ' ') {
                i++;
                continue;
            }
            int start = i;
            while (start > 0 && i - start < CONTEXT && printable[start - 1] && ops.get(start - 1).kind() == ' ') {
                start--;
            }
            int end = i;
            while (true) {
                while (end < ops.size() && ops.get(end).kind() != ' ') {
                    end++;
                }
                int gap = end;
                while (gap < ops.size() && ops.get(gap).kind() == ' ' && printable[gap]) {
                    gap++;
                }
                boolean joins = gap < ops.size() && ops.get(gap).kind() != ' ' && gap - end <= 2 * CONTEXT;
                if (joins) {
                    end = gap;
                    continue;
                }
                end = Math.min(gap, end + CONTEXT);
                break;
            }
            hunk(out, ops, start, end, showA, showB);
            i = end;
        }
        return out.toString();
    }

    private static void hunk(StringBuilder out, List<Op> ops, int start, int end, List<String> showA,
                             List<String> showB) {
        int oldStart = -1;
        int newStart = -1;
        int oldCount = 0;
        int newCount = 0;
        for (int k = start; k < end; k++) {
            Op op = ops.get(k);
            if (op.oldIndex() >= 0) {
                oldStart = oldStart < 0 ? op.oldIndex() + 1 : oldStart;
                oldCount++;
            }
            if (op.newIndex() >= 0) {
                newStart = newStart < 0 ? op.newIndex() + 1 : newStart;
                newCount++;
            }
        }
        // An empty side names the line before it, as diff(1) does.
        if (oldCount == 0) {
            oldStart = linesBefore(ops, start, true);
        }
        if (newCount == 0) {
            newStart = linesBefore(ops, start, false);
        }
        out.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
        for (int k = start; k < end; k++) {
            Op op = ops.get(k);
            String line = op.kind() == '+' ? showB.get(op.newIndex()) : showA.get(op.oldIndex());
            out.append(op.kind()).append(ManagedBlocks.redact(line)).append('\n');
        }
    }

    private static int linesBefore(List<Op> ops, int start, boolean old) {
        int n = 0;
        for (int k = 0; k < start; k++) {
            if ((old ? ops.get(k).oldIndex() : ops.get(k).newIndex()) >= 0) {
                n++;
            }
        }
        return n;
    }

    private static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : text.lines().toList();
    }

    /** A longest-common-subsequence edit script. The files are small. */
    private static List<Op> ops(List<String> a, List<String> b) {
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(new Op(' ', i++, j++));
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op('-', i++, -1));
            } else {
                ops.add(new Op('+', -1, j++));
            }
        }
        while (i < a.size()) {
            ops.add(new Op('-', i++, -1));
        }
        while (j < b.size()) {
            ops.add(new Op('+', -1, j++));
        }
        return ops;
    }
}
