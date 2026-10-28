package com.omnidude.topology;

import com.omnidude.device.Device;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.DeviceType;
import com.omnidude.device.Status;
import com.omnidude.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapService {

    private static final int GRID_COLUMNS = 6;
    private static final double GRID_DX = 170;
    private static final double GRID_DY = 130;
    private static final double GRID_MARGIN = 90;

    public record MapSummary(Long id, String name, boolean published) {
    }

    public record NodeView(Long deviceId, String name, String ip, DeviceType type, Status status, boolean enabled,
                           double x, double y, boolean pinned, String icon, boolean virtualNode, boolean deduced) {
    }

    /** Os enlaces do mapa são os que ligam dois dispositivos presentes nele. */
    public record MapView(Long id, String name, String backgroundImage, List<NodeView> nodes,
                          List<LinkService.LinkView> links) {
    }

    private final NetMapRepository maps;
    private final MapNodeRepository nodes;
    private final DeviceRepository devices;
    private final LinkService linkService;

    public MapService(NetMapRepository maps, MapNodeRepository nodes, DeviceRepository devices, LinkService linkService) {
        this.maps = maps;
        this.nodes = nodes;
        this.devices = devices;
        this.linkService = linkService;
    }

    @Transactional(readOnly = true)
    public List<MapSummary> list() {
        return maps.findAll().stream().map(m -> new MapSummary(m.getId(), m.getName(), m.getPublicToken() != null)).toList();
    }

    @Transactional
    public MapSummary create(String name) {
        if (maps.existsByName(name)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um mapa com esse nome");
        }
        NetMap m = new NetMap();
        m.setName(name);
        m.setCreatedAt(Instant.now().getEpochSecond());
        m = maps.save(m);
        return new MapSummary(m.getId(), m.getName(), m.getPublicToken() != null);
    }

    @Transactional(readOnly = true)
    public MapView get(long id) {
        NetMap map = maps.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Mapa não encontrado"));
        List<MapNode> list = nodes.findByMapId(id);
        Map<Long, Device> byId = devices.findAllById(list.stream().map(MapNode::getDeviceId).toList()).stream()
                .collect(Collectors.toMap(Device::getId, Function.identity()));
        List<NodeView> views = list.stream().filter(n -> byId.containsKey(n.getDeviceId())).map(n -> {
            Device d = byId.get(n.getDeviceId());
            return new NodeView(d.getId(), d.getName(), d.getPrimaryIp(), d.getDeviceType(), d.getStatus(),
                    d.isEnabled(), n.getX(), n.getY(), n.isPinned(), n.getIcon(), d.isVirtualNode(),
                    d.isVirtualNode() && d.getOrigin() == com.omnidude.device.Origin.DISCOVERY);
        }).toList();
        return new MapView(map.getId(), map.getName(), map.getBackgroundImage(), views,
                linkService.between(views.stream().map(NodeView::deviceId).toList()));
    }

    @Transactional
    public void placeNode(long mapId, long deviceId, Double x, Double y) {
        if (!maps.existsById(mapId) || !devices.existsById(deviceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Mapa ou dispositivo não encontrado");
        }
        MapNode node = nodes.findById(new MapNodeId(mapId, deviceId)).orElseGet(() -> {
            MapNode n = new MapNode();
            n.setMapId(mapId);
            n.setDeviceId(deviceId);
            return n;
        });
        if (x != null && y != null) {
            node.setX(x);
            node.setY(y);
        } else if (nodes.findById(new MapNodeId(mapId, deviceId)).isEmpty()) {
            long n = nodes.countByMapId(mapId);
            node.setX(GRID_MARGIN + (n % GRID_COLUMNS) * GRID_DX);
            node.setY(GRID_MARGIN + (n / GRID_COLUMNS) * GRID_DY);
        }
        nodes.save(node);
    }

    @Transactional
    public void placeInDefaultMap(long deviceId) {
        maps.findFirstByOrderByIdAsc().ifPresent(m -> placeNode(m.getId(), deviceId, null, null));
    }

    @Transactional
    public void moveNode(long mapId, long deviceId, double x, double y, Boolean pinned) {
        MapNode node = nodes.findById(new MapNodeId(mapId, deviceId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Nó não encontrado no mapa"));
        node.setX(x);
        node.setY(y);
        if (pinned != null) {
            node.setPinned(pinned);
        }
    }

    @Transactional
    public void removeNode(long mapId, long deviceId) {
        nodes.deleteByMapIdAndDeviceId(mapId, deviceId);
    }
}
