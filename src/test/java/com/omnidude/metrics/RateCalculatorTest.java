package com.omnidude.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.metrics.RateCalculator.Counters;
import com.omnidude.metrics.RateCalculator.Rate;
import org.junit.jupiter.api.Test;

class RateCalculatorTest {

    private static final long GIGA = 1_000_000_000L;

    private static Counters c64(long tsMs, long in, long out) {
        return new Counters(tsMs, in, out, 64);
    }

    private static Counters c32(long tsMs, long in, long out) {
        return new Counters(tsMs, in, out, 32);
    }

    @Test
    void firstSampleHasNoRate() {
        assertThat(RateCalculator.compute(null, c64(1000, 10, 10), GIGA)).isEqualTo(Rate.NONE);
    }

    @Test
    void computesBitsPerSecond() {
        // 7.500.000 octetos em 60 s = 1 Mb/s; 750.000 octetos = 100 kb/s
        Rate r = RateCalculator.compute(c64(0, 1_000, 5_000), c64(60_000, 7_501_000, 755_000), GIGA);
        assertThat(r.inBps()).isEqualTo(1_000_000L);
        assertThat(r.outBps()).isEqualTo(100_000L);
    }

    @Test
    void usesRealElapsedTimeNotTheNominalInterval() {
        Rate r = RateCalculator.compute(c64(0, 0, 0), c64(61_500, 7_687_500, 0), GIGA);   // 61,5 s
        assertThat(r.inBps()).isEqualTo(1_000_000L);
    }

    @Test
    void idleInterfaceIsZeroNotMissing() {
        Rate r = RateCalculator.compute(c64(0, 500, 500), c64(60_000, 500, 500), GIGA);
        assertThat(r.inBps()).isZero();
        assertThat(r.outBps()).isZero();
    }

    @Test
    void counter64GoingBackwardsIsAReset() {
        Rate r = RateCalculator.compute(c64(0, 9_000_000, 9_000_000), c64(60_000, 1_000, 1_000), GIGA);
        assertThat(r).isEqualTo(Rate.NONE);
    }

    @Test
    void counter32WrapIsRecoveredWhenThePlausibleRateFits() {
        // 100 Mb/s: a volta de 32 bits leva ~343 s; em 10 s o contador cruza 2^32 uma vez
        long prev = (1L << 32) - 62_500_000;                      // 62,5 MB antes de dar a volta
        long cur = 62_500_000;                                      // +125 MB no total em 10 s = 100 Mb/s
        Rate r = RateCalculator.compute(c32(0, prev, 0), c32(10_000, cur, 0), 100_000_000L);
        assertThat(r.inBps()).isEqualTo(100_000_000L);
        assertThat(r.outBps()).isZero();
    }

    @Test
    void counter32DropThatImpliesImpossibleRateIsAReset() {
        // queda grande para um enlace de 10 Mb/s: se fosse volta, a taxa seria absurda
        Rate r = RateCalculator.compute(c32(0, 3_000_000_000L, 0), c32(10_000, 1_000, 0), 10_000_000L);
        assertThat(r.inBps()).isNull();
    }

    @Test
    void counter32IsAmbiguousWhenItCouldHaveWrappedMoreThanOnce() {
        // 1 Gb/s dá a volta de 32 bits em ~34 s: com 60 s entre leituras não dá para saber
        Rate r = RateCalculator.compute(c32(0, 100, 100), c32(60_000, 200, 200), GIGA);
        assertThat(r).isEqualTo(Rate.NONE);
        // o mesmo intervalo em 100 Mb/s (volta em ~343 s) é confiável
        Rate slow = RateCalculator.compute(c32(0, 0, 0), c32(60_000, 750_000, 0), 100_000_000L);
        assertThat(slow.inBps()).isEqualTo(100_000L);
    }

    @Test
    void rateAboveInterfaceSpeedIsDiscarded() {
        Rate r = RateCalculator.compute(c64(0, 0, 0), c64(1_000, 1_000_000_000L, 0), 100_000_000L);   // 8 Gb/s em 100 Mb
        assertThat(r.inBps()).isNull();
        Rate ok = RateCalculator.compute(c64(0, 0, 0), c64(1_000, 12_000_000L, 0), 100_000_000L);     // 96 Mb/s
        assertThat(ok.inBps()).isEqualTo(96_000_000L);
    }

    @Test
    void unknownSpeedAcceptsAnyRate() {
        Rate r = RateCalculator.compute(c64(0, 0, 0), c64(1_000, 5_000_000_000L, 0), null);
        assertThat(r.inBps()).isEqualTo(40_000_000_000L);
    }

    @Test
    void changeOfCounterWidthRestartsTheSeries() {
        assertThat(RateCalculator.compute(c32(0, 100, 100), c64(60_000, 200, 200), 100_000_000L)).isEqualTo(Rate.NONE);
    }

    @Test
    void nonPositiveElapsedTimeIsIgnored() {
        assertThat(RateCalculator.compute(c64(5_000, 1, 1), c64(5_000, 9, 9), GIGA)).isEqualTo(Rate.NONE);
        assertThat(RateCalculator.compute(c64(5_000, 1, 1), c64(4_000, 9, 9), GIGA)).isEqualTo(Rate.NONE);
    }

    @Test
    void errorDeltas() {
        assertThat(RateCalculator.delta(10L, 25L, 32)).isEqualTo(15);
        assertThat(RateCalculator.delta(null, 25L, 32)).isNull();
        assertThat(RateCalculator.delta(25L, null, 32)).isNull();
        assertThat(RateCalculator.delta((1L << 32) - 5, 10L, 32)).as("volta de 32 bits").isEqualTo(15);
        assertThat(RateCalculator.delta(100L, 3L, 64)).as("reset de 64 bits").isNull();
    }
}
