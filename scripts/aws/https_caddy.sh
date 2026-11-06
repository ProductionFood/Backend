#!/usr/bin/env bash
# Pone HTTPS delante de la API con Caddy (certificado de Let's Encrypt automatico).
# Uso en la EC2:   bash https_caddy.sh productionfood.duckdns.org
# Requisitos: el dominio ya apunta a la IP de la EC2 y los puertos 80 y 443 estan abiertos.
set -euo pipefail
DOMINIO="${1:?Indique el dominio, p. ej. productionfood.duckdns.org}"

echo "==> Descargando Caddy"
sudo curl -fsSL "https://caddyserver.com/api/download?os=linux&arch=amd64" -o /usr/local/bin/caddy
sudo chmod +x /usr/local/bin/caddy
/usr/local/bin/caddy version

id caddy >/dev/null 2>&1 || {
  sudo groupadd --system caddy
  sudo useradd --system --gid caddy --create-home --home-dir /var/lib/caddy --shell /usr/sbin/nologin caddy
}
sudo mkdir -p /etc/caddy
printf '%s {\n    reverse_proxy localhost:8080\n}\n' "$DOMINIO" | sudo tee /etc/caddy/Caddyfile >/dev/null

sudo tee /etc/systemd/system/caddy.service >/dev/null <<'UNIT'
[Unit]
Description=Caddy (HTTPS para ProductionFood)
After=network-online.target

[Service]
User=caddy
Group=caddy
ExecStart=/usr/local/bin/caddy run --config /etc/caddy/Caddyfile
ExecReload=/usr/local/bin/caddy reload --config /etc/caddy/Caddyfile
AmbientCapabilities=CAP_NET_BIND_SERVICE
Restart=on-failure

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now caddy
echo "==> Esperando el certificado"
for i in $(seq 30); do
  curl -fsS -o /dev/null "https://${DOMINIO}/v3/api-docs" 2>/dev/null && break
  sleep 4
done
curl -s -o /dev/null -w "https://${DOMINIO}/v3/api-docs -> %{http_code}\n" "https://${DOMINIO}/v3/api-docs"
