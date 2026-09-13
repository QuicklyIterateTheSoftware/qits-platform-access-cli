package eu.wohlben.qits.cli.tui.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where an option's values come from when the platform, not the person, knows them.
 * <p>
 * One small bean per kind of value — projects, repositories, runs — named on the option by
 * {@link Completes} and reused by every option of that kind. The screen asks for the values, shows
 * them as a searchable list and puts the chosen {@link Choice#value()} on the command line; it
 * knows nothing else about them, which is why one source can serve every {@code --project} there
 * will ever be.
 * <p>
 * A source is an ordinary CDI bean and calls the platform with the session the CLI already holds.
 * Throwing is normal — a session may have expired, a service may be down — and the screen falls
 * back to a field to type into rather than becoming unusable.
 */
public interface CompletionSource {

    /**
     * What a person picks.
     *
     * @param value what goes on the command line
     * @param label what a person recognises, and what typing in the list matches: a run's label
     *              carries its branch, its status and its time, while its value is the bare id
     */
    record Choice(String value, String label) {

        public static Choice of(String value) {
            return new Choice(value, value);
        }
    }

    /**
     * @param chosen the values already picked on this command line, keyed by long option name
     *               without the dashes — for example {@code {"project": "qits"}}
     */
    List<Choice> choices(Map<String, String> chosen);

    /**
     * Long option names, without dashes, that must be chosen before this source can be asked.
     * <p>
     * The screen orders the option rows so these come first, and refuses to open this one until
     * they are set — it says which to pick and calls nothing.
     */
    default Set<String> dependsOn() {
        return Set.of();
    }
}
