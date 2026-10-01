package eu.wohlben.qits.cli.mcp;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.util.KeyUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * The deployed posture for bearers, with a local key in place of a live idp.
 * <p>
 * {@code quarkus.oidc.public-key} replaces the key fetch, and {@code auth-server-url} is cleared
 * beside it so the tenant never tries to reach an idp. The audience is the shipped configuration's.
 * The key pair is a file rather than made per run: the profile and the test are loaded by two class
 * loaders, and a key made in a static field would be two keys. It exists for this suite only and
 * protects nothing.
 * <p>
 * The projects service the commands call is {@link ProjectsStub}, a route in this same application.
 */
public class BearerProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.oidc.auth-server-url", "",
                "quarkus.oidc.public-key", pem("/bearer-verification-key.pem")
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", ""),
                "QITS_PROJECTS_URL", "http://localhost:${quarkus.http.test-port:8081}");
    }

    /** A token as the idp gives an agent: the platform's audience, its role in {@code groups}. */
    static String agentToken(String audience) {
        try {
            return Jwt.claims()
                    .issuer("http://dev-qits-idp:8080/idp")
                    .subject("workspace-7f3a")
                    .audience(Set.of(audience))
                    .groups(Set.of("qits:agent"))
                    .expiresIn(Duration.ofMinutes(5))
                    .jws()
                    .keyId("mcp-suite-key")
                    .sign(KeyUtils.decodePrivateKey(pem("/bearer-signing-key.pem")));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign the test token", e);
        }
    }

    private static String pem(String resource) {
        try (InputStream in = BearerProfile.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
