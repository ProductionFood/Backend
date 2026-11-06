#!/usr/bin/env bash
# Carga seed_datos.sql en la base de ProductionFood que corre en Docker.
#
# Precondicion: la base esta MIGRADA (la aplicacion arranco al menos una vez y Flyway aplico
# V1-V3, que ya traen el administrador inicial y los roles) y SIN datos de negocio.
# El volcado usa REPLACE INTO, de modo que las filas que ya existen (roles, unidades, admin)
# se sobrescriben con el mismo contenido.
#
# Variables opcionales:
#   CONTENEDOR  nombre del contenedor MySQL      (por defecto: productionfood-mysql)
#   BASE        nombre de la base                (por defecto: productionfood)
#   MYSQL_PWD   contrasena de root del contenedor (por defecto: la del entorno local de desarrollo)
#   FORZAR=1    carga aunque la base ya tenga clientes (sobrescribe filas con el mismo id)
set -euo pipefail

CONTENEDOR="${CONTENEDOR:-productionfood-mysql}"
BASE="${BASE:-productionfood}"
export MYSQL_PWD="${MYSQL_PWD:-local}"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL="${AQUI}/seed_datos.sql"

[ -f "$SQL" ] || { echo "No existe $SQL" >&2; exit 1; }

mysql_en_contenedor() {
  docker exec -i -e MYSQL_PWD="$MYSQL_PWD" "$CONTENEDOR" mysql -uroot --default-character-set=utf8mb4 "$@"
}

existentes="$(mysql_en_contenedor -N -e "SELECT COUNT(*) FROM ${BASE}.clientes" 2>/dev/null || true)"
[ -n "$existentes" ] || { echo "No se pudo leer ${BASE}.clientes: arranque la aplicacion una vez para migrar la base." >&2; exit 1; }
if [ "$existentes" != "0" ] && [ "${FORZAR:-0}" != "1" ]; then
  echo "La base ya tiene ${existentes} clientes. Use FORZAR=1 para cargar de todos modos." >&2
  exit 1
fi

echo "Cargando $SQL en ${CONTENEDOR}/${BASE} ..."
mysql_en_contenedor "$BASE" < "$SQL"
echo "Listo:"
mysql_en_contenedor -N -e "SELECT 'clientes',COUNT(*) FROM ${BASE}.clientes UNION ALL SELECT 'productos',COUNT(*) FROM ${BASE}.productos UNION ALL SELECT 'pedidos',COUNT(*) FROM ${BASE}.pedidos UNION ALL SELECT 'usuarios',COUNT(*) FROM ${BASE}.usuarios"
