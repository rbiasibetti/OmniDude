package com.omnidude.topology;

import com.omnidude.device.NetInterface;
import com.omnidude.device.NetInterfaceRepository;
import com.omnidude.device.DeviceRepository;
import com.omnidude.traffic.TrafficService;
import com.omnidude.web.ApiException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {

    public record LinkRequest(@NotNull Long sourceDeviceId, @NotNull Long targetDeviceId, Long sourceInterfaceId,
                              Long targetInterfaceId, @Size(max = 100) String label,
                              @jakarta.validation.constraints.Pattern(regexp = "CABLE|WIFI|VPN|INTERNET|OTHER",
                                      message = "tipo deve ser CABLE, WIFI, VPN, INTERNET ou OTHER") String kind) {
    }

    public record LinkView(Long id, Long sourceDeviceId, Long targetDeviceId, Long sourceInterfaceId,
                           Long targetInterfaceId, String sourceInterface, String targetInterface, LinkOrigin origin,
                           LinkState state, String label, String kind, String evidence, TrafficService.LinkTraffic traffic) {
    }

    /** Resultado de propor um enlace (LLDP). */
    public enum Outcome { CREATED, UPDATED, EXISTS, REJECTED }

    private final LinkRepository links;
    private final DeviceRepository devices;
    private final NetInterfaceRepository interfaces;
    private final JdbcTemplate jdbc;
    private final TrafficService traffic;

    public LinkService(LinkRepository links, DeviceRepository devices, NetInterfaceRepository interfaces, JdbcTemplate jdbc,
                       TrafficService traffic) {
        this.links = links;
        this.devices = devices;
        this.interfaces = interfaces;
        this.jdbc = jdbc;
        this.traffic = traffic;
    }

    /** Enlaces que têm o dispositivo em alguma ponta (usado para empurrar a taxa ao mapa após cada coleta). */
    @Transactional(readOnly = true)
    public List<LinkView> ofDevice(long deviceId) {
        return views(links.findByDevice(deviceId));
    }

    @Transactional(readOnly = true)
    public List<LinkView> all() {
        return views(links.findAll());
    }

    @Transactional(readOnly = true)
    public List<LinkView> between(Collection<Long> deviceIds) {
        return deviceIds.isEmpty() ? List.of() : views(links.findBetween(deviceIds));
    }

    @Transactional
    public LinkView create(LinkRequest req) {
        Canonical c = canonical(req.sourceDeviceId(), req.sourceInterfaceId(), req.targetDeviceId(), req.targetInterfaceId());
        if (!links.findBySourceDeviceIdAndTargetDeviceId(c.a, c.b).stream().noneMatch(l -> compatible(l, c))) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um enlace entre esses dispositivos (nessas interfaces)");
        }
        Link l = new Link();
        l.setSourceDeviceId(c.a);
        l.setTargetDeviceId(c.b);
        l.setSourceInterfaceId(c.ifA);
        l.setTargetInterfaceId(c.ifB);
        l.setLabel(blankToNull(req.label()));
        l.setKind(blankToNull(req.kind()));
        l.setOrigin(LinkOrigin.MANUAL);
        l.setState(LinkState.CONFIRMED);
        l.setCreatedAt(Instant.now().getEpochSecond());
        jdbc.update("DELETE FROM link_rejections WHERE source_device_id = ? AND target_device_id = ?", c.a, c.b);
        return views(List.of(links.save(l))).get(0);
    }

    /** Reassocia as interfaces e/ou muda o rótulo; os dispositivos de um enlace não mudam (crie outro). */
    @Transactional
    public LinkView update(long id, LinkRequest req) {
        Link l = find(id);
        Canonical c = canonical(req.sourceDeviceId(), req.sourceInterfaceId(), req.targetDeviceId(), req.targetInterfaceId());
        if (!c.a.equals(l.getSourceDeviceId()) || !c.b.equals(l.getTargetDeviceId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Os dispositivos de um enlace não podem ser trocados");
        }
        boolean clash = links.findBySourceDeviceIdAndTargetDeviceId(c.a, c.b).stream()
                .anyMatch(o -> !o.getId().equals(id) && Objects.equals(o.getSourceInterfaceId(), c.ifA)
                        && Objects.equals(o.getTargetInterfaceId(), c.ifB));
        if (clash) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um enlace nessas interfaces");
        }
        l.setSourceInterfaceId(c.ifA);
        l.setTargetInterfaceId(c.ifB);
        l.setLabel(blankToNull(req.label()));
        l.setKind(blankToNull(req.kind()));
        return views(List.of(l)).get(0);
    }

    @Transactional
    public LinkView confirm(long id) {
        Link l = find(id);
        l.setState(LinkState.CONFIRMED);
        return views(List.of(l)).get(0);
    }

    /** Descartar uma sugestão a rejeita (não volta no próximo LLDP); apagar um enlace confirmado não. */
    @Transactional
    public void delete(long id) {
        Link l = find(id);
        if (l.getState() == LinkState.SUGGESTED) {
            jdbc.update("INSERT OR IGNORE INTO link_rejections(source_device_id, target_device_id, rejected_at) VALUES (?,?,?)",
                    l.getSourceDeviceId(), l.getTargetDeviceId(), Instant.now().getEpochSecond());
        }
        links.delete(l);
    }

    /** Resultado de uma proposta: o que aconteceu e qual enlace ficou (para conciliar com o que não foi reencontrado). */
    public record Proposed(Outcome outcome, Long linkId) {
    }

    /** Propõe um enlace vindo do LLDP (compatível com chamadas antigas). */
    @Transactional
    public Outcome suggest(long deviceA, Long ifA, long deviceB, Long ifB) {
        return propose(deviceA, ifA, deviceB, ifB, LinkOrigin.LLDP, null).outcome();
    }

    /** Força da evidência: uma proposta mais forte substitui a origem de uma sugestão mais fraca. */
    private static int rank(LinkOrigin o) {
        return switch (o) {
            case MANUAL -> 4;
            case LLDP, CDP, MNDP -> 3;
            case FDB -> 2;
            case INFERRED -> 1;
        };
    }

    /**
     * Propõe um enlace descoberto. Idempotente: o mesmo enlace visto dos dois lados, ou revisto depois, não duplica;
     * interfaces que faltavam são preenchidas; uma sugestão fraca (INFERRED) é promovida por evidência mais forte;
     * pares rejeitados pelo usuário não voltam. Enlaces já confirmados nunca mudam de origem.
     */
    @Transactional
    public Proposed propose(long deviceA, Long ifA, long deviceB, Long ifB, LinkOrigin origin, String evidence) {
        Canonical c = canonical(deviceA, ifA, deviceB, ifB);
        Integer rejected = jdbc.queryForObject(
                "SELECT count(*) FROM link_rejections WHERE source_device_id = ? AND target_device_id = ?",
                Integer.class, c.a, c.b);
        if (rejected != null && rejected > 0) {
            return new Proposed(Outcome.REJECTED, null);
        }
        for (Link l : links.findBySourceDeviceIdAndTargetDeviceId(c.a, c.b)) {
            if (compatible(l, c)) {
                boolean changed = false;
                if (l.getSourceInterfaceId() == null && c.ifA != null) {
                    l.setSourceInterfaceId(c.ifA);
                    changed = true;
                }
                if (l.getTargetInterfaceId() == null && c.ifB != null) {
                    l.setTargetInterfaceId(c.ifB);
                    changed = true;
                }
                if (l.getState() == LinkState.SUGGESTED && rank(origin) > rank(l.getOrigin())) {
                    l.setOrigin(origin);
                    l.setEvidence(evidence);
                    changed = true;
                } else if (l.getState() == LinkState.SUGGESTED && origin == l.getOrigin() && evidence != null
                        && !evidence.equals(l.getEvidence())) {
                    l.setEvidence(evidence);                  // a evidência (porta, MACs) pode ter mudado
                }
                return new Proposed(changed ? Outcome.UPDATED : Outcome.EXISTS, l.getId());
            }
        }
        Link l = new Link();
        l.setSourceDeviceId(c.a);
        l.setTargetDeviceId(c.b);
        l.setSourceInterfaceId(c.ifA);
        l.setTargetInterfaceId(c.ifB);
        l.setOrigin(origin);
        l.setEvidence(evidence);
        l.setState(LinkState.SUGGESTED);
        l.setCreatedAt(Instant.now().getEpochSecond());
        return new Proposed(Outcome.CREATED, links.save(l).getId());
    }

    /**
     * Remove sugestões automáticas das origens dadas que a última rodada não reencontrou (o host mudou de porta, o
     * equipamento ganhou LLDP...). Não conta como rejeição e nunca toca em enlaces confirmados. Devolve quantas saíram.
     */
    @Transactional
    public int removeStaleSuggestions(java.util.Set<Long> keep, java.util.Set<LinkOrigin> origins) {
        List<Link> stale = links.findAll().stream().filter(l -> l.getState() == LinkState.SUGGESTED
                && origins.contains(l.getOrigin()) && !keep.contains(l.getId())).toList();
        links.deleteAll(stale);
        return stale.size();
    }

    // ------------------------------------------------------------------------------------------------

    /** Ordem canônica (source &lt; target) e validação: dispositivos existem e as interfaces pertencem a eles. */
    private Canonical canonical(Long devA, Long ifA, Long devB, Long ifB) {
        if (devA.equals(devB)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Um enlace precisa ligar dois dispositivos diferentes");
        }
        if (!devices.existsById(devA) || !devices.existsById(devB)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Dispositivo não encontrado");
        }
        checkOwner(ifA, devA);
        checkOwner(ifB, devB);
        return devA < devB ? new Canonical(devA, ifA, devB, ifB) : new Canonical(devB, ifB, devA, ifA);
    }

    private void checkOwner(Long interfaceId, Long deviceId) {
        if (interfaceId != null && interfaces.findById(interfaceId).map(i -> !i.getDeviceId().equals(deviceId)).orElse(true)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A interface " + interfaceId + " não pertence ao dispositivo " + deviceId);
        }
    }

    /** Dois enlaces entre o mesmo par são "o mesmo" se cada interface é igual ou desconhecida (nula) em um dos lados. */
    private static boolean compatible(Link l, Canonical c) {
        return (l.getSourceInterfaceId() == null || c.ifA == null || l.getSourceInterfaceId().equals(c.ifA))
                && (l.getTargetInterfaceId() == null || c.ifB == null || l.getTargetInterfaceId().equals(c.ifB));
    }

    private record Canonical(Long a, Long ifA, Long b, Long ifB) {
    }

    private Link find(long id) {
        return links.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Enlace não encontrado"));
    }

    private List<LinkView> views(List<Link> list) {
        List<Long> ifIds = list.stream().flatMap(l -> java.util.stream.Stream.of(l.getSourceInterfaceId(), l.getTargetInterfaceId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, NetInterface> byId = interfaces.findAllById(ifIds).stream()
                .collect(Collectors.toMap(NetInterface::getId, Function.identity(), (x, y) -> x));
        return list.stream().map(l -> new LinkView(l.getId(), l.getSourceDeviceId(), l.getTargetDeviceId(),
                l.getSourceInterfaceId(), l.getTargetInterfaceId(), ifName(byId, l.getSourceInterfaceId()),
                ifName(byId, l.getTargetInterfaceId()), l.getOrigin(), l.getState(), l.getLabel(), l.getKind(), l.getEvidence(),
                l.getState() == LinkState.SUGGESTED ? null : traffic.linkTraffic(l, byId))).toList();
    }

    private static String ifName(Map<Long, NetInterface> byId, Long id) {
        NetInterface i = id == null ? null : byId.get(id);
        return i == null ? null : i.getName();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
