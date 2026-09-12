package eu.wohlben.qits.cli.access.platform;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformClientTest {

    private static final URI URL = URI.create("https://projects.dev.wohlben.eu/projects/api/projects");

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (k, v) -> true);
    }

    @Test
    void theServiceMessageIsReadFromEachKnownShape() {
        assertThat(PlatformClient.serviceMessage("{\"message\":\"repository not found\"}")).isEqualTo("repository not found");
        assertThat(PlatformClient.serviceMessage("{\"errors\":[{\"message\":\"a\"},{\"message\":\"b\"}]}")).isEqualTo("a; b");
        assertThat(PlatformClient.serviceMessage(
                "{\"title\":\"Constraint Violation\",\"violations\":[{\"field\":\"create.body.branch\",\"message\":\"must not be blank\"}]}"))
                .isEqualTo("create.body.branch: must not be blank");
        assertThat(PlatformClient.serviceMessage("{\"title\":\"Bad Request\"}")).isEqualTo("Bad Request");
        assertThat(PlatformClient.serviceMessage("<html>gateway</html>")).isEmpty();
        assertThat(PlatformClient.serviceMessage("")).isEmpty();
    }

    @Test
    void a401NamesTheChallengeError() {
        CliFailure failure = PlatformClient.refusal("GET", URL, 401, headers(Map.of("WWW-Authenticate",
                List.of("Bearer realm=\"qits\", error=\"invalid_token\", error_description=\"the token has expired\""))), "");
        assertThat(failure.getMessage())
                .startsWith("The platform refused the token (HTTP 401: invalid_token, the token has expired)");
        assertThat(failure.exitCode()).isEqualTo(CliFailure.FAILED);
        assertThat(PlatformClient.refusal("GET", URL, 401, headers(Map.of()), "").getMessage())
                .startsWith("The platform refused the token (HTTP 401).");
    }

    @Test
    void a403AndOtherStatusesSayWhatHappened() {
        assertThat(PlatformClient.refusal("GET", URL, 403, headers(Map.of()), "{\"message\":\"x\"}").getMessage())
                .startsWith("Your roles do not allow this (HTTP 403)");
        assertThat(PlatformClient.refusal("POST", URL, 409, headers(Map.of()), "{\"message\":\"stale\"}").getMessage())
                .isEqualTo("POST " + URL + " answered HTTP 409: stale");
        assertThat(PlatformClient.refusal("GET", URL, 302, headers(Map.of("Location", List.of("https://idp/login"))), "").getMessage())
                .contains("HTTP 302").contains("a redirect to https://idp/login");
    }
}
