package eu.wohlben.qits.cli.tui.api;

/**
 * Reads {@link TuiCommand} off a command object.
 * <p>
 * One reader, because two things ask: the screen, which shows a command according to how it
 * behaves, and the CLI itself, which refuses a browser command where there is no browser. A command
 * is a CDI bean and what the container hands back may be a generated subclass of the annotated
 * class, so the superclasses are walked.
 */
public final class TuiCommands {

    private TuiCommands() {
    }

    /** The command's declaration, or null when it carries none. */
    public static TuiCommand declaredOn(Object command) {
        if (command == null) {
            return null;
        }
        for (Class<?> type = command.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            TuiCommand declared = type.getAnnotation(TuiCommand.class);
            if (declared != null) {
                return declared;
            }
        }
        return null;
    }

    public static Interaction interactionOf(Object command) {
        TuiCommand declared = declaredOn(command);
        return declared == null ? Interaction.PLAIN : declared.interaction();
    }

    public static Output outputOf(Object command) {
        TuiCommand declared = declaredOn(command);
        return declared == null ? Output.TEXT : declared.output();
    }
}
