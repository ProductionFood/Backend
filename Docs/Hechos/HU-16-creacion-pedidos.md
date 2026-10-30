# HU-16 — Creación de pedidos · Backend verificado

| | |
|---|---|
| Fecha de implementación | 2026-10-30 |
| Commits / PR | PRs #108–#111 a `HU-16/creacion-pedidos`; este cambio añade la documentación (PR5) |
| Colección Postman | `Docs/Postman/ProductionFood-HU16.postman_collection.json` (`Preparación`, `Creación (CP-01…CP-10)`, `Transiciones de estado (CP-11…CP-15, CP-18)`, `Cancelación y devolución de stock (CP-16…CP-17)`, `Edición de cabecera (CP-19…CP-20)`, `Cliente desactivado (CP-21)`, `Roles y consulta (CP-22…CP-23)`, `Seguridad (SEC-01…SEC-05)`) |
| Estado QA | **Backend verificado en caliente 2026-10-30 · frontend pendiente** |

> Esta ficha documenta la cabecera del pedido y su máquina de estados. El detalle del pedido
> (las líneas que descuentan stock) es de HU-17; la devolución posterior a la entrega es de
> HU-18. La verificación en caliente se ejecutó el 2026-10-30 contra la API real en `:8085` y
> MySQL 8.4, y la suite completa de pruebas quedó en verde. Los ejemplos salen de los DTO
> reales y de la especificación; los ids dependen de la base. El contrato de consumo está en el
> manual [`../Manuales_Consumo_Frontend/HU-16-creacion-pedidos.md`](../Manuales_Consumo_Frontend/HU-16-creacion-pedidos.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/pedidos` | ADMIN, VENTAS, PRODUCCION, CONSULTA | 200/400/401/403 | Listado paginado con filtros `idCliente`, `estado`, `fechaInicio` y `fechaFin` combinados con `AND`, orden de lista blanca y totales derivados del detalle |
| GET | `/api/v1/pedidos/{id}` | ADMIN, VENTAS, PRODUCCION, CONSULTA | 200/400/401/403/404 | Cabecera del pedido con su cliente embebido, totales derivados y bandera `editable` |
| POST | `/api/v1/pedidos` | ADMIN, VENTAS | 201/400/401/403/409 | Alta de la cabecera en estado `PENDIENTE`; `Location: /api/v1/pedidos/{id}` |
| PUT | `/api/v1/pedidos/{id}` | ADMIN, VENTAS | 200/400/401/403/404/409 | Edición de cliente y fecha de entrega solo mientras el pedido está `PENDIENTE` |
| PATCH | `/api/v1/pedidos/{id}/estado` | ADMIN, VENTAS | 200/400/401/403/404/409 | Transición de la máquina de estados; `CANCELADO` devuelve el stock de las líneas vendidas |

**Roles.** Lectura para `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`. Escritura (alta, edición de
cabecera y transiciones) solo para `ADMIN` y `VENTAS`. `COMPRAS` no participa del módulo: recibe
`403 SIN_PERMISO` en los cinco endpoints. No existe `DELETE` y no debe añadirse: un pedido
referencia a su cliente y su estado es el registro de la venta; el cierre correcto es cancelarlo,
que además devuelve el stock comprometido.

Piezas internas:

| Pieza | Rol |
|---|---|
| `EstadoPedido` | Enum canónico (`PENDIENTE`, `EN_PREPARACION`, `ENTREGADO`, `CANCELADO`) y máquina de transiciones en un `Map` inmutable; `puedeTransicionarA` y `transicionesValidas` |
| `Pedido` | POJO de la cabecera con `nombreCliente`, `totalPedido` y `cantidadItems` como proyecciones derivadas, no columnas de la tabla |
| `PedidoRowMapper` | Traduce la fila del `JOIN` con `clientes` y fija el total a escala 2 (`HALF_UP`) |
| `PedidoRepository` | JDBC con `SELECT` compartido por listado y detalle, filtros parametrizados, `INSERT` sin `fecha_pedido`, `UPDATE` condicionado al estado y consulta de líneas a devolver |
| `PedidoService` | Reglas de negocio: cliente existente y activo, fecha de entrega, edición solo en `PENDIENTE`, transición y devolución de stock transaccional |
| `PedidoController` | Contrato HTTP, `@PreAuthorize` y despacho del `PATCH` por estado destino |
| `LineaDevolucion` | Par `(idLote, cantidad)` que se repone al cancelar; se lee del kardex, no de `detalle_pedido` |
| `CrearPedidoRequest` / `ActualizarPedidoRequest` | Solo `idCliente` obligatorio y `fechaEntrega` opcional; no aceptan `fechaPedido` ni `estado` |
| `CambiarEstadoPedidoRequest` | Estado destino como enum canónico; un literal no canónico falla en la deserialización con `400 VALIDACION_FALLIDA` |
| `PedidoResponse` / `PedidoClienteRefResponse` | Respuesta con el cliente embebido, `estado` como `String`, totales derivados y `editable` |
| `InventarioPtService.registrarEntrada` | Único camino autorizado para modificar `inventario_producto_terminado`; bloquea la fila, actualiza el saldo e inserta el movimiento de kardex en la transacción del llamador |
| `ClienteRepository.existeActivo` | Valida CA-01 sin duplicar la regla de estado del cliente |

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| R-01 | **La fecha del pedido la pone la base.** El `INSERT` solo escribe `id_cliente`, `estado` y `fecha_entrega`; la columna `fecha_pedido` tiene `DEFAULT (curdate())` desde V2 §C-07 y la aplicación nunca la envía. `CrearPedidoRequest` no tiene el campo, así que un `fechaPedido` en el cuerpo se ignora. La conexión fija `serverTimezone=America/Bogota` y Jackson usa esa misma zona, de modo que la fecha de la base y la del servidor coinciden |
| R-02 | **Máquina de estados única en el enum.** `PENDIENTE → EN_PREPARACION \| CANCELADO`; `EN_PREPARACION → ENTREGADO \| CANCELADO`; `ENTREGADO` y `CANCELADO` no admiten más transiciones (ni siquiera sobre sí mismos). El servicio valida la transición **antes** de tocar inventario y el controlador despacha el `PATCH` por destino: `PENDIENTE` no es destino de nadie y responde `409 TRANSICION_INVALIDA` |
| R-03 | **El stock sale al agregar la línea, no al entregar.** HU-16 no descuenta inventario al crear ni al cambiar de estado; la salida `SALIDA_VENTA` es de HU-17. Cancelar es el disparador de la devolución, no el alta |
| R-04 | **Cancelar devuelve el stock al lote de origen.** Por cada `SALIDA_VENTA` del kardex ligada a una línea del pedido, `cambiarEstado` llama a `InventarioPtService.registrarEntrada(idLote, cantidad, ENTRADA_DEVOLUCION, "pedidos", idPedido, …)`. Si una línea consumió de varios lotes, se repone a cada uno: la devolución es por movimiento de salida, no por línea agregada. Todo ocurre en la misma transacción que el cambio de estado |
| R-05 | **La cabecera solo se edita en `PENDIENTE`.** `PUT` responde `409 PEDIDO_NO_EDITABLE` en cualquier otro estado. `esEditable(id)` expone la misma regla para que HU-17 no toque el detalle de un pedido en preparación o cerrado. La respuesta trae `editable` ya calculado |
| R-06 | **La entrega puede ser hoy o futura; nunca pasada.** `fechaEntrega` es opcional; si viene, no puede ser anterior a hoy (`400 FECHA_ENTREGA_INVALIDA`). Hoy y cualquier fecha futura se aceptan. La validación usa `LocalDate.now()` del servidor de aplicación (ver desviaciones) |
| R-07 | **La actividad del cliente solo se exige al crear o editar.** Un pedido ya registrado sigue su curso aunque el cliente se desactive. La comprobación es `existeActivo`; un cliente inactivo en el alta responde `409 CLIENTE_INACTIVO` y uno inexistente `409 CLIENTE_INEXISTENTE` |

## Modelo de datos

**Sin migración Flyway nueva.** La historia usa el esquema existente:

- `pedidos`: `id_cliente` con llave foránea a `clientes`, `fecha_pedido` con
  `DEFAULT (curdate())` (V2 §C-07), `fecha_entrega` nullable, `estado` con
  `CHECK ck_pedidos_estado` (V2 §C-12) e índice `idx_pedidos_fecha (fecha_pedido, estado)`.
- `detalle_pedido`: `cantidad`, `precio_unitario` añadido en V2 §C-01, único
  `(id_pedido, id_producto)` y comprobaciones de cantidad y precio. HU-16 solo lo lee para
  calcular totales y para encontrar las líneas de una devolución; las escrituras del detalle
  son de HU-17.
- `movimientos_inventario_pt`: kardex por lote (V2 §C-04) con
  `tipo_movimiento`, `cantidad`, `saldo_anterior`, `saldo_posterior`, `tabla_origen`,
  `id_origen` e índices por lote y por origen.
- `inventario_producto_terminado`: saldo por lote que `InventarioPtService` bloquea y actualiza.

`totalPedido` y `cantidadItems` **no son columnas de `pedidos`**: la consulta los calcula con dos
subconsultas correlacionadas sobre `detalle_pedido` (`SUM(cantidad × precio_unitario)` y
`COUNT(*)`) y `COALESCE(…, 0)` para un pedido recién creado. Por eso el alta responde `0.00` y
`0` hasta que HU-17 agregue líneas.

## Contrato resumido

**POST /api/v1/pedidos → 201** (`Location: /api/v1/pedidos/45`):

```json
{ "idCliente": 7, "fechaEntrega": "2026-11-02" }
```

```json
{ "idPedido": 45,
  "cliente": { "idCliente": 7, "nombre": "Tienda La Esquina" },
  "fechaPedido": "2026-10-30", "fechaEntrega": "2026-11-02",
  "estado": "PENDIENTE", "totalPedido": 0.00, "cantidadItems": 0,
  "editable": true }
```

Sin `fechaEntrega`, el campo no se serializa (la configuración global omite los `null`); no
llega como `"fechaEntrega": null`. El cuerpo del `POST` no acepta `fechaPedido` ni `estado`: la
fecha la pone la base y el estado inicial siempre es `PENDIENTE`.

**GET /api/v1/pedidos?estado=PENDIENTE&fechaInicio=2026-10-01&fechaFin=2026-10-31&page=0&size=20&sort=fechaPedido,desc → 200**
(`PageResponse<PedidoResponse>`):

```json
{
  "content": [
    { "idPedido": 45,
      "cliente": { "idCliente": 7, "nombre": "Tienda La Esquina" },
      "fechaPedido": "2026-10-30", "fechaEntrega": "2026-11-02",
      "estado": "PENDIENTE", "totalPedido": 0.00, "cantidadItems": 0,
      "editable": true }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

Filtros opcionales y acumulativos: `idCliente`, `estado`, `fechaInicio` y `fechaFin` (rango
inclusivo sobre `fecha_pedido`). Orden de lista blanca: `fechaPedido`, `fechaEntrega`, `estado` e
`id`; por defecto `fechaPedido,desc`, con desempate estable por `id_pedido DESC`. Un rango
invertido responde `400 RANGO_FECHAS_INVALIDO`, un campo de orden fuera de la lista
`400 CAMPO_ORDEN_INVALIDO` y un `estado` o una fecha mal formados `400 PARAMETRO_INVALIDO`.

**PUT /api/v1/pedidos/45 → 200**:

```json
{ "idCliente": 7, "fechaEntrega": "2026-11-05" }
```

**PATCH /api/v1/pedidos/45/estado → 200**:

```json
{ "estado": "EN_PREPARACION" }
```

**Errores:** `CLIENTE_INEXISTENTE` (409, alta y edición); `CLIENTE_INACTIVO` (409, alta y
edición); `FECHA_ENTREGA_INVALIDA` (400, entrega anterior a hoy); `PEDIDO_NO_EDITABLE` (409,
`PUT` fuera de `PENDIENTE`); `TRANSICION_INVALIDA` (409, salto o destino prohibido);
`RECURSO_NO_ENCONTRADO` (404, id inexistente); `VALIDACION_FALLIDA` (400, cuerpo inválido,
`idCliente` ausente o estado no canónico); `PARAMETRO_INVALIDO`, `CAMPO_ORDEN_INVALIDO` y
`RANGO_FECHAS_INVALIDO` (400, consulta); `NO_AUTENTICADO` (401); `SIN_PERMISO` y
`USUARIO_INACTIVO` (403).

## Máquina de estados

```
PENDIENTE ──▶ EN_PREPARACION ──▶ ENTREGADO
    │                │
    └────────────────┴──────────▶ CANCELADO
```

| Estado actual | Destinos permitidos | Efecto |
|---|---|---|
| `PENDIENTE` | `EN_PREPARACION`, `CANCELADO` | `CANCELADO` devuelve el stock de las líneas vendidas |
| `EN_PREPARACION` | `ENTREGADO`, `CANCELADO` | `CANCELADO` devuelve el stock de las líneas vendidas |
| `ENTREGADO` | ninguno | Terminal; una devolución posterior es HU-18 |
| `CANCELADO` | ninguno | Terminal; no se reactiva |

El `PATCH` acepta el enum como literal exacto. `PENDIENTE` no es destino de ninguna transición y
responde `409 TRANSICION_INVALIDA` con el estado actual y los destinos disponibles. Un salto
(`PENDIENTE → ENTREGADO`) o una vuelta desde `ENTREGADO` también responden 409 y **no** mueven
inventario: la validación ocurre antes de la devolución.

## Trazabilidad lote ↔ línea y doble devolución

La tabla `detalle_pedido` no guarda de qué lote salió cada línea. Esa trazabilidad vive en el
kardex de producto terminado, que HU-17 alimenta con un `SALIDA_VENTA` por lote consumido y con
`tabla_origen = 'detalle_pedido'` e `id_origen = id_detalle_pedido` (R-10 de HU-17). Al cancelar,
`PedidoRepository.lineasParaDevolucion` recorre esos movimientos y devuelve exactamente lo que
hay que reponer, sin reconstruir el reparto FEFO en memoria. Solo considera los `SALIDA_VENTA`
del pedido: las entradas de una cancelación anterior no se vuelven a contar.

El cambio de estado se escribe con un `UPDATE` condicionado al estado leído:

```sql
UPDATE pedidos SET estado = :nuevo
WHERE id_pedido = :id AND estado = :esperado
```

El servicio ejecuta ese `UPDATE` **antes** de devolver stock y aborta con `409` si afecta cero
filas. El bloqueo de fila de InnoDB se mantiene hasta el commit, de modo que una segunda
cancelación concurrente encuentra el pedido ya en `CANCELADO`, actualiza cero filas y no repone
el stock dos veces. Un pedido sin líneas vendidas se cancela con `200` y no genera ningún
movimiento (CP-18).

## Evidencia (pruebas backend)

- **Suite completa:** `mvn -q test` con **exit code 0** y **1221 pruebas, 0 fallos, 0 errores y
  0 omitidas** (informes de Surefire, ejecutado el 2026-10-30 en `HU-16/pr5-docs`). El test de
  integración de cancelación corre contra MySQL y solo se omite si la base del `docker-compose`
  no responde; en esta ejecución se ejecutó.
- **Pruebas de la HU:** **104 pruebas** repartidas en
  `PedidoServiceTest` (27), `PedidoSeguridadTest` (45, incluidas las parametrizadas de
  SEC-01…SEC-05 sobre los cinco endpoints), `PedidoControllerTest` (21),
  `PedidoRepositoryTest` (10) y `PedidoCancelacionIntegrationTest` (1).
- **Cobertura de las reglas:** R-01 (el `INSERT` no envía `fecha_pedido`), R-02 (transiciones
  válidas y saltos prohibidos), R-04 (dos lotes repuestos y dos `ENTRADA_DEVOLUCION` con origen
  `pedidos`), R-05 (edición solo en `PENDIENTE`), R-06 (entrega de hoy válida y de ayer
  rechazada), R-07 (alta con cliente inactivo rechazada), CP-18 (cancelación sin líneas), la
  carrera del `UPDATE` condicional y la matriz de roles de lectura y escritura.
- El estado de QA va en la fila `Estado QA` de la cabecera, no aquí.

## QA ciego (2026-10-30)

Ejecutado contra la API viva en `:8085` y MySQL 8.4, sin leer el código Java ni la colección
Postman; solo especificación, tarea de QA y contrato.

| Batería | Resultado |
|---|---|
| CP-01…CP-23 | 23/23 ejecutados · 22 PASA · 1 PASA con salvedad (CP-21, por la regla configurable de HU-05) |
| SEC-01…SEC-05 × 5 endpoints | 25/25 PASA (SEC-04 confirma `@EnableMethodSecurity`) |
| Casos adversariales propios | 26/26 PASA (ids no numéricos, `size`/`page` fuera de rango, `sort` inválido, rango invertido, cuerpo vacío, campos desconocidos, doble cancelación y cancelación concurrente, `415`, `405`) |
| Verificación SQL | `@@time_zone = -05:00`, `CURDATE()` correcto, `fecha_pedido` con `DEFAULT (curdate())` y `CHECK` de estados presentes |
| CP-16/CP-17 | Saldos de lote `45.00/47.00 → 50.00/50.00` y dos `ENTRADA_DEVOLUCION` con origen `pedidos` |
| Latencia máxima | 20.7 ms (límite 2 000 ms) |
| Defectos críticos/altos | 0 |

Hallazgos sin cambio de código:

- **DEF-H16-001 (Media, no es defecto de HU-16):** desactivar un cliente con pedidos en curso
  responde `409 CLIENTE_CON_PEDIDOS_ACTIVOS`. Es la regla documentada por HU-05 (error del
  contrato §5) y configurable por `app.clientes.bloquear-desactivacion-con-pedidos`; el pedido
  existente sigue su curso (R-07) en cuanto el cliente queda inactivo.
- **DEF-H16-002 (Baja, dato de prueba):** el ejemplo de CP-01 en `tarea-qa.md` usa una fecha de
  entrega fija (`2026-09-20`) que ya quedó en el pasado; el producto responde correctamente
  `400 FECHA_ENTREGA_INVALIDA`. La prueba debe usar una fecha relativa a hoy.

Los datos de prueba se limpiaron al terminar (pedidos, líneas, movimientos, lotes, usuarios y
clientes del sufijo de QA).

## Desviaciones vs especificación

- **`GET /pedidos/{id}` no devuelve las líneas.** La tabla de endpoints de la especificación
  dice «Obtener pedido con su detalle», pero el detalle es de HU-17: la implementación devuelve
  la cabecera, el cliente embebido, los totales derivados y `editable`. Hasta que HU-17 agregue
  el detalle, no existe un endpoint de líneas y el frontend solo puede mostrar la cabecera.
- **`fechaEntrega` nula se omite del JSON.** El ejemplo de CP-07 en la especificación muestra
  `"fechaEntrega": null`, pero la configuración global (`spring.jackson.default-property-inclusion:
  non_null`) omite los campos nulos. La respuesta real no incluye la propiedad; el consumidor
  debe tratar la ausencia como «sin fecha de entrega».
- **R-06 se valida con la fecha del servidor de aplicación, no con `curdate()`.** `fechaPedido`
  la pone MySQL (R-01), pero la comparación de `fechaEntrega` usa `LocalDate.now()` de la JVM.
  Con la conexión configurada en `America/Bogota` ambas fuentes coinciden; si el despliegue
  separa las zonas horarias, un pedido creado cerca de la medianoche podría aceptar o rechazar
  una entrega «de hoy» de forma distinta a la fecha registrada. Queda como riesgo operativo, no
  como cambio de contrato.
- **CP-21 depende de una regla configurable de HU-05.** Con la configuración por defecto
  (`app.clientes.bloquear-desactivacion-con-pedidos=true`), desactivar un cliente que tiene
  pedidos `PENDIENTE` o `EN_PREPARACION` responde `409 CLIENTE_CON_PEDIDOS_ACTIVOS`. Para
  ejecutar CP-21 tal como lo describe la tarea de QA (crear, desactivar y seguir operando el
  pedido) hay que arrancar la API con esa regla en `false`. El backend de pedidos no cambia:
  R-07 se cumple y el pedido existente sigue su curso.
- **CP-16 y CP-17 requieren sembrar el detalle por SQL.** Las líneas de pedido y sus
  `SALIDA_VENTA` son de HU-17 y todavía no tienen endpoint. La colección crea el pedido por API
  y documenta el `INSERT` de `detalle_pedido` y de `movimientos_inventario_pt` necesario para
  probar la devolución. Si el pedido tiene líneas pero no tiene `SALIDA_VENTA`, la cancelación
  responde `200` y no repone nada: la devolución se guía por el kardex, no por el detalle.

## Defectos y cierre

| ID | Hallazgo | Estado |
|---|---|---|
| — | No se registraron defectos críticos ni altos durante la implementación y la verificación | — |

**PRs:** #108 (persistencia JDBC, enum de estados y repositorio), #109 (servicio con validación
de cliente y fecha de entrega), #110 (cambio de estado con devolución de stock al cancelar) y
#111 (controlador con roles y Swagger), mergeados en `HU-16/creacion-pedidos`; esta
documentación va como PR5 sobre `HU-16/pr5-docs`.

## Dependencias para HU-17 y HU-18

- **HU-17 (detalle del pedido).** Debe usar `PedidoService.esEditable(id)` antes de tocar el
  detalle: solo un pedido `PENDIENTE` admite líneas. Al agregar cada línea, HU-17 descuenta stock
  del lote con `SALIDA_VENTA` y `tabla_origen = 'detalle_pedido'` / `id_origen =
  id_detalle_pedido`; sin esa fila de kardex, la cancelación de HU-16 no puede saber a qué lote
  devolver. El total y `cantidadItems` de la cabecera se recalculan solos porque son
  subconsultas sobre `detalle_pedido`.
- **HU-18 (devoluciones).** `ENTREGADO` es terminal en HU-16: una devolución posterior no es una
  cancelación. HU-18 debe registrar su propio documento y mover stock con
  `ENTRADA_DEVOLUCION` sobre el lote de origen, sin reutilizar el `PATCH` de estado. La
  devolución por cancelación de HU-16 y la devolución comercial de HU-18 comparten el mismo
  kardex, pero son operaciones distintas.

## Pendientes / deuda

- **Frontend:** no existe pantalla de pedidos; el manual de consumo es el contrato para Angular,
  que debe crear la cabecera sin campos de fecha de pedido ni estado, listar con filtros,
  editar solo los pedidos `editable` y confirmar la cancelación mostrando a qué lote vuelve cada
  producto.
- **Detalle del pedido:** hasta HU-17 no hay endpoint de líneas; la pantalla de cancelación no
  puede listar los productos desde la API y debe explicar que la devolución se calcula en el
  servidor.
- **Datos de QA en la base:** la colección crea usuarios, clientes y pedidos de prueba en el
  entorno local; CP-16/CP-17 añaden filas de detalle y kardex por SQL. No son datos de negocio
  ni viajan con el repositorio.
