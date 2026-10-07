package eu.wohlben.qits.cli.access.report.locate;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Which bracket closes which: {@code ()}, {@code []} and {@code {}}. */
final class Brackets {

    private Brackets() {
    }

    /**
     * For every bracket token the index of its partner, -1 for every other token; null when the
     * brackets do not balance, which no locator guesses past.
     */
    static int[] match(List<Token> tokens) {
        int[] partner = new int[tokens.size()];
        Arrays.fill(partner, -1);
        Deque<Integer> open = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is('(') || t.is('[') || t.is('{')) {
                open.push(i);
            } else if (t.is(')') || t.is(']') || t.is('}')) {
                if (open.isEmpty() || !pair(tokens.get(open.peek()), t)) {
                    return null;
                }
                int opener = open.pop();
                partner[opener] = i;
                partner[i] = opener;
            }
        }
        return open.isEmpty() ? partner : null;
    }

    private static boolean pair(Token opener, Token closer) {
        return opener.is('(') && closer.is(')') || opener.is('[') && closer.is(']')
                || opener.is('{') && closer.is('}');
    }
}
