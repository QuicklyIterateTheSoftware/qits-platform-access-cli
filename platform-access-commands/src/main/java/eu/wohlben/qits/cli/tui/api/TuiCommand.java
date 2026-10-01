package eu.wohlben.qits.cli.tui.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The few things about a command that picocli's model cannot say, said once, next to the command.
 * <p>
 * Every member is optional and both have a working default, so a command that carries no
 * {@code @TuiCommand} at all still appears in {@code qits tui} and still runs. Nothing outside this
 * annotation and {@link CompletionSource} may make the TUI treat one command differently from
 * another — that is the whole of the contract, and the fictional-command test holds it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TuiCommand {

    Interaction interaction() default Interaction.PLAIN;

    Output output() default Output.TEXT;

    /** Whether the command reads a document on stdin. The screen does not read it. */
    Input input() default Input.NONE;
}
