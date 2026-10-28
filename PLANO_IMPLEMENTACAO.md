# OmniDude NMS — Plano de Implementação (v2, revisado)

Ferramenta de monitoramento de rede inspirada no The Dude: descobre dispositivos, mostra a topologia num mapa interativo, monitora serviços e tráfego e gera alertas. Distribuída como um único `.jar` com UI web embutida.

Esta versão substitui o plano inicial. A seção 11 mostra, item por item, o que mudou e por quê.

---

## 1. Escopo e requisitos

### 1.1 Dentro do escopo (v1.0)

- Cadastro manual e descoberta automática (CIDR) de dispositivos, com revisão antes de importar.
- Monitoramento de disponibilidade: ICMP, TCP, HTTP/HTTPS, SNMP e DNS, com histerese (evita alerta falso).
- Coleta de tráfego por interface via SNMP (contadores de 64 bits) e cálculo de throughput e utilização.
- Descoberta de enlaces por LLDP (sugeridos, o usuário confirma) e enlaces manuais.
- Mapas lógicos interativos (vários mapas, imagem de fundo opcional), com posições persistidas.
- Eventos/alertas persistidos, com confirmação (ack) e notificações por e-mail, Telegram e webhook.
- Autenticação, perfis ADMIN/VIEWER, HTTPS opcional, credenciais SNMP criptografadas.
- Retenção e agregação automática de métricas.

### 1.2 Fora do escopo da v1.0 (backlog)

Syslog, SNMP traps, API RouterOS, mapa geográfico, alta disponibilidade/cluster, app móvel, descoberta via CDP/FDB (LLDP cobre a v1), auditoria detalhada de ações.

### 1.3 Requisitos não funcionais e dimensionamento

| Item | Meta v1.0 |
|---|---|
| Dispositivos | até 500 |
| Interfaces monitoradas | até 1.000 (o usuário escolhe quais; a descoberta sugere as com `oper_status=up`) |
| Intervalo padrão | ICMP/serviços 30 s · tráfego 60 s |
| Volume de métricas | 1.000 interfaces ÷ 60 s ≈ 17 linhas/s ≈ 1,4 M linhas/dia |
| Retenção bruta | 7 dias (≈ 10 M linhas, estimativa de ~300 MB) |
| Retenção agregada (1 h) | 400 dias |
| Usuários simultâneos na UI | até 10 |
| Plataformas | Linux (principal) e Windows; JRE 21 |

Esses números são estimativas de projeto e serão validados na Fase 6 com teste de carga. O dimensionamento mostra que monitorar *todas* as interfaces de todos os equipamentos não cabe em SQLite. Por isso `interfaces.monitored` existe e vem desligado por padrão.

---

## 2. Arquitetura

### 2.1 Stack (corrigida)

| Camada | Tecnologia | Observação |
|---|---|---|
| Runtime | Java 21, Spring Boot 3.3+ | Virtual threads (`spring.threads.virtual.enabled=true`) para milhares de checagens de I/O bloqueante. |
| Banco | SQLite (`xerial/sqlite-jdbc`) em modo WAL | Arquivo único em `${OMNIDUDE_DATA_DIR}/omnidude.db`. |
| Migrações | Flyway | Substitui `ddl-auto`; o schema é versionado. |
| Acesso a dados | Spring Data JPA (`hibernate-community-dialects`) para CRUD; `JdbcTemplate` em lote para métricas e eventos | Métricas não passam pelo Hibernate. |
| SNMP | SNMP4J 3.x (v1/v2c/v3, GETBULK) | |
| ICMP | `ReachabilityProbe` com implementação por processo `ping` do SO (padrão) e fallback TCP | Java não tem ICMP puro (ver ADR-2). |
| Tempo real | Server-Sent Events (SSE) | Fluxo é servidor→cliente; comandos vão por REST (ADR-5). |
| Segurança | Spring Security (sessão + CSRF), BCrypt, AES-GCM para segredos | |
| Frontend | HTML + ES modules, Alpine.js, Cytoscape.js (mapa), uPlot (gráficos), Tailwind CSS pré-compilado | Todas as libs empacotadas em `static/vendor` (funciona sem internet). |
| Build | Maven → fat jar; `jlink`/`jpackage` opcional para runtime embutido | "Binário único" exige JRE 21 ou o runtime do jpackage. |

### 2.2 Decisões de arquitetura (ADRs)

| # | Decisão | Motivo |
|---|---|---|
| ADR-1 | SQLite + WAL, **um único writer lógico** | SQLite aceita uma escrita por vez. Métricas e eventos entram numa fila (`BlockingQueue`) e são gravados em lote a cada 1 s por uma thread dedicada. O pool de leitura tem ~4 conexões. `foreign_keys`, `busy_timeout`, `journal_mode=WAL` e `transaction_mode=IMMEDIATE` são aplicados em **cada** conexão, como parâmetros da URL JDBC. O modo `IMMEDIATE` evita `SQLITE_BUSY_SNAPSHOT` quando uma transação de leitura vira escrita. Como o Spring não conhece os códigos de erro do SQLite, o projeto traz um `sql-error-codes.xml` que os mapeia (sem isso, violação de unicidade viraria erro 500 e não 409). |
| ADR-2 | ICMP via processo `ping` do SO, com `ReachabilityProbe` abstrato | `InetAddress.isReachable` só envia ICMP real com root/`CAP_NET_RAW` e senão cai para TCP/7. O binário `ping` já tem as permissões necessárias. Custo: um processo por checagem, aceitável com virtual threads e semáforo. A interface permite trocar por implementação nativa depois. Validado no spike da Fase 0. |
| ADR-3 | Timestamps como `INTEGER` (epoch segundos UTC) | SQLite não tem tipo data. Epoch é ordenável, indexável e compacto. |
| ADR-4 | Descoberta **propõe**, o usuário **importa** | Evita poluir o inventário e permite descartar falsos positivos. Resultados ficam em `discovery_hosts`. |
| ADR-5 | SSE em vez de WebSocket/STOMP | O tráfego é quase só servidor→cliente. SSE reconecta sozinho, atravessa proxies e dispensa biblioteca no cliente. Atualizações de taxa são agrupadas a cada 2 s. |
| ADR-6 | Mapa com Cytoscape.js | Canvas puro exigiria implementar pan/zoom, hit-test, seleção e arrastar. Cytoscape resolve isso e aguenta centenas de nós. O Tailwind estiliza só a UI ao redor. |
| ADR-7 | Sem credenciais SNMP embutidas no código | O usuário cadastra perfis SNMP (inclusive `public`, se quiser). A descoberta só testa os perfis que ele selecionar. |
| ADR-8 | Servidor escuta em `127.0.0.1` por padrão | Expor na rede é decisão explícita (`server.address`), de preferência com TLS. |
| ADR-9 | Uma sessão SNMP por dispositivo por vez | Evita sobrecarregar agentes SNMP fracos (switches pequenos). |

### 2.3 Estrutura de pacotes

```
com.omnidude
├── config/         # Hikari/SQLite, Flyway, executores, propriedades omnidude.*
├── security/       # login, usuários, CSRF, criptografia de segredos (SecretCipher)
├── device/         # Device, DeviceAddress, Interface, SnmpProfile + serviços/REST
├── topology/       # Map, MapNode, Link, sugestões LLDP
├── monitoring/
│   ├── probe/      # ReachabilityProbe, TcpProbe, HttpProbe, DnsProbe, SnmpProbe
│   ├── scheduler/  # PollingScheduler (jitter, limite de concorrência, backoff)
│   └── state/      # StatusEngine (máquina de estados com histerese)
├── snmp/           # SnmpClientFactory, IfTablePoller, LldpPoller, SystemInfoPoller
├── metrics/        # MetricsWriter (fila + lote), RateCalculator, RollupJob, RetentionJob
├── discovery/      # DiscoveryJob, CidrExpander, Fingerprinter (fingerprints.yml)
├── event/          # EventService, ack
├── notification/   # Email, Telegram, Webhook, regras
└── web/            # controllers REST, SseHub, DTOs
```

### 2.4 Fluxo de dados

```
PollingScheduler ──(a cada intervalo, com jitter)──▶ Probe (thread virtual, timeout, semáforo global)
        │                                                   │
        │                                                   ▼
        │                                         CheckResult (ok/falha, ms)
        │                                                   │
        ▼                                                   ▼
   IfTablePoller ──▶ RateCalculator              StatusEngine (histerese)
        │                   │                       │            │
        └──────▶ MetricsWriter (fila → lote 1 s) ◀──┘            ▼
                            │                              EventService ──▶ Notification
                            ▼                                    │
                         SQLite                                  ▼
                                                              SseHub ──▶ navegador
```

Pontos de concorrência:

- Um `ScheduledThreadPoolExecutor` pequeno só *dispara* as tarefas; a execução acontece em um executor de virtual threads.
- Semáforo global (padrão 200 checagens simultâneas) e um lock por dispositivo para SNMP (ADR-9).
- Dispositivos `DOWN` têm checagens SNMP/serviço suspensas; só o ICMP continua, para detectar a volta.
- Jitter aleatório de até 10% do intervalo evita rajadas sincronizadas.

---

## 3. Modelo de dados (SQLite, migração Flyway `V1__init.sql`)

Convenções: datas em epoch segundos UTC (ADR-3); booleanos como `INTEGER` 0/1; enums como `TEXT` com `CHECK`; segredos (`*_enc`) armazenados criptografados com AES-GCM; chaves estrangeiras sempre indexadas.

```sql
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
  in_errors    INTEGER,                       -- erros desde a amostra anterior (diferença, não o contador); NULL na 1ª ou após reset
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
```

### 3.1 Notas do modelo

- **Primeiro acesso:** se `users` estiver vazia, a aplicação cria `admin` com senha aleatória, exibida **uma vez** no log de inicialização, e exige troca no primeiro login.
- **Status do dispositivo é derivado** (seção 4.1) e gravado em `devices.status` só para consulta rápida e para a UI.
- **Status e utilização do enlace são derivados:** link `DOWN` se qualquer ponta estiver `DOWN` (dispositivo ou `oper_status=2`); utilização = `max(in_bps, out_bps) / speed_bps` da interface de menor velocidade.
- **Exclusão de interface** remove os enlaces associados (`ON DELETE CASCADE`). Com `SET NULL`, dois enlaces entre o mesmo par de dispositivos colidiriam no índice único ao perderem a interface (defeito encontrado ao validar o schema). A coleta SNMP nunca apaga interfaces: marca `last_seen_at`, e só a exclusão manual remove a linha. Enlace sem interface associada continua possível (`NULL` na criação).
- **Retenção** (jobs diários, configuráveis em `settings`):
  1. Agrega `interface_metrics` e `check_results` em `*_hourly` para horas já fechadas.
  2. Apaga bruto com mais de 7 dias, agregado com mais de 400 dias e eventos com mais de 180 dias (os não confirmados são mantidos).
  3. `PRAGMA wal_checkpoint(TRUNCATE)` e `PRAGMA optimize`. `VACUUM` só manual.
- **Backup:** `VACUUM INTO 'backup.db'` agendável (Fase 5), sem parar o serviço.
- **Migração `V2__discovery_topology.sql`** (Fase 3): `discovery_jobs.hosts_known` (hosts já cadastrados que a varredura reencontrou), `discovery_hosts.os_info` (sistema deduzido pelo fingerprint) e a tabela `link_rejections` (pares de dispositivos cujo enlace sugerido por LLDP o usuário descartou; em ordem canônica, como `links`). Nunca se edita uma migração aplicada: mudanças novas entram em `V3__…`.

---

## 4. Regras centrais

### 4.1 Máquina de estados com histerese

Estado de cada `service_check`:

| Transição | Condição |
|---|---|
| `UNKNOWN → UP` | primeiro sucesso |
| `UP → WARNING` | sucesso com `response_ms > warn_latency_ms` |
| `* → DOWN` | `consecutive_failures >= fail_threshold` (padrão 3) |
| `DOWN → UP` | `consecutive_successes >= recover_threshold` (padrão 1) |

Estado do **dispositivo**, calculado a partir das checagens habilitadas:

1. `DOWN` se o ICMP estiver `DOWN` (sem ICMP configurado, a âncora é o SNMP; sem nenhum dos dois, `DOWN` se qualquer checagem estiver `DOWN`).
2. `WARNING` se o ICMP estiver `UP` mas qualquer outra checagem estiver `DOWN` ou `WARNING`, ou se o ICMP estiver em `WARNING`.
3. `UP` se todas estiverem `UP`.
4. `UNKNOWN` enquanto não houver resultado. Dispositivo com `enabled=0` não gera checagens nem eventos.

Cada mudança de estado gera um `event` (e notificação, conforme as regras). **Supressão por dependência:** se o dispositivo está `DOWN`, os `CHECK_FAILED` dos seus serviços não geram evento nem notificação; só o `DEVICE_DOWN`.

### 4.2 Cálculo de throughput

OIDs: `ifHCInOctets` 1.3.6.1.2.1.31.1.1.6, `ifHCOutOctets` 1.3.6.1.2.1.31.1.1.10, `ifHighSpeed` 1.3.6.1.2.1.31.1.1.1.15, `ifOperStatus` 1.3.6.1.2.1.2.2.1.8. Em SNMPv1 não existem contadores HC; nesse caso usa-se `ifInOctets`/`ifOutOctets` (32 bits) com intervalo máximo de 10 s para equipamentos ≥ 100 Mbps, ou o aviso "v1 sem HC" na UI.

```
dt   = ts_atual - ts_anterior            (segundos reais das amostras, não o intervalo configurado)
d    = atual - anterior
se atual < anterior:
    se contador de 32 bits e plausível  -> d = atual + 2^32 - anterior   (wrap)
    senão (64 bits, ou sysUpTime diminuiu) -> amostra descartada, in_bps = NULL   (reset/reboot)
bps  = d * 8 / dt
se bps > 1,5 * speed_bps               -> amostra descartada (leitura inconsistente)
```

A primeira amostra de uma interface só grava os contadores (`in_bps = NULL`). A coleta usa GETBULK sobre as colunas necessárias (uma consulta por dispositivo, não por interface).

### 4.3 Descoberta

1. **Entrada:** CIDR IPv4 + perfis SNMP selecionados (testados na ordem) + portas TCP a sondar (padrão 22, 80, 443, 3389) + confirmação de que o usuário **tem autorização** para varrer a rede (a API recusa sem ela).
2. **Política e limites** (propriedades `omnidude.discovery.*`; a tela de configurações chega na Fase 5):
   - Só redes privadas, loopback, link-local, CGNAT e as de documentação são varridas; faixa pública exige `allow-public=true`.
   - Máximo de 4.096 hosts por job (≈ /20): acima disso exige `confirmLarge` e gera um evento de aviso; teto absoluto de 65.536.
   - 128 hosts simultâneos e 200 hosts iniciados por segundo; um job por vez; cancelável.
   - Hosts **já cadastrados não são sondados**: são só contados (`hosts_known`).
3. **Por host:** ICMP e portas TCP em paralelo (vivo = qualquer resposta). Se vivo, tenta cada perfil SNMP até um responder (`sysDescr`, `sysObjectID`, `sysName`). Não há DNS reverso.
4. **MAC:** tabela ARP *local* (`/proc/net/arp`, ou `arp -a`), lida **ao fim** da varredura (o host só aparece nela depois de trocar pacotes) e só válida para hosts da mesma sub-rede L2. O `ifPhysAddress` de cada interface é lido na importação e guardado em `interfaces`.
5. **Fingerprint:** regras em `fingerprints.yml` (embutido; um arquivo de mesmo nome no diretório de dados vem antes) casam `sysObjectID` por prefixo de componente, regex de `sysDescr` e portas abertas, e dão `device_type` e `os_info`. Primeira regra que casa vence; sem regra → `OTHER`.
6. **Revisão:** os achados ficam em `discovery_hosts` (`NEW`). O usuário marca quais importar, ajusta nome e tipo, e escolhe o mapa de destino e se as portas abertas viram checagens TCP.
7. **Importação**, em três etapas, nesta ordem: (a) cria os dispositivos com as checagens padrão (ICMP, mais SNMP se um perfil respondeu); (b) lê as interfaces por SNMP (`monitored=1` só nas que estão `oper_status=up` e não são loopback); (c) só então lê o LLDP, porque casar vizinhos exige que os dois lados já existam.
8. **Topologia (LLDP):** cada vizinho de `lldpRemTable` é casado com um dispositivo conhecido pelo IP de gerência (`lldpRemManAddr`, em `devices.primary_ip` ou `device_addresses`) e, depois, pelo MAC do chassi (em `devices.mac_address` ou `interfaces.mac_address`). O casamento vira um enlace `SUGGESTED` (origem `LLDP`) em ordem canônica, com as interfaces resolvidas por nome. Idempotente: o mesmo enlace visto dos dois lados, ou revisto depois, não duplica, e interfaces que faltavam são preenchidas. Descartar uma sugestão grava uma rejeição (ela não volta); apagar um enlace confirmado não.

> Uso responsável: a descoberta e os testes de comunidade SNMP devem ser executados apenas em redes próprias ou autorizadas. Isso aparece na tela de descoberta e no README.

---

## 5. API REST e SSE

Todas as rotas exigem sessão autenticada; escrita (POST/PUT/DELETE) exige `ADMIN`. CSRF ativo.

| Recurso | Endpoints |
|---|---|
| Autenticação | `POST /login`, `POST /logout`, `GET /api/me`, `POST /api/me/password` |
| Dispositivos | `GET/POST /api/devices`, `GET/PUT/DELETE /api/devices/{id}`, `POST /api/devices/{id}/refresh` (lê sistema e interfaces por SNMP), `POST /api/devices/{id}/lldp` (lê os vizinhos) |
| Interfaces | `GET /api/devices/{id}/interfaces`, `PUT /api/interfaces/{id}` (monitored, alias) |
| Checagens | `GET/POST /api/devices/{id}/checks`, `PUT/DELETE /api/checks/{id}`, `GET /api/checks/{id}/results?from&to` |
| Métricas | `GET /api/interfaces/{id}/metrics?from&to&resolution=raw\|hour` |
| Mapas | `GET/POST /api/maps`, `GET /api/maps/{id}` (nós + enlaces), `PUT /api/maps/{id}/nodes/{deviceId}` (posição/pin), `POST/DELETE /api/maps/{id}/nodes` |
| Enlaces | `GET/POST /api/links`, `PUT/DELETE /api/links/{id}`, `POST /api/links/{id}/confirm`, `POST /api/topology/refresh` (LLDP de todos os equipamentos SNMP) |
| Descoberta | `POST/GET /api/discovery/jobs`, `GET /api/discovery/jobs/{id}`, `GET /api/discovery/jobs/{id}/hosts`, `POST /api/discovery/jobs/{id}/cancel`, `POST /api/discovery/jobs/{id}/import`, `POST /api/discovery/hosts/{id}/ignore` |
| Eventos | `GET /api/events?severity&device&unacked&from&to`, `POST /api/events/{id}/ack` |
| Perfis SNMP | `GET/POST /api/snmp-profiles`, `PUT/DELETE /api/snmp-profiles/{id}` (segredos nunca retornam na leitura) |
| Notificações | `GET/POST /api/notification-channels`, `POST /api/notification-channels/{id}/test`, `GET/POST /api/notification-rules` |
| Admin | `GET/PUT /api/settings`, `GET/POST/PUT /api/users`, `POST /api/admin/backup` |
| Resumo | `GET /api/summary` (contadores UP/WARNING/DOWN, eventos sem ack, piores latências, maiores utilizações) |

**SSE** em `GET /api/stream`, eventos nomeados:

| Evento | Conteúdo | Cadência |
|---|---|---|
| `device-status` | `{deviceId, status, since}` | imediato, na mudança |
| `interface-rates` | lista `{interfaceId, inBps, outBps, ts}` | agrupado a cada 2 s |
| `event` | evento novo | imediato |
| `discovery-progress` | `{jobId, scanned, total, found, status}` | a cada 1 s durante o job |

O cliente, ao reconectar, recarrega `GET /api/summary` e o mapa atual para reconciliar o estado.

---

## 6. Interface (layout corrigido)

SPA servida pelo Spring (`src/main/resources/static`), com Alpine.js para estado e módulos ES por tela.

```
+-----------------------------------------------------------------------------+
| OmniDude NMS     ● 42 UP   ▲ 3 WARNING   ✖ 2 DOWN        [ Buscar IP/nome ]  |
+-------------+---------------------------------------------+-----------------+
| Dashboard   |  Mapa: [ Principal ▾ ]  [+ Link] [Fixar] [⌂ Enquadrar]        |
| Mapas       |                                             |  INSPETOR       |
| Dispositivos|   [Router Core] ====== 1 Gbps ====== [SW-A] |  (ocultável)    |
| Descoberta  |         ‖                                   |  Info SNMP      |
| Eventos     |      100 Mbps                               |  Serviços       |
| Configurações|       ‖                                    |  Interfaces     |
|             |    [Servidor DB]                            |  Gráfico Rx/Tx  |
+-------------+---------------------------------------------+-----------------+
| EVENTOS: ✖ 10:42 SW-B DOWN · ● 10:40 Core-R1 UP · ▲ 10:31 SRV-DB http lento  |
+-----------------------------------------------------------------------------+
```

| Área | Conteúdo |
|---|---|
| **Cabeçalho** | Contadores UP/WARNING/DOWN (atualizados por SSE), busca rápida por IP/nome/MAC, menu do usuário. |
| **Menu lateral** | **Dashboard** (contadores, piores latências, interfaces mais utilizadas, últimos eventos) · **Mapas** (seletor de mapa e imagem de fundo) · **Dispositivos** (tabela filtrável/ordenável com ações em massa) · **Descoberta** (assistente em 3 passos: parâmetros → progresso → revisão/importação) · **Eventos** (filtros e ack) · **Configurações** (perfis SNMP, intervalos, retenção, notificações, usuários, backup). |
| **Mapa (Cytoscape.js)** | Nó = ícone por tipo + anel de status (verde UP, âmbar WARNING, vermelho DOWN, cinza UNKNOWN). O status também usa **forma/ícone**, não só cor (acessibilidade). Enlace: espessura = velocidade da interface; cor por utilização (< 50% verde, 50–80% âmbar, > 80% vermelho; cinza sem dados); rótulo opcional com a taxa. Arrastar nó salva a posição (`dragfree`); *pin* trava o nó; zoom/pan; criar enlace arrastando de um nó a outro. |
| **Inspetor** | Abre ao clicar num nó: informações SNMP, checagens com status e latência, tabela de interfaces (marcar `monitored`), gráfico uPlot de Rx/Tx (1 h / 24 h / 7 d / 30 d usando bruto ou agregado), botões renomear, trocar ícone, pausar monitoramento (`enabled`), excluir. |
| **Painel de eventos** | Últimos eventos em tempo real; clicar centraliza o nó no mapa. |

Build do CSS: o Tailwind standalone CLI gera `static/css/app.css`, que é **commitado**. Assim `mvn package` não depende de Node. O script `tools/build-css.sh` regenera o arquivo em desenvolvimento.

---

## 7. Segurança

- **Autenticação:** login por formulário, sessão com cookie `HttpOnly` + `SameSite=Strict`, CSRF habilitado; bloqueio temporário (5 min) após 5 falhas seguidas por usuário, em memória.
- **Segredos:** `SecretCipher` (AES-256-GCM). A chave vem de `OMNIDUDE_SECRET_KEY` ou, na falta, é gerada em `${DATA_DIR}/secret.key` com permissão 600 e aviso no log. Segredos nunca voltam pela API nem aparecem em logs.
- **Rede:** bind em `127.0.0.1` (ADR-8); TLS por configuração do Spring (`server.ssl.*`); orientar proxy reverso para exposição.
- **Entradas:** validação de CIDR/IP/portas e limites de descoberta. Checagens HTTP só usam URLs/hosts configurados por um ADMIN.
- **Perfis:** `VIEWER` só leitura; tudo que altera inventário, credenciais ou descoberta exige `ADMIN`.

---

## 8. Fases de execução

Estimativas de esforço para uma pessoa em dedicação parcial, só como ordem de grandeza. Cada fase termina com algo demonstrável e critérios de aceite verificáveis.

### Fase 0 — Spikes e esqueleto (≈ 1 semana)
- Projeto Maven (Boot 3.3, Java 21), CI com `mvn verify`, Flyway com `V1__init.sql`, Hikari com os PRAGMAs do ADR-1.
- **Spike ICMP:** `ExternalPingProbe` em Linux e Windows, com e sem root.
- **Spike SQLite:** 200 escritas/s em lote + leituras concorrentes por 10 min, sem `SQLITE_BUSY`; teste de exclusão em cascata e do índice `ux_links_pair`.
- **Spike SNMP:** SNMP4J contra um agente net-snmp (container) e um agente simulado.
- **Spike mapa:** Cytoscape com 500 nós e 800 enlaces mantendo ≥ 30 fps.
- **Critério de saída:** os quatro spikes aprovados ou o ADR correspondente revisado.

### Fase 1 — Fatia vertical: dispositivo → ping → mapa (≈ 2–3 semanas)
- Segurança (login, usuário admin inicial, CSRF, perfis), `SecretCipher`.
- CRUD de dispositivos, checagem ICMP, `StatusEngine` com histerese, eventos, SSE.
- UI: login, cabeçalho com contadores, mapa com nós arrastáveis e posição persistida, painel de eventos.
- **Aceite:** cadastrar um IP, vê-lo verde no mapa; derrubar o host, ver `DOWN` após 3 falhas, com evento e atualização na tela sem recarregar; reiniciar o app e as posições permanecem.

### Fase 2 — Serviços e inspetor (≈ 2 semanas)
- Checagens TCP, HTTP/HTTPS, DNS e SNMP-reachability (`sysUpTime`); `service_checks` configuráveis; latência; `WARNING` por latência.
- Inspetor lateral, gráfico de latência, tabela de dispositivos, Dashboard básico, confirmação de eventos.
- **Aceite:** serviço com porta fechada deixa o dispositivo em `WARNING`; checagem da porta 161 é feita por SNMP GET, não por TCP; supressão por dependência funciona.

### Fase 3 — Descoberta e topologia (≈ 2–3 semanas) — **concluída**
- Cliente SNMP com *walk* (v1/v2c), leitura do grupo `system`, do IF-MIB e do LLDP; inventário de interfaces.
- `DiscoveryJob` com política de redes, limites, progresso por SSE e cancelamento; fingerprint por `fingerprints.yml`; ARP local; revisão e importação.
- Enlaces: CRUD manual com seleção de interface, sugestões por LLDP, confirmação e rejeição; enlaces desenhados no mapa; layout automático.
- **Aceite:** descoberta de um /24 de laboratório em < 2 min; importar cria dispositivos, checagens e interfaces; vizinhos LLDP aparecem como sugestões e viram enlaces ao confirmar; CIDR acima do limite é recusado sem confirmação.

### Fase 4 — SNMP e tráfego (≈ 2 semanas) — **concluída**
(A leitura de interfaces e os enlaces manuais foram antecipados para a Fase 3.)
- Perfis SNMP v3 (USM); `IfTablePoller` (GETBULK, contadores HC), `RateCalculator` com wrap/reset, `MetricsWriter` para `interface_metrics`.
- Cor e espessura dos enlaces por utilização; gráficos Rx/Tx no inspetor; `RollupJob` e agregação horária de `check_results` e `interface_metrics`.
- **Aceite:** testes unitários de wrap 32 bits, reset 64 bits e reboot; throughput bate com o `iperf` dentro de ±5%; após 8 dias simulados, bruto > 7 dias foi apagado e o agregado preservado.

### Fase 5 — Notificações, administração e retenção (≈ 2 semanas)
- Canais e regras (e-mail, Telegram, webhook), tela de configurações, usuários, backup (`VACUUM INTO`), modo de manutenção.
- **Aceite:** evento `CRITICAL` dispara notificação nos três canais de teste; backup restaurável; VIEWER não consegue alterar nada.

### Fase 6 — Endurecimento e empacotamento (≈ 2 semanas) — revisão de segurança e systemd prontos; carga e Dockerfile pendentes
- Teste de carga (500 dispositivos / 1.000 interfaces simulados) validando a seção 1.3; revisão de segurança; documentação (instalação, permissões do `ping`, uso responsável, backup).
- `mvn clean package` → `omnidude.jar`; Dockerfile; unidade `systemd`; opcional `jpackage` para Windows/Linux.
- **Aceite:** o jar sobe em máquina limpa com apenas JRE 21; CPU < 25% de um núcleo e heap < 512 MB na carga alvo; nenhuma biblioteca carregada de CDN.

---

## 9. Estratégia de testes

| Nível | O que cobre | Ferramentas |
|---|---|---|
| Unidade | `StatusEngine`, `RateCalculator`, `CidrExpander`, `Fingerprinter`, `SecretCipher`, normalização de enlaces | JUnit 5, AssertJ |
| Integração | Repositórios com SQLite real (arquivo temporário), Flyway, cascatas e índices, `MetricsWriter` sob concorrência | `@SpringBootTest`/`@DataJpaTest` |
| SNMP | Pollers contra agente simulado e container net-snmp | snmp4j-agent, Testcontainers |
| API | Autorização por perfil, CSRF, contratos REST e SSE | MockMvc, WebTestClient |
| UI | Fluxos críticos (login, arrastar nó, descoberta) | Playwright (poucos testes, smoke) |
| Carga | Cenário da seção 1.3 | script próprio com agentes simulados |

---

## 10. Riscos e mitigação

| Risco | Impacto | Mitigação |
|---|---|---|
| ICMP via processo não escala ou varia por SO | alto | Spike na Fase 0; interface `ReachabilityProbe`; fallback TCP |
| Escrita concorrente no SQLite | alto | ADR-1; spike; métricas em lote; `monitored` limitado |
| Crescimento do banco | médio | Retenção e agregação desde a Fase 4; dimensionamento revalidado na Fase 6 |
| Agentes SNMP lentos ou inconsistentes | médio | Timeout/retries por perfil, uma sessão por dispositivo, descarte de amostras suspeitas |
| Descoberta agressiva na rede | médio | Limites, taxa configurável, cancelamento, aviso de uso responsável |
| Esforço do mapa interativo | médio | Cytoscape.js, spike na Fase 0 |
| Falsos alertas | médio | Histerese, supressão por dependência |

---

## 11. Rastreabilidade: lacunas e inconsistências resolvidas

### 11.1 Correções técnicas

| Plano original | Problema | Correção neste plano |
|---|---|---|
| ICMP via "Java NIO Sockets" | Java não envia ICMP puro | ADR-2: `ping` do SO atrás de `ReachabilityProbe` |
| JPA sem tratar o SQLite | dialeto, FKs desligadas, `SQLITE_BUSY` | ADR-1: dialeto community, PRAGMAs por conexão, WAL, writer único em lote, Flyway |
| TCP connect na porta 161 | SNMP é UDP | Checagem SNMP GET (seção 8, Fase 2) |
| MAC "na tabela ARP" do alvo | ARP do alvo lista vizinhos | `ifPhysAddress`; ARP local só para a mesma L2 (4.3) |
| `ifInOctets`/`ifOutOctets` | 32 bits estouram em segundos | HC de 64 bits, tratamento de wrap/reset (4.2) |
| `DATETIME` em texto | sem tipo data no SQLite | ADR-3: epoch `INTEGER` |

### 11.2 Lacunas do modelo preenchidas

| Lacuna | Tabela / mecanismo |
|---|---|
| Sem interfaces | `interfaces` (ifIndex, velocidade, status, `monitored`) |
| Velocidade no enlace | movida para `interfaces`; enlace guarda só referências |
| Sem credenciais SNMP | `snmp_profiles` com segredos criptografados |
| Sem alertas persistidos | `events` + `notification_*` |
| Sem configuração | `settings` |
| Sem configuração de checagem | `service_checks` (intervalo, timeout, limiares) |
| Sem autenticação | `users`, Spring Security, perfis |
| Métricas sem retenção/índice | `*_hourly`, PK composta `WITHOUT ROWID`, jobs de retenção |
| `ip_address UNIQUE` quebra multi-homed | `device_addresses` |
| Posição x/y no dispositivo | `map_nodes` por mapa (vários mapas) |
| Enlaces duplicáveis / auto-link | `CHECK (source < target)` + índice único |
| FKs sem índice | índices criados em todas |
| `status` livre / `WARNING` sem regra | `CHECK` nos enums e regras formais (4.1) |
| Sem histerese | `fail_threshold` / `recover_threshold` |
| Descoberta sem etapa de revisão | `discovery_jobs` / `discovery_hosts` |
| Sem descoberta de topologia | LLDP → enlaces `SUGGESTED` |

### 11.3 Inconsistências do documento corrigidas

| Inconsistência | Resolução |
|---|---|
| Arquivo `netpulse.db` num projeto chamado OmniDude | `omnidude.db` |
| "Mapa Geográfico/Lógico" sem suporte a geografia | Somente mapas **lógicos**, vários, com imagem de fundo opcional; geográfico vai para o backlog |
| "Dashboard" sem descrição | Definido na seção 6 |
| "Reposicionamento de Enlaces" descrevia criação de links | Dividido: criar enlace (arrastar entre nós) e reassociar interfaces (inspetor/edição do link) |
| Painel de eventos, tabela de ativos e logs sem fase | Incluídos nas Fases 1, 2 e 5 |
| "Binário único" | Fat jar + JRE 21; `jlink`/`jpackage` opcional |
| Tailwind: CDN vs. jar único | CSS pré-compilado e commitado, libs em `static/vendor` |
| WebSocket/STOMP para fluxo unidirecional | SSE (ADR-5) |
| Backend completo antes da UI | Fatias verticais desde a Fase 1 |
| Sem testes, segurança ou retenção nas fases | Seções 7 e 9; Fases 0, 3, 5 e 6 |

---

## 12. Estado da implementação

### Fases 0 e 1 — concluídas

Código em `src/`, instruções em [README.md](README.md). Verificado: 65 testes automatizados (unidade, integração com SQLite real e API com segurança) e o fluxo completo no navegador (primeiro acesso, troca de senha, criar dispositivo, status em tempo real, arrastar e fixar nós, eventos e confirmação, reinício do servidor).

| Critério de aceite da Fase 1 | Resultado |
|---|---|
| Cadastrar um IP e vê-lo verde no mapa | Atendido (`127.0.0.1` fica UP e o anel do nó fica verde) |
| Derrubar o host: `DOWN` após 3 falhas, com evento, sem recarregar a tela | Atendido com IPs inalcançáveis; atualização por SSE |
| Reiniciar o app e as posições permanecem | Atendido |

| Spike da Fase 0 | Resultado |
|---|---|
| SQLite: escrita em lote + escritas transacionais + leituras concorrentes | 15 s, 3.000 amostras (200/s) gravadas sem perda, 74 escritas JPA e ~2.000 leituras, **0 erros de lock**. O teste longo de 10 min está parametrizado (`-Dspike.seconds=600`) e **ainda não foi executado** |
| ICMP via `ping` do SO | Validado em Linux **sem root**. **Windows e macOS não foram testados** (só os parsers de saída têm teste) |
| Cascatas e índice único de enlaces | Validado no SQLite real; achou e corrigiu o defeito do `SET NULL` (seção 3.1) |
| SNMP4J contra agente | Coberto na Fase 3 por um agente SNMP em processo com MIB (GET, GETNEXT, GETBULK); o contra o `net-snmp` real em container ainda não foi feito |
| Cytoscape com 500 nós / 30 fps | **Não executado**; o mapa foi testado só com 5 nós |

### Defeitos encontrados na validação e corrigidos

- Violações de restrição do SQLite chegavam como `UncategorizedSQLException` (daria 500 em vez de 409 numa corrida por IP): `sql-error-codes.xml`.
- Cliente SSE desconectado gerava `ERROR` com stack trace em SO localizado (`Pipe quebrado`), porque o Spring só reconhece a mensagem em inglês: tratador de `IOException` na API.
- Menu lateral inteiro desabilitado por `:disabled` com valor `undefined` no Alpine.
- Foco do modal, canvas sem acompanhar o redimensionamento, zoom exagerado com um só nó, rótulo `undefined` após mudança de status.

### Desvios em relação ao plano v2

- `users.must_change_password` (o plano exigia a troca, mas o schema não tinha o campo).
- Bloqueio de login por **usuário**, não por usuário/IP, e em memória.
- Enlaces: o mapa devolvia `links: []`; o CRUD de enlaces foi concluído na Fase 3.
- Itens do menu (Dashboard, Descoberta, Configurações) aparecem desabilitados ("em breve").

### Fase 2 — serviços, inspetor e Dashboard — concluída

Verificado: 106 testes automatizados (as sondas contra servidores falsos locais, a API completa e o caminho SNMP ponta a ponta) e a interface no navegador (criar e editar serviços, verificar agora, gráfico, perfil SNMP, Dashboard, layout em 375 px).

| Critério de aceite da Fase 2 | Resultado |
|---|---|
| Serviço com porta fechada deixa o dispositivo em `WARNING` | Atendido (teste de API e navegador: TCP e HTTP) |
| A porta 161 é checada por SNMP GET, não por TCP | Atendido (`SnmpProbe` lê `sysUpTime`; testado contra agente SNMP em processo, v1 e v2c) |
| Supressão por dependência | Atendido: com o dispositivo DOWN só o `DEVICE_DOWN` gera evento (teste dedicado) |
| Gráfico de latência no inspetor | Atendido (1 h, 6 h, 24 h, 7 d; falhas em barras vermelhas; disponibilidade, média e máximo) |
| Dashboard básico | Atendido (contagens, problemas ativos, maiores latências, últimos eventos; atualiza por SSE) |

O que foi entregue:

- **Sondas** `TcpProbe`, `HttpProbe` (HTTP/HTTPS; método, caminho, status aceitos, texto esperado, verificação TLS opcional; redirecionamentos não são seguidos), `DnsProbe` (cliente DNS mínimo em UDP com repetição em TCP; exige NOERROR com ao menos um registro) e `SnmpProbe` (SNMP4J, sessão UDP compartilhada).
- **API:** CRUD de checagens com validação por tipo (`CheckValidator`), `POST /api/checks/{id}/run` ("verificar agora"), `GET /api/checks/{id}/results?range=` (agregado em até ~240 pontos), perfis SNMP com a community criptografada (nunca devolvida), `GET /api/dashboard`.
- **Interface:** inspetor com lista de serviços e gráfico (uPlot), formulários de serviço e de dispositivo (novo/editar), gerenciador de perfis SNMP, Dashboard.

Decisões e desvios em relação ao plano v2:

- **SNMP v1/v2c apenas.** O v3 continuava adiado (entregue na Fase 4; a API recusava o cadastro com mensagem clara até lá). A porta SNMP padrão da checagem vem do perfil do dispositivo; `snmp_profiles.timeout_ms` ainda não é usado (a checagem tem o próprio timeout e as retentativas vêm do perfil).
- **HTTP:** o cabeçalho `Host` não pode ser definido (o `HttpClient` do JDK o restringe), então hosts virtuais por nome não são testáveis. Com `verifyTls=false` o certificado não é validado, nem o nome (o alvo é sempre um IP).
- **Regra "dispositivo DOWN só executa a âncora" corrigida.** Na Fase 1 ela só poupava o ICMP; um dispositivo cuja âncora fosse SNMP, ou sem âncora, nunca seria reavaliado. Agora a âncora é a checagem ICMP (ou SNMP); sem âncora nada é pulado. Coberto por testes.
- **Inspetor e Dashboard atualizam por SSE (mudanças de estado) e por consulta a cada 10 s** (latências), em vez de uma mensagem SSE por amostra, para não multiplicar tráfego com centenas de dispositivos.
- **Retenção:** as amostras brutas ficam 7 dias; o gráfico de 7 d usa o bruto. A agregação horária foi entregue na Fase 4.

Defeitos encontrados na validação e corrigidos:

- A opção "não verificar certificado" não funcionava com IP: um `X509TrustManager` simples ainda deixa o JSSE verificar o nome do host. Passou a ser um `X509ExtendedTrustManager`.
- "+ Serviço" depois de editar outro serviço abria com o tipo errado (a lista de tipos lia o formulário anterior).
- O inspetor ocupava espaço fora do mapa, e em 375 px o cabeçalho e o menu transbordavam e o inspetor sobrepunha o cabeçalho. Agora o inspetor fecha ao sair do mapa e vira tela cheia no celular.
- Após "verificar agora" o gráfico recarregava antes do lote de 1 s do `MetricsWriter`; ganhou um novo carregamento atrasado.

Limitações conhecidas desta fase:

- A interface não tem testes automatizados (só verificação manual no navegador); o plano prevê smoke tests com Playwright na Fase 6.
- ICMP continua validado apenas em Linux; HTTPS contra um servidor TLS real só foi exercitado pelo caminho de falha (o JDK não oferece um servidor TLS de teste simples).
- O DNS só consulta o dispositivo como servidor: não há checagem "resolve este nome via o resolver do sistema".

### Fase 3 — descoberta e topologia — concluída

**Ordem trocada a pedido:** a descoberta passou à frente do tráfego. Ela só dependia do tráfego no passo de listar interfaces, então a leitura do IF-MIB e do LLDP foi antecipada; contadores, throughput e gráficos Rx/Tx seguem na Fase 4.

Verificado: 182 testes automatizados (76 novos: CIDR e política de redes, fingerprint, ARP, leitura SNMP com agente que implementa GET/GETNEXT/GETBULK, descoberta ponta a ponta, enlaces, inventário) e a interface no navegador contra 3 agentes SNMP falsos com topologia LLDP e um servidor TCP na loopback `127.0.60.x`.

| Critério de aceite da Fase 3 | Resultado |
|---|---|
| Descoberta de um /24 de laboratório em < 2 min | Atendido (a faixa /29 de laboratório termina em poucos segundos; o /24 tem o mesmo custo por host, limitado por 128 sondas simultâneas e 200 hosts/s) |
| Importar cria dispositivos, checagens e interfaces | Atendido (ICMP, SNMP, TCP das portas abertas, interfaces com `monitored` só nas UP não-loopback) |
| Vizinhos LLDP viram sugestões e enlaces ao confirmar | Atendido (visto dos dois lados vira um enlace só; interfaces resolvidas por nome) |
| CIDR acima do limite recusado sem confirmação | Atendido (e faixa pública recusada; ver 4.3) |

Entregue: `CidrRange`, `HostScanner`, `DiscoveryService` (um job por vez, progresso SSE, cancelamento, recuperação de jobs interrompidos por reinício), `DiscoveryImportService`, `Fingerprinter` (+ `fingerprints.yml`), `ArpTable`; `SnmpClient`, `SnmpInventory`, `LldpReader`, `DeviceInventoryService`; `LinkService`, `TopologyService`. Na interface: assistente de Descoberta em 3 passos, enlaces no mapa (sugerido tracejado, com falha pontilhado e vermelho), modo "+ Enlace", "Detectar enlaces", "Organizar" (layout automático que respeita nós fixados) e a lista de interfaces no inspetor.

Decisões e desvios em relação ao plano v2:

- **Autorização explícita e política de redes** (não previstas): a API exige `authorized=true` e recusa faixas públicas por padrão. É um controle de uso responsável que vale também para quem usa a API direto.
- **Limites em propriedades** (`omnidude.discovery.*`), não em `settings`, até a tela de configurações da Fase 5.
- **Hosts já cadastrados não são sondados** (só contados), o que acelera a re-varredura.
- **MAC só vem da tabela ARP local** (lida ao fim); o MAC por interface vem do SNMP na importação. Sem DNS reverso: o nome sugerido é o `sysName`.
- **`discovery_hosts.os_info` e `link_rejections`** vieram na migração V2; o plano v2 não previa a tabela de rejeições, necessária para descartar uma sugestão de forma durável.
- **SNMPv3 seguiu adiado** (entregue na Fase 4).
- **Enlace DOWN** era derivado só do estado dos dispositivos nas pontas; o `ifOperStatus` só era atualizado por "Ler via SNMP" (a coleta de tráfego da Fase 4 passou a atualizá-lo).

Defeitos encontrados na validação e corrigidos:

- `Fingerprinter` tinha dois construtores e o Spring não subia (`No default constructor`); o teste de contexto pegou antes de qualquer uso.
- NPE por *unboxing* em um ternário `long : Long` ao ler a velocidade de interface sem `ifSpeed` (a *loopback*).
- Na tela de revisão, todos os `<select>` de tipo mostravam "Roteador": `<option>` de `x-for` não existia quando o `x-model` aplicou o valor. Corrigido com `:selected` também nos selects de enlace, mapa e perfil SNMP.
- "N já rejeitado(s)" contava observações (o par é visto pelos dois lados), não enlaces; agora conta pares.
- O layout em grade deixava os enlaces passando por trás de outros nós; ganhou o botão "Organizar".

Limitações conhecidas:

- Só IPv4 na descoberta; sem CDP nem tabela de endereços de switch (só LLDP).
- Testado contra agentes SNMP simulados, não contra `net-snmp`, `snmpd` de roteadores ou switches reais: a interpretação do LLDP depende de como cada fabricante preenche `lldpRemLocalPortNum` (assumimos ifIndex) e `lldpRemPortId`.
- O ICMP continua validado só em Linux; o spike longo do SQLite (10 min) e o mapa com 500 nós seguem pendentes.
- A interface não tem testes automatizados (só verificação manual).

### Fase 4 — SNMP e tráfego — concluída

Verificado: 223 testes automatizados (41 novos) e a interface no navegador contra 3 agentes SNMP falsos com contadores que crescem, topologia LLDP e uma interface que cai e volta.

| Critério de aceite da Fase 4 | Resultado |
|---|---|
| Testes unitários de wrap 32 bits, reset 64 bits e reboot | Atendido (`RateCalculatorTest`, 13 casos; o reboot é o caso "contador de 64 bits que diminui") |
| Throughput bate com o `iperf` dentro de ±5% | **Não verificado**: só contra contadores simulados (taxa esperada calculada a mão, com tolerância pelo jitter do relógio). Falta um teste com tráfego real |
| Após 8 dias simulados, o bruto > 7 dias é apagado e o agregado preservado | Atendido (`RollupRetentionTest`: raw de 8 d sai, agregado de 30 d fica, o de 401 d sai) |
| Cor e espessura dos enlaces por utilização; gráficos Rx/Tx | Atendido (verificado no navegador; 1 h a 90 d) |

Entregue: `SnmpClient` com **SNMPv3** (USM; `noAuthNoPriv`, `authNoPriv`, `authPriv`; SHA, SHA-256, SHA-512; AES-128/192/256) e perfis v3 na API e na interface; `IfTablePoller`; `RateCalculator`; `TrafficCollector` (coleta periódica por dispositivo) e `TrafficService` (taxa recente em memória, utilização de enlaces, séries); `MetricsWriter` ganhou a fila de `interface_metrics`; `RollupJob` (agregado horário de tráfego e de latência) e `RetentionJob` (agrega antes de apagar). Na interface: perfis v3, taxa ↓/↑ por interface, gráfico Rx/Tx (1 h a 90 d), enlaces coloridos pela utilização, atualização por SSE (`link-traffic`) e latência de 30 d a partir do agregado.

Decisões e desvios em relação ao plano v2:

- **GET direto em vez de GETBULK** no `IfTablePoller`: o poller consulta só os índices monitorados (3 interfaces × 7 colunas por pacote), o que gasta menos pacotes que percorrer as colunas inteiras quando poucas portas são monitoradas. Prefere os contadores de 64 bits e cai nos de 32 bits por interface; em v1 pede só os de 32 bits.
- **Uma sessão SNMP por conjunto de credenciais v3.** A tabela USM do SNMP4J é indexada pelo nome do usuário; dois perfis com o mesmo usuário e senhas diferentes colidiriam numa sessão única. Sessões ociosas por mais de 10 min são fechadas.
- **SHA-1 registrado explicitamente.** O SNMP4J 3.8.2 só registra SHA-2 por padrão; sem o `AuthSHA`, perfis `SHA` simplesmente não respondem (descoberto pelos testes).
- **Taxa ambígua não é inventada.** Contador de 32 bits cujo intervalo permitiria mais de uma volta na velocidade da interface (1 Gb/s dá a volta em ~34 s) fica sem taxa; taxa acima de 125% da velocidade da interface é descartada.
- **`in_errors`/`out_errors` guardam a diferença** entre amostras (não o contador bruto), com `NULL` na primeira amostra ou após reset.
- **Taxas recentes ficam em memória** (e no banco como histórico). Após um reinício, a primeira amostra de cada interface não tem taxa. Uma taxa com mais de 3 intervalos vira "sem dado", nunca "0".
- **Dispositivos DOWN ou em manutenção não são consultados** (só dariam timeout); falha de coleta não muda o estado do dispositivo, isso é da checagem SNMP.
- **`ifOperStatus` atualizado pela coleta** e, se a interface pertence a um enlace confirmado, a mudança gera `LINK_DOWN`/`LINK_UP`. Portas sem enlace não geram evento (ruído de portas de acesso).
- **Reagregação das últimas 2 horas fechadas** a cada execução do `RollupJob` (idempotente), para absorver amostras atrasadas; depois de uma parada longa, retoma da última hora gravada.
- **Latência de 30 d** passou a vir do agregado horário (sem mínimo; atrasada em até ~1 h).

Defeitos encontrados na validação e corrigidos:

- Perfis `SHA` (SHA-1) não respondiam: o SNMP4J não registra `AuthSHA` por padrão.
- O agente de teste respondia v3 com um `PDU` simples em vez de `ScopedPDU`, e a resposta se perdia.
- Cabeçalho do gráfico de tráfego truncava o nome da interface com 6 botões de período; passou a quebrar linha.

Limitações conhecidas:

- Nada foi medido contra tráfego real (`iperf`) nem contra equipamentos reais; SNMPv3 foi testado só contra o agente simulado do SNMP4J (sem VACM).
- Sem limiares de utilização/erros gerando alertas (entram com as regras da Fase 5); o `THRESHOLD` do enum de eventos ainda não é usado.
- Contadores de 32 bits em enlaces rápidos ficam frequentemente sem taxa (por desenho; veja acima).
- O agregado horário de tráfego não guarda erros nem percentis.
- A interface continua sem testes automatizados.

### Complemento à Fase 4 — fabricante pelo MAC (OUI)

Pedido depois da Fase 4: enriquecer a identificação de dispositivos com as tabelas `ieee-oui.txt` e `mac-vendor.txt` do arp-scan.

- **`OuiRegistry`** carrega as duas tabelas (embutidas em `src/main/resources/oui`, 53 mil prefixos) e consulta pelo prefixo mais longo de 12 a 1 dígitos hexadecimais (MA-L, MA-M e MA-S do IEEE e as entradas extras do arp-scan). O `mac-vendor.txt` do diretório de dados vence as embutidas. MAC com o bit "administrado localmente" e sem prefixo conhecido é sinalizado como aleatório/virtual, sem chute de fabricante.
- **Calculado na leitura, não gravado:** o fabricante não tem coluna (sem migração): `DeviceView` e `HostView` ganharam `macVendor` e `macLocal`. Atualizar a tabela muda o resultado na hora.
- **`fingerprints.yml` ganhou `vendor:`** (regex sobre o fabricante, combinável com `sysObjectId`, `sysDescr` e `ports`). Regras que combinam fabricante e sistema vêm antes do "Linux" genérico (Ubiquiti, Synology, QNAP e VMs); as só por fabricante vêm por último, para fabricantes de tipo quase sempre único (MikroTik, Fortinet, impressoras, Synology/QNAP, Cisco → switch etc.). Fabricantes ambíguos (Apple, Dell, HP, Intel, TP-Link) não geram palpite.
- **Onde atua:** na descoberta, depois da leitura da tabela ARP (o MAC só existe ao final da varredura), reaplica as regras com o fabricante; no "Ler via SNMP", depois de o MAC do dispositivo ser definido pelas interfaces. Nunca sobrescreve o tipo escolhido pelo usuário.
- **Interface:** fabricante na revisão da descoberta, MAC e fabricante no inspetor, coluna "Fabricante" na lista (a partir de 768 px) e busca por fabricante.
- **Limites:** só há MAC para hosts na mesma rede local ou lidos por SNMP; o arp-scan é GPLv2 (confira a compatibilidade com a licença escolhida; as tabelas descendem do registro público do IEEE). Sem testes automatizados da tela.
- **Testes:** 15 novos (consulta, formatos, prefixo mais longo, sobrescrita pelo diretório de dados, regras de fabricante, API e inventário), 238 no total.

### Fase 3.5 — topologia por FDB/ARP/CDP/MNDP e descoberta passiva — concluída

Pedido depois da Fase 4: a detecção de enlaces só funcionava com LLDP, e um roteador Wi-Fi com dispositivos ligados não gerava enlace nenhum. Resposta: cruzar as tabelas que os equipamentos já têm (FDB, ARP, vizinhos de fabricante) e, onde não há dado, **inferir de forma declarada**.

Verificado: 264 testes (26 novos: motor de inferência, 10 cenários de API contra agentes simulados, identidade passiva) e a interface no navegador (nó virtual, enlaces por FDB e inferidos, evidência no enlace, "Confirmar sugeridos"); a migração V3 foi aplicada sobre um banco V2 existente.

- **Leitores SNMP:** `BridgeReader` (dot1d e Q-BRIDGE, status aprendido/próprio), `ArpReader` (ipNetToMedia e ipNetToPhysical), `VendorNeighborReader` (CDP `cdpCacheTable`, MNDP `mtxrNeighborTable`).
- **`FdbInference`** (lógica pura): por porta de cada switch, vizinho gerenciável que cobre os demais; roteador/firewall/AP único; host com MAC único; senão nó virtual. Dispositivo visto em várias portas vale só na de menos MACs.
- **Nó virtual** = `devices.virtual = 1` (switch, hub ou AP sem SNMP), com chave estável `unmanaged:<dispositivo>:<ifIndex>` em `primary_ip` (a coluna é `NOT NULL UNIQUE`, e recriar `devices` por causa dos CHECKs exigiria soltar todas as FKs). Sem checagens, SNMP ou IP; fora das contagens do cabeçalho e do Dashboard; só nome, tipo e notas editáveis.
- **Migração V3:** recria `links` (o CHECK de `origin` não aceitava as novas origens; nada referencia `links`), com `origin` ∈ MANUAL, LLDP, CDP, MNDP, FDB, INFERRED e a coluna `evidence`; `devices.virtual`; `discovery_hosts.hints`.
- **Conciliação:** a rodada completa remove sugestões FDB/INFERRED que não reencontrou e nós virtuais sem enlace confirmado; com qualquer falha de leitura, nada é removido. Evidência mais forte promove uma sugestão fraca (INFERRED → FDB → vizinho anunciado). Enlaces confirmados e rejeições nunca mudam.
- **Gateway:** de `/proc/net/route` (Linux) e do prefixo da interface local; dispositivos sem nenhum enlace sólido na mesma rede viram `INFERRED` para o gateway. Gateway não cadastrado é avisado. Em outros sistemas, só vale o único ROUTER/FIREWALL cadastrado.
- **ARP:** preenche o MAC que faltava (tabela do servidor + dos roteadores SNMP) e lista hosts fora do inventário.
- **Descoberta passiva** (`PassiveIdentity`, em unicast por host): NetBIOS (NBSTAT), SSDP/UPnP (M-SEARCH + descrição XML, buscada só do próprio host) e mDNS (PTR reverso). Alimenta o nome sugerido e o campo `banner:` das regras (`InternetGatewayDevice` → roteador, `[Printer]` → impressora, NetBIOS → Windows/Samba).
- **Interface:** origem e evidência no enlace, "(sugerido · FDB)" / "(inferido)" nos rótulos, inferido em cinza pontilhado, "Confirmar sugeridos", nó virtual tracejado, resumo da detecção, dicas na revisão da descoberta.

Limitações e o que ficou de fora:

- **Roteador sem SNMP, sem API e sem LLDP** continua sem revelar quem está no Wi-Fi ou no cabo: só o enlace inferido ao gateway. APIs de equipamento (MikroTik API, OpenWrt, UniFi) e leases de DHCP, que resolveriam isso, não foram feitas.
- **Traceroute** (enlaces de camada 3) e **mDNS/SSDP por multicast** não foram implementados (a identidade passiva é por host, em unicast).
- FDB por VLAN com `community@vlan` (Cisco) não é lida; redundância (anéis, LAG) pode gerar enlaces paralelos sugeridos; a lógica de borda assume árvore.
- **Nada foi validado contra equipamento real**: os OIDs de CDP e MNDP seguem as MIBs, e a FDB só foi lida de agentes simulados. A UI segue sem testes automatizados.
- Um nó virtual apagado pelo usuário reaparece na rodada seguinte, se a evidência continuar.

### Complemento — enlaces manuais, tipos de enlace e nós lógicos

Pedido depois da Fase 3.5: permitir enlaces manuais para incorporar dispositivos externos (DNS, VPNs). O enlace manual (**+ Enlace**) já existia desde a Fase 3; o que faltava era poder representar o que não tem IP e escolher os dois lados sem depender de clicar no mapa.

- **Nó lógico:** `POST /api/devices` com `"logical": true` cria um dispositivo `virtual = 1`, `origin = MANUAL`, sem checagens, SNMP nem IP (a chave `logical:<uuid>` ocupa `primary_ip`, como nos nós deduzidos). Fica no mapa, fora das contagens, e só nome, tipo e notas mudam. A limpeza da topologia só remove nós `DISCOVERY`: os do usuário nunca somem. Dispositivo externo com IP continua sendo um dispositivo comum (monitorado, com DNS/TCP/ICMP).
- **Tipo do enlace** (`links.kind`, migração V4, sem CHECK: validado pela API): CABLE, WIFI, VPN, INTERNET, OTHER. A API aceita e devolve `kind`; a interface desenha VPN, Internet e Wi-Fi com traços e cores próprios e os nomeia no rótulo.
- **Interface:** "Nó lógico" no cadastro; "Ligar a…" no inspetor; o formulário de novo enlace passou a ter seletores de origem e destino (as interfaces são recarregadas ao trocar) e o campo de tipo; o inspetor de um nó sem IP esconde status, serviços e interfaces.
- **Testes:** 8 novos (nó lógico, validações, tipo do enlace, DNS externo com IP público, exclusão em cascata, permissão de VIEWER, mapa, e rodadas da topologia preservando nós e enlaces manuais).
- **Limites:** o destino de um enlace só pode ser um dispositivo cadastrado (não há enlace para um nome DNS ou IP avulso); um dispositivo externo precisa de IP literal (nomes de host não são resolvidos); o estado de um enlace VPN/Internet não é monitorado por si só, só pelos dispositivos nas pontas.

### Complemento — tipos de dispositivo Nuvem e Rede

Pedido depois dos nós lógicos: `CLOUD` (Nuvem) e `NETWORK` (Rede) entram em `DeviceType`, nos rótulos e nos ícones da interface (nuvem e três nós ligados). Valem para nós lógicos e para dispositivos com IP; o nó lógico sem tipo vira `CLOUD`. As regras do `fingerprints.yml` também aceitam os tipos.

- **Migração V5, em Java** (`V5__DeviceTypes`): o CHECK de `devices.device_type` não aceitava os valores novos e o SQLite não altera um CHECK, então `devices` é recriada. É em Java porque o procedimento recomendado exige `PRAGMA foreign_keys = OFF`, que não vale dentro de transação; com as chaves ligadas, o `DROP TABLE` apagaria em cascata interfaces, checagens, enlaces e posições. A migração roda fora da transação do Flyway, abre a sua, confere `foreign_key_check` antes de confirmar (e desfaz se falhar), preserva ids e o contador de `AUTOINCREMENT`, recria os 3 índices e religa as chaves sempre.
- **Teste da migração:** `DeviceTypeMigrationTest` migra um banco V4 povoado (dispositivos, interfaces, checagens, enlace, nó de mapa) com as mesmas opções de conexão da aplicação e confere que nada caiu em cascata, que o próximo id não voltou atrás, que o CHECK e a unicidade de `primary_ip` continuam, e que o `ON DELETE CASCADE` segue funcionando.
- **Limite:** a migração é a primeira que recria uma tabela central; como não há backup agendado (Fase 5), copie `omnidude.db` antes de atualizar uma instalação com dados que importem.

### Complemento — mapa público somente leitura

Pedido: publicar um mapa para acesso público, apenas para visualização, sem detalhar dispositivos nem o menu.

- **Modelo (V6):** `maps.public_token` (NULL = não publicado; índice único parcial), `public_show_status` e `public_show_names`. O token tem 256 bits (base64url, 43 caracteres); despublicar o apaga e publicar de novo gera outro, então um link morto nunca ressuscita. Publicar, despublicar, regenerar e as opções: `GET/PUT /api/maps/{id}/publication`, só ADMIN (inclusive a leitura, porque o link é a credencial).
- **Redação no servidor:** `MapPublicationService.publicView` monta a resposta do zero com uma lista fixa de campos; ids públicos sequenciais (`n0`…), sem IP, MAC, fabricante, interfaces, tráfego, serviços, notas, rótulos, evidências nem sugestões de enlace. Nós deduzidos viram "Switch/hub não gerenciável". Com nomes ocultos o campo `name` nem existe; sem estado, `status` não existe e o enlace nunca acusa falha. A página não decide nada do que mostra.
- **Rotas:** `GET /public/{token}` (página) e `GET /api/public/maps/{token}`, mais `common.js`, `public.js`, `cytoscape.min.js` e `public.css`, abertos; todo o resto continua exigindo login (SSE, `app.js`, `alpine`, API). Escrita na visão pública nunca é 2xx. Token inexistente, malformado ou despublicado responde o mesmo 404.
- **Endurecimento:** sem cookies (o filtro do XSRF pula a visão pública) nem sessão; `Cache-Control: no-store`, `X-Robots-Tag: noindex`, `Referrer-Policy: no-referrer`, CSP com `default-src 'none'` e `script-src 'self'` (o único estilo inline admitido é o do Cytoscape, por hash); `X-Frame-Options: DENY` (não embutível); limite de 120 requisições/min por origem (`PublicRateLimiter`, janela fixa; atrás de proxy conta o IP do proxy).
- **Interface:** botão **Publicar** (verde "Publicado" quando ativo) com painel de opções, link com Copiar/Abrir, "Gerar novo link" e aviso de que o servidor escuta só em 127.0.0.1; página pública própria (Cytoscape sem arrastar nós, legenda, "atualizado às…", consulta a cada 30 s, mensagem de "não encontrado" quando despublicada). Constantes e ícones foram extraídos para `common.js`, usado pelos dois.
- **Testes:** 10 novos (publicação e permissões, a lista fixa de campos com varredura por segredos, opções, nós lógicos e deduzidos, link morto após despublicar/regenerar, 404 uniforme, cabeçalhos e ausência de cookies, o resto do app protegido, limite de taxa), 285 no total. Verificado no navegador: publicar, abrir o link, ícones, console sem violações de CSP, celular.
- **Limites:** o link é um segredo de portador: quem o tiver vê o mapa, e não há senha, validade nem lista de acessos; o limite de taxa é por processo; mapa grande em tela de celular fica pequeno (amplie com os dedos); fundo do mapa e utilização dos enlaces não são mostrados na página pública.

### Fase 5 — notificações, administração e retenção — concluída

Verificado: 320 testes (35 novos: notificações contra servidores locais HTTP e SMTP simulado, ajustes, usuários e sessões, backup, manutenção) e a interface no navegador (canal webhook criado e testado contra um receptor local, modo de manutenção com aviso, usuários, backup). A migração V7 foi aplicada sobre um banco V6 existente.

| Critério de aceite da Fase 5 | Resultado |
|---|---|
| Evento `CRITICAL` dispara notificação nos três canais de teste | Atendido (`aCriticalEventReachesAllThreeChannelTypes`, pelo caminho real: evento gravado, publicado, entrega assíncrona) |
| Backup restaurável | Atendido: o backup é aberto e conferido (`integrity_check`) ao ser criado e, nos testes e no jar, aberto como um banco comum com os dados e as migrações |
| VIEWER não consegue alterar nada | Atendido, e vai além: configurações, canais, usuários, backups e ajustes são ADMIN até para leitura |

Entregue:

- **Notificações** (`notification/`): `WebhookNotifier` (JSON, HMAC-SHA256, sem redirecionamentos), `TelegramNotifier` (Bot API; o token nunca aparece em erros), `EmailNotifier` (SMTP via Angus Mail; STARTTLS obrigatório quando escolhido). `NotificationService` casa regras (gravidade mínima, tipos, dispositivo, *incluir recuperação*), envia de forma assíncrona com até 3 tentativas (rede, 5xx, 429), e registra tudo em `notification_log`. O gancho é `EventService.publish`, o ponto único por onde todo evento passa.
- **Anti-ruído:** anti-repetição por canal e mensagem (janela configurável), teto de 30 mensagens por minuto por canal, modo de manutenção global (com prazo opcional que expira sozinho). Tudo suprimido fica no histórico com o motivo.
- **Ajustes** (`SettingsService`, tabela `settings`): 14 ajustes com mínimo, máximo e padrão vindo do `application.yml`, lidos de um cache em memória (o coletor de tráfego os consulta a cada segundo), gravados em lote com tudo-ou-nada. Os consumidores (retenção, coleta de tráfego, padrões de checagem nova, limites da descoberta) passaram a lê-los; as propriedades viraram só o padrão.
- **Usuários** (`UserAdminService`): criar com senha provisória, perfil, ativar, redefinir senha, apagar; salvaguardas contra auto-alteração e contra remover o último administrador. `SessionRevocation`/`SessionRevocationFilter` derrubam as sessões abertas quando o acesso muda (as autoridades ficam fixas na sessão, então rebaixar alguém não valia até o logout).
- **Backup** (`BackupService`): `VACUUM INTO` em `backups/` (diretório `700`, arquivos `600`), conferência de integridade e do histórico de migrações, listagem, download, exclusão (nome validado por padrão, nunca sai da pasta), poda dos N mais recentes e execução automática por hora configurável (no máximo um por dia). Falha do automático vira um evento `SYSTEM`.
- **Interface:** *Configurações* (só ADMIN) com abas Notificações (canais, regras, histórico, "Testar"), Ajustes e manutenção (agrupados, com padrão e "restaurar"), Usuários e Backup (com o passo a passo de restauração); aviso de manutenção no topo para todos.

Decisões e desvios em relação ao plano v2:

- **Migração V7:** `notification_log` (histórico) e `notification_rules.include_recovery`. O plano tinha as tabelas de canais e regras, mas não o histórico nem a recuperação: sem a opção, uma regra "atenção ou pior" nunca avisaria da volta (INFO).
- **Modo de manutenção é global**, além da pausa por dispositivo que já existia (`devices.enabled`). Janelas agendadas por dispositivo ficaram de fora.
- **Backup agendado por hora do dia**, não por expressão cron livre.
- **Restauração é manual** (parar, trocar o arquivo, iniciar), documentada na tela e no README; não há botão de restaurar.

Defeitos encontrados na validação e corrigidos:

- O botão "Testar" ficava desativado para sempre: `:disabled="a && b"` devolve `undefined` e o Alpine não remove o atributo; passou a `!!(a && b)`.
- O envio registrava o `event_id` com chave estrangeira para `events`, e um evento apagado pela retenção derrubaria o registro do envio; a coluna agora recebe o id só se o evento existe.
- O teste do reset de ajuste enviava `null` por um `ObjectMapper` que omite nulos; o reset ficou coberto com JSON escrito à mão (o navegador envia o `null` normalmente).

Limitações conhecidas:

- **Nada foi exercitado contra a API real do Telegram nem um provedor SMTP**; STARTTLS e SSL não têm teste (o SMTP simulado fala texto puro).
- Sem limiares de utilização/erros que gerem eventos `THRESHOLD`: as regras de notificação existem, mas o que gera esse tipo de evento ainda não.
- O limite por minuto e a anti-repetição são por processo e em memória (reiniciar os zera).
- Webhook e Telegram aceitam qualquer host (o ADMIN cadastra a URL); não há lista de destinos permitidos.
- A interface continua sem testes automatizados.

### Complemento — identidade estável com DHCP

Pedido: como manter a identificação dos equipamentos quando o DHCP troca o IP. Antes, o IP era a chave: a troca aparecia como queda mais um host novo (e importá-lo duplicava o dispositivo).

- **Modelo (V8):** `device_macs` (vários MACs por dispositivo, origem MANUAL/DEVICE/ARP/IFACE/DISCOVERY, flag `random` para MAC administrado localmente, copiado do `devices.mac_address` existente), `device_ip_history` (AUTO/DISCOVERY/MANUAL) e `discovery_hosts.matched_device_id/matched_by`. Zero mudança no que já usa `devices.primary_ip`: as checagens leem o IP a cada execução, então trocar o IP preserva histórico, enlaces e mapa.
- **`IdentityService.reconcile(arp)`** (por minuto, com o ARP do servidor e, a cada N min, o dos roteadores/firewalls SNMP): MAC estável conhecido num IP livre + dispositivo DOWN = atualiza o IP (evento informativo); mesmo MAC em dois IPs = endereço adicional; dispositivo UP = espera; IP de um dispositivo visto com MAC alheio = aviso único (conflito de identidade: o estado pode estar errado); MAC aleatório, ambíguo ou IP ocupado por outro dispositivo nunca agem sozinhos. A atualização automática é ajuste (`identity.auto-update-ip`); "Procurar novo IP" a força para um dispositivo.
- **`SubnetSweeper`:** o ARP de um IP que ninguém contatou não está na tabela; com dispositivos DOWN de MAC estável, um byte UDP para a porta 9 de cada endereço da sub-rede local (privada, /22 ou menor, só onde o servidor tem interface; no máximo a cada 5 min) faz o kernel resolvê-lo. Sem privilégio e sem esperar resposta.
- **Onde aprende:** cadastro por descoberta, "Ler via SNMP" (MAC de cada interface, exceto loopback), leitura de ARP, edição manual (grava o histórico) e a tela (adicionar ou remover MAC; um MAC pertence a um dispositivo só). O "Detectar enlaces" também reconcilia com o ARP que já lê.
- **Descoberta:** `IdentityService.recognize` marca o host que tem o MAC (ou, só como sugestão, o nome) de um dispositivo com outro IP; a revisão mostra "Parece ser X" com **Atualizar IP** (`/adopt`), "Selecionar todos" o exclui e importá-lo exige `forceNew`.
- **Interface:** seção *Identidade* no inspetor (MACs com fabricante e origem, aleatórios marcados, adicionar/remover, IPs anteriores, **Procurar novo IP** em dispositivo DOWN) e o aviso na revisão da descoberta.
- **Testes:** 21 novos (troca por MAC, dispositivo UP, duas interfaces, aleatório e ambíguo, atualização desligada com aviso único, IP ocupado, conflito e confirmação de MAC, aprendizado, histórico manual, API de MACs, MACs do SNMP, localizar, política da varredura, reconhecimento, adoção, importação como novo, backfill da V8). Verificado no navegador: reconhecimento, "Atualizar IP", evento e inspetor.
- **Limites:** só vale onde há ARP (rede local do servidor ou roteadores SNMP); celular com MAC aleatório só por nome; sem as concessões do servidor DHCP (a fonte ideal: MAC, IP e nome, e as reservas) nem leitura de `dnsmasq.leases`/`dhcpd.leases`; o aviso e a espera de um dispositivo UP usam o estado das checagens, então um dispositivo cujo IP antigo passou a outra máquina (que responde ao ping) fica "UP" até o aviso de conflito.

### Fase 6 (parcial) — revisão de segurança e systemd

Pedido: seguir apenas com o `systemd` e a revisão de segurança; teste de carga e Dockerfile ficam de fora por ora.

- **Revisão de segurança** (relatório completo em `SEGURANCA.md`, política de relato em `SECURITY.md`): sem falhas de injeção SQL, XSS ou autorização; corrigidos o Spring Boot 3.3 sem suporte (agora 3.5.16, SQLite JDBC 3.53.4.0, SNMP4J 3.13.1), o bloqueio de login que permitia negar acesso ao `admin` e crescia sem limite (agora por usuário+IP, com decaimento, teto por usuário e teto de entradas), a criação da `secret.key` com permissão aberta por um instante, permissões de arquivos de dados (diretório 700, arquivos 600, reaplicadas na partida), cabeçalhos ausentes (CSP, `Referrer-Policy`, `Permissions-Policy`, HSTS), cookie `Secure` e proxy reverso confiável (`OMNIDUDE_COOKIE_SECURE`, `OMNIDUDE_FORWARD_HEADERS=native`, `OMNIDUDE_TRUSTED_PROXIES`), SSE ilimitado (teto de 500) e JSON inválido respondendo 200 (agora 400).
- **Guardas automáticas:** `SecurityPostureTest` varre por reflexão a autorização de todas as rotas e confere os cabeçalhos; `LoginAttemptServiceTest`, `FilePermissionsTest`, `SseHubTest` e `SystemdUnitTest`. Total: 364 testes.
- **systemd** (`packaging/`): `omnidude.service` (usuário `omnidude`, `StateDirectory` 0700, `ProtectSystem=strict`, `NoNewPrivileges`, apenas `CAP_NET_RAW` para o `ping`, filtro de chamadas de sistema, sem `MemoryDenyWriteExecute` porque o JIT da JVM precisa), `omnidude.env.example` (modo 600), `install.sh` (idempotente, também atualiza o jar) e `uninstall.sh` (`--purge` apaga tudo). `systemd-analyze security`: exposição 1,7. Instruções em `INSTALL.md`.
- **Riscos aceitos:** CSP com `'unsafe-eval'` (Alpine), SSRF por quem é ADMIN (webhook, HTTP, Telegram), regex do `fingerprints.yml` escrita pelo administrador, bloqueios em memória (zeram ao reiniciar), link do mapa público como credencial ao portador, backups sem a chave de criptografia.
- **Não verificado:** instalar e iniciar o serviço como root em servidor real, e o `ping` sob `CAP_NET_RAW`; teste de intrusão externo; fuzzing de protocolos.

### Complemento — manual ilustrado (GitHub Pages)

Pedido: um manual completo e ilustrado, em HTML navegável, para publicar no GitHub Pages.

- **Publicação:** `docs/` (17 páginas: início e 16 capítulos, `.nojekyll`, `404.html`); Pages em *Settings → Pages → Deploy from a branch → `/docs`*. Sem CDN, fontes nem scripts de terceiros; abre também direto do disco.
- **Conteúdo:** introdução, instalação (jar, systemd, HTTPS), primeiros passos (interface anotada), mapa e dispositivos, checagens e latência, SNMP e tráfego, descoberta, topologia, identidade com DHCP, eventos e notificações, mapa público, administração, segurança, API REST, solução de problemas, referência e glossário.
- **Ilustrações:** 31 capturas de tela de uma rede de laboratório simulada (tiradas por script com Chrome headless, dados fictícios) e 4 diagramas SVG que acompanham o tema claro/escuro.
- **Navegação:** menu lateral, índice da página com destaque da seção, busca local (tecla `/`), tema claro/escuro, ampliação de imagens, botão de copiar nos blocos de código, anterior/próximo, impressão e celular.
- **Manutenção:** fontes em `manual/` (um fragmento HTML por capítulo) e gerador `manual/build.py` (só biblioteca padrão); `manual/check.py` confere links, âncoras e imagens, e `build.py --check` acusa `docs/` fora de dia com as fontes.
- **Limites:** o texto descreve a versão atual e repete as limitações já conhecidas; a interface do manual foi conferida por captura em desktop e celular, em tema claro e escuro, mas não por testes automatizados.

### Próximo: restante da Fase 6
Teste de carga (500 dispositivos / 1.000 interfaces simulados) e Dockerfile (ambos adiados a pedido). Pendências herdadas: spike de 10 min do SQLite, mapa com 500 nós, ICMP fora do Linux, SNMP contra equipamentos reais, limiares que gerem `THRESHOLD`, teste em servidor real da unidade systemd.
