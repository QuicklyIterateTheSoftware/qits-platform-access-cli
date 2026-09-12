package eu.wohlben.qits.cli.access.login;

import eu.wohlben.qits.cli.access.idp.IdpUrl;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.SessionFile;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "login", mixinStandardHelpOptions = true,
        description = {
                "Sign in through the browser and store the session.",
                "The session is written to $XDG_CONFIG_HOME/qits/t.json (default ~/.config/qits/t.json)."})
public class LoginCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--idp-url", paramLabel = "<url>",
            description = "The idp's public base URL. Default: QITS_IDP_URL, else "
                    + "https://idp.<QITS_ENV_NAME>.<QITS_DOMAIN>/idp.")
    String idpUrl;

    @CommandLine.Option(names = "--no-browser",
            description = "Only print the sign-in address; do not start a browser.")
    boolean noBrowser;

    @Override
    public Integer call() throws Exception {
        Map<String, String> env = System.getenv();
        String idp;
        try {
            idp = IdpUrl.resolve(idpUrl, env);
        } catch (IllegalArgumentException refused) {
            System.err.println(refused.getMessage());
            return 2;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
        return new LoginFlow(new TokenClient(idp), SessionFile.fromEnvironment(env), in, System.out, System.err,
                Clock.systemUTC(), noBrowser ? null : url -> Browser.open(url, env)).run();
    }
}
