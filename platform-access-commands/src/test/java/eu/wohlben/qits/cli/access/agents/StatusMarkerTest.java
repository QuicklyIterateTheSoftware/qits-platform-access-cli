package eu.wohlben.qits.cli.access.agents;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The session names' palette, copied: a square per status, the block glyph against it. */
class StatusMarkerTest {

    @Test
    void everyStatusHasItsSquare() {
        assertThat(StatusMarker.of("REPORTED", false, null)).isEqualTo("⬜");
        assertThat(StatusMarker.of("REFINED", false, null)).isEqualTo("🟪");
        assertThat(StatusMarker.of("READY_FOR_DEV", false, null)).isEqualTo("🟪");
        assertThat(StatusMarker.of("IMPLEMENTING", false, null)).isEqualTo("🟦");
        assertThat(StatusMarker.of("IMPLEMENTED", false, null)).isEqualTo("🟦");
        assertThat(StatusMarker.of("VERIFYING", false, null)).isEqualTo("🟦");
        assertThat(StatusMarker.of("VERIFIED", false, null)).isEqualTo("✅");
        assertThat(StatusMarker.of("DONE", false, null)).isEqualTo("🟩");
        assertThat(StatusMarker.of(" dropped ", false, null)).isEqualTo("⬜");
    }

    @Test
    void anUnknownStatusHasNoSquare() {
        assertThat(StatusMarker.of("SHIPPED", false, null)).isEmpty();
        assertThat(StatusMarker.of(null, false, null)).isEmpty();
    }

    @Test
    void aBlockStandsDirectlyInFrontOfTheSquare() {
        assertThat(StatusMarker.of("IMPLEMENTING", true, null)).isEqualTo("❗🟦");
        assertThat(StatusMarker.of("IMPLEMENTING", true, "EXPLICIT")).isEqualTo("❗🟦");
        assertThat(StatusMarker.of("IMPLEMENTING", true, "BOTH")).isEqualTo("❗🟦");
        assertThat(StatusMarker.of("IMPLEMENTING", true, "AGENT_WAITING")).isEqualTo("⁉️🟦");
        assertThat(StatusMarker.of(null, true, null)).isEqualTo("❗");
    }
}
