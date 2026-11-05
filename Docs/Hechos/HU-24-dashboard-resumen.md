# HU-24 — Dashboard resumen · Backend verificado

| | |
|---|---|
| Historia | HU-24 — Dashboard con el resumen del día |
| Módulo | Reportes (`reporte`) |
| Endpoints | `GET /api/v1/dashboard`, `GET /api/v1/dashboard/pedidos-hoy`, `GET /api/v1/dashboard/vencimientos` |
| Fecha de implementación | 2026-11-05 |
| Colección Postman | `Docs/Postman/ProductionFood-HU24.postman_collection.json` (`Preparación`, `Dashboard (CP-…)`, `Pedidos de hoy`, `Vencimientos`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Pull requests | #167 `GET /dashboard` · #168 `/dashboard/pedidos-hoy` y `/dashboard/vencimientos` · #169 corrección de zona horaria (D-01) · PR docs · PR final |
| Estado QA | **QA ciego verificado · CP-01…CP-21 pasan (CP-02 verificado tras la corrección de D-01) y SEC-01…SEC-05 pasan en los 3 endpoints (SEC-04 no aplica en `/dashboard`) · frontend pendiente** |

> Esta ficha documenta el dashboard del día: cinco tarjetas (pedidos de hoy, compras pendientes,
> stock bajo, producción de hoy y lotes por vencer) en una sola llamada, más dos detalles
> paginados. Esta historia solo lee: no escribe, no audita y no mueve stock. Reutiliza las alertas
> de HU-27 para la tarjeta de stock bajo. El contrato práctico para el frontend está en el manual
> [`../Manuales_Consumo_Frontend/HU-24-dashboard-resumen.md`](../Manuales_Consumo_Frontend/HU-24-dashboard-resumen.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/dashboard?diasVencimiento=7` | Cualquier rol autenticado | 200/400/401 | Resumen del día con las cinco tarjetas, `fecha` y `generadoEn` de la base de datos |
| GET | `/api/v1/dashboard/pedidos-hoy` | ADMIN, VENTAS, CONSULTA | 200/400/401/403 | Pedidos de hoy paginados, con cliente, estado, fechas y total propio; filtro `estado` y `sort` de lista blanca |
| GET | `/api/v1/dashboard/vencimientos` | ADMIN, VENTAS, PRODUCCION, CONSULTA | 200/400/401/403 | Todos los lotes de producto terminado con stock que vencen dentro del horizonte, vencidos incluidos; paginado |

**Roles.** El resumen raíz lo consulta cualquier rol autenticado: el filtrado de tarjetas por rol
(R-05) es de presentación y lo hace el cliente. Los dos detalles sí restringen: `pedidos-hoy` es
de `ADMIN`, `VENTAS` y `CONSULTA` (`COMPRAS` y `PRODUCCION` reciben `403 SIN_PERMISO`);
`vencimientos` es de `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA` (`COMPRAS` recibe
`403 SIN_PERMISO`).

**Solo lectura y sin caché.** Los tres recursos solo admiten `GET` (más `HEAD`/`OPTIONS` del
framework); `POST`, `PUT`, `PATCH` y `DELETE` responden `405 METODO_NO_PERMITIDO`. Cada llamada
consulta la base: un cambio se refleja de inmediato.

Piezas internas:

| Pieza | Rol |
|---|---|
| `DashboardRepository` | JDBC de las tarjetas y de los dos detalles: pedidos y planes de hoy, compras pendientes, lotes de producto terminado por vencer; la fecha y la hora salen de la base |
| `DashboardService` | Valida `diasVencimiento` (0 a 365), `page`, `size` y `sort`; arma las tarjetas y toma el stock bajo de `AlertaService.listar(false)` |
| `DashboardController` | Los tres endpoints con `@PreAuthorize` y Swagger; solo despacha al servicio |
| DTO de respuesta | `DashboardResponse` y sus tarjetas (`PedidosHoyResponse`, `ComprasPendientesResponse`, `StockBajoResponse`, `ProduccionHoyResponse`, `PorVencerResponse`), más las filas `PedidoHoyResponse` y `VencimientoResponse` |

**Sin migración Flyway nueva.** Se reutilizan `pedidos`, `detalle_pedido`, `compras`, `detalle_compra`,
`planes_produccion`, `detalle_plan_produccion`, `lotes`, `inventario_producto_terminado` y la vista
`v_inventario_materia_prima` (vía HU-27), con los índices ya existentes. El único cambio de
configuración es `spring.datasource.hikari.connection-init-sql` (PR #169, ver D-01).

## Reglas de negocio implementadas

- **R-01 · «hoy» es el día de la base de datos, en America/Bogota (-05:00).** `fecha` y `generadoEn`
  salen de la base (`curdate()`/`now()`), sin `LocalDate.now` de Java. Para que ese día sea el de
  Colombia en todas las conexiones, el pool fija `SET time_zone = '-05:00'` al abrir cada una
  (D-01).
- **R-02 · pedidos de hoy.** `cantidad` cuenta todos los pedidos con `fecha_pedido` de hoy,
  **cancelados incluidos**, y es la suma de `porEstado`, que siempre trae las cuatro claves
  (`PENDIENTE`, `EN_PREPARACION`, `ENTREGADO`, `CANCELADO`). `total` es lo vendido: `SUM(cantidad
  × precio_unitario)` del detalle y **excluye los cancelados** (R-06 de la especificación).
- **R-03 · compras pendientes sin filtro de fecha.** Cuenta las compras en estado `PENDIENTE` de
  cualquier fecha (las canceladas y las recibidas no cuentan). `masAntigua` es la de menor fecha
  (a igual fecha, la de menor id) con `diasPendiente` medido contra el día de la base; es `null`
  si no hay pendientes.
- **R-04 · stock bajo = alertas de HU-27.** Viene de `AlertaService.listar(false)`, la misma fuente
  que `/inventario/alertas`: mismas materias primas, mismo orden (`AGOTADO` primero y mayor
  faltante primero) y mismos números. `cantidad` y `agotadas` se derivan de la lista completa.
- **R-05 · producción de hoy.** `cantidad` cuenta todos los planes con `fecha_produccion` de hoy,
  cancelados incluidos; `porEstado` trae las cuatro claves (`PLANIFICADO`, `EN_PROCESO`,
  `COMPLETADO`, `CANCELADO`). `avancePromedio` (escala 2) es el promedio del avance por plan de los
  **no cancelados**.
- **R-06 · por vencer solo con stock.** Lotes de **producto terminado** con `cantidad_disponible >
  0` cuyo vencimiento es hoy más `diasVencimiento` o antes, **vencidos incluidos**
  (`diasParaVencer` negativo). Los lotes de materia prima no entran. El rango es inclusivo
  (vence en hoy + 7 entra; hoy + 8 no).
- **R-07 · `items` de `porVencer` con tope de 10.** En `/dashboard` la lista trae como máximo los
  10 lotes más próximos a vencer; `cantidad` y `vencidos` cuentan todos. El detalle completo y
  paginado está en `/dashboard/vencimientos`.
- **R-08 · `diasVencimiento` de 0 a 365, 7 por defecto.** Fuera de rango o no numérico responde
  `400 PARAMETRO_INVALIDO`; vacío (`?diasVencimiento=`) toma el valor por defecto.
- **R-09 · detalles paginados.** `page` desde 0, `size` de 1 a 100 (20 por defecto). `sort`
  admite `idPedido`, `cliente`, `estado` y `total` en `pedidos-hoy` (por defecto `idPedido,desc`), y
  `vence`, `producto` y `disponible` en `vencimientos` (por defecto `vence,asc`). El `total` de
  cada fila de `pedidos-hoy` es el del propio pedido (0.00 sin líneas) y se muestra también en los
  cancelados.

## Decisiones clave

| Tema | Decisión implementada |
|---|---|
| Cantidad de pedidos y planes | Incluye `CANCELADO` (es la suma de `porEstado`). El total de pedidos excluye los cancelados. Para CP-10/CP-11 la especificación hablaba de «2 planes»; con un cancelado de por medio `cantidad` es 3 |
| `avancePromedio` | Promedio del avance por plan de los no cancelados. Un plan sin detalle cuenta 0 % y la sobreproducción no se acota a 100 (D-04, a confirmar con negocio) |
| Por vencer | Solo producto terminado con stock; incluye vencidos; `items` máximo 10 en `/dashboard` y `cantidad`/`vencidos` cuentan todos |
| Stock bajo sin tope | `stockBajo.items` no se acota, para coincidir exactamente con `/inventario/alertas` (CP-21 de HU-27). Con muchas alertas la tarjeta pesa más; el cliente puede mostrar los primeros N (D-02) |
| Un solo origen de «hoy» | `fecha` y `generadoEn` son de la base; no se usa `LocalDate.now()` ni la zona de Java |
| Zona del pool | `connection-init-sql: SET time_zone = '-05:00'` en las diez conexiones (D-01) |
| Roles | El resumen raíz es abierto a cualquier autenticado; el filtrado por rol es del cliente (R-05 de la especificación); los detalles restringen en el backend |
| Sin caché ni migración | Se calcula en cada llamada con los índices existentes; sin tablas ni columnas nuevas |
| Solo `GET` | Los demás métodos responden `405 METODO_NO_PERMITIDO`; sin escrituras ni auditoría |
| Filtro `estado` | Solo los valores canónicos en mayúsculas; `pendiente` o un valor desconocido responde `400 PARAMETRO_INVALIDO` |
| Importes | Se serializan como número JSON (`284500.0`); el cliente los formatea |

## Modelo de datos

**Sin tablas nuevas.** El dashboard se deriva de:

| Origen | Uso |
|---|---|
| `pedidos` / `detalle_pedido` / `clientes` | Pedidos de hoy, `porEstado`, total y detalle (índice `idx_pedidos_fecha`) |
| `compras` / `detalle_compra` | Compras pendientes, total y la más antigua |
| `planes_produccion` / `detalle_plan_produccion` | Planes de hoy y avance promedio (índice `idx_planes_fecha`) |
| `lotes` / `inventario_producto_terminado` / `productos` | Lotes de producto terminado por vencer con stock (índice `idx_lotes_vencimiento`) |
| Vista `v_inventario_materia_prima` (vía `AlertaService`) | Stock bajo, igual que HU-27 |

## Contrato resumido

**GET /api/v1/dashboard?diasVencimiento=7 → 200**:

```json
{ "fecha": "2026-11-05",
  "generadoEn": "2026-11-05T15:06:21.318",
  "pedidosHoy": { "cantidad": 7, "total": 284500.00,
    "porEstado": { "PENDIENTE": 3, "EN_PREPARACION": 2, "ENTREGADO": 2, "CANCELADO": 0 } },
  "comprasPendientes": { "cantidad": 2, "total": 620000.00,
    "masAntigua": { "idCompra": 10, "fecha": "2026-10-20", "diasPendiente": 16 } },
  "stockBajo": { "cantidad": 2, "agotadas": 1,
    "items": [
      { "nombre": "Levadura", "disponible": 0.00, "minimo": 5.00,
        "faltante": 5.00, "nivel": "AGOTADO" },
      { "nombre": "Mantequilla", "disponible": 4.50, "minimo": 20.00,
        "faltante": 15.50, "nivel": "BAJO" } ] },
  "produccionHoy": { "cantidad": 2,
    "porEstado": { "PLANIFICADO": 1, "EN_PROCESO": 1, "COMPLETADO": 0, "CANCELADO": 0 },
    "avancePromedio": 45.50 },
  "porVencer": { "cantidad": 2, "vencidos": 1,
    "items": [
      { "codigoLote": "CR-2026-0914", "producto": "Croissant", "vence": "2026-11-03",
        "diasParaVencer": -2, "disponible": 8.00 },
      { "codigoLote": "PF-2026-0917", "producto": "Pan francés", "vence": "2026-11-08",
        "diasParaVencer": 3, "disponible": 40.00 } ] } }
```

Sin compras pendientes, `masAntigua` llega como `null` (es la única clave que se serializa
nula). Con base vacía todas las cifras son 0 y las listas `[]`.

**GET /api/v1/dashboard/pedidos-hoy?estado=PENDIENTE&page=0&size=20&sort=total,desc → 200**:

```json
{ "content": [
    { "idPedido": 41, "cliente": "Panadería La Esquina", "estado": "PENDIENTE",
      "fechaPedido": "2026-11-05", "fechaEntrega": null, "total": 84500.00 } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true }
```

**GET /api/v1/dashboard/vencimientos?diasVencimiento=7&page=0&size=20&sort=vence,asc → 200**:

```json
{ "content": [
    { "idLote": 17, "codigoLote": "CR-2026-0914", "producto": "Croissant",
      "vence": "2026-11-03", "diasParaVencer": -2, "disponible": 8.00 } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true }
```

**Errores:** `PARAMETRO_INVALIDO` (400: `diasVencimiento` fuera de 0..365 o no numérico, `estado` no
canónico, `page`/`size` inválidos); `CAMPO_ORDEN_INVALIDO` (400: `sort` fuera de la lista
blanca); `NO_AUTENTICADO` (401); `SIN_PERMISO` (403); `METODO_NO_PERMITIDO` (405).

## Archivos creados

```
src/main/java/co/edu/corposucre/productionfood/reporte/
├── DashboardController.java
├── DashboardService.java
├── DashboardRepository.java
└── dto/
    ├── DashboardResponse.java
    ├── PedidosHoyResponse.java
    ├── ComprasPendientesResponse.java
    ├── StockBajoResponse.java
    ├── ProduccionHoyResponse.java
    ├── PorVencerResponse.java
    ├── PedidoHoyResponse.java
    └── VencimientoResponse.java

src/main/resources/application.yml            (hikari.connection-init-sql, PR #169)

src/test/java/co/edu/corposucre/productionfood/reporte/
├── DashboardRepositoryTest.java
├── DashboardServiceTest.java
├── DashboardControllerTest.java
├── DashboardSeguridadTest.java
└── ZonaHorariaConexionTest.java
```

## Evidencia (pruebas backend)

Las pruebas cubren el repositorio JDBC contra MySQL (cada tarjeta, los bordes del rango de
vencimiento, el lote sin stock y el lote vencido), las reglas del servicio (cancelados, avance
promedio, tope de 10 ítems, validación de parámetros), el contrato HTTP, la matriz de roles y
seguridad, y la configuración de zona horaria del pool.

`mvn test` sobre la suite completa: **2075 tests, 0 fallos**. Antes de la corrección de la
zona horaria la suite completa daba 2074 tests sin fallos; el PR #169 añade
`ZonaHorariaConexionTest`.

## Smoke y QA ciego

QA ciego contra la API real en `:8086` y MySQL (esquema aislado `pf_hu24_qa`), solo por HTTP y
SQL:

- **CP-01…CP-21: 20 PASS y CP-02 verificado tras la corrección de D-01.** Con la hora local de
  las 15:xx, `fecha` coincidió con `curdate()`. La simulación de las 20:00 hora de Colombia
  (`SET timestamp`) dio el día correcto con la sesión en -05:00 y el día siguiente con la sesión
  en +00:00; tras el PR #169 todas las conexiones del pool quedan en -05:00.
- **Cruce con SQL a volumen** (126 pedidos hoy, total 2512500, 81 compras pendientes, avance
  46,53, 22 lotes por vencer con 10 vencidos y 10 ítems): todo coincide con las consultas
  directas.
- **SEC-01…SEC-05 en los 3 endpoints: 14 PASS y SEC-04 no aplica en `/dashboard`** (cualquier rol
  autenticado accede). `COMPRAS` y `PRODUCCION` reciben `403 SIN_PERMISO` en `pedidos-hoy`, y
  `COMPRAS` en `vencimientos`. SEC-03 se probó con un token forjado con el secreto del servidor
  (expirado hace 1 hora y hace 1 segundo; un token forjado vigente sí da `200`). Además, el rol sale
  de la base y no del claim del token, un usuario inactivo recibe `403 USUARIO_INACTIVO` y un token
  válido de un usuario inexistente recibe `401`.
- **CP-17 (rendimiento):** todos los endpoints por debajo de 50 ms con 512 pedidos (1514
  detalles), 208 compras, 112 lotes con inventario y 103 planes (mediana de `/dashboard` 8 ms; 100
  peticiones con 20 hilos, p50 25 ms y máximo 42 ms). El límite es 2 s.
- **CP-21 de HU-27 (coherencia dashboard vs `/inventario/alertas` y `/resumen`): PASS en 6
  estados**, con volumen (150 materias primas en alerta, empates incluidos) y sin caché: misma
  cantidad, agotadas, orden y cifras.
- **Validaciones adicionales:** `diasVencimiento` (−1, 366, `abc`, `1.5` → `400
  PARAMETRO_INVALIDO`; 0 y 365 → `200`), `page`/`size`, `sort` y `estado` inválidos con error
  JSON estándar, rutas inexistentes (`404 RECURSO_NO_ENCONTRADO`) y métodos de escritura
  (`405`).
- **D-01 re-verificado tras el PR #169:** 180 de 180 peticiones concurrentes con `generadoEn` en
  hora local y las 10 conexiones del pool en -05:00.

### Defectos del QA

| ID | Severidad | Hallazgo | Resolución |
|---|---|---|---|
| D-01 | ALTA | Una conexión del pool operaba con `time_zone = +00:00`: `generadoEn` salía en UTC en ~10 % de las peticiones y `curdate()` devolvía el día siguiente a partir de las 7 p.m. de Colombia | Corregido en el PR #169 con `hikari.connection-init-sql: SET time_zone = '-05:00'` |
| D-02 | BAJA | `stockBajo.items` sin tope (150 ítems con 150 alertas) | Se mantiene sin tope a propósito: coincide con `/inventario/alertas`; el cliente puede mostrar los primeros N |
| D-03 | BAJA | Los `401` sin token o con token inválido traen el mensaje «Su sesión ha expirado» | Transversal y preexistente, fuera de HU-24 |
| D-04 | INFO | Un plan sin detalle cuenta 0 % y la sobreproducción (30/10) aporta 300 % al `avancePromedio` | Decisión documentada; confirmar con negocio |
| D-05 | INFO | Importes como `284500.0` (escala de la serialización JSON, transversal); filtro `estado` sensible a mayúsculas y su mensaje no lista los valores válidos; `page` fuera de rango devuelve `200` vacío | Comportamiento documentado; el cliente formatea |
| D-06 | INFO | La consulta de compras pendientes recorre el índice `(fecha, estado)` completo | Aceptable con el volumen actual (208 filas, < 50 ms); revisar a gran escala |

**Efecto transversal de D-01.** La conexión con la zona equivocada también escribía fechas:
`bitacora.fecha`, `inventario.fecha_actualizacion` e `inventario_producto_terminado.fecha_ingreso_estante`
quedaron con horas mezcladas (15:xx y 20:xx en el mismo minuto) en la base de QA. La corrección
evita nuevas filas con la hora equivocada, pero **no repara el dato histórico**.

## Desviaciones vs especificación

- **`cantidad` de pedidos y planes incluye los cancelados.** CP-10/CP-11 de la especificación
  esperaban «2 planes» y «aparte en `porEstado`»; la implementación cuenta los cancelados en
  `cantidad` y los muestra en `porEstado.CANCELADO`. El total de pedidos sí excluye los cancelados.
- **`porEstado` trae siempre las cuatro claves.** El ejemplo de la especificación omitía
  `CANCELADO` en `produccionHoy`.
- **`generadoEn` y `diasVencimiento`.** La especificación no los definía; el JSON añade
  `generadoEn` (hora de la base) y el parámetro `diasVencimiento` fija el horizonte (7 por
  defecto, 0 a 365).
- **Importes.** El ejemplo mostraba `284500.00`; el JSON real sale como número (`284500.0`).

## Pendientes / deuda

- **Frontend pendiente.** No existe la pantalla del dashboard; el manual de consumo es el contrato
  para Angular, incluido el filtrado de tarjetas por rol (R-05).
- **`EXPLAIN` no ejecutado por el desarrollo.** No hay cliente `mysql` en el entorno de
  implementación; el QA midió tiempos por debajo de 50 ms y comprobó el uso de los índices.
- **Datos históricos con hora mezclada por D-01.** Las filas de bitácora, inventario y lotes
  escritas por la conexión en UTC quedan como están; es una deuda transversal.
- **`generadoEn` de `/inventario/alertas`.** Lo calcula `AlertaService` con `LocalDateTime.now()`
  de Java, mientras `ultimaActualizacion` y las fechas del dashboard son de la base. Con la
  corrección del pool, la base queda en -05:00; ambas horas coinciden **solo si la JVM corre en
  America/Bogota** (o en -05:00). Si el contenedor o el servidor de Java corre en UTC, `generadoEn`
  de las alertas seguirá 5 horas adelantado. El código no fija la zona de la JVM.
- **`stockBajo.items` sin tope** (D-02) y **`avancePromedio`** (D-04): decisiones a confirmar con
  negocio si el volumen o el criterio cambian.
- **Mensaje de los `401`** (D-03) y **serialización de importes** (D-05): transversales.
- **Datos de QA en la base.** Quedaron usuarios y registros de prueba locales; no forman parte del
  negocio ni viajan con el repositorio.
