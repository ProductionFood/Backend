# Datos de la panadería

Base de datos de **demostración académica**: el contenido simula la operación de una panadería
colombiana (proveedores, clientes, inventario, producción, ventas y devoluciones) para poder
exponer el sistema con información realista. Los datos en sí no llevan marcas de prueba.

## Qué crea

| Entidad | Cantidad aprox. |
|---|---|
| Usuarios (8, uno o dos por rol) | 8 + admin inicial |
| Proveedores / clientes | 12 / 35 |
| Materias primas / productos / recetas | 38 / 34 / 29 (5 productos sin receta) |
| Compras (recibidas, pendientes, canceladas) | 41 (35 / 4 / 2), a lo largo de ~90 días |
| Lotes de materia prima (uno por línea recibida) | 112 |
| Planes de producción (30 completados, 4 en proceso, 3 planificados, 3 cancelados) | 40, a lo largo de ~60 días |
| Consumos reales de materia prima | ~1 000, con desviaciones de −3 %, 0 %, +5 % y +8 % |
| Lotes de producto terminado | ~126 (algunos vencen en los próximos días) |
| Pedidos / líneas | 150 / ~450, estados variados |
| Devoluciones (aprobadas, rechazadas, pendientes) | 16 (8 / 3 / 5) |

Quedan a propósito 4 materias primas bajo su stock mínimo (una agotada) para las alertas y el
dashboard.

Limitaciones de la API (no del generador): la fecha de los pedidos y de las devoluciones la
pone la base (`curdate()`), así que **todos los pedidos quedan con la fecha de hoy**; y un lote
de producto terminado no puede registrarse ya vencido.

## Regenerar (a través de la API)

Requiere la API en marcha sobre una base recién migrada y **sin datos** (solo el administrador
inicial). El generador solo usa la biblioteca estándar de Python 3.

```bash
python3 scripts/seed/generar_datos.py                 # usa http://localhost:8086
python3 scripts/seed/generar_datos.py --url http://localhost:8085 --semilla 2026
```

Usa una semilla fija (reproducible); las fechas son relativas al día en que se ejecuta. Si se
interrumpe, se puede relanzar: recuerda lo ya creado en `scripts/seed/.estado_generacion.json`
(se borra al terminar). Se niega a correr si la base ya tiene clientes o materias primas.

Después de regenerar, para actualizar `seed_datos.sql`:

```bash
{ echo "SET FOREIGN_KEY_CHECKS=0;"; docker exec productionfood-mysql mysqldump -uroot -p<clave> \
  --no-create-info --replace --complete-insert --skip-triggers --skip-comments --single-transaction \
  --ignore-table=productionfood.flyway_schema_history productionfood; echo "SET FOREIGN_KEY_CHECKS=1;"; } \
  > scripts/seed/seed_datos.sql
```

## Cargar el volcado SQL

Con la base **migrada y sin datos** (arranque la aplicación una vez para que Flyway aplique
V1–V3) y el contenedor `productionfood-mysql` en marcha:

```bash
scripts/seed/cargar_seed.sh
```

Variables opcionales: `CONTENEDOR`, `BASE`, `MYSQL_PWD` (clave de root) y `FORZAR=1` para cargar
sobre una base que ya tiene clientes. El volcado usa `REPLACE INTO`, así que las filas que ya
trae la migración (roles, unidades, administrador) se sobrescriben sin duplicarse. No contiene
la clave de root; los usuarios incluyen su hash BCrypt.

## Usuarios

Contraseña común de los usuarios nuevos: `Panaderia2026*`.

| Rol | Nombre | Correo |
|---|---|---|
| ADMIN (inicial) | Administrador Inicial | `admin@productionfood.local` (contraseña `Admin123*`) |
| ADMIN | Marcela Restrepo Gómez | `marcela.restrepo@productionfood.local` |
| PRODUCCION | Andrés Felipe Ocampo | `andres.ocampo@productionfood.local` |
| PRODUCCION | Luz Dary Cárdenas | `luz.cardenas@productionfood.local` |
| COMPRAS | Julián Esteban Vargas | `julian.vargas@productionfood.local` |
| COMPRAS | Paola Andrea Salazar | `paola.salazar@productionfood.local` |
| VENTAS | Camila Fernanda Ruiz | `camila.ruiz@productionfood.local` |
| VENTAS | Santiago Marín Herrera | `santiago.marin@productionfood.local` |
| CONSULTA | Natalia Quintero Duarte | `natalia.quintero@productionfood.local` |

## Fechas

El generador registra pedidos y devoluciones con la fecha del día (la API no permite otra).
`ajustar_fechas.sql` reparte los pedidos entregados y cancelados en los últimos ~60 días y mueve las
devoluciones tras su pedido; el volcado `seed_datos.sql` ya incluye ese ajuste. Si se regenera la
base con `generar_datos.py`, ejecutar después:

    docker exec -i productionfood-mysql mysql -uroot -plocal productionfood < scripts/seed/ajustar_fechas.sql

Las fechas del volcado son las del momento en que se generó (noviembre de 2026); cargado más
adelante, los datos parecerán antiguos.
