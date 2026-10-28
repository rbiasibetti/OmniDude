package com.omnidude.metrics;

/**
 * Converte duas leituras de um contador de octetos em bits por segundo, tratando wrap e reset.
 *
 * <ul>
 *   <li>Contador de 64 bits que diminui: só pode ser reset (reinício do equipamento ou da interface); sem taxa.</li>
 *   <li>Contador de 32 bits que diminui: um wrap, se a taxa resultante for plausível para a velocidade da interface;
 *       caso contrário, reset.</li>
 *   <li>Contador de 32 bits cujo intervalo permitiria dar mais de uma volta na velocidade da interface: ambíguo,
 *       sem taxa (um enlace de 1 Gb/s dá a volta em 34 s).</li>
 *   <li>Taxa acima da velocidade da interface (com folga): glitch ou reset, descartada.</li>
 * </ul>
 */
public final class RateCalculator {

    /** Uma leitura bruta; {@code bits} é a largura do contador (32 ou 64). */
    public record Counters(long tsMs, long inOctets, long outOctets, int bits) {
    }

    public record Rate(Long inBps, Long outBps) {
        public static final Rate NONE = new Rate(null, null);
    }

    private static final long WRAP_32 = 1L << 32;
    private static final double SPEED_SLACK = 1.25;

    private RateCalculator() {
    }

    /** @param speedBps velocidade da interface, ou {@code null} se desconhecida */
    public static Rate compute(Counters prev, Counters cur, Long speedBps) {
        if (prev == null || prev.bits() != cur.bits()) {      // primeira amostra ou troca de 32 para 64 bits
            return Rate.NONE;
        }
        long dtMs = cur.tsMs() - prev.tsMs();
        if (dtMs <= 0) {
            return Rate.NONE;
        }
        return new Rate(bps(prev.inOctets(), cur.inOctets(), dtMs, cur.bits(), speedBps),
                bps(prev.outOctets(), cur.outOctets(), dtMs, cur.bits(), speedBps));
    }

    /** Diferença de um contador de erros/descartes; {@code null} se diminuiu (reset) ou não há leitura anterior. */
    public static Long delta(Long prev, Long cur, int bits) {
        if (prev == null || cur == null) {
            return null;
        }
        long d = cur - prev;
        if (d >= 0) {
            return d;
        }
        return bits == 32 && prev <= 0xFFFFFFFFL ? d + WRAP_32 : null;
    }

    private static Long bps(long prev, long cur, long dtMs, int bits, Long speedBps) {
        boolean knownSpeed = speedBps != null && speedBps > 0;
        long delta = cur - prev;
        if (delta < 0) {
            if (bits != 32) {
                return null;
            }
            delta += WRAP_32;
        }
        if (bits == 32 && knownSpeed && (speedBps / 8.0) * (dtMs / 1000.0) >= WRAP_32) {
            return null;                                       // pode ter dado mais de uma volta: não dá para saber
        }
        double bitsPerSecond = delta * 8.0 * 1000.0 / dtMs;
        if (knownSpeed && bitsPerSecond > speedBps * SPEED_SLACK) {
            return null;
        }
        return Math.round(bitsPerSecond);
    }
}
