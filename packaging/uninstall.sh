#!/usr/bin/env bash
# Remove o serviço do OmniDude. Os dados (/var/lib/omnidude) e a configuração (/etc/omnidude) FICAM, a menos que --purge.
#   sudo ./packaging/uninstall.sh [--purge]
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "erro: rode como root (sudo)." >&2; exit 1; }
PURGE=0
[ "${1:-}" = "--purge" ] && PURGE=1

systemctl disable --now omnidude.service 2>/dev/null || true
rm -f /etc/systemd/system/omnidude.service
systemctl daemon-reload
rm -rf /opt/omnidude /usr/share/doc/omnidude

if [ "$PURGE" -eq 1 ]; then
  echo "APAGANDO os dados e a configuração em 5 s (Ctrl+C cancela)…"
  sleep 5
  rm -rf /var/lib/omnidude /etc/omnidude
  id omnidude >/dev/null 2>&1 && userdel omnidude || true
  getent group omnidude >/dev/null && groupdel omnidude || true
else
  echo "removido. Dados em /var/lib/omnidude e configuração em /etc/omnidude foram mantidos."
fi
