# HU-09 — Lotes de materia prima · Backend verificado

| | |
|---|---|
| Fecha de implementación | 2026-10-23 |
| Commits / PR | PRs #60–#63 a `HU-09/lotes-materia-prima`; este PR añade la documentación |
| Colección Postman | `Docs/Postman/ProductionFood-HU09.postman_collection.json` (`Preparación`, `CRUD (CP-01…CP-20)`, `Roles (matriz)`, `Seguridad (SEC-01…05)`) |
| Estado QA | **QA backend verificado en caliente 2026-10-23 (20 CP + 15 SEC) · frontend pendiente** |

> Esta ficha documenta el backend de la historia. La verificación en caliente se ejecutó el
> 2026-10-23 contra la API real en `:8085` y MySQL 8.4 con un agente ciego: los 20 casos
> funcionales y los 15 de seguridad pasaron, sin defectos. El consumo desde el frontend sigue
> pendiente. Los ejemplos de contrato de más abajo salen de la especificación y de los DTO
> reales.

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/lotes-materia-prima` | ADMIN, PRODUCCION, COMPRAS, CONSULTA | 200/400/401/403 | Listado paginado con filtros `idMateriaPrima`, `soloVencidos` y `proximosAVencer`, orden de lista blanca y datos de la materia prima embebidos |
| GET | `/api/v1/lotes-materia-prima/{id}` | ADMIN, PRODUCCION, COMPRAS, CONSULTA | 200/400/401/403/404 | Obtener un lote con su materia prima, fechas, cantidad y campos derivados `diasParaVencer`/`vencido` |
| POST | `/api/v1/lotes-materia-prima` | ADMIN, PRODUCCION, COMPRAS | 201/400/401/403/404/409 | Registrar un lote; suma al inventario solo cuando es un alta manual (sin `idDetalleCompra`) |

**No existe `DELETE` ni `PUT`/`PATCH`** para lotes. La tabla `lotes` es compartida con los lotes
de producto terminado (HU-19) y los movimientos del kardex la referencian como origen; borrar o
reescribir un lote rompería la trazabilidad del inventario. La cantidad del lote es la cantidad
**con la que ingresó** y no se edita después.

**Roles.** Lectura: `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA`. Escritura: `ADMIN`,
`PRODUCCION` y `COMPRAS`. `VENTAS` no participa en el módulo y recibe `403 SIN_PERMISO` en los
tres endpoints.

Piezas internas:

| Pieza | Rol |
|---|---|
| `Lote` | Entidad JPA sobre `lotes` (`idLote`, `codigoLote`, `idProducto`, `idMateriaPrima`, `idDetalleCompra`, fechas y cantidad) |
| `LoteRepository` | Spring Data; `insertar` con `saveAndFlush`, `existsByCodigoLote`, `buscarDetallePorId` y `listarConDetalle` (nativas con `join materias_primas` y `join unidades_medida`) |
| `LoteConDetalle` | Proyección de interfaz con el nombre de la materia prima y la abreviatura de la unidad |
| `LoteService` | Alta transaccional, bifurcación R-02, normalización R-06, validación de fechas/cantidad, filtros y `@Auditable` |
| `LoteController` | Los tres endpoints con `@PreAuthorize` y Swagger |
| DTOs | `CrearLoteMateriaPrimaRequest`, `LoteMateriaPrimaResponse`, `MateriaPrimaRefResponse` y `SaldosInventarioResponse` |
| `InventarioService` | Único componente autorizado a ejecutar `UPDATE inventario`; el alta del lote delega en `registrarEntrada` |

Sin migración Flyway propia: `lotes` ya existe en V1 y V2 añade `id_detalle_compra`, los
`CHECK` de exclusividad, fechas y cantidad, y crea `movimientos_inventario_mp` (kardex).

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| R-01 | El alta manual suma stock en la misma transacción que crea el lote; la suma se delega siempre en `InventarioService.registrarEntrada`, el único componente que ejecuta `UPDATE inventario` |
| R-02 | Un lote con `idDetalleCompra` **no vuelve a sumar** porque el stock ya entró con la recepción de la compra; solo el alta manual suma |
| R-05 | Un lote ya vencido se registra igual, **sí suma** y se marca con `vencido = true`; la marca es informativa y no bloquea |
| R-06 | El código se recorta y se guarda en mayúsculas antes de comprobar duplicados, para que la collation *case-insensitive* no deje pasar variantes de capitalización |

## Reglas de negocio implementadas

- **R-01 · registrar un lote suma al inventario.** El alta hace tres escrituras en una sola
  transacción: `INSERT lotes`, `UPDATE inventario` (vía
  `InventarioService.registrarEntrada`) e `INSERT movimientos_inventario_mp` con tipo
  `ENTRADA_LOTE`. Las tres o ninguna. Ningún otro componente ejecuta `UPDATE inventario`.
- **R-02 · la doble suma.** Si el cuerpo trae `idDetalleCompra`, el lote nace de una compra ya
  recibida y **no vuelve a sumar**; el stock ya entró con la recepción (HU-13). Solo el alta
  manual, sin `idDetalleCompra`, suma. La respuesta del alta manual incluye
  `inventarioActualizado`; con `idDetalleCompra` la clave se omite.
- **R-03 · exclusividad materia prima / producto terminado.** El servicio fija
  `idProducto = NULL`; el `CHECK ck_lotes_exclusividad` de V2 impide ambos llenos o ambos nulos.
  El listado y el detalle filtran `id_materia_prima is not null`, de modo que los lotes de
  producto terminado de HU-19 no se mezclan con esta historia.
- **R-04 · fechas.** `fechaProduccion` y `fechaVencimiento` son obligatorias; el vencimiento no
  puede ser anterior a la producción. **Iguales sí se permiten** (insumos de un solo día).
  El DTO solo exige `@NotNull`; la comparación se hace en el servicio para devolver
  `FECHAS_INVALIDAS`.
- **R-05 · lotes ya vencidos.** Se registran igualmente y **sí suman** al inventario, porque
  físicamente están en la bodega. Se marcan con `vencido = true` y `diasParaVencer` negativo;
  la marca es informativa y no bloquea el alta.
- **R-06 · normalización del código.** El código se recorta con `trim()` y se guarda en
  mayúsculas (`Locale.ROOT`) antes de comprobar duplicados. La collation es
  *case-insensitive*, así que `h-2026-0912` y `H-2026-0912` colisionan y devuelven
  `409 CODIGO_LOTE_DUPLICADO`.
- **CA-01 · código obligatorio y único, máx. 30.** `@NotBlank` + `@Size(max = 30)`; el
  duplicado se comprueba antes del `INSERT`.
- **CA-02 · la materia prima debe existir.** Se comprueba con
  `MateriaPrimaRepository.existsById`. No se mira el estado: registrar inventario de un insumo
  desactivado sigue siendo una operación legítima.
- **CA-03 · fechas obligatorias.** `@NotNull` en el DTO y comparación en el servicio.
- **CA-04 · cantidad obligatoria y mayor que cero.** `@NotNull` y `@Digits(integer = 8,
  fraction = 2)` en el DTO; el cero y el negativo se rechazan en el servicio con
  `CANTIDAD_INVALIDA` (no con el genérico `VALIDACION_FALLIDA`).
- **CA-05 · listar por materia prima.** Filtro opcional `idMateriaPrima`, combinable con
  `soloVencidos` y `proximosAVencer`.
- **Paginación y orden.** `page >= 0`, `size` entre 1 y 100. Lista blanca de orden:
  `codigoLote`, `fechaProduccion`, `fechaVencimiento`, `cantidad` e `id` (que se traduce a
  `idLote`). Por defecto `fechaVencimiento,asc`. Cualquier otro campo responde
  `400 CAMPO_ORDEN_INVALIDO`.
- **Filtros de vencimiento.** `soloVencidos` solo filtra cuando vale `true`; `false` y `null`
  equivalen a no filtrar. `proximosAVencer` son días de ventana y debe ser mayor que cero;
  trae los lotes con vencimiento entre hoy y hoy más esos días, extremos incluidos.
- **Campos derivados.** `diasParaVencer` y `vencido` se calculan en la respuesta contra la
  fecha del servidor; no se persisten. `inventarioActualizado` solo viaja en el alta manual.
- **Auditoría.** `@Auditable(accion = "CREAR", tabla = "lotes")` sobre el alta, con detalle
  `'Lote registrado: ' + #resultado.codigoLote`.

## Modelo de datos

**Tabla `lotes`** (compartida con HU-19; V2 añade `id_detalle_compra` y tres `CHECK`)

| Columna | Tipo | Notas |
|---|---|---|
| `id_lote` | `int unsigned` PK AI | |
| `codigo_lote` | `varchar(30)` NOT NULL UNIQUE | CA-01; se guarda en mayúsculas (R-06) |
| `id_producto` | `int unsigned` NULL | `NULL` en esta historia (R-03) |
| `id_materia_prima` | `int unsigned` NULL | Informado aquí |
| `id_detalle_compra` | `int unsigned` NULL | FK a `detalle_compra`; evita la doble suma (R-02) |
| `fecha_produccion` | `date` NOT NULL | CA-03 |
| `fecha_vencimiento` | `date` NOT NULL | CA-03; `CHECK >= produccion` |
| `cantidad` | `decimal(10,2)` NOT NULL | CA-04; `CHECK > 0` |

**Tabla `movimientos_inventario_mp`** — recibe el movimiento `ENTRADA_LOTE` con
`tabla_origen = 'lotes'`, `id_origen = id_lote`, `saldo_anterior` y `saldo_posterior`.

## Contrato resumido

**POST /api/v1/lotes-materia-prima → 201** (con cabecera
`Location: /api/v1/lotes-materia-prima/{id}`):

```json
// petición (alta manual: sí suma)
{ "codigoLote": "H-2026-0912", "idMateriaPrima": 5,
  "fechaProduccion": "2026-09-10", "fechaVencimiento": "2027-03-10",
  "cantidad": 100.00 }
```

```json
// respuesta de contrato (los valores de saldo y días dependen de la base y de la fecha)
{ "idLote": 14, "codigoLote": "H-2026-0912",
  "materiaPrima": { "idMateriaPrima": 5, "nombre": "Harina de trigo", "unidad": "kg" },
  "fechaProduccion": "2026-09-10", "fechaVencimiento": "2027-03-10",
  "cantidad": 100.00, "diasParaVencer": 138, "vencido": false,
  "inventarioActualizado": { "anterior": 12.50, "posterior": 112.50 } }
```

**Alta con `idDetalleCompra`:** misma forma, pero `inventarioActualizado` **no aparece** y el
inventario no cambia (R-02).

**GET /api/v1/lotes-materia-prima → 200** — `PageResponse` con `content`, `page`, `size`,
`totalElements`, `totalPages`, `first` y `last`. Cada elemento tiene la forma del objeto anterior
sin `inventarioActualizado` (la inclusión global `non_null` de Jackson omite la clave).

**GET /api/v1/lotes-materia-prima/{id} → 200** — mismo objeto, también sin
`inventarioActualizado`. Un id inexistente **o de un lote de producto terminado** responde
`404 RECURSO_NO_ENCONTRADO`.

## Evidencia (pruebas backend)

Conteo de los tests de la rama, tomado de los fuentes:

| Clase | Casos | Cobertura |
|---|---|---|
| `InventarioServiceTest` | 9 | Entrada, salida, saldo insuficiente, cantidades inválidas, orden bloqueo→UPDATE→kardex y propagación del fallo del kardex |
| `LoteServiceTest` | 17 | Alta con y sin suma, R-02, duplicados normalizados, MP inexistente, fechas, cantidad, R-05, R-06, filtros y listado/obtención |
| `CrearLoteMateriaPrimaRequestTest` | 9 | Obligatoriedad, longitud del código, decimales, `idDetalleCompra` opcional |
| `LoteControllerTest` | 16 | 201 + `Location`, 400/404/409, filtros, paginación y `proximosAVencer=0` |
| `LoteSeguridadTest` | 23 | SEC-01…SEC-05 parametrizados en los 3 endpoints y matriz de roles de lectura/escritura |
| **Total** | **74** | |

La suite completa quedó en **526 tests verdes** (`mvn test`) al cerrar el PR4.

- **Verificación en caliente ejecutada el 2026-10-23** contra la API real (`:8085`) y MySQL 8.4
  con un agente ciego: CP-01…CP-20 y SEC-01…SEC-05 (en los 3 endpoints) pasaron, sin defectos
  funcionales ni de seguridad. Evidencia destacada: el inventario subió exactamente la cantidad
  (CP-02), el kardex `ENTRADA_LOTE` coincidió con el saldo (CP-03), un lote con
  `idDetalleCompra` no sumó ni generó movimiento (CP-04), el `CHECK` rechazó el lote con
  producto y materia prima a la vez (CP-17) y un fallo forzado del kardex revirtió el saldo y
  el lote (CP-18, con un trigger temporal de prueba).
- CP-04 necesitó un fixture SQL (`proveedor → compra RECIBIDA → detalle_compra`) porque la API
  de compras (HU-13) todavía no existe; el lote se creó luego por el endpoint real.
- La conciliación `inventario.cantidad_disponible` contra el último `saldo_posterior` del kardex
  devolvió **0 filas** al cierre.
- La colección de Postman cubre los casos HTTP; los de integridad sin endpoint (CP-03, CP-17 y
  CP-18) se verificaron con las consultas SQL de `PFDocs/HU-09/tarea-qa.md`.
- El estado de QA va en la fila `Estado QA`, no aquí.

## Desviaciones vs especificación

- **`cantidad`: el DTO no lleva `@DecimalMin`.** La especificación (CA-04) lo menciona, pero
  rechazar el cero o el negativo en el DTO devolvería `VALIDACION_FALLIDA` en lugar del
  `CANTIDAD_INVALIDA` que exigen CP-12 y CP-13. El DTO solo replica el dominio de la columna
  con `@Digits` y el servicio hace la comprobación de positividad.
- **`idDetalleCompra` inválido responde `409 CONFLICTO_INTEGRIDAD`.** La especificación solo
  lista las FK como parte del modelo; si el id no existe en `detalle_compra`, la base rechaza
  la FK y el manejador global devuelve `409 CONFLICTO_INTEGRIDAD`. Es el comportamiento real y
  está documentado en el manual para que el frontend no lo confunda con
  `CODIGO_LOTE_DUPLICADO`.
- **`soloVencidos=false` no significa «solo no vencidos».** El servicio normaliza `false` y
  `null` a «no filtrar». El repositorio solo aplica el `where` cuando recibe `TRUE`.
- **`sort=id` se traduce a `idLote`.** El campo público de orden es `id`; `sort=idLote` es
  inválido (`CAMPO_ORDEN_INVALIDO`). La especificación no fija los nombres de orden, así que se
  documenta en el manual.
- **`GET /{id}` devuelve 404 también para lotes de producto terminado.** No es un fallo: el
  endpoint es de esta historia y el repositorio excluye las filas con `id_materia_prima` nulo.
- **No hay `DELETE`.** La especificación no lo pide y el modelo compartido con HU-19 lo
  desaconseja; no se añade.

## Pendientes / deuda

- **Verificación en caliente contra MySQL** de toda la matriz de `tarea-qa.md`, incluidos los
  casos de integridad y la conciliación `inventario` ↔ `movimientos_inventario_mp`.
- **CP-04 real:** requiere una fila en `detalle_compra`; mientras HU-13 no exista como API, la
  fila se crea por SQL. La colección Postman deja la variable `idDetalleCompra` preparada y
  documenta que, si la fila no existe, la respuesta es `409 CONFLICTO_INTEGRIDAD`.
- **Frontend:** no existe pantalla de lotes; el manual de consumo es el contrato para Angular.
- **Trazabilidad por lote en el consumo:** no existe. Al producir (HU-22) se descuenta del total
  de `inventario`, no de un lote concreto. Es una limitación del esquema original, no de esta
  historia; la trazabilidad completa exigiría saldo por lote y consumo FEFO.
- **CP-03/CP-17/CP-18** no tienen endpoint: se verifican con las consultas SQL de la tarea de QA.
