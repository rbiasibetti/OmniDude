-- Fase 5 (notificações, administração e retenção)

-- Histórico de envios: uma linha por tentativa final (enviado, falhou ou suprimido), para o usuário ver por que algo não chegou.
CREATE TABLE notification_log (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  ts         INTEGER NOT NULL,
  channel_id INTEGER REFERENCES notification_channels(id) ON DELETE SET NULL,
  channel    TEXT NOT NULL,                   -- nome do canal na hora do envio (sobrevive à exclusão do canal)
  event_id   INTEGER REFERENCES events(id) ON DELETE SET NULL,
  status     TEXT NOT NULL CHECK (status IN ('SENT','FAILED','SUPPRESSED','TEST')),
  detail     TEXT,                            -- erro ou motivo da supressão
  attempts   INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX ix_notification_log_ts ON notification_log(ts DESC);

-- Recuperação (DEVICE_UP, CHECK_RECOVERED, LINK_UP) é INFO: sem isto, uma regra "WARNING ou mais" avisaria da queda
-- e nunca da volta. Com a opção ligada, a recuperação segue o filtro de tipo e de dispositivo, não o de gravidade.
ALTER TABLE notification_rules ADD COLUMN include_recovery INTEGER NOT NULL DEFAULT 1;
