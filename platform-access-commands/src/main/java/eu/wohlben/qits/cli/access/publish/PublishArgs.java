package eu.wohlben.qits.cli.access.publish;

import java.util.List;

/**
 * The manual checks that give qits-publish's own argument grammar under picocli.
 * <p>
 * Every option in this package is declared as a repeatable, non-required list, so picocli itself
 * never rejects a missing or a repeated flag: the checks here do, with the same message and the
 * same exit code (1) as the client this package replaces. Only a truly unrecognised flag is left to
 * picocli — every command declares an {@code @Unmatched} field for that, and {@link #noExtras} turns
 * it into the same refusal qits-publish gave.
 */
final class PublishArgs {

    private PublishArgs() {
    }

    /** The one value a required flag must have: not absent, not empty, and not given twice. */
    static String requiredOnce(List<String> values, String name) {
        String value = optionalOnce(values, name, null);
        if (value == null || value.isEmpty()) {
            throw CliException.policy(name + " is required");
        }
        return value;
    }

    /** The one value an optional flag has, or {@code fallback} — refusing a flag given twice. */
    static String optionalOnce(List<String> values, String name, String fallback) {
        if (values.isEmpty()) {
            return fallback;
        }
        if (values.size() > 1) {
            throw CliException.policy(name + " was given more than once");
        }
        return values.get(0);
    }

    /**
     * Refuses every stray token picocli did not place: an unrecognised flag, or a bare argument
     * nobody asked for. The first one found names itself, the way qits-publish's own parser did.
     */
    static void noExtras(List<String> unmatched) {
        if (unmatched.isEmpty()) {
            return;
        }
        String first = unmatched.get(0);
        if (first.startsWith("-") && !first.equals("-")) {
            throw CliException.policy("unknown option " + first);
        }
        throw CliException.policy("unexpected argument " + first);
    }
}
