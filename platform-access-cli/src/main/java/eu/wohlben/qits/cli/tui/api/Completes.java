package eu.wohlben.qits.cli.tui.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the {@link CompletionSource} that fills this option's values.
 * <p>
 * It goes beside the {@code @CommandLine.Option} it belongs to, so an option says once where its
 * values come from and every command that takes the same kind of value names the same source.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Completes {

    Class<? extends CompletionSource> value();
}
