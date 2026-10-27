package eu.wohlben.qits.cli.access.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBlocksTest {

    private static final List<String> NPM = List.of("//registry.qits.example/artifacts/npm/npm/",
            "//mirror.qits.example/npm/npmjs/");
    private static final String MAVEN = "https://registry.qits.example/artifacts/maven/maven";

    // ---------------------------------------------------------------- npm

    @Test
    void npmCreatesTheBlockInAnEmptyFile() {
        ManagedBlocks.Result result = ManagedBlocks.npmrc("", NPM, "tok-1", false);

        assertThat(result.text()).isEqualTo("""
                # >>> qits registry-login: managed by the qits CLI, edits here are replaced >>>
                //registry.qits.example/artifacts/npm/npm/:_authToken=tok-1
                //mirror.qits.example/npm/npmjs/:_authToken=tok-1
                # <<< qits registry-login <<<
                """);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void npmReplacesOnlyItsBlockAndKeepsForeignLines() {
        String before = """
                # mine
                save-exact=true
                //npm.pkg.github.com/:_authToken=ghp-foreign
                """;
        String first = ManagedBlocks.npmrc(before, NPM, "tok-1", false).text();
        String second = ManagedBlocks.npmrc(first + "color=false\n", NPM, "tok-2", false).text();

        assertThat(first).startsWith(before + "\n# >>> qits");
        assertThat(second).startsWith(before).contains(":_authToken=tok-2").doesNotContain("tok-1")
                .endsWith("# <<< qits registry-login <<<\ncolor=false\n");
        assertThat(second.lines().filter(l -> l.contains("qits registry-login")).count()).isEqualTo(2);
    }

    @Test
    void npmStaleLinesStayWithAWarningUnlessAskedToGo() {
        String before = """
                # the old login for //registry.dev.localhost:8080/
                //mirror.dev.localhost:8080/artifacts/npm/npmjs/:_auth=b2xk
                //registry.qits.example/artifacts/npm/npm/:_authToken=handmade
                save-exact=true
                """;
        ManagedBlocks.Result kept = ManagedBlocks.npmrc(before, NPM, "tok", false);
        ManagedBlocks.Result removed = ManagedBlocks.npmrc(before, NPM, "tok", true);

        assertThat(kept.text()).contains("dev.localhost:8080/artifacts/npm/npmjs/:_auth=").contains("handmade");
        assertThat(kept.warnings()).hasSize(2);
        assertThat(removed.text()).doesNotContain(":_auth=b2xk").doesNotContain("handmade")
                .contains("# the old login for //registry.dev.localhost:8080/").contains("save-exact=true");
        assertThat(removed.warnings()).isEmpty();
    }

    // ---------------------------------------------------------------- Maven

    @Test
    void mavenMakesASettingsFileFromNothing() {
        String text = ManagedBlocks.mavenSettings("", MAVEN, "tok-1", false).text();

        assertThat(text).startsWith("<?xml").contains("<mirrors>").contains("<servers>")
                .contains("<id>qits-maven-host</id>").contains("<mirrorOf>qits-maven</mirrorOf>")
                .contains("<url>" + MAVEN + "</url>").contains("<name>Authorization</name>")
                .contains("<value>Bearer tok-1</value>").endsWith("</settings>\n");
        assertThat(text.split("Bearer tok-1", -1)).hasSize(3);
        assertThat(text.split("<server>", -1)).hasSize(3);
    }

    @Test
    void mavenReplacesItsBlocksAndKeepsEverythingElse() {
        String before = """
                <?xml version="1.0"?>
                <!-- my comment with <mirrors> in it -->
                <settings>
                  <localRepository>/data/m2</localRepository>
                  <mirrors>
                    <mirror><id>corp</id><mirrorOf>central</mirrorOf><url>https://corp.example/m2</url></mirror>
                  </mirrors>
                  <servers>
                    <server><id>corp</id><username>me</username><password>pw</password></server>
                  </servers>
                </settings>
                """;
        String first = ManagedBlocks.mavenSettings(before, MAVEN, "tok-1", false).text();
        String second = ManagedBlocks.mavenSettings(first, MAVEN, "tok-2", false).text();

        assertThat(second).isEqualTo(first.replace("tok-1", "tok-2"));
        assertThat(ManagedBlocks.mavenSettings(second, MAVEN, "tok-2", false).text()).isEqualTo(second);
        assertThat(first).contains("<!-- my comment with <mirrors> in it -->")
                .contains("<localRepository>/data/m2</localRepository>")
                .contains("<mirror><id>corp</id>").contains("<password>pw</password>");
        // Ours comes first in each section, so Maven picks it before anything older.
        assertThat(first.indexOf("qits-maven-host")).isLessThan(first.indexOf("<id>corp</id>"));
        assertThat(first).contains("""
                  <mirrors>
                    <!-- >>> qits registry-login: managed by the qits CLI, edits here are replaced >>> -->
                    <mirror>
                      <id>qits-maven-host</id>
                """);
    }

    @Test
    void mavenAddsMissingSectionsAndOpensSelfClosingOnes() {
        String text = ManagedBlocks.mavenSettings("<settings>\n  <mirrors/>\n</settings>\n", MAVEN, "t", false).text();

        assertThat(text).doesNotContain("<mirrors/>").contains("</mirrors>").contains("  <servers>\n");
        assertThat(text.indexOf("<servers>")).isGreaterThan(text.indexOf("</mirrors>"));
    }

    @Test
    void mavenStaleEntriesGoOnlyWhenAsked() {
        String before = """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <mirrors>
                    <mirror>
                      <id>qits-maven-host</id>
                      <mirrorOf>qits-maven</mirrorOf>
                      <url>http://registry.dev.localhost:8080/artifacts/maven/maven</url>
                    </mirror>
                    <mirror><id>corp</id><mirrorOf>central</mirrorOf><url>https://corp.example/m2</url></mirror>
                  </mirrors>
                  <servers>
                    <server>
                      <id>qits-maven-host</id>
                      <username>old-client</username>
                      <password>old-secret</password>
                    </server>
                  </servers>
                </settings>
                """;
        ManagedBlocks.Result kept = ManagedBlocks.mavenSettings(before, MAVEN, "tok", false);
        ManagedBlocks.Result removed = ManagedBlocks.mavenSettings(before, MAVEN, "tok", true);

        assertThat(kept.text()).contains("registry.dev.localhost").contains("old-secret");
        assertThat(kept.warnings()).hasSize(2);
        assertThat(removed.text()).doesNotContain("dev.localhost").doesNotContain("old-secret")
                .contains("<id>corp</id>");
        assertThat(removed.text().split("<id>qits-maven-host</id>", -1)).hasSize(3);
        assertThat(removed.warnings()).isEmpty();
    }

    @Test
    void mavenRefusesAFileThatIsNoSettings() {
        assertThatThrownBy(() -> ManagedBlocks.mavenSettings("<project/>", MAVEN, "t", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redactionHidesEveryKindOfCredential() {
        assertThat(ManagedBlocks.redact("//r/:_authToken=abc.def")).isEqualTo("//r/:_authToken=<token>");
        assertThat(ManagedBlocks.redact("//r/:_auth=Zm9vOmJhcg==")).isEqualTo("//r/:_auth=<token>");
        assertThat(ManagedBlocks.redact("<value>Bearer eyJ.x.y</value>")).isEqualTo("<value>Bearer <token></value>");
        assertThat(ManagedBlocks.redact("<password>pw</password>")).isEqualTo("<password><token></password>");
        assertThat(ManagedBlocks.redact("save-exact=true")).isEqualTo("save-exact=true");
    }
}
