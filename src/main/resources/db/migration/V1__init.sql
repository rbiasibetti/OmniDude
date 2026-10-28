-- Configurações chave/valor (intervalos padrão, retenção, limites de descoberta etc.)
CREATE TABLE settings (
  setting_key   TEXT PRIMARY KEY,
  setting_value TEXT NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL CHECK (role IN ('ADMIN','VIEWER')),
  enabled       INTEGER NOT NULL DEFAULT 1,
  must_change_password INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL
);

-- Perfis SNMP reutilizáveis; os segredos ficam criptografados
CREATE TABLE snmp_profiles (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  name              TEXT NOT NULL UNIQUE,
  version           TEXT NOT NULL CHECK (version IN ('1','2c','3')),
  port              INTEGER NOT NULL DEFAULT 161,
  timeout_ms        INTEGER NOT NULL DEFAULT 2000,
  retries           INTEGER NOT NULL DEFAULT 1,
  community_enc     TEXT,                     -- v1/v2c
  v3_username       TEXT,                     -- v3
  v3_security_level TEXT CHECK (v3_security_level IN ('NO_AUTH_NO_PRIV','AUTH_NO_PRIV','AUTH_PRIV')),
  v3_auth_protocol  TEXT CHECK (v3_auth_protocol IN ('SHA','SHA256','SHA512')),
  v3_auth_pass_enc  TEXT,
  v3_priv_protocol  TEXT CHECK (v3_priv_protocol IN ('AES128','AES192','AES256')),
  v3_priv_pass_enc  TEXT
);

-- Dispositivos (inventário). Posição no mapa NÃO fica aqui: vide map_nodes.
CREATE TABLE devices (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  name            TEXT NOT NULL,
  hostname        TEXT,
  primary_ip      TEXT NOT NULL UNIQUE,
  mac_address     TEXT,
  device_type     TEXT NOT NULL DEFAULT 'OTHER'
                  CHECK (device_type IN ('ROUTER','SWITCH','FIREWALL','ACCESS_POINT','SERVER',
                                         'WORKSTATION','PRINTER','STORAGE','OTHER')),
  os_info         TEXT,
  sys_descr       TEXT,
  sys_object_id   TEXT,
  last_boot_at    INTEGER,                    -- derivado de sysUpTime; detecta reboot (reset de contadores)
  status          TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('UNKNOWN','UP','WARNING','DOWN')),
  status_since    INTEGER,
  snmp_profile_id INTEGER REFERENCES snmp_profiles(id) ON DELETE SET NULL,
  enabled         INTEGER NOT NULL DEFAULT 1, -- 0 = manutenção, sem checagens nem alertas
  origin          TEXT NOT NULL DEFAULT 'MANUAL' CHECK (origin IN ('MANUAL','DISCOVERY')),
  notes           TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);
CREATE INDEX ix_devices_status ON devices(status);
CREATE INDEX ix_devices_snmp_profile ON devices(snmp_profile_id);
CREATE INDEX ix_devices_mac ON devices(mac_address);

-- IPs adicionais de equipamentos multi-homed (o IP principal fica em devices.primary_ip)
CREATE TABLE device_addresses (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id  INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  ip_address TEXT NOT NULL UNIQUE
);
CREATE INDEX ix_device_addresses_device ON device_addresses(device_id);

-- Interfaces (IF-MIB). A velocidade vive aqui, não no enlace.
CREATE TABLE interfaces (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  if_index     INTEGER NOT NULL,
  name         TEXT NOT NULL,                 -- ifName
  descr        TEXT,                          -- ifDescr
  alias        TEXT,                          -- ifAlias
  mac_address  TEXT,                          -- ifPhysAddress
  speed_bps    INTEGER,                       -- ifHighSpeed * 1e6
  admin_status INTEGER,                       -- 1=up 2=down 3=testing
  oper_status  INTEGER,
  monitored    INTEGER NOT NULL DEFAULT 0,    -- coleta de tráfego ligada?
  last_seen_at INTEGER,
  UNIQUE (device_id, if_index)
);

-- Mapas lógicos (o antigo "geográfico/lógico" passa a ser só lógico, com imagem de fundo opcional)
CREATE TABLE maps (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  name             TEXT NOT NULL UNIQUE,
  background_image TEXT,
  created_at       INTEGER NOT NULL
);

-- Posição de cada dispositivo em cada mapa
CREATE TABLE map_nodes (
  map_id    INTEGER NOT NULL REFERENCES maps(id) ON DELETE CASCADE,
  device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  x         REAL NOT NULL DEFAULT 0.0,
  y         REAL NOT NULL DEFAULT 0.0,
  pinned    INTEGER NOT NULL DEFAULT 0,
  icon      TEXT,                              -- NULL = ícone do device_type
  PRIMARY KEY (map_id, device_id)
) WITHOUT ROWID;
CREATE INDEX ix_map_nodes_device ON map_nodes(device_id);

-- Enlaces. Status e utilização são derivados das interfaces (não armazenados).
-- Ordem canônica source < target evita duplicata A-B / B-A; a aplicação normaliza antes de gravar.
CREATE TABLE links (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  source_device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  target_device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  source_interface_id INTEGER REFERENCES interfaces(id) ON DELETE CASCADE,
  target_interface_id INTEGER REFERENCES interfaces(id) ON DELETE CASCADE,
  origin              TEXT NOT NULL DEFAULT 'MANUAL' CHECK (origin IN ('MANUAL','LLDP')),
  state               TEXT NOT NULL DEFAULT 'CONFIRMED' CHECK (state IN ('SUGGESTED','CONFIRMED')),
  label               TEXT,
  created_at          INTEGER NOT NULL,
  CHECK (source_device_id < target_device_id)
);
CREATE UNIQUE INDEX ux_links_pair ON links(
  source_device_id, target_device_id,
  COALESCE(source_interface_id, 0), COALESCE(target_interface_id, 0));
CREATE INDEX ix_links_target ON links(target_device_id);
CREATE INDEX ix_links_src_if ON links(source_interface_id);
CREATE INDEX ix_links_tgt_if ON links(target_interface_id);

-- Checagens configuradas por dispositivo (substitui monitored_services; agora guarda a configuração)
CREATE TABLE service_checks (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id            INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  type                 TEXT NOT NULL CHECK (type IN ('ICMP','TCP','HTTP','HTTPS','SNMP','DNS')),
  name                 TEXT NOT NULL,
  port                 INTEGER,               -- NULL para ICMP
  params               TEXT,                  -- JSON: path HTTP, status esperado, nome DNS etc.
  interval_s           INTEGER NOT NULL DEFAULT 30,
  timeout_ms           INTEGER NOT NULL DEFAULT 2000,
  warn_latency_ms      INTEGER,               -- acima disso o serviço fica em WARNING
  fail_threshold       INTEGER NOT NULL DEFAULT 3,   -- falhas seguidas para virar DOWN
  recover_threshold    INTEGER NOT NULL DEFAULT 1,   -- sucessos seguidos para voltar a UP
  enabled              INTEGER NOT NULL DEFAULT 1,
  -- estado corrente
  status               TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('UNKNOWN','UP','WARNING','DOWN')),
  status_since         INTEGER,
  consecutive_failures INTEGER NOT NULL DEFAULT 0,
  consecutive_successes INTEGER NOT NULL DEFAULT 0,
  last_check_at        INTEGER,
  last_response_ms     INTEGER,
  UNIQUE (device_id, type, port)
);

-- Resultado bruto de cada checagem (retenção curta)
CREATE TABLE check_results (
  check_id    INTEGER NOT NULL REFERENCES service_checks(id) ON DELETE CASCADE,
  ts          INTEGER NOT NULL,
  success     INTEGER NOT NULL,
  response_ms INTEGER,
  PRIMARY KEY (check_id, ts)
) WITHOUT ROWID;

CREATE TABLE check_results_hourly (
  check_id   INTEGER NOT NULL REFERENCES service_checks(id) ON DELETE CASCADE,
  hour_ts    INTEGER NOT NULL,                -- início da hora (epoch s)
  ok_count   INTEGER NOT NULL,
  fail_count INTEGER NOT NULL,
  avg_ms     REAL,
  max_ms     INTEGER,
  PRIMARY KEY (check_id, hour_ts)
) WITHOUT ROWID;

-- Métricas de tráfego: contadores brutos (64 bits) + taxas calculadas
CREATE TABLE interface_metrics (
  interface_id INTEGER NOT NULL REFERENCES interfaces(id) ON DELETE CASCADE,
  ts           INTEGER NOT NULL,
  in_octets    INTEGER NOT NULL,
  out_octets   INTEGER NOT NULL,
  in_bps       INTEGER,                       -- NULL na primeira amostra ou após reset de contador
  out_bps      INTEGER,
  in_errors    INTEGER,
  out_errors   INTEGER,
  PRIMARY KEY (interface_id, ts)
) WITHOUT ROWID;

CREATE TABLE interface_metrics_hourly (
  interface_id INTEGER NOT NULL REFERENCES interfaces(id) ON DELETE CASCADE,
  hour_ts      INTEGER NOT NULL,
  avg_in_bps   INTEGER,
  max_in_bps   INTEGER,
  avg_out_bps  INTEGER,
  max_out_bps  INTEGER,
  samples      INTEGER NOT NULL,
  PRIMARY KEY (interface_id, hour_ts)
) WITHOUT ROWID;

-- Eventos e alertas (painel inferior e tela Alertas)
CREATE TABLE events (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  ts              INTEGER NOT NULL,
  severity        TEXT NOT NULL CHECK (severity IN ('INFO','WARNING','CRITICAL')),
  type            TEXT NOT NULL CHECK (type IN ('DEVICE_DOWN','DEVICE_UP','DEVICE_WARNING',
                                                'CHECK_FAILED','CHECK_RECOVERED','LINK_DOWN','LINK_UP',
                                                'THRESHOLD','DISCOVERY','SYSTEM')),
  device_id       INTEGER REFERENCES devices(id) ON DELETE SET NULL,
  check_id        INTEGER REFERENCES service_checks(id) ON DELETE SET NULL,
  interface_id    INTEGER REFERENCES interfaces(id) ON DELETE SET NULL,
  message         TEXT NOT NULL,
  acknowledged_at INTEGER,
  acknowledged_by INTEGER REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX ix_events_ts ON events(ts DESC);
CREATE INDEX ix_events_device_ts ON events(device_id, ts DESC);
CREATE INDEX ix_events_unacked ON events(ts DESC) WHERE acknowledged_at IS NULL;

-- Notificações
CREATE TABLE notification_channels (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  name       TEXT NOT NULL UNIQUE,
  type       TEXT NOT NULL CHECK (type IN ('EMAIL','TELEGRAM','WEBHOOK')),
  config_enc TEXT NOT NULL,                   -- JSON criptografado (SMTP, token, URL...)
  enabled    INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE notification_rules (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id   INTEGER NOT NULL REFERENCES notification_channels(id) ON DELETE CASCADE,
  min_severity TEXT NOT NULL DEFAULT 'WARNING' CHECK (min_severity IN ('INFO','WARNING','CRITICAL')),
  event_types  TEXT,                          -- CSV de events.type; NULL = todos
  device_id    INTEGER REFERENCES devices(id) ON DELETE CASCADE,  -- NULL = todos
  enabled      INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX ix_notification_rules_channel ON notification_rules(channel_id);

-- Descoberta: jobs e hosts encontrados (staging antes da importação, ADR-4)
CREATE TABLE discovery_jobs (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  cidr             TEXT NOT NULL,
  snmp_profile_ids TEXT,                      -- JSON: perfis a tentar
  probe_ports      TEXT,                      -- JSON: portas TCP a sondar
  status           TEXT NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING','RUNNING','DONE','CANCELLED','FAILED')),
  hosts_total      INTEGER NOT NULL DEFAULT 0,
  hosts_scanned    INTEGER NOT NULL DEFAULT 0,
  hosts_found      INTEGER NOT NULL DEFAULT 0,
  created_by       INTEGER REFERENCES users(id) ON DELETE SET NULL,
  started_at       INTEGER,
  finished_at      INTEGER,
  error            TEXT
);

CREATE TABLE discovery_hosts (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  job_id          INTEGER NOT NULL REFERENCES discovery_jobs(id) ON DELETE CASCADE,
  ip_address      TEXT NOT NULL,
  hostname        TEXT,
  mac_address     TEXT,
  sys_descr       TEXT,
  sys_object_id   TEXT,
  suggested_type  TEXT,
  open_ports      TEXT,                       -- JSON
  snmp_profile_id INTEGER REFERENCES snmp_profiles(id) ON DELETE SET NULL,
  state           TEXT NOT NULL DEFAULT 'NEW' CHECK (state IN ('NEW','IMPORTED','IGNORED')),
  UNIQUE (job_id, ip_address)
);
CREATE INDEX ix_discovery_hosts_snmp ON discovery_hosts(snmp_profile_id);

-- Dado inicial
INSERT INTO maps (name, created_at) VALUES ('Principal', CAST(strftime('%s','now') AS INTEGER));
