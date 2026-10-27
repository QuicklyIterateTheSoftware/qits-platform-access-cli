package eu.wohlben.qits.cli.access.registry;

import eu.wohlben.qits.cli.access.session.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryLoginsTest {

    private static final List<String> NPM = List.of("//registry.qits.example/artifacts/npm/npm/");
    private static final String MAVEN = "https://registry.qits.example/artifacts/maven/maven";
    private static final String TOKEN = "SECRET-tok-1";

    @TempDir
    Path home;

    private RegistryLogins logins() {
        return new RegistryLogins(home.resolve(".config/qits"));
    }

    private RegistryState state() {
        return new RegistryState(home.resolve(".npmrc").toString(), home.resolve(".m2/settings.xml").toString(),
                NPM, MAVEN);
    }

    private static String diffs(List<RegistryLogins.FileChange> changes) {
        return String.join("", changes.stream().map(RegistryLogins.FileChange::diff).toList());
    }

    @Test
    void writesBothFilesWithMode0600() throws Exception {
        Files.writeString(home.resolve(".npmrc"), "save-exact=true\n");
        Files.setPosixFilePermissions(home.resolve(".npmrc"), PosixFilePermissions.fromString("rw-r--r--"));

        List<RegistryLogins.FileChange> changes = logins().apply(state(), TOKEN, false, false);

        assertThat(changes).allMatch(RegistryLogins.FileChange::changed);
        for (Path file : List.of(home.resolve(".npmrc"), home.resolve(".m2/settings.xml"))) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
            assertThat(Files.readString(file)).contains(TOKEN);
        }
        assertThat(Files.readString(home.resolve(".npmrc"))).startsWith("save-exact=true\n");
        // No temporary file is left beside them.
        try (var listing = Files.list(home)) {
            assertThat(listing.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void anUnchangedFileIsNotWritten() throws Exception {
        logins().apply(state(), TOKEN, false, false);
        Path npmrc = home.resolve(".npmrc");
        FileTime old = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(npmrc, old);

        List<RegistryLogins.FileChange> again = logins().apply(state(), TOKEN, false, false);

        assertThat(again).noneMatch(RegistryLogins.FileChange::changed);
        assertThat(Files.getLastModifiedTime(npmrc)).isEqualTo(old);
    }

    @Test
    void aSymbolicLinkStaysALink() throws Exception {
        Path real = Files.createDirectories(home.resolve("dotfiles")).resolve("npmrc");
        Files.writeString(real, "color=false\n");
        Files.createSymbolicLink(home.resolve(".npmrc"), real);

        logins().apply(state(), TOKEN, false, false);

        assertThat(Files.isSymbolicLink(home.resolve(".npmrc"))).isTrue();
        assertThat(Files.readString(real)).startsWith("color=false\n").contains(TOKEN);
    }

    // ---------------------------------------------------------------- --dry-run

    @Test
    void aDryRunWritesNothingAndShowsAFreshFileAsAddedLines() throws Exception {
        String diff = diffs(logins().apply(state(), TOKEN, false, true));

        assertThat(home.resolve(".npmrc")).doesNotExist();
        assertThat(home.resolve(".m2")).doesNotExist();
        assertThat(diff).startsWith("--- " + home.resolve(".npmrc") + "\n+++ " + home.resolve(".npmrc") + "\n@@ -0,0 +1,3 @@\n")
                .contains("+//registry.qits.example/artifacts/npm/npm/:_authToken=<token>\n")
                .contains("--- " + home.resolve(".m2/settings.xml") + "\n")
                .containsPattern("\\n\\+ +<value>Bearer <token></value>\\n");
        assertThat(diff.lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(2);
    }

    @Test
    void aDryRunOfAReplaceShowsTheChangedLinesWithContext() throws Exception {
        Files.writeString(home.resolve(".npmrc"), "a=1\nb=2\nc=3\nd=4\n//mirror.dev.localhost:8080/x/:_auth=b2xk\n");
        logins().apply(state(), "SECRET-older", false, false);
        String before = Files.readString(home.resolve(".npmrc"));

        String diff = diffs(logins().apply(state(), TOKEN, true, true));

        assertThat(Files.readString(home.resolve(".npmrc"))).isEqualTo(before);
        // Foreign lines show only when they change; context comes from the block alone.
        assertThat(diff).contains("""
                @@ -5,1 +4,0 @@
                -//mirror.dev.localhost:8080/x/:_auth=<token>
                @@ -7,3 +6,3 @@
                 # >>> qits registry-login: managed by the qits CLI, edits here are replaced >>>
                -//registry.qits.example/artifacts/npm/npm/:_authToken=<token>
                +//registry.qits.example/artifacts/npm/npm/:_authToken=<token>
                 # <<< qits registry-login <<<
                """).doesNotContain("b=2");
    }

    @Test
    void aDryRunWithNothingToChangeHasNoDiff() throws Exception {
        logins().apply(state(), TOKEN, false, false);

        assertThat(diffs(logins().apply(state(), TOKEN, false, true))).isEmpty();
    }

    @Test
    void aDryRunNeverShowsAToken() throws Exception {
        Files.createDirectories(home.resolve(".m2"));
        Files.writeString(home.resolve(".m2/settings.xml"),
                "<settings><servers><server><id>x</id><password>SECRET-pw</password></server></servers></settings>\n");
        Files.writeString(home.resolve(".npmrc"), "//old.example/:_authToken=SECRET-old\n");
        logins().apply(state(), "SECRET-older", false, false);

        String diff = diffs(logins().apply(state(), TOKEN, true, true));

        assertThat(diff).isNotEmpty().doesNotContain("SECRET");
    }

    @Test
    void aDryRunMasksAMultiLinePasswordAndForeignHeaderValues() throws Exception {
        Files.createDirectories(home.resolve(".m2"));
        Files.writeString(home.resolve(".m2/settings.xml"), """
                <settings>
                  <servers>
                    <server>
                      <id>corp</id>
                      <password>
                        SECRET-multi-line
                      </password>
                      <privateKey>SECRET-key</privateKey>
                      <configuration><httpHeaders><property><name>X-Api</name>
                        <value>SECRET-header</value></property></httpHeaders></configuration>
                    </server>
                  </servers>
                </settings>
                """);
        String settings = home.resolve(".m2/settings.xml").toString();
        // --remove-stale has nothing to drop here, so make every foreign line change: a new state
        // points at a fresh file and the old one is the content above.
        String before = Files.readString(Path.of(settings));
        String masked = ManagedBlocks.maskSettings(before);

        assertThat(masked).doesNotContain("SECRET").contains("<password>\n<token>\n")
                .contains("<value><token></value>").contains("<privateKey><token></privateKey>");
        assertThat(masked.lines().count()).isEqualTo(before.lines().count());

        String diff = diffs(logins().apply(state(), TOKEN, false, true));
        assertThat(diff).doesNotContain("SECRET").doesNotContain("corp").doesNotContain("X-Api");
    }

    @Test
    void aDryRunMasksForeignNpmSecretsByKey() {
        String before = """
                //npm.example/:_authToken=SECRET-a
                //npm.example/:_password=SECRET-b
                //npm.example/:username=SECRET-c
                //npm.example/:certfile=SECRET-d
                my-client-secret=SECRET-e
                API_TOKEN=SECRET-f
                save-exact=true
                """;

        String masked = ManagedBlocks.maskNpmrc(before);

        assertThat(masked).doesNotContain("SECRET").contains("save-exact=true")
                .contains("my-client-secret=<token>");
        assertThat(masked.lines().count()).isEqualTo(before.lines().count());
    }

    @Test
    void aDryRunThatDropsForeignSecretLinesShowsThemMasked() throws Exception {
        Files.writeString(home.resolve(".npmrc"), """
                //registry.dev.localhost:8080/:_password=SECRET-old
                //registry.dev.localhost:8080/:client-secret=SECRET-other
                //npm.example/:_authToken=SECRET-kept
                """);

        String diff = diffs(logins().apply(state(), TOKEN, true, true));

        assertThat(diff).doesNotContain("SECRET").doesNotContain("npm.example")
                .contains("-//registry.dev.localhost:8080/:_password=<token>")
                .contains("-//registry.dev.localhost:8080/:client-secret=<token>");
    }

    // ---------------------------------------------------------------- the daemon's hook

    @Test
    void theHookDoesNothingUntilConfigured() throws Exception {
        Session session = new Session("https://idp", "qits-cli", TOKEN, Instant.now().plusSeconds(900), "SECRET-r",
                Instant.now().plus(Duration.ofDays(30)));

        assertThat(logins().accessTokenChanged(session)).isNull();
        assertThat(logins().minimumMargin()).isZero();
        assertThat(home.resolve(".npmrc")).doesNotExist();

        logins().saveState(state());

        assertThat(logins().minimumMargin()).isEqualTo(RegistryLogins.MARGIN);
        assertThat(logins().accessTokenChanged(session)).startsWith("registry logins updated in ").doesNotContain(TOKEN);
        assertThat(Files.readString(home.resolve(".npmrc"))).contains(TOKEN);
        assertThat(Files.readString(home.resolve(".config/qits/registries.json"))).doesNotContain("SECRET");
        assertThat(logins().accessTokenChanged(session)).isNull();
    }
}
