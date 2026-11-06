#!/usr/bin/env bash
# Despliegue de la API en una EC2 Amazon Linux 2023 contra una base RDS MySQL 8.4.
# Se ejecuta en la propia EC2 (consola de EC2 -> Conectar). Pide la contrasena de la base por
# teclado (no queda en el historial ni en el repositorio) y genera el secreto JWT.
#
#   RAMA=chore/despliegue-aws bash desplegar_ec2.sh
#
# Variables opcionales: RAMA (main), REPO, BASE (productionfood), USUARIO_DB (admin).
set -euo pipefail

REPO="${REPO:-https://github.com/ProductionFood/Backend.git}"
RAMA="${RAMA:-main}"
BASE="${BASE:-productionfood}"
USUARIO_DB="${USUARIO_DB:-admin}"
DESTINO=/opt/productionfood

read -r -p "Endpoint de RDS (p. ej. productionfood-db.xxxx.us-east-2.rds.amazonaws.com): " DB_HOST
read -r -s -p "Contrasena de la base (${USUARIO_DB}): " DB_PASSWORD; echo

echo "==> Paquetes"
sudo dnf install -y git maven java-25-amazon-corretto-devel mariadb105 >/dev/null

if ! swapon --show | grep -q swapfile; then
  echo "==> Swap de 2 GB (la instancia gratuita tiene 1 GB de RAM)"
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
fi

echo "==> Comprobando la base"
MYSQL_PWD="$DB_PASSWORD" mysql -h "$DB_HOST" -u "$USUARIO_DB" --ssl-mode=REQUIRED -e "SELECT VERSION(), @@time_zone;" "$BASE"

echo "==> Codigo (${RAMA})"
sudo rm -rf "$DESTINO"; sudo mkdir -p "$DESTINO"; sudo chown "$USER" "$DESTINO"
git clone --depth 1 -b "$RAMA" "$REPO" "$DESTINO"
cd "$DESTINO"
mvn -q -DskipTests package
JAR="$(ls target/*.jar | grep -v original | head -1)"

echo "==> Configuracion (/etc/productionfood.env, solo root)"
JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
sudo tee /etc/productionfood.env >/dev/null <<ENV
SPRING_DATASOURCE_URL=jdbc:mysql://${DB_HOST}:3306/${BASE}?useSSL=true&requireSSL=true&serverTimezone=America/Bogota&characterEncoding=utf8
DB_USER=${USUARIO_DB}
DB_PASSWORD=${DB_PASSWORD}
JWT_SECRET=${JWT_SECRET}
SERVER_PORT=8080
ENV
sudo chmod 600 /etc/productionfood.env

sudo tee /etc/systemd/system/productionfood.service >/dev/null <<UNIT
[Unit]
Description=ProductionFood API
After=network-online.target

[Service]
EnvironmentFile=/etc/productionfood.env
Environment=TZ=America/Bogota
ExecStart=/usr/bin/java -Xmx512m -Duser.timezone=America/Bogota -jar ${DESTINO}/${JAR}
Restart=on-failure
User=ec2-user

[Install]
WantedBy=multi-user.target
UNIT
sudo systemctl daemon-reload
sudo systemctl enable --now productionfood

echo "==> Esperando a que la API migre la base y responda"
for i in $(seq 60); do
  curl -fs -o /dev/null http://localhost:8080/v3/api-docs && break
  sleep 5
done
curl -s -o /dev/null -w "health: %{http_code}\n" http://localhost:8080/v3/api-docs

if [ -f scripts/seed/seed_datos.sql ] && [ "${CARGAR_SEED:-1}" = "1" ]; then
  echo "==> Seed de datos"
  clientes="$(MYSQL_PWD="$DB_PASSWORD" mysql -h "$DB_HOST" -u "$USUARIO_DB" --ssl-mode=REQUIRED -N -e "SELECT COUNT(*) FROM clientes" "$BASE")"
  if [ "$clientes" = "0" ]; then
    MYSQL_PWD="$DB_PASSWORD" mysql -h "$DB_HOST" -u "$USUARIO_DB" --ssl-mode=REQUIRED --default-character-set=utf8mb4 "$BASE" < scripts/seed/seed_datos.sql
    echo "Seed cargado."
  else
    echo "La base ya tiene ${clientes} clientes; no se carga la seed."
  fi
fi
echo "Listo: http://<IP publica de la EC2>:8080/swagger-ui.html"
