package eu.wohlben.qits.cli.access.report.locate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * TypeScript or JavaScript source as tokens, by hand and character by character. Comments are
 * stepped over; string and template literals become one token holding their unescaped value, a
 * template's {@code ${…}} substitutions scanned (and dropped) to their own closing brace however
 * deep they nest; and a {@code /} where an operand is expected starts a regex literal, so a brace
 * or a parenthesis inside one is not counted. No parser, so the native binary gains nothing.
 */
final class ScriptScanner {

    /** Keywords after which a {@code /} starts an operand, which is a regex literal. */
    private static final Set<String> BEFORE_OPERAND = Set.of("return", "typeof", "case", "do", "else", "in", "of",
            "new", "delete", "void", "throw", "instanceof", "yield", "await");

    private final String s;
    private final int n;
    private int i;
    private int line = 1;

    private ScriptScanner(String source) {
        this.s = source;
        this.n = source.length();
    }

    static List<Token> scan(String source) {
        List<Token> out = new ArrayList<>();
        new ScriptScanner(source).lex(out, false);
        return out;
    }

    /** Tokens into {@code out}; {@code substitution}: return after the {@code }} that closes a {@code ${}. */
    private void lex(List<Token> out, boolean substitution) {
        int depth = 0;
        Token previous = null;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && at(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && at(i + 1) == '*') {
                blockComment();
                continue;
            }
            Token token;
            if (c == '\'' || c == '"') {
                token = string(c);
            } else if (c == '`') {
                token = template();
            } else if (c == '/' && operandExpected(previous) && (token = regex()) != null) {
                // the regex literal, scanned
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) {
                    i++;
                }
                token = new Token(Token.Kind.IDENT, s.substring(start, i), line, null);
            } else if (Character.isDigit(c) || c == '.' && Character.isDigit(at(i + 1))) {
                int start = i;
                while (i < n && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                token = new Token(Token.Kind.NUMBER, s.substring(start, i), line, null);
            } else {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    if (substitution && depth == 0) {
                        i++;
                        return;
                    }
                    depth--;
                }
                token = Token.symbol(c, line);
                i++;
            }
            out.add(token);
            previous = token;
        }
    }

    private char at(int index) {
        return index < n ? s.charAt(index) : '\0';
    }

    /** Whether a {@code /} after {@code previous} begins an operand rather than dividing. */
    private static boolean operandExpected(Token previous) {
        if (previous == null) {
            return true;
        }
        return switch (previous.kind()) {
            case SYMBOL -> !previous.is(')') && !previous.is(']');
            case IDENT -> BEFORE_OPERAND.contains(previous.text());
            default -> false;
        };
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

    /** A regex literal from its opening {@code /}, flags included; null, and nothing consumed, when the line ends first. */
    private Token regex() {
        int j = i + 1;
        boolean inClass = false;
        while (true) {
            if (j >= n || s.charAt(j) == '\n') {
                return null;
            }
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                break;
            }
            j++;
        }
        j++;
        while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) {
            j++;
        }
        Token token = new Token(Token.Kind.REGEX, s.substring(i, j), line, null);
        i = j;
        return token;
    }

    /** A quoted string; one left open ends at the line's end. */
    private Token string(char quote) {
        int start = line;
        StringBuilder value = new StringBuilder();
        i++;
        while (i < n) {
            char c = s.charAt(i);
            if (c == quote) {
                i++;
                break;
            }
            if (c == '\n') {
                break;
            }
            if (c == '\\') {
                escape(value);
            } else {
                value.append(c);
                i++;
            }
        }
        return new Token(Token.Kind.STRING, null, start, List.of(value.toString()));
    }

    /** A template literal: its literal stretches, the substitutions between them scanned and dropped. */
    private Token template() {
        int start = line;
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        i++;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '`') {
                i++;
                break;
            }
            if (c == '\\') {
                escape(part);
            } else if (c == '$' && at(i + 1) == '{') {
                parts.add(part.toString());
                part = new StringBuilder();
                i += 2;
                lex(new ArrayList<>(), true);
            } else {
                if (c == '\n') {
                    line++;
                }
                part.append(c);
                i++;
            }
        }
        parts.add(part.toString());
        return new Token(Token.Kind.TEMPLATE, null, start, List.copyOf(parts));
    }

    /** At a backslash: its escape's value onto {@code value}, and past it. */
    private void escape(StringBuilder value) {
        i++;
        if (i >= n) {
            return;
        }
        char c = s.charAt(i);
        i++;
        switch (c) {
            case 'n' -> value.append('\n');
            case 't' -> value.append('\t');
            case 'r' -> value.append('\r');
            case 'b' -> value.append('\b');
            case 'f' -> value.append('\f');
            case 'v' -> value.append('\u000B');
            case '0' -> value.append('\0');
            case 'x' -> hex(value, 2);
            case 'u' -> {
                if (at(i) == '{') {
                    int close = s.indexOf('}', i);
                    if (close > i) {
                        try {
                            value.appendCodePoint(Integer.parseInt(s.substring(i + 1, close), 16));
                            i = close + 1;
                            return;
                        } catch (IllegalArgumentException malformed) {
                            // kept as written
                        }
                    }
                    value.append('u');
                } else {
                    hex(value, 4);
                }
            }
            case '\r' -> {
                if (at(i) == '\n') {
                    i++;
                }
                line++;
            }
            case '\n' -> line++;
            default -> value.append(c);
        }
    }

    private void hex(StringBuilder value, int digits) {
        if (i + digits <= n) {
            try {
                value.append((char) Integer.parseInt(s.substring(i, i + digits), 16));
                i += digits;
                return;
            } catch (NumberFormatException malformed) {
                // kept as written
            }
        }
        value.append(s.charAt(i - 1));
    }
}
