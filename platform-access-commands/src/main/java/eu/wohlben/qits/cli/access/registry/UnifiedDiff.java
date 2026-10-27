package eu.wohlben.qits.cli.access.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A unified diff of two small texts, three lines of context, for {@code --dry-run}. Lines are
 * compared as they are and printed through {@code show}, which redacts them: a token that changes
 * shows as a changed line, but never as the token.
 */
final class UnifiedDiff {

    private static final int CONTEXT = 3;

    private UnifiedDiff() {
    }

    private record Op(char kind, String line) {
    }

    /** Empty when the texts are equal. */
    static String of(String path, String before, String after, UnaryOperator<String> show) {
        if (before.equals(after)) {
            return "";
        }
        List<String> a = before.isEmpty() ? List.of() : before.lines().toList();
        List<String> b = after.isEmpty() ? List.of() : after.lines().toList();
        List<Op> ops = ops(a, b);

        StringBuilder out = new StringBuilder("--- ").append(path).append('\n')
                .append("+++ ").append(path).append('\n');
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind() == ' ') {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            // Extend the hunk while the next change is within two contexts.
            while (true) {
                int next = end;
                while (next < ops.size() && ops.get(next).kind() != ' ') {
                    next++;
                }
                int gap = next;
                while (gap < ops.size() && ops.get(gap).kind() == ' ') {
                    gap++;
                }
                if (gap < ops.size() && gap - next <= 2 * CONTEXT) {
                    end = gap;
                } else {
                    end = Math.min(ops.size(), next + CONTEXT);
                    break;
                }
            }
            int oldStart = 1 + start - count(ops, 0, start, '+');
            int newStart = 1 + start - count(ops, 0, start, '-');
            int oldCount = (end - start) - count(ops, start, end, '+');
            int newCount = (end - start) - count(ops, start, end, '-');
            out.append("@@ -").append(oldCount == 0 ? oldStart - 1 : oldStart).append(',').append(oldCount)
                    .append(" +").append(newCount == 0 ? newStart - 1 : newStart).append(',').append(newCount)
                    .append(" @@\n");
            for (int k = start; k < end; k++) {
                out.append(ops.get(k).kind()).append(show.apply(ops.get(k).line())).append('\n');
            }
            i = end;
        }
        return out.toString();
    }

    /** How many ops of {@code kind} lie in {@code [from, to)}. */
    private static int count(List<Op> ops, int from, int to, char kind) {
        int n = 0;
        for (int k = from; k < to; k++) {
            if (ops.get(k).kind() == kind) {
                n++;
            }
        }
        return n;
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
                ops.add(new Op(' ', a.get(i++)));
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op('-', a.get(i++)));
            } else {
                ops.add(new Op('+', b.get(j++)));
            }
        }
        while (i < a.size()) {
            ops.add(new Op('-', a.get(i++)));
        }
        while (j < b.size()) {
            ops.add(new Op('+', b.get(j++)));
        }
        return ops;
    }
}
