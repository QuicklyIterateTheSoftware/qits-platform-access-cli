package eu.wohlben.qits.cli.access.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * Claude's MCP {@code headersHelper}: prints the header the qits MCP server wants, with the bearer
 * this process would call the platform with.
 * <p>
 * <b>The second place {@code qits} prints a token</b>, beside {@code git-credential get}, and for the
 * same reason: the program that runs it reads the token from stdout. stderr never carries one.
 * <p>
 * <b>Interim.</b> A container's token lasts an hour, so a header written once at launch goes stale
 * in a long session; a helper Claude runs when it connects does not. Follow-up qits-684 gives every
 * workspace a long-lived token of its own, which the MCP entry then carries as a plain header, and
 * this command goes. Keep it this small so that is one deletion.
 * <p>
 * In the token home ({@code QITS_TOKEN}) the bearer is that token, unchanged: the code path is the
 * same, only the credential the context picks is different.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "mcp-credential", mixinStandardHelpOptions = true,
        description = {
                "The headers helper of Claude's MCP entry for the qits MCP server. Claude runs it; a person does not.",
                "Prints {\"Authorization\":\"Bearer <token>\"} on stdout: inside the platform the container's own "
                        + "credential, on a workstation the session of `qits login`, refreshed first when needed, and "
                        + "with a workspace token (QITS_TOKEN) that token as it is.",
                "Interim: once every workspace has a long-lived token of its own (qits-684) the entry carries it "
                        + "as a plain header and this command goes."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  \"qits\": {\"type\": \"http\", \"url\": \"http://dev-qits-platform-access-mcp-service:8080/mcp\", "
                        + "\"headersHelper\": \"qits mcp-credential\"}",
                "",
                "The example is the entry in Claude's MCP configuration. Do not run it yourself: it prints a token "
                        + "on stdout, because that is what a headers helper does.",
                "",
                "With QITS_TOKEN set the header carries that token, so an entry rendered with this helper keeps "
                        + "working on a runner node; the url there is the public vhost, not the wire alias."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Printed.",
                "1:No token to be had (not signed in, the session ended, or the idp refused or cannot be reached): "
                        + "one line on stderr and nothing on stdout, so Claude connects without the header and is "
                        + "told 401."})
public class McpCredentialCommand extends PlatformCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected int execute(CliContext context) {
        String bearer;
        try {
            bearer = context.credential().bearer();
        } catch (CliFailure noToken) {
            // A failure's message never holds the token or a secret.
            return refuse(context, noToken.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return refuse(context, "Interrupted before a token was had.");
        } catch (RuntimeException unexpected) {
            // Its message is not known to be free of the token, so it is not printed.
            return refuse(context, "No token to be had: " + unexpected.getClass().getSimpleName() + ".");
        }
        ObjectNode headers = JSON.createObjectNode().put("Authorization", "Bearer " + bearer);
        context.out().println(headers.toString());
        context.out().flush();
        return 0;
    }

    private static int refuse(CliContext context, String line) {
        context.err().println(line);
        context.err().flush();
        return CliFailure.FAILED;
    }
}
