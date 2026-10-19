package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.session.Credential;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * <b>Which client methods a row's call went through</b>: the recording double {@code CliPactTest}
 * holds each row's {@link CliRow#reaches} against.
 * <p>
 * Every request {@code PlatformClient} makes asks its credential for the bearer first, on the
 * calling thread. {@link #CREDENTIAL} is a credential that, when asked, writes down every frame of
 * the CLI's own code on the stack as {@code Class#method}, so a row that says it calls {@code
 * ProjectsApi#work} and in fact calls {@code ProjectsApi#workChildren} fails. A client that sends
 * without a {@code PlatformClient} (the idp's token form, the publish client) asks nothing of it,
 * and its row is held to the pact's request alone.
 */
public final class Reached {

    private static final String CODE = "eu.wohlben.qits.cli.";

    private static final ThreadLocal<Set<String>> FRAMES = new ThreadLocal<>();

    /** The bearer every contract row sends: the pact does not hold it. */
    public static final Credential CREDENTIAL = () -> {
        Set<String> frames = FRAMES.get();
        if (frames != null) {
            StackWalker.getInstance().forEach(frame -> {
                String type = frame.getClassName();
                if (type.startsWith(CODE)) {
                    frames.add(type.substring(type.lastIndexOf('.') + 1) + "#" + frame.getMethodName());
                }
            });
        }
        return "pact-consumer-test";
    };

    private Reached() {
    }

    /** What {@code work} went through, every {@code Class#method} on the stack of each bearer read. */
    public static Set<String> during(Callable<?> work) throws Exception {
        Set<String> frames = new LinkedHashSet<>();
        FRAMES.set(frames);
        try {
            work.call();
        } finally {
            FRAMES.remove();
        }
        return frames;
    }
}
