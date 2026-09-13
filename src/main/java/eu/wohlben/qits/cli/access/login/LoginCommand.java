package eu.wohlben.qits.cli.access.login;

import eu.wohlben.qits.cli.access.idp.IdpUrl;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Callable;

@TuiCommand(interaction = Interaction.BROWSER)
@CommandLine.Command(name = "login", mixinStandardHelpOptions = true,
        description = {
                "Sign in through the browser and store the session. Run it first, and again when a command says "
                        + "`Not signed in` or `Session ended`.",
                "The session goes to $XDG_CONFIG_HOME/qits/t.json (default ~/.config/qits/t.json). The page shows a "
                        + "code: paste it at `Paste the code:`. The codes of one sign-in last 5 minutes."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits login",
                "  qits login --idp-url https://idp.dev.wohlben.eu/idp",
                "  qits login --no-browser",
                "",
                "Over SSH, use --no-browser and open the printed address on a machine with a browser."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Signed in; the session is stored.", "1:The sign-in did not complete.",
                "2:The idp address cannot be worked out (see --idp-url), or the command was used wrongly."})
public class LoginCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--idp-url", paramLabel = "<url>",
            description = "The idp's public base URL. Default: QITS_IDP_URL, else "
                    + "https://idp.<QITS_ENV_NAME>.<QITS_DOMAIN>/idp, else (no QITS_DOMAIN) the platform on this "
                    + "machine, http://idp.<QITS_ENV_NAME or prod>.localhost:8080/idp.")
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
