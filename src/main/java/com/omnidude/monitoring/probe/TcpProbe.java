package com.omnidude.monitoring.probe;

import com.omnidude.monitoring.CheckType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Sucesso = a conexão TCP é estabelecida dentro do timeout. O tempo medido é o do handshake. */
@Component
public class TcpProbe implements Probe {

    @Override
    public Set<CheckType> types() {
        return Set.of(CheckType.TCP);
    }

    @Override
    public ProbeResult execute(Target target) {
        if (!AddressValidator.isValidIp(target.address()) || target.port() == null) {
            return ProbeResult.fail("alvo inválido");
        }
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.address(), target.port()), target.timeoutMs());
            return ProbeResult.ok(elapsedMs(start));
        } catch (SocketTimeoutException e) {
            return ProbeResult.fail("timeout");
        } catch (IOException e) {
            return ProbeResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    static int elapsedMs(long startNanos) {
        return (int) Math.max(0, Math.round((System.nanoTime() - startNanos) / 1_000_000.0));
    }
}
