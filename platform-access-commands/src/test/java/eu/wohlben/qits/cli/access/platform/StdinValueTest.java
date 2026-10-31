package eu.wohlben.qits.cli.access.platform;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A secret read on stdin: a terminal and nothing are refused, one line break comes off. */
class StdinValueTest {

    private static final String EXAMPLE = "`... | qits agent-mcp credentials set --key K`";

    private static InputStream of(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aTerminalIsRefusedAndNeverRead() {
        InputStream untouchable = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("a terminal is never read");
            }
        };

        assertThatThrownBy(() -> StdinValue.read(true, untouchable, "the credential", EXAMPLE))
                .isInstanceOfSatisfying(CliFailure.class, f -> {
                    assertThat(f.exitCode()).isEqualTo(CliFailure.USAGE);
                    assertThat(f.getMessage()).contains("Pipe the credential in on stdin").contains(EXAMPLE)
                            .contains("Nothing was sent");
                });
    }

    @Test
    void nothingOrALoneLineBreakIsRefused() {
        for (String nothing : new String[] {"", "\n", "\r\n"}) {
            assertThatThrownBy(() -> StdinValue.read(false, of(nothing), "the credential", EXAMPLE))
                    .isInstanceOfSatisfying(CliFailure.class, f -> {
                        assertThat(f.exitCode()).isEqualTo(CliFailure.USAGE);
                        assertThat(f.getMessage()).contains("Nothing on stdin");
                    });
        }
    }

    @Test
    void exactlyOneTrailingLineBreakComesOff() throws CliFailure {
        assertThat(StdinValue.read(false, of("value\n"), "v", EXAMPLE)).isEqualTo("value");
        assertThat(StdinValue.read(false, of("value\r\n"), "v", EXAMPLE)).isEqualTo("value");
        assertThat(StdinValue.read(false, of("value\n\n"), "v", EXAMPLE)).isEqualTo("value\n");
        assertThat(StdinValue.read(false, of("  value "), "v", EXAMPLE)).isEqualTo("  value ");
        assertThat(StdinValue.read(false, of("line one\nline two"), "v", EXAMPLE)).isEqualTo("line one\nline two");
    }

    @Test
    void aRefusalNeverQuotesTheValue() {
        assertThatThrownBy(() -> StdinValue.read(true, of("s3cret"), "the credential", EXAMPLE))
                .satisfies(f -> assertThat(f.getMessage()).doesNotContain("s3cret"));
    }
}
