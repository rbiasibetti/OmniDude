package com.omnidude.topology;

import com.omnidude.device.Device;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.Status;
import com.omnidude.web.ApiException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publicação de um mapa para acesso público somente leitura. A visão pública é montada aqui, a partir do zero, com um
 * conjunto fixo e pequeno de campos: o que não está na lista (IPs, MACs, interfaces, tráfego, evidências, notas, ids
 * internos) não existe na resposta, então nenhum erro da página pode vazá-lo.
 */
@Service
public class MapPublicationService {

    /** Estado administrativo da publicação. {@code path} é o caminho da URL pública, ou {@code null}. */
    public record Publication(boolean published, String path, boolean showStatus, boolean showNames) {
    }

    public record Update(Boolean published, Boolean showStatus, Boolean showNames, Boolean regenerate) {
    }

    /** Nó público: {@code name} é {@code null} com nomes ocultos; {@code status} é {@code null} sem estado ou sem monitoramento. */
    public record PublicNode(String id, String name, String type, String status, double x, double y, boolean logical) {
    }

    /** Enlace público: só a forma, a natureza e, se permitido, se está com falha. */
    public record PublicLink(String source, String target, String kind, boolean down) {
    }

    public record PublicMap(String name, boolean showStatus, boolean showNames, long generatedAt,
                            List<PublicNode> nodes, List<PublicLink> links) {
    }

    public static final String PATH_PREFIX = "/public/";
    private static final String DEDUCED_NAME = "Switch/hub não gerenciável";

    private final SecureRandom random = new SecureRandom();
    private final NetMapRepository maps;
    private final MapNodeRepository nodes;
    private final DeviceRepository devices;
    private final LinkRepository links;

    public MapPublicationService(NetMapRepository maps, MapNodeRepository nodes, DeviceRepository devices,
                                 LinkRepository links) {
        this.maps = maps;
        this.nodes = nodes;
        this.devices = devices;
        this.links = links;
    }

    @Transactional(readOnly = true)
    public Publication get(long mapId) {
        return of(find(mapId));
    }

    @Transactional
    public Publication update(long mapId, Update req) {
        NetMap m = find(mapId);
        if (req.showStatus() != null) {
            m.setPublicShowStatus(req.showStatus());
        }
        if (req.showNames() != null) {
            m.setPublicShowNames(req.showNames());
        }
        if (Boolean.FALSE.equals(req.published())) {
            m.setPublicToken(null);                                  // revoga: o link antigo deixa de existir
        } else if (Boolean.TRUE.equals(req.published()) && m.getPublicToken() == null
                || Boolean.TRUE.equals(req.regenerate()) && m.getPublicToken() != null) {
            m.setPublicToken(newToken());
        }
        return of(m);
    }

    private NetMap find(long id) {
        return maps.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Mapa não encontrado"));
    }

    private static Publication of(NetMap m) {
        return new Publication(m.getPublicToken() != null,
                m.getPublicToken() == null ? null : PATH_PREFIX + m.getPublicToken(), m.isPublicShowStatus(),
                m.isPublicShowNames());
    }

    /** 256 bits aleatórios em base64url (43 caracteres): não dá para adivinhar nem enumerar. */
    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ------------------------------------------------------------------------------------------------

    /** Visão pública; token desconhecido ou de mapa despublicado é sempre o mesmo 404. */
    @Transactional(readOnly = true)
    public PublicMap publicView(String token) {
        NetMap map = token == null || token.isBlank() ? null : maps.findByPublicToken(token).orElse(null);
        if (map == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Mapa não encontrado");
        }
        boolean showStatus = map.isPublicShowStatus();
        boolean showNames = map.isPublicShowNames();
        List<MapNode> placed = nodes.findByMapId(map.getId());
        Map<Long, Device> byId = devices.findAllById(placed.stream().map(MapNode::getDeviceId).toList()).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity()));

        Map<Long, String> publicIds = new HashMap<>();             // id interno → "n0", "n1"...: o id real não sai daqui
        List<PublicNode> out = new ArrayList<>();
        for (MapNode n : placed) {
            Device d = byId.get(n.getDeviceId());
            if (d == null) {
                continue;
            }
            String id = "n" + out.size();
            publicIds.put(d.getId(), id);
            String name = !showNames ? null
                    : d.isVirtualNode() && d.getOrigin() == com.omnidude.device.Origin.DISCOVERY ? DEDUCED_NAME : d.getName();
            String status = showStatus && !d.isVirtualNode() ? (d.isEnabled() ? d.getStatus() : Status.UNKNOWN).name() : null;
            out.add(new PublicNode(id, name, d.getDeviceType().name(), status, n.getX(), n.getY(), d.isVirtualNode()));
        }
        List<PublicLink> outLinks = new ArrayList<>();
        if (!publicIds.isEmpty()) {
            for (Link l : links.findBetween(publicIds.keySet())) {
                if (l.getState() != LinkState.CONFIRMED) {
                    continue;                                       // sugestões ainda não foram aceitas por ninguém
                }
                Device a = byId.get(l.getSourceDeviceId());
                Device b = byId.get(l.getTargetDeviceId());
                boolean down = showStatus && (a.getStatus() == Status.DOWN || b.getStatus() == Status.DOWN);
                outLinks.add(new PublicLink(publicIds.get(l.getSourceDeviceId()), publicIds.get(l.getTargetDeviceId()),
                        l.getKind(), down));
            }
        }
        return new PublicMap(map.getName(), showStatus, showNames, System.currentTimeMillis() / 1000, out, outLinks);
    }
}
