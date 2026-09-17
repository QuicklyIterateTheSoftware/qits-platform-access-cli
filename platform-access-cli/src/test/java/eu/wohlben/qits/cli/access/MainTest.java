package eu.wohlben.qits.cli.access;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The argv[0] alias: started as {@code qits-publish}, {@code qits} behaves as {@code qits
 * artifacts publish}. {@link Main#invokedAs()} itself reads {@code /proc/self/cmdline}, so it is
 * proven by the native smoke test instead (see the release recipe); what a unit test can hold is
 * that {@link Main#effectiveArgs} does the right thing with whatever name that lookup returns.
 */
class MainTest {

    @Test
    void startedAsQitsPublishPrependsArtifactsPublish() {
        assertThat(Main.effectiveArgs(new String[] {"sbom", "submit"}, "qits-publish"))
                .containsExactly("artifacts", "publish", "sbom", "submit");
    }

    @Test
    void aSymlinkOrACopyBothNameThemselvesQitsPublishSoBothAlias() {
        // /proc/self/cmdline carries whatever path was executed — the symlink's own name, or the
        // copy's — and Main only ever compares its final segment, so either shape reaches here as
        // exactly "qits-publish".
        assertThat(Main.effectiveArgs(new String[0], "qits-publish")).containsExactly("artifacts", "publish");
    }

    @Test
    void startedAsQitsLeavesArgsAlone() {
        assertThat(Main.effectiveArgs(new String[] {"projects", "list"}, "qits"))
                .containsExactly("projects", "list");
    }

    @Test
    void anUnreadableInvocationNameLeavesArgsAlone() {
        assertThat(Main.effectiveArgs(new String[] {"login"}, "")).containsExactly("login");
    }
}
