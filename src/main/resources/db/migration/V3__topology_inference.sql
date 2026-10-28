-- Fase 3.5 (topologia por ARP, FDB, CDP e MNDP; inferência de enlaces)

-- O CHECK de links.origin só aceitava MANUAL e LLDP, e o SQLite não altera um CHECK: a tabela é recriada.
-- Nada referencia links, então o DROP não dispara cascata. Os ids são preservados.
CREATE TABLE links_new (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  source_device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  target_device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  source_interface_id INTEGER REFERENCES interfaces(id) ON DELETE CASCADE,
  target_interface_id INTEGER REFERENCES interfaces(id) ON DELETE CASCADE,
  -- de onde veio a evidência: vizinho anunciado (LLDP, CDP, MNDP), tabela de encaminhamento (FDB),
  -- ou suposição por falta de dados (INFERRED)
  origin              TEXT NOT NULL DEFAULT 'MANUAL'
                      CHECK (origin IN ('MANUAL','LLDP','CDP','MNDP','FDB','INFERRED')),
  state               TEXT NOT NULL DEFAULT 'CONFIRMED' CHECK (state IN ('SUGGESTED','CONFIRMED')),
  label               TEXT,
  evidence            TEXT,                   -- por que o enlace foi proposto (texto para o usuário decidir)
  created_at          INTEGER NOT NULL,
  CHECK (source_device_id < target_device_id)
);
INSERT INTO links_new(id, source_device_id, target_device_id, source_interface_id, target_interface_id, origin, state,
                      label, created_at)
SELECT id, source_device_id, target_device_id, source_interface_id, target_interface_id, origin, state, label, created_at
FROM links;
DROP TABLE links;
ALTER TABLE links_new RENAME TO links;
CREATE UNIQUE INDEX ux_links_pair ON links(
  source_device_id, target_device_id,
  COALESCE(source_interface_id, 0), COALESCE(target_interface_id, 0));
CREATE INDEX ix_links_target ON links(target_device_id);
CREATE INDEX ix_links_src_if ON links(source_interface_id);
CREATE INDEX ix_links_tgt_if ON links(target_interface_id);

-- Nó que representa um switch, hub ou AP sem SNMP deduzido pela topologia. Não tem IP, checagens nem SNMP;
-- primary_ip guarda uma chave estável "unmanaged:<dispositivo>:<ifIndex>" (a coluna é NOT NULL UNIQUE).
ALTER TABLE devices ADD COLUMN virtual INTEGER NOT NULL DEFAULT 0;

-- Identidade por NetBIOS, SSDP/UPnP e mDNS (texto curto) dos hosts da descoberta.
ALTER TABLE discovery_hosts ADD COLUMN hints TEXT;
