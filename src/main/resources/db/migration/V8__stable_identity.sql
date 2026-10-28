-- Identidade estável com DHCP: o IP deixa de ser a identidade e passa a ser um atributo que muda.

-- MACs conhecidos de cada dispositivo (um dispositivo pode ter vários: Wi-Fi e cabo, várias interfaces).
-- random = MAC "administrado localmente" (celular com endereço privado, VM, bridge): não serve de identidade automática.
CREATE TABLE device_macs (
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  mac          TEXT NOT NULL,
  source       TEXT NOT NULL CHECK (source IN ('MANUAL','DEVICE','ARP','IFACE','DISCOVERY')),
  random       INTEGER NOT NULL DEFAULT 0,
  last_seen_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, mac)
) WITHOUT ROWID;
CREATE INDEX ix_device_macs_mac ON device_macs(mac);

-- Quem tinha qual IP e quando mudou: explica por que o dispositivo "mudou de endereço" e permite conferir.
CREATE TABLE device_ip_history (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id  INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  old_ip     TEXT NOT NULL,
  new_ip     TEXT NOT NULL,
  changed_at INTEGER NOT NULL,
  reason     TEXT NOT NULL CHECK (reason IN ('AUTO','DISCOVERY','MANUAL'))
);
CREATE INDEX ix_device_ip_history_device ON device_ip_history(device_id, changed_at DESC);

-- Host da descoberta que parece ser um dispositivo já cadastrado com outro IP.
ALTER TABLE discovery_hosts ADD COLUMN matched_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL;
ALTER TABLE discovery_hosts ADD COLUMN matched_by TEXT;     -- MAC, MAC_RANDOM ou HOSTNAME

-- Os MACs que os dispositivos já têm passam a valer como identidade. Bit "administrado localmente" = segundo dígito
-- hexadecimal do primeiro octeto em {2,3,6,7,a,b,e,f}.
INSERT OR IGNORE INTO device_macs(device_id, mac, source, random, last_seen_at)
SELECT id, lower(mac_address), 'DEVICE',
       CASE WHEN substr(lower(mac_address), 2, 1) IN ('2','3','6','7','a','b','e','f') THEN 1 ELSE 0 END, updated_at
FROM devices WHERE mac_address IS NOT NULL AND length(mac_address) = 17 AND virtual = 0;
