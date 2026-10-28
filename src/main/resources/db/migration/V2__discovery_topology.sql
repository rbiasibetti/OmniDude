-- Fase 3 (descoberta e topologia)

-- Hosts já cadastrados que a varredura reencontrou: contados, mas não oferecidos para importação.
ALTER TABLE discovery_jobs ADD COLUMN hosts_known INTEGER NOT NULL DEFAULT 0;

-- Sistema operacional deduzido por fingerprint (descoberta) para o momento da importação.
ALTER TABLE discovery_hosts ADD COLUMN os_info TEXT;

-- Pares que o usuário recusou ao descartar um enlace sugerido por LLDP: não voltam a ser sugeridos.
-- Ordem canônica (source < target), a mesma da tabela links.
CREATE TABLE link_rejections (
  source_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  target_device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  rejected_at      INTEGER NOT NULL,
  PRIMARY KEY (source_device_id, target_device_id),
  CHECK (source_device_id < target_device_id)
) WITHOUT ROWID;
CREATE INDEX ix_link_rejections_target ON link_rejections(target_device_id);
