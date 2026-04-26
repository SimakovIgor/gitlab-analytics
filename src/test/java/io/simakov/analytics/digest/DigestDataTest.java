package io.simakov.analytics.digest;

import io.simakov.analytics.digest.DigestData.ServiceRow;
import io.simakov.analytics.digest.DigestData.TeamSection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DigestDataTest {

    // ── pctChange ─────────────────────────────────────────────────────────────

    @Test
    void pctChangeReturnsNullWhenPrevIsZero() {
        assertThat(DigestData.pctChange(10, 0)).isNull();
    }

    @Test
    void pctChangePositiveGrowth() {
        assertThat(DigestData.pctChange(12, 10)).isEqualTo(20);
    }

    @Test
    void pctChangeNegativeGrowth() {
        assertThat(DigestData.pctChange(8, 10)).isEqualTo(-20);
    }

    @Test
    void pctChangeZeroWhenEqual() {
        assertThat(DigestData.pctChange(10, 10)).isEqualTo(0);
    }

    // ── pctChangeDouble ────────────────────────────────────────────────────────

    @Test
    void pctChangeDoubleReturnsNullWhenPrevIsNull() {
        assertThat(DigestData.pctChangeDouble(10.0, null)).isNull();
    }

    @Test
    void pctChangeDoubleReturnsNullWhenPrevIsZero() {
        assertThat(DigestData.pctChangeDouble(10.0, 0.0)).isNull();
    }

    @Test
    void pctChangeDoubleReturnsNullWhenCurrentIsNull() {
        assertThat(DigestData.pctChangeDouble(null, 10.0)).isNull();
    }

    @Test
    void pctChangeDoublePositiveGrowth() {
        assertThat(DigestData.pctChangeDouble(15.0, 10.0)).isEqualTo(50);
    }

    // ── TeamSection.colorHex ──────────────────────────────────────────────────

    @Test
    void colorHexReturnsDistinctColorsForKnownIndices() {
        assertThat(teamSection(1).colorHex()).isEqualTo("#5046cf");
        assertThat(teamSection(2).colorHex()).isEqualTo("#3d9e6c");
        assertThat(teamSection(3).colorHex()).isEqualTo("#e67e22");
        assertThat(teamSection(4).colorHex()).isEqualTo("#c0392b");
        assertThat(teamSection(5).colorHex()).isEqualTo("#2980b9");
        assertThat(teamSection(6).colorHex()).isEqualTo("#8e44ad");
    }

    @Test
    void colorHexReturnsFallbackForUnknownIndex() {
        assertThat(teamSection(0).colorHex()).isEqualTo("#8a8573");
        assertThat(teamSection(99).colorHex()).isEqualTo("#8a8573");
    }

    private TeamSection teamSection(int colorIndex) {
        return new TeamSection("Team", colorIndex, 0, 0, null, null, java.util.List.of());
    }

    // ── ServiceRow.deploysColor ───────────────────────────────────────────────

    @Test
    void deploysColorGreenWhenThreeOrMore() {
        assertThat(new ServiceRow("svc", 3.0, null, null).deploysColor()).isEqualTo("#3d9e6c");
        assertThat(new ServiceRow("svc", 5.5, null, null).deploysColor()).isEqualTo("#3d9e6c");
    }

    @Test
    void deploysColorOrangeWhenOneToThree() {
        assertThat(new ServiceRow("svc", 1.0, null, null).deploysColor()).isEqualTo("#e67e22");
        assertThat(new ServiceRow("svc", 2.9, null, null).deploysColor()).isEqualTo("#e67e22");
    }

    @Test
    void deploysColorGrayWhenBelowOne() {
        assertThat(new ServiceRow("svc", 0.0, null, null).deploysColor()).isEqualTo("#d1cec5");
        assertThat(new ServiceRow("svc", 0.5, null, null).deploysColor()).isEqualTo("#d1cec5");
    }

    // ── ServiceRow.cfrColor ────────────────────────────────────────────────────

    @Test
    void cfrColorGrayWhenNull() {
        assertThat(new ServiceRow("svc", 1.0, null, null).cfrColor()).isEqualTo("#b5b09c");
    }

    @Test
    void cfrColorGreenWhenZero() {
        assertThat(new ServiceRow("svc", 1.0, null, 0.0).cfrColor()).isEqualTo("#3d9e6c");
    }

    @Test
    void cfrColorOrangeWhenUpTo15() {
        assertThat(new ServiceRow("svc", 1.0, null, 15.0).cfrColor()).isEqualTo("#e67e22");
        assertThat(new ServiceRow("svc", 1.0, null, 5.0).cfrColor()).isEqualTo("#e67e22");
    }

    @Test
    void cfrColorRedWhenAbove15() {
        assertThat(new ServiceRow("svc", 1.0, null, 16.0).cfrColor()).isEqualTo("#c0392b");
        assertThat(new ServiceRow("svc", 1.0, null, 50.0).cfrColor()).isEqualTo("#c0392b");
    }
}
