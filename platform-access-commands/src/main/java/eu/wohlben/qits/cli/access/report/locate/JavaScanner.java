package eu.wohlben.qits.cli.access.report.locate;

import java.util.ArrayList;
import java.util.List;

/**
 * Java source as tokens, by hand and character by character: identifiers, numbers, literals and
 * single punctuation characters, each with its line. Comments are stepped over, and so is every
 * brace inside a string, a char literal or a text block, which is all the locator needs to match
 * the braces that matter. No parser, so the native binary gains nothing.
 */
final class JavaScanner {

    private final String s;
    private final int n;
    private int i;
    private int line = 1;

    private JavaScanner(String source) {
        this.s = source;
        this.n = source.length();
    }

    static List<Token> scan(String source) {
        return new JavaScanner(source).tokens();
    }

    private List<Token> tokens() {
        List<Token> out = new ArrayList<>();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && at(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && at(i + 1) == '*') {
                blockComment();
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                out.add(textBlock());
            } else if (c == '"' || c == '\'') {
                out.add(quoted(c));
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) {
                    i++;
                }
                out.add(new Token(Token.Kind.IDENT, s.substring(start, i), line, null));
            } else if (Character.isDigit(c) || c == '.' && Character.isDigit(at(i + 1))) {
                int start = i;
                while (i < n && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Token.Kind.NUMBER, s.substring(start, i), line, null));
            } else {
                out.add(Token.symbol(c, line));
                i++;
            }
        }
        return out;
    }

    private char at(int index) {
        return index < n ? s.charAt(index) : '\0';
    }

    private void blockComment() {
        i += 2;
        while (i < n && !(s.charAt(i) == '*' && at(i + 1) == '/')) {
            if (s.charAt(i) == '\n') {
                line++;
            }
            i++;
        }
        i = Math.min(n, i + 2);
    }

    /** A {@code """} text block, to its closing {@code """}; an escaped quote does not close it. */
    private Token textBlock() {
        int start = line;
        i += 3;
        while (i < n && !s.startsWith("\"\"\"", i)) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                c = at(i);
            }
            if (c == '\n') {
                line++;
            }
            i++;
        }
        i = Math.min(n, i + 3);
        return new Token(Token.Kind.STRING, null, start, null);
    }

    /** A string or char literal; one left open ends at the line's end, as javac would complain there. */
    private Token quoted(char quote) {
        int start = line;
        i++;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                break;
            }
            i++;
            if (c == '\\') {
                if (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == quote) {
                break;
            }
        }
        return new Token(Token.Kind.STRING, null, start, null);
    }
}
