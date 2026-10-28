package com.omnidude.discovery;

import java.util.ArrayList;
import java.util.List;

/** Faixa IPv4 em notação CIDR. Hosts = todos os endereços exceto rede e broadcast (em /31 e /32, todos). */
public final class CidrRange {

    /** Blocos que podem ser varridos sem {@code allow-public}: privados, loopback, link-local, CGNAT e de documentação. */
    private static final long[][] ALLOWED = {
            {ip(10, 0, 0, 0), 8}, {ip(172, 16, 0, 0), 12}, {ip(192, 168, 0, 0), 16}, {ip(127, 0, 0, 0), 8},
            {ip(169, 254, 0, 0), 16}, {ip(100, 64, 0, 0), 10},
            {ip(192, 0, 2, 0), 24}, {ip(198, 51, 100, 0), 24}, {ip(203, 0, 113, 0), 24},
    };

    private final long network;
    private final int prefix;

    private CidrRange(long network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    /** @throws IllegalArgumentException com mensagem pronta para o usuário */
    public static CidrRange parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Informe a faixa em CIDR, por exemplo 192.168.1.0/24");
        }
        String t = text.trim();
        int slash = t.indexOf('/');
        String addr = slash < 0 ? t : t.substring(0, slash);
        int prefix;
        try {
            prefix = slash < 0 ? 32 : Integer.parseInt(t.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Prefixo inválido em '" + t + "'");
        }
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("O prefixo deve estar entre 0 e 32");
        }
        String[] parts = addr.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Só IPv4 é suportado (a.b.c.d/n): '" + t + "'");
        }
        long ip = 0;
        for (String p : parts) {
            int o;
            try {
                o = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Endereço inválido: '" + t + "'");
            }
            if (o < 0 || o > 255 || !p.matches("\\d{1,3}")) {
                throw new IllegalArgumentException("Endereço inválido: '" + t + "'");
            }
            ip = (ip << 8) | o;
        }
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return new CidrRange(ip & mask, prefix);
    }

    private static long ip(int a, int b, int c, int d) {
        return ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | d;
    }

    private long first() {
        return prefix >= 31 ? network : network + 1;
    }

    private long last() {
        long broadcast = network + (1L << (32 - prefix)) - 1;
        return prefix >= 31 ? broadcast : broadcast - 1;
    }

    public long hostCount() {
        return last() - first() + 1;
    }

    public String normalized() {
        return str(network) + "/" + prefix;
    }

    public List<String> hosts() {
        List<String> out = new ArrayList<>((int) Math.min(hostCount(), 70_000));
        for (long a = first(); a <= last(); a++) {
            out.add(str(a));
        }
        return out;
    }

    /** A faixa inteira cabe em um dos blocos permitidos? (Uma faixa maior que o bloco, ou que o cruze, não cabe.) */
    public boolean insideAllowedNetworks() {
        long start = network;
        long end = network + (1L << (32 - prefix)) - 1;
        for (long[] block : ALLOWED) {
            long bStart = block[0];
            long bEnd = bStart + (1L << (32 - (int) block[1])) - 1;
            if (start >= bStart && end <= bEnd) {
                return true;
            }
        }
        return false;
    }

    private static String str(long ip) {
        return ((ip >> 24) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 8) & 255) + "." + (ip & 255);
    }

    /** Ordenação numérica de IPv4 em texto (para listar resultados). */
    public static long toLong(String ip) {
        String[] p = ip.split("\\.");
        return p.length == 4 ? ip(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                Integer.parseInt(p[3])) : 0;
    }
}
