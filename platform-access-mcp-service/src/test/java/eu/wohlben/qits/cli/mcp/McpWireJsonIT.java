package eu.wohlben.qits.cli.mcp;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

/**
 * {@link McpWireJsonTest} against the packaged native runner, which is the only place the
 * qits-1226 failure can be seen: a runner whose Vert.x JSON codec was frozen at image build time with
 * a bare ObjectMapper writes every content item with {@code "_meta":null,"annotations":null} and the
 * result with {@code "structuredContent":null}. The released 2026.1012.174516 runner fails this;
 * McpTools' codec reset is what makes every build pass it.
 * <p>
 * Failsafe, and only under {@code -Dnative} (the service pom's native profile): a plain
 * {@code mvn verify} has no runner to start. docker/Dockerfile's {@code service-it} stage runs it
 * against the very runner the image ships, so a bad build fails the release request. By hand, against
 * any runner:
 *
 * <pre>
 * ./mvnw -B -pl platform-access-mcp-service -am -Dnative test-compile \
 *   failsafe:integration-test failsafe:verify -Dnative.image.path=&lt;runner&gt;
 * </pre>
 * <p>
 * The runner is a separate process: {@link BearerProfile}'s overrides reach it as system properties,
 * which is how its bearer key and projects URL arrive, but test beans do not, so {@link ProjectsStub}
 * is absent there and the call is an error result. The assertion holds for that just as well.
 */
@QuarkusIntegrationTest
@TestProfile(BearerProfile.class)
class McpWireJsonIT extends McpWireJsonTest {
}
