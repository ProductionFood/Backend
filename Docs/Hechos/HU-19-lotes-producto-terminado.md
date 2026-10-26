# HU-19 — Lotes de producto terminado · Backend verificado

| | |
|---|---|
| Fecha de implementación | 2026-10-25 |
| Commits / PR | PRs #80–#85 a `HU-19/lotes-producto-terminado`; este PR añade la documentación |
| Colección Postman | `Docs/Postman/ProductionFood-HU19.postman_collection.json` (`Preparación`, `Lotes de producto (CP-01…CP-24)`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Estado QA | **QA backend verificado en caliente 2026-10-25 (24 CP + 20 SEC) · frontend pendiente** |

> Esta ficha documenta el backend de la historia. La verificación en caliente se ejecutó el
> 2026-10-25 contra la API real en `:8085` y MySQL 8.4 con un agente ciego: los 24 casos
> funcionales y los 20 de seguridad (5 casos × 4 endpoints) pasaron. El consumo desde el
> frontend sigue pendiente. Los ejemplos salen de la especificación, de los DTO reales y de la
> ejecución de QA; los saldos y los días dependen de la base y de la fecha.

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/lotes-producto` | ADMIN, VENTAS, PRODUCCION, CONSULTA | 200/400/401/403 | Listado paginado con filtros `idProducto`, `soloVencidos`, `proximosAVencer` y `conStock`, orden de lista blanca y producto embebido |
| GET | `/api/v1/lotes-producto/{id}` | ADMIN, VENTAS, PRODUCCION, CONSULTA | 200/400/401/403/404 | Detalle con producto, dos saldos y los campos derivados `diasParaVencer`/`vencido` |
| POST | `/api/v1/lotes-producto` | ADMIN, PRODUCCION | 201/400/401/403/404/409 | Registra el lote y, en la misma transacción, su inventario y el movimiento `ENTRADA_PRODUCCION` |
| GET | `/api/v1/lotes-producto/{id}/movimientos` | ADMIN, PRODUCCION, VENTAS | 200/400/401/403/404 | Kardex paginado con saldos anterior y posterior |

**No existe `PUT`, `PATCH` ni `DELETE`**: responden `405 METODO_NO_PERMITIDO`. Los movimientos
del kardex referencian el lote como origen y borrarlo rompería la trazabilidad de un retiro
sanitario. La cantidad del lote es la cantidad **con la que ingresó** y no cambia después.

**Roles.** Listado y detalle: `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`. Alta: `ADMIN` y
`PRODUCCION`. Kardex: `ADMIN`, `PRODUCCION` y `VENTAS`; `CONSULTA` queda fuera porque el kardex
expone el detalle operativo de quién movió el stock y con qué documento. `COMPRAS` no participa
y recibe `403 SIN_PERMISO` en los cuatro endpoints.

Piezas internas:

| Pieza | Rol |
|---|---|
| `LoteProductoService` | Alta transaccional, validaciones, normalización R-08, filtros, detalle, kardex y `@Auditable` |
| `LoteProductoController` | Los cuatro endpoints con `@PreAuthorize` y Swagger |
| `LoteRepository` | `buscarProductoPorId`, `listarProductos` con `countQuery` y `esLoteProducto`; reutiliza `insertar` y `existsByCodigoLote` de HU-09 |
| `LoteProductoConDetalle` | Proyección de interfaz con los alias del producto, la abreviatura de la unidad y los dos saldos |
| `InventarioPtRepository` | JDBC para `crearParaLote`, `registrarMovimiento`, `buscarMovimientos` y `contarMovimientos` |
| `TipoMovimientoPt` | Enum espejo del `CHECK ck_mov_pt_tipo`; el nombre viaja como texto, nunca como ordinal |
| DTOs | `CrearLoteProductoRequest`, `LoteProductoResponse` y `ProductoRefResponse` |
| `v_stock_producto_terminado` | Vista agregada por producto; excluye los lotes vencidos (R-04) |

**Sin migración Flyway nueva.** V1 ya crea `lotes` e `inventario_producto_terminado` con sus
PK y `UNIQUE`; V2 añade `ck_lotes_cantidad`, `ck_lotes_exclusividad`, `ck_lotes_fechas`,
`ck_inv_pt_no_negativo`, el kardex `movimientos_inventario_pt` y la vista
`v_stock_producto_terminado`. Esta historia solo escribe código sobre el esquema existente.

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| R-02 | El alta escribe tres filas en una sola transacción: `INSERT lotes`, `INSERT inventario_producto_terminado` e `INSERT movimientos_inventario_pt` con tipo `ENTRADA_PRODUCCION`. Las tres o ninguna |
| R-03 | `lotes.cantidad` es la cantidad de ingreso y **nunca se modifica**; el saldo vive en `inventario_producto_terminado.cantidad_disponible`. La respuesta los publica como `cantidadIngresada` y `cantidadDisponible` |
| R-05 | Un vencimiento pasado se rechaza con `400 LOTE_YA_VENCIDO`; un lote que vence **hoy** sí se registra porque todavía es vendible |
| R-06 | El servicio fija `idProducto` informado e `idMateriaPrima`/`idDetalleCompra` en `NULL`; `ck_lotes_exclusividad` impide ambos llenos o ambos nulos |
| R-08 | El código se recorta y se guarda en mayúsculas (`Locale.ROOT`) antes de comprobar duplicados; el `UNIQUE` es *case-insensitive* y la tabla es compartida con HU-09 |

## Reglas de negocio implementadas

- **R-01 · esta historia crea el stock vendible.** Sin fila en
  `inventario_producto_terminado`, HU-17 no puede vender y responde `STOCK_INSUFICIENTE`.
- **R-02 · alta transaccional.** El `INSERT` del lote fuerza `flush` para obtener el `id_lote`
  que necesitan el inventario y el kardex. La fila de inventario nace con
  `cantidad_disponible = cantidad`; el movimiento `ENTRADA_PRODUCCION` registra
  `saldo_anterior = 0` y `saldo_posterior = cantidad`. Un fallo en cualquiera revierte las tres.
- **R-03 · ingreso contra saldo.** `cantidadIngresada` sale de `lotes.cantidad` y
  `cantidadDisponible` del inventario; al crear coinciden, después divergen. El kardex explica
  la diferencia con `saldoAnterior`, `saldoPosterior` y `cantidad` por movimiento.
- **R-04 · un vencido no es stock vendible.** `stockTotalProducto` se lee de
  `v_stock_producto_terminado`, que suma solo lotes con `fecha_vencimiento >= curdate()`. El
  lote vencido conserva su saldo pero no cuenta para vender.
- **R-05 · no se registran vencidos.** Se comprueba después de `FECHAS_INVALIDAS`: primero el
  orden producción/vencimiento, luego el vencimiento contra la fecha del servidor.
- **R-06 · exclusividad.** El lote de producto lleva `id_producto` y deja `id_materia_prima` en
  `NULL`; las consultas de esta ruta exigen `l.id_producto is not null`, así que no mezclan los
  lotes de materia prima de HU-09.
- **R-08 · normalización del código.** `"  pf-2026-001 "` se guarda como `PF-2026-001`; la
  comprobación previa y la carrera contra el `UNIQUE` responden el mismo `409`.
- **CA-01 · código obligatorio y único.** `@NotBlank` + `@Size(max = 30)`; el duplicado se
  comprueba antes del `INSERT` y el `UNIQUE` de la carrera también se traduce a
  `CODIGO_LOTE_DUPLICADO`.
- **CA-02 · el producto debe existir.** Se comprueba con `ProductoRepository.existsById`; no se
  mira el estado, porque un lote de un producto desactivado sigue siendo legítimo.
- **CA-03/CA-04 · fechas y cantidad.** `@NotNull` en las fechas y `@Digits(integer = 8,
  fraction = 2)` en la cantidad. La coherencia de fechas y la positividad se validan en el
  servicio para responder `FECHAS_INVALIDAS` y `CANTIDAD_INVALIDA`, no `VALIDACION_FALLIDA`.
- **CA-05 · inventario automático.** Cada alta crea exactamente una fila de inventario; la
  relación es 1:1 porque `inventario_producto_terminado.id_lote` es `UNIQUE`.
- **CA-06 · filtros.** `idProducto` acota a un producto; `soloVencidos=true` deja solo vencidos;
  `proximosAVencer` es una ventana de días con ambos extremos incluidos; `conStock=true` deja
  solo lotes con saldo mayor que cero. `false` y `null` equivalen a no filtrar en ambos booleanos.
- **Listado.** `page >= 0`, `size` entre 1 y 100. Lista blanca: `codigoLote`, `fechaProduccion`,
  `fechaVencimiento` e `id` (traducido a `idLote`). Por defecto `fechaVencimiento,asc`; otro
  campo responde `400 CAMPO_ORDEN_INVALIDO`.
- **Kardex.** Orden por `fecha`, `tipo`, `cantidad`, `saldoAnterior`, `saldoPosterior` e `id`;
  por defecto `id,desc`. El repositorio añade siempre `id_movimiento DESC` como desempate para
  paginar de forma estable. Un lote sin movimientos devuelve página vacía, no 404.
- **Campos derivados.** `diasParaVencer` y `vencido` se calculan contra la fecha del servidor y
  no se persisten. `vencido` es informativo: un lote puede vencer tras registrarse y seguir con
  saldo. El alta se audita con `@Auditable(accion = "CREAR", tabla = "lotes")`.

## Modelo de datos

**Tabla `lotes`** (compartida con HU-09; V1 la crea y V2 añade los `CHECK`)

| Columna | Tipo | Notas |
|---|---|---|
| `id_lote` | `int unsigned` PK AI | |
| `codigo_lote` | `varchar(30)` NOT NULL UNIQUE | CA-01; único en toda la tabla y en mayúsculas (R-08) |
| `id_producto` | `int unsigned` NULL | Informado aquí (R-06) |
| `id_materia_prima` | `int unsigned` NULL | `NULL` en esta historia (R-06) |
| `id_detalle_compra` | `int unsigned` NULL | `NULL` aquí; maquinaria de HU-09 |
| `fecha_produccion` | `date` NOT NULL | CA-03 |
| `fecha_vencimiento` | `date` NOT NULL | CA-03; `CHECK >= produccion` |
| `cantidad` | `decimal(10,2)` NOT NULL | CA-04; cantidad de ingreso, no saldo (R-03) |

`inventario_producto_terminado` guarda el saldo en `cantidad_disponible decimal(10,2)` con
`CHECK >= 0`; `id_lote` es UNIQUE (1:1) y `fecha_ingreso_estante` tiene
`DEFAULT CURRENT_TIMESTAMP`. `movimientos_inventario_pt` recibe `ENTRADA_PRODUCCION` con
`tabla_origen = 'lotes'`, `id_origen = id_lote`, los dos saldos y
`observacion = 'Lote <código>'`; `id_usuario` queda `NULL` y el kardex lo muestra como
`"Sistema"`. La vista `v_stock_producto_terminado` agrega el saldo por producto, cuenta
`lotes_activos`, calcula `proximo_vencimiento` y excluye vencidos y saldos en cero.

## Contrato resumido

**POST /api/v1/lotes-producto → 201** (con `Location: /api/v1/lotes-producto/{id}`):

```json
// petición
{ "codigoLote": "PF-2026-1018", "idProducto": 3,
  "fechaProduccion": "2026-10-20", "fechaVencimiento": "2026-11-01",
  "cantidad": 25.00 }
```

```json
// respuesta de contrato (saldos y días dependen de la base y de la fecha)
{ "idLote": 30, "codigoLote": "PF-2026-1018",
  "producto": { "idProducto": 3, "nombre": "Croissant", "unidad": "kg" },
  "fechaProduccion": "2026-10-20", "fechaVencimiento": "2026-11-01",
  "cantidadIngresada": 25.00, "cantidadDisponible": 25.00,
  "diasParaVencer": 7, "vencido": false, "stockTotalProducto": 90.50 }
```

**GET /api/v1/lotes-producto → 200** — `PageResponse` con `content`, `page`, `size`,
`totalElements`, `totalPages`, `first` y `last`; cada elemento de `content` tiene la forma del
objeto anterior. Por defecto: `page=0`, `size=20`, `sort=fechaVencimiento,asc`.

**GET /api/v1/lotes-producto/{id} → 200** — mismo objeto. Un id inexistente **o de un lote de
materia prima** responde `404 RECURSO_NO_ENCONTRADO`.

**GET /api/v1/lotes-producto/{id}/movimientos → 200**:

```json
{ "content": [
    { "fecha": "2026-10-25T20:52:11", "tipo": "ENTRADA_PRODUCCION",
      "cantidad": 25.00, "saldoAnterior": 0.00, "saldoPosterior": 25.00,
      "origen": { "tabla": "lotes", "id": 30 }, "usuario": "Sistema" }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true }
```

**Errores:** `CODIGO_LOTE_DUPLICADO` y `PRODUCTO_INEXISTENTE` (409); `FECHAS_INVALIDAS`,
`LOTE_YA_VENCIDO`, `CANTIDAD_INVALIDA`, `VALIDACION_FALLIDA`, `PARAMETRO_INVALIDO` y
`CAMPO_ORDEN_INVALIDO` (400); `RECURSO_NO_ENCONTRADO` (404); `SIN_PERMISO` y `USUARIO_INACTIVO`
(403); `NO_AUTENTICADO` (401); `METODO_NO_PERMITIDO` (405).

## Evidencia (pruebas backend)

| Clase | Casos | Cobertura |
|---|---|---|
| `LoteProductoServiceTest` | 32 | Alta transaccional, R-02/R-03/R-05/R-06/R-08, duplicados, carrera del `UNIQUE`, fallo del inventario, fechas, cantidad, filtros, orden, detalle y kardex |
| `LoteProductoControllerTest` | 22 | 201 + `Location`, `VALIDACION_FALLIDA` por campo, 400/404/409, filtros, orden de listado y kardex, id no numérico |
| `LoteProductoSeguridadTest` | 36 | SEC-01…SEC-05 parametrizados en los 4 endpoints y matriz de roles con tokens reales |
| **Total de la HU** | **90** | |

La suite completa quedó en **829 tests verdes** (`mvn test`) al cerrar el PR6.

- **Verificación en caliente del 2026-10-25** contra la API real (`:8085`) y MySQL 8.4, con un
  agente ciego: **CP-01…CP-24 = 24/24** y **SEC-01…SEC-05 en los 4 endpoints = 20/20**, sin
  defectos críticos ni altos. Todos los endpoints respondieron en menos de 0.01 s; Swagger
  publica las cuatro rutas y la bitácora registra `CREAR` sobre `lotes`.
- **CP-02/CP-03** se comprobaron en base: cada lote creado tiene su fila en
  `inventario_producto_terminado` y su movimiento `ENTRADA_PRODUCCION` con
  `tabla_origen = 'lotes'`.
- **CP-17** se verificó simulando una venta por SQL (HU-17 no existe como API):
  `lotes.cantidad` permaneció en 25 y `cantidad_disponible` bajó a 15. **CP-18/CP-19** movieron
  el vencimiento por SQL: la vista dejó de sumar el lote y la fila con su saldo siguió
  existiendo; la fecha se restauró al terminar. **CP-20** lo rechazó `ck_lotes_exclusividad`
  con el error 3819 de MySQL. **CP-21** se forzó con un trigger temporal (retirado después):
  la transacción revirtió y quedaron **0 lotes, 0 filas de inventario y 0 movimientos**.
- **CP-04** no usó `GET /productos/{id}/stock` porque esa ruta es de HU-17 y todavía no existe
  (404): se usó `GET /api/v1/productos/{id}` y su `stockDisponible`, que pasó de 25 a 50 tras
  el alta. No es un defecto de HU-19.
- La colección cubre los casos HTTP; los de integridad sin endpoint (CP-02, CP-03, CP-17,
  CP-18, CP-19, CP-20 y CP-21) se verificaron con las consultas SQL de
  `PFDocs/HU-19/tarea-qa.md`. El estado de QA va en la fila `Estado QA`, no aquí.

## Desviaciones vs especificación

- **El alta vive en `LoteProductoService`, no en `LoteService.crearLoteProducto`.** La tarea de
  backend proponía ampliar el servicio de HU-09; se creó uno propio para no mezclar historias
  en una clase ya grande. El contrato HTTP y las reglas son los de la especificación.
- **`cantidad` no lleva `@DecimalMin`.** Rechazar el cero o el negativo en el DTO devolvería
  `VALIDACION_FALLIDA` en lugar del `CANTIDAD_INVALIDA` de CP-12/CP-13. El DTO replica el
  dominio de la columna con `@Digits` y el servicio comprueba la positividad.
- **`conStock=false` no significa «solo sin stock».** Se normaliza a «no filtrar», igual que
  `soloVencidos=false` en HU-09; solo `true` filtra. Es una decisión (DEF-04 aceptado).
- **`proximosAVencer` exige un mínimo de 1.** `0` y negativos responden
  `400 PARAMETRO_INVALIDO`; el contrato transversal no fijaba el mínimo. Coherente con HU-09
  (DEF-05 aceptado).
- **`sort=id` se traduce a `idLote`.** En el listado el campo público es `id`; en el kardex son
  los del movimiento (`fecha`, `tipo`, `cantidad`, `saldoAnterior`, `saldoPosterior`, `id`).
- **No hay `PUT`, `PATCH` ni `DELETE`.** La especificación no los pide y el kardex referencia
  el lote; responden `405 METODO_NO_PERMITIDO`.
- **`GET /productos/{id}/stock` no existe.** Es de HU-17. El stock del producto se consulta con
  `GET /api/v1/productos/{id}` y su `stockDisponible`, que ya agrega los lotes vigentes.

## Defectos y cierre

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| DEF-01 | Medio | El kardex ignoraba `sort`: cualquier campo devolvía 200 con un orden fijo | Corregido en PR #84 (lista blanca + `400 CAMPO_ORDEN_INVALIDO`) |
| DEF-03 | Bajo | Los errores de deserialización no señalaban `fieldErrors` | Corregido en #84 y #85 (tipo inválido y fechas no parseables ya identifican el campo) |
| DEF-02 | Bajo | `Content-Type` sin `charset=UTF-8` | Aceptado: es global de la API, no de la HU |
| DEF-04 | Bajo | `conStock=false` equivale a no filtrar | Aceptado y documentado como decisión |
| DEF-05 | Bajo | `proximosAVencer` exige `>= 1` | Aceptado: coherente con HU-09 |

## Pendientes / deuda

- **Frontend:** no existe pantalla de lotes de producto terminado; el manual de consumo es el
  contrato para Angular.
- **HU-17 y HU-18:** sin ventas ni devoluciones reales no se puede ejercitar el ciclo completo
  del kardex (`SALIDA_VENTA`, `ENTRADA_DEVOLUCION`). CP-17 se validó con una venta simulada por
  SQL y esas filas no forman parte del flujo de la aplicación.
- **R-07 · integración con planes de producción:** producir (HU-21) y registrar el lote siguen
  siendo dos acciones manuales; nada genera el lote automáticamente. Mejora de fase posterior.
- **Datos de QA en la base:** quedaron los lotes `QA-H19-A-*`, `QA-H19-B-*` y
  `QA-H19-FIX-001` (ids 20–30) del producto 3. Son datos de prueba locales; no son datos de
  negocio ni viajan con el repositorio.
- **DEF-02 (charset):** si el frontend necesita `charset=UTF-8` explícito, se corrige en la
  configuración global de la API, no en esta historia.
