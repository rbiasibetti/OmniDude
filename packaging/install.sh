#!/usr/bin/env bash
# Instala (ou atualiza) o OmniDude como serviço systemd. Rode como root:
#   sudo ./packaging/install.sh [caminho/para/omnidude.jar] [--start]
# Sem argumento, usa target/omnidude-*-SNAPSHOT.jar. Pode ser rodado de novo para atualizar o jar: a configuração e os
# dados em /etc/omnidude e /var/lib/omnidude não são tocados.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
JAR=""
START=0
for arg in "$@"; do
  case "$arg" in
    --start) START=1 ;;
    -h|--help) sed -n '2,6p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) JAR="$arg" ;;
  esac
done

die() { echo "erro: $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || die "rode como root (sudo)."
command -v systemctl >/dev/null || die "systemd não encontrado."

if [ -z "$JAR" ]; then
  JAR="$(ls -1 "$ROOT"/target/omnidude-*.jar 2>/dev/null | grep -v '\.original$' | head -n1 || true)"
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || die "jar não encontrado. Compile com 'mvn clean package' ou informe o caminho."

JAVA_BIN=/usr/bin/java
[ -x "$JAVA_BIN" ] || die "java não encontrado em $JAVA_BIN. Instale um JRE 21 (ex.: openjdk-21-jre-headless)."
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[ "${JAVA_MAJOR:-0}" -ge 21 ] || die "o OmniDude precisa de Java 21 ou superior (encontrado: ${JAVA_MAJOR:-desconhecido})."

PING_BIN="$(command -v ping || true)"
[ -n "$PING_BIN" ] || echo "aviso: o utilitário 'ping' não está instalado: as checagens ICMP vão falhar (instale iputils-ping)." >&2

if ! getent group omnidude >/dev/null; then groupadd --system omnidude; fi
if ! id omnidude >/dev/null 2>&1; then
  useradd --system --gid omnidude --home-dir /var/lib/omnidude --no-create-home --shell /usr/sbin/nologin omnidude
fi

install -d -m 0755 /opt/omnidude
install -m 0644 "$JAR" /opt/omnidude/omnidude.jar.new
mv -f /opt/omnidude/omnidude.jar.new /opt/omnidude/omnidude.jar

install -d -m 0750 -o root -g omnidude /etc/omnidude
if [ ! -f /etc/omnidude/omnidude.env ]; then
  install -m 0600 -o root -g root "$HERE/systemd/omnidude.env.example" /etc/omnidude/omnidude.env
  echo "criado /etc/omnidude/omnidude.env (edite se precisar)."
fi
install -m 0644 "$HERE/systemd/omnidude.service" /etc/systemd/system/omnidude.service
install -d -m 0755 /usr/share/doc/omnidude
[ -f "$ROOT/INSTALL.md" ] && install -m 0644 "$ROOT/INSTALL.md" /usr/share/doc/omnidude/INSTALL.md

systemctl daemon-reload
systemctl enable omnidude.service >/dev/null

# Se o ping do sistema não tiver capacidade própria, o CAP_NET_RAW da unidade cobre; se ainda assim falhar, veja o INSTALL.md.
if [ "$START" -eq 1 ]; then
  systemctl restart omnidude.service
  echo "serviço iniciado."
elif systemctl is-active --quiet omnidude.service; then
  systemctl restart omnidude.service
  echo "serviço reiniciado com o jar novo."
else
  echo "instalado. Inicie com: sudo systemctl start omnidude"
fi
echo "acompanhe com: journalctl -u omnidude -f   (a senha inicial do admin aparece aqui, uma vez, se você não a definiu)"
