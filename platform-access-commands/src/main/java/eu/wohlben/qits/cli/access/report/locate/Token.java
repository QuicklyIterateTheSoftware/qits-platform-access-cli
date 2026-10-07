package eu.wohlben.qits.cli.access.report.locate;

import java.util.List;

/**
 * One token of a scanned source file: what the locators walk instead of a syntax tree.
 *
 * @param kind  what it is
 * @param text  the identifier or the punctuation character; null for literals
 * @param line  the 1-based line it starts on
 * @param parts a string literal's value, unescaped, as one part; a template literal's literal
 *              stretches, one more than it has substitutions; null for anything else
 */
record Token(Kind kind, String text, int line, List<String> parts) {

    enum Kind { IDENT, NUMBER, STRING, TEMPLATE, REGEX, SYMBOL }

    static Token symbol(char c, int line) {
        return new Token(Kind.SYMBOL, String.valueOf(c), line, null);
    }

    boolean is(char symbol) {
        return kind == Kind.SYMBOL && text.length() == 1 && text.charAt(0) == symbol;
    }

    boolean isIdent(String name) {
        return kind == Kind.IDENT && text.equals(name);
    }

    boolean isIdent() {
        return kind == Kind.IDENT;
    }
}
