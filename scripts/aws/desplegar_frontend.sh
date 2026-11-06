#!/usr/bin/env bash
# Compila el frontend Angular en la EC2 y lo sirve con Caddy en el mismo dominio que la API.
# El frontend de produccion usa apiUrl '/api/v1' (relativo), asi que no hace falta CORS.
#
#   bash desplegar_frontend.sh productionfood.duckdns.org
#
# Variables opcionales: REPO_FRONT (https://github.com/ProductionFood/Frontend.git), RAMA_FRONT (main).
set -euo pipefail
DOMINIO="${1:?Indique el dominio, p. ej. productionfood.duckdns.org}"
REPO_FRONT="${REPO_FRONT:-https://github.com/ProductionFood/Frontend.git}"
RAMA_FRONT="${RAMA_FRONT:-main}"
WEB=/var/www/productionfood
FUENTE=/opt/productionfood-frontend

echo "==> Node.js 24 LTS"
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
  ARCHIVO="$(curl -fsSL https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt | grep 'linux-x64.tar.xz' | awk '{print $2}')"
  curl -fsSL "https://nodejs.org/dist/latest-v24.x/${ARCHIVO}" -o /tmp/node.tar.xz
  sudo tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1
fi
node -v; npm -v

echo "==> Codigo del frontend (${RAMA_FRONT})"
sudo rm -rf "$FUENTE"; sudo mkdir -p "$FUENTE"; sudo chown "$USER" "$FUENTE"
git clone --depth 1 -b "$RAMA_FRONT" "$REPO_FRONT" "$FUENTE"
cd "$FUENTE/productionfood-frontend"

echo "==> Compilando (unos minutos; la instancia tiene 1 GB de RAM y usa swap)"
npm ci --no-audit --no-fund
NODE_OPTIONS=--max-old-space-size=1536 npx ng build --configuration production
SALIDA="$(ls -d dist/*/browser 2>/dev/null | head -1)"
[ -n "$SALIDA" ] || SALIDA="$(ls -d dist/* | head -1)"
echo "Salida: $SALIDA"

echo "==> Publicando en ${WEB}"
sudo rm -rf "$WEB"; sudo mkdir -p "$WEB"
sudo cp -r "$SALIDA"/. "$WEB"/
sudo chmod -R a+rX "$WEB"

echo "==> Caddy: SPA en / y API en /api"
sudo tee /etc/caddy/Caddyfile >/dev/null <<CADDY
${DOMINIO} {
    encode gzip

    @api path /api/* /v3/api-docs /v3/api-docs/* /swagger-ui /swagger-ui/* /swagger-ui.html
    handle @api {
        reverse_proxy localhost:8080
    }

    handle {
        root * ${WEB}
        try_files {path} /index.html
        file_server
    }
}
CADDY
sudo systemctl reload caddy || sudo systemctl restart caddy
sleep 3
curl -s -o /dev/null -w "frontend https://${DOMINIO}/ -> %{http_code}\n" "https://${DOMINIO}/"
curl -s -o /dev/null -w "api      https://${DOMINIO}/v3/api-docs -> %{http_code}\n" "https://${DOMINIO}/v3/api-docs"
