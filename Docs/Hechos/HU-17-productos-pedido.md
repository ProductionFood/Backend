# HU-17 — Agregar productos al pedido · HECHA

| | |
|---|---|
| Fecha de implementación | 2026-10-30 |
| Commits / PR | PRs #115–#120 a `HU-17/productos-pedido`; este cambio añade la documentación (PR6) |
| Colección Postman | `Docs/Postman/ProductionFood-HU17.postman_collection.json` (`Preparación`, `Stock`, `Detalle (CP-01…CP-15)`, `Edición y eliminación (CP-16…CP-19)`, `Estados no editables (CP-20…CP-21)`, `Concurrencia (CP-09)`, `Seguridad (SEC-01…SEC-05)`) |
| Estado QA | **QA ciego verificado 2026-10-30 · 23/24 CP y 25/25 SEC · frontend pendiente** |

> Esta ficha documenta el detalle del pedido: las líneas que descuentan inventario de producto
> terminado, la consulta FEFO de stock y el ajuste por diferencia al editar. La cabecera del
> pedido y su máquina de estados son de HU-16; la devolución posterior a la entrega es de HU-18.
> La verificación en caliente se ejecutó el 2026-10-30 contra la API real en
> `http://127.0.0.1:9211` y MySQL 8.4. Los ejemplos salen de ejecuciones reales contra esa API;
> los ids, nombres y cantidades dependen de la base. El contrato de consumo está en el manual
> [`../Manuales_Consumo_Frontend/HU-17-productos-pedido.md`](../Manuales_Consumo_Frontend/HU-17-productos-pedido.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/pedidos/{idPedido}/detalles` | ADMIN, VENTAS, PRODUCCION, CONSULTA | 200/400/401/403/404 | Lista las líneas con su producto, precio copiado, subtotal, lotes consumidos, total, estado y `editable` |
| GET | `/api/v1/productos/{id}/stock` | ADMIN, VENTAS, PRODUCCION, CONSULTA | 200/400/401/403/404 | Stock vendible agregado por producto y lotes en orden FEFO; excluye vencidos y saldos en cero |
| POST | `/api/v1/pedidos/{idPedido}/detalles` | ADMIN, VENTAS | 201/400/401/403/404/409 | Agrega una línea, copia el precio del catálogo y descuenta stock FEFO; `Location: /api/v1/pedidos/{id}/detalles/{idDetalle}` |
| PUT | `/api/v1/pedidos/{idPedido}/detalles/{idDetalle}` | ADMIN, VENTAS | 200/400/401/403/404/409 | Cambia la cantidad moviendo stock solo por la diferencia |
| DELETE | `/api/v1/pedidos/{idPedido}/detalles/{idDetalle}` | ADMIN, VENTAS | 204/400/401/403/404/409 | Quita la línea y devuelve su cantidad neta a los lotes de origen; sin cuerpo |

**Roles.** Lectura para `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`; escritura solo para `ADMIN`
y `VENTAS`. `COMPRAS` recibe `403 SIN_PERMISO` en los cinco endpoints. La consulta de stock
también es de lectura: no se exige que el producto esté activo, porque su inventario sigue
existiendo aunque ya no pueda agregarse a un pedido.

Piezas internas:

| Pieza | Rol |
|---|---|
| `InventarioPtService` | Único componente autorizado a modificar `inventario_producto_terminado`: `registrarSalidaFefo`, `registrarEntrada`, `registrarDevolucion` y `stockDisponible`. No abre transacción: la abre el servicio llamador |
| `InventarioPtRepository` | JDBC de la vista FEFO, bloqueo `FOR UPDATE`, actualización de saldo, kardex y neto por origen. Aplica `ORDER BY fecha_vencimiento ASC, id_lote ASC` en la consulta |
| `LoteDisponible` / `LoteConsumido` / `SaldoLote` | Proyecciones internas del stock por lote y de lo consumido en una salida |
| `DetallePedido` / `DetallePedidoRowMapper` | Registro de la línea con las proyecciones de nombre y unidad que aporta el `JOIN` a `productos` y `unidades_medida` |
| `DetallePedidoRepository` | `listarPorPedido`, `obtenerPorId`, `obtenerPorIdParaActualizar` (`FOR UPDATE`), `insertar`, `actualizarCantidad`, `eliminar`, `existeProducto` y `calcularTotal` |
| `DetallePedidoService` | Reglas R-02 a R-10: validaciones en orden, precio copiado, `INSERT` antes de la salida FEFO, ajuste por diferencia, devolución neta y total recalculado. `@Transactional` y `@Auditable` en las escrituras |
| `DetallePedidoController` | Subrecurso anidado con `@PreAuthorize`, Swagger, `201 + Location` y `204` |
| `StockProductoController` | `GET /productos/{id}/stock`; comparte la base `/productos` con `ProductoController` sin conflicto porque `/{id}/stock` es distinto de `/{id}` |
| `PedidoInexistenteException` | `404 PEDIDO_INEXISTENTE`, distinto del `404 RECURSO_NO_ENCONTRADO` genérico: distingue que el que falta es el pedido padre |
| `PedidoRepository.lineasParaDevolucion` | Netea `SALIDA_VENTA − ENTRADA_DEVOLUCION` por lote al cancelar (HU-16); excluye las devoluciones de cancelación porque su origen es `pedidos`, no `detalle_pedido` |
| `AgregarDetallePedidoRequest` / `ActualizarCantidadRequest` | Solo `idProducto` + `cantidad` y `cantidad`; la cantidad no lleva `@Positive` para que un cero responda `CANTIDAD_INVALIDA` del servicio y no `VALIDACION_FALLIDA` |
| `DetallePedidoListaResponse` / `DetallePedidoResponse` / `StockProductoResponse` | Listado con estado, `editable`, total e ítems; línea con `subtotal` y `lotesConsumidos`; stock con `lotesActivos`, `proximoVencimiento` y `lotes` |
| `LoteConsumidoResponse` / `LoteDisponibleResponse` | Lotes consumidos por una línea y lotes vendibles de un producto, ambos con su vencimiento |

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| R-01 | **El stock se agrega por producto desde los lotes.** `v_stock_producto_terminado` (V2 §C-02) suma los lotes vendibles y `v_lotes_disponibles_fefo` devuelve el detalle. No hay columna de stock por producto: el saldo vive por lote y el agregado se calcula en cada consulta |
| R-02 | **FEFO se ordena en la consulta, nunca en la vista.** `ORDER BY fecha_vencimiento ASC, id_lote ASC` está en `InventarioPtRepository.lotesDisponiblesFefo`; MySQL ignora el `ORDER BY` declarado dentro de una vista y no avisa. El desempate por `id_lote` hace determinista dos lotes que vencen el mismo día |
| R-03 | **Los vencidos no son stock vendible.** La vista filtra `fecha_vencimiento >= curdate()` y los saldos en cero; por eso `cantidadDisponible` del endpoint de stock es lo que realmente se puede vender, no el inventario bruto |
| R-04 | **El precio se copia al agregar la línea.** `DetallePedidoService.agregar` toma `productos.precio`, lo fija a escala 2 y lo escribe en `detalle_pedido.precio_unitario`. Ni el `GET` ni el `PUT` vuelven a consultar el catálogo: subir el precio hoy no cambia lo ya vendido |
| R-05 | **Bloqueo por lote en el orden de la llave.** `registrarSalidaFefo` lee los lotes vendibles, bloquea sus filas con `SELECT ... FOR UPDATE` ordenado por `id_lote ASC` y **revalida el stock sobre los saldos ya bloqueados**. El orden evita deadlocks; la relectura evita vender dos veces el último pan. El `CHECK (cantidad_disponible >= 0)` de V2 es la última red |
| R-06 | **Un producto no se repite en el pedido.** `existeProducto` responde `409 PRODUCTO_DUPLICADO` antes del `INSERT`; el `UNIQUE uk_detalle_pedido (id_pedido, id_producto)` detiene la carrera y su `DuplicateKeyException` se traduce al mismo 409 |
| R-07 | **Editar mueve solo la diferencia.** Aumentar descuenta la diferencia con FEFO; reducir devuelve la diferencia a los lotes de origen. Repetir la misma cantidad no mueve stock. Nunca se devuelve todo y se vuelve a descontar |
| R-08 | **Eliminar devuelve la cantidad neta completa.** `registrarDevolucion` lee del kardex el neto por lote y repone recorriendo los lotes en orden inverso al FEFO (primero los que vencen más tarde). Si el kardex no respalda la cantidad, aborta la transacción en vez de devolver de menos en silencio |
| R-09 | **El detalle se edita en `PENDIENTE` y `EN_PREPARACION`.** Es una regla propia del detalle, más amplia que la de la cabecera de HU-16, que solo admite `PENDIENTE` para cambiar cliente y fecha. `editable` de la respuesta implementa esta regla y el `GET` no exige estado |
| R-10 | **La trazabilidad lote ↔ línea vive en el kardex.** Cada lote consumido genera su `SALIDA_VENTA` con `tabla_origen = 'detalle_pedido'` e `id_origen = id_detalle_pedido`. Por eso el `INSERT` de la línea va **antes** de la salida FEFO: sin el id no habría origen que registrar. `lotesConsumidos` se reconstruye del kardex también en el `GET` y el `PUT`, no solo en el `201` |
| R-11 | **La cancelación netea lo que aún está descontado.** `PedidoRepository.lineasParaDevolucion` agrupa por lote `SALIDA_VENTA` menos `ENTRADA_DEVOLUCION` de las líneas del pedido y descarta los netos no positivos. Las devoluciones de una cancelación anterior tienen `tabla_origen = 'pedidos'` y quedan fuera del `JOIN` con `detalle_pedido`, de modo que cancelar después de una reducción devuelve solo el neto y no stock de más |
| Auditoría | `@Auditable` en agregar (`CREAR`), editar (`EDITAR`) y eliminar (`ELIMINAR`) sobre `detalle_pedido`, con HU-04 |

## Modelo de datos

**Sin migración Flyway nueva.** La historia usa el esquema de V1 y las correcciones de V2:

- `detalle_pedido`: `id_pedido`, `id_producto`, `cantidad` `decimal(10,2)` con
  `CHECK (cantidad > 0)` y `precio_unitario` `decimal(10,2)` añadido en V2 §C-01; el
  `UNIQUE KEY uk_detalle_pedido (id_pedido, id_producto)` es de V2 §C-13.
- `inventario_producto_terminado`: saldo por lote (`id_lote` único) que bloquea y actualiza
  `InventarioPtService`; el `CHECK (cantidad_disponible >= 0)` es de V2.
- `movimientos_inventario_pt`: kardex por lote (V2 §C-04) con `tipo_movimiento`, `cantidad`,
  `saldo_anterior`, `saldo_posterior`, `tabla_origen`, `id_origen` e índices por lote y origen.
- `v_stock_producto_terminado`: agregado vendible por producto, sin vencidos.
- `v_lotes_disponibles_fefo`: lotes vendibles con vencimiento; **la vista no ordena**, el orden
  lo aplica la consulta que la usa.

`totalPedido` y `cantidadItems` **no son columnas**: el listado los calcula con
`SUM(cantidad × precio_unitario)` y el tamaño de la lista devuelta. Un pedido sin líneas
responde `detalles: []`, `totalPedido: 0.00` y `cantidadItems: 0`.

## Contrato resumido

**GET /api/v1/productos/872/stock → 200:**

```json
{
  "idProducto": 872,
  "nombre": "Pan francés",
  "cantidadDisponible": 52.0,
  "lotesActivos": 2,
  "proximoVencimiento": "2026-11-01",
  "lotes": [
    { "codigoLote": "PF-2026-DOC-1015", "vence": "2026-11-01", "disponible": 12.0 },
    { "codigoLote": "PF-2026-DOC-1017", "vence": "2026-11-04", "disponible": 40.0 }
  ]
}
```

Los lotes ya vienen en orden FEFO; el consumidor no debe reordenarlos. `proximoVencimiento` es
la fecha del primero y se omite si no hay lotes vendibles.

**POST /api/v1/pedidos/658/detalles → 201** (`Location: /api/v1/pedidos/658/detalles/287`):

```json
{ "idProducto": 872, "cantidad": 30.00 }
```

```json
{
  "idDetallePedido": 287,
  "producto": { "idProducto": 872, "nombre": "Pan francés", "unidad": "kg" },
  "cantidad": 30.0,
  "precioUnitario": 1200.0,
  "subtotal": 36000.0,
  "lotesConsumidos": [
    { "codigoLote": "PF-2026-DOC-1015", "vence": "2026-11-01", "cantidad": 12.0 },
    { "codigoLote": "PF-2026-DOC-1017", "vence": "2026-11-04", "cantidad": 18.0 }
  ],
  "totalPedido": 36000.0
}
```

El lote que vence primero se consumió primero aunque se hubiera creado después (CP-04).
`lotesConsumidos` es el comprobante de trazabilidad y de aplicación de FEFO.

**GET /api/v1/pedidos/658/detalles → 200** (`DetallePedidoListaResponse`, no `PageResponse`):

```json
{
  "idPedido": 658,
  "estado": "PENDIENTE",
  "editable": true,
  "detalles": [
    {
      "idDetallePedido": 287,
      "producto": { "idProducto": 872, "nombre": "Pan francés", "unidad": "kg" },
      "cantidad": 30.0,
      "precioUnitario": 1200.0,
      "subtotal": 36000.0,
      "lotesConsumidos": [
        { "codigoLote": "PF-2026-DOC-1015", "vence": "2026-11-01", "cantidad": 12.0 },
        { "codigoLote": "PF-2026-DOC-1017", "vence": "2026-11-04", "cantidad": 18.0 }
      ]
    }
  ],
  "totalPedido": 36000.0,
  "cantidadItems": 1
}
```

En los ítems del listado `totalPedido` no viaja: el total agregado va una sola vez en el
wrapper (la aplicación omite propiedades nulas). El array llega en el orden de alta de las
líneas (`ORDER BY id_detalle_pedido`).

**PUT /api/v1/pedidos/658/detalles/287 → 200:**

```json
{ "cantidad": 6.00 }
```

```json
{
  "idDetallePedido": 287,
  "producto": { "idProducto": 872, "nombre": "Pan francés", "unidad": "kg" },
  "cantidad": 6.0,
  "precioUnitario": 1200.0,
  "subtotal": 7200.0,
  "lotesConsumidos": [
    { "codigoLote": "PF-2026-DOC-1015", "vence": "2026-11-01", "cantidad": 6.0 }
  ],
  "totalPedido": 7200.0
}
```

La reducción de 30 a 6 devolvió 24 al kardex y el neto quedó en 6 sobre el primer lote. El
precio no cambia: el `PUT` no acepta producto ni precio.

**DELETE /api/v1/pedidos/658/detalles/287 → 204**, sin cuerpo. La línea se elimina después de
reponer su cantidad neta a los lotes de origen.

**Errores reales:**

```json
// 409 · STOCK_INSUFICIENTE
{ "timestamp": "2026-10-30T18:51:16.923721982", "status": 409, "error": "Conflict",
  "code": "STOCK_INSUFICIENTE",
  "message": "Solo hay 52 unidades disponibles de Pan francés. Se solicitaron 100.",
  "path": "/api/v1/pedidos/658/detalles", "fieldErrors": [] }
```

```json
// 409 · PRODUCTO_DUPLICADO
{ "timestamp": "2026-10-30T18:51:17.018265254", "status": 409, "error": "Conflict",
  "code": "PRODUCTO_DUPLICADO",
  "message": "El producto indicado ya está en el pedido; edite la cantidad de la línea existente.",
  "path": "/api/v1/pedidos/658/detalles", "fieldErrors": [] }
```

```json
// 400 · CANTIDAD_INVALIDA
{ "timestamp": "2026-10-30T18:51:17.063947045", "status": 400, "error": "Bad Request",
  "code": "CANTIDAD_INVALIDA",
  "message": "La cantidad debe ser un número mayor que cero.",
  "path": "/api/v1/pedidos/658/detalles", "fieldErrors": [] }
```

```json
// 409 · PRODUCTO_INACTIVO
{ "timestamp": "2026-10-30T18:51:17.112532820", "status": 409, "error": "Conflict",
  "code": "PRODUCTO_INACTIVO",
  "message": "El producto con id 50 está inactivo; no puede agregarse a un pedido.",
  "path": "/api/v1/pedidos/658/detalles", "fieldErrors": [] }
```

```json
// 409 · PEDIDO_NO_EDITABLE
{ "timestamp": "2026-10-30T18:51:17.178630968", "status": 409, "error": "Conflict",
  "code": "PEDIDO_NO_EDITABLE",
  "message": "El pedido con id 658 no es editable: solo los pedidos PENDIENTE o EN_PREPARACION admiten cambios en su detalle.",
  "path": "/api/v1/pedidos/658/detalles", "fieldErrors": [] }
```

```json
// 404 · RECURSO_NO_ENCONTRADO (línea de otro pedido)
{ "timestamp": "2026-10-30T18:51:26.241470522", "status": 404, "error": "Not Found",
  "code": "RECURSO_NO_ENCONTRADO",
  "message": "No se encontró la línea de detalle con id 2.",
  "path": "/api/v1/pedidos/15/detalles/2", "fieldErrors": [] }
```

**Códigos:** `PEDIDO_INEXISTENTE` 404; `PEDIDO_NO_EDITABLE`, `PRODUCTO_INEXISTENTE`,
`PRODUCTO_INACTIVO`, `PRODUCTO_DUPLICADO` y `STOCK_INSUFICIENTE` 409; `CANTIDAD_INVALIDA` y
`PARAMETRO_INVALIDO` 400; línea de otro pedido `RECURSO_NO_ENCONTRADO` 404; producto inexistente
en stock `RECURSO_NO_ENCONTRADO` 404; `NO_AUTENTICADO` 401; `SIN_PERMISO` y `USUARIO_INACTIVO`
403.

## FEFO, bloqueo y trazabilidad

```
POST /pedidos/{id}/detalles
  ├─ pedido existe y admite cambios (R-09)
  ├─ cantidad > 0 (CA-03)
  ├─ producto existe y está activo (CA-02)
  ├─ producto no repetido (R-06)
  ├─ pre-chequeo de stock con nombre del producto (CA-04)
  ├─ INSERT detalle_pedido                 ← primero, para tener id_origen (R-10)
  ├─ registrarSalidaFefo:
  │     SELECT v_lotes_disponibles_fefo ORDER BY fecha_vencimiento, id_lote (R-02)
  │     SELECT ... FOR UPDATE ORDER BY id_lote (R-05)
  │     revalidar stock sobre saldos bloqueados (R-05)
  │     por lote: UPDATE saldo + INSERT SALIDA_VENTA (R-10)
  └─ COMMIT: línea y kardex juntos, o ninguno
```

Si la salida FEFO falla después del `INSERT`, la transacción revierte la línea y ningún lote
queda descontado. Por eso el orden importa: invertirlo dejaría una línea sin trazabilidad o un
descuento sin línea.

## Evidencia (pruebas backend)

- **Suite completa:** `mvn test` ejecutado el 2026-10-30 con **1360 pruebas, 0 fallos, 0 errores
  y 0 omitidas** (informes de Surefire; la suite incluye las pruebas de integración contra
  MySQL). La corrida se hizo al cerrar la documentación.
- **Pruebas focalizadas por PR** (cada fila es la suite que se corrió al cerrar ese PR):

  | PR | Pruebas focalizadas |
  |---|---|
  | #115 · FEFO e inventario PT | `InventarioPtServiceTest` 21 + `InventarioPtRepositoryTest` 2 |
  | #116 · Persistencia y DTOs | `DetallePedidoRepositoryTest` 7 |
  | #117 · Servicio listado/alta | `DetallePedidoServiceTest` 15 + `DetallePedidoFefoIntegrationTest` 11 |
  | #118 · Servicio edición/eliminación | `DetallePedidoServiceTest` 30 + `DetallePedidoEdicionIntegrationTest` 6 + `PedidoRepositoryTest` 10 + `PedidoCancelacionIntegrationTest` 1 |
  | #119 · Controladores y seguridad | `DetallePedidoServiceTest` 32 + `DetallePedidoControllerTest` 20 + `DetallePedidoSeguridadTest` 45 |
  | #120 · Fix del mensaje de stock en carrera | `DetallePedidoServiceTest` 33 + `DetallePedidoControllerTest` 20 |

  Las cifras de `DetallePedidoServiceTest` crecen entre PRs porque es la misma clase; el número
  final es 33.
- **Cobertura de las reglas:** un lote, varios lotes, stock exacto, stock insuficiente, exclusión
  de vencidos, orden FEFO con alta invertida, concurrencia por la última unidad, precio copiado,
  duplicado por comprobación y por `UNIQUE`, ajuste por diferencia al aumentar y reducir,
  devolución neta al eliminar, cancelación que netea el kardex, `editable` en los cuatro estados
  y la matriz de roles y la batería SEC-01…SEC-05 sobre los cinco endpoints.
- El estado de QA va en la fila `Estado QA` de la cabecera, no aquí.

## QA ciego (2026-10-30)

Ejecutado contra la API viva en `http://127.0.0.1:9211` y MySQL 8.4, sin leer el código Java ni
la colección Postman; solo la especificación, la tarea de QA y el contrato.

| Batería | Resultado |
|---|---|
| CP-01…CP-24 | 23 PASA · CP-22 **NO VERIFICABLE por API** · 0 fallos |
| SEC-01…SEC-05 × 5 endpoints | 25/25 PASA |
| FEFO | Correcto incluso con el alta de lotes en orden inverso |
| Concurrencia CP-09 | Una petición `201`, otra `409`; stock final 0 y ningún saldo negativo |
| Precio congelado CP-14 | La línea conserva `1200.00` después de subir el catálogo |
| Ajuste por diferencia CP-16/CP-17 | Aumentar generó una sola `SALIDA_VENTA` de 5; reducir, una sola `ENTRADA_DEVOLUCION` de la diferencia |
| Cancelación tras reducción | Devolvió solo el neto con origen `pedidos`; el stock volvió al valor inicial |
| Defectos críticos/altos/medios | 0 |

- **CP-22 (transaccionalidad) no es verificable por API.** Provocar un fallo interno después del
  primer descuento exige tocar el esquema o los triggers del servidor en vivo, algo que el QA
  ciego no hace. Está cubierto por una prueba automatizada de rollback con espía que verifica
  que, si la salida falla, no queda ningún lote descontado ni línea creada.
- **DEF-01 (Baja), corregido en el PR #120.** En la carrera por el último ítem, el mensaje de
  `STOCK_INSUFICIENTE` salía del inventario con id de producto y dos decimales
  (`"Solo hay 0.00 unidades disponibles del producto 813…"`) mientras el alta normal usaba
  nombre y enteros. El servicio ahora relee el saldo y responde el mismo texto legible en los
  dos caminos; se re-testeó y pasa.
- Los datos de la prueba se limpiaron al terminar (pedidos, líneas, movimientos, lotes, productos,
  clientes y usuarios del sufijo de QA).

## Desviaciones vs especificación

- **El listado no es `PageResponse`.** La especificación solo pide `GET /pedidos/{id}/detalles`;
  la implementación devuelve `DetallePedidoListaResponse(idPedido, estado, editable, detalles,
  totalPedido, cantidadItems)`, sin paginación, igual que el detalle de compra de HU-14. El
  detalle de un pedido es un conjunto acotado y la pantalla lo consume completo.
- **`lotesConsumidos` se reconstruye del kardex en todas las lecturas.** El ejemplo de la
  especificación solo lo muestra en el `201`; el `GET` y el `PUT` también lo devuelven, para que
  la pantalla de detalle y la edición muestren la trazabilidad sin una consulta extra.
- **El `GET` no exige estado ni producto activo.** Un pedido `ENTREGADO` o `CANCELADO` se lista
  con `editable: false`, y el stock de un producto desactivado sigue consultable. La actividad
  del producto (CA-02) solo se exige al agregar una línea.
- **R-09 del detalle es más amplia que R-05 de la cabecera.** El detalle se edita en
  `PENDIENTE` y `EN_PREPARACION`; la cabecera de HU-16 solo en `PENDIENTE`. Es una diferencia
  consciente: preparar el pedido no impide corregir cantidades, pero cambiar cliente o fecha
  después de iniciar la preparación sí se bloquea.
- **El mensaje de `STOCK_INSUFICIENTE` de la carrera se unificó (PR #120).** Antes el camino de
  concurrencia heredaba el texto del inventario; ahora ambos caminos usan nombre y cifras sin
  decimales sobrantes. DEF-01 quedó cerrado.
- **Una línea sin kardex aborta la edición.** Si una fila de `detalle_pedido` se siembra por SQL
  sin sus movimientos (como hacían los datos de QA de HU-16), reducir o eliminar responde
  `500 ERROR_INTERNO` con el mensaje de inconsistencia del kardex. Es una defensa deliberada:
  el flujo real de la API siempre escribe línea y kardex en la misma transacción. No afecta al
  contrato ni al frontend.

## Defectos y cierre

| ID | Hallazgo | Estado |
|---|---|---|
| DEF-01 | Mensaje de `STOCK_INSUFICIENTE` inconsistente en la carrera por el último ítem | Cerrado en PR #120 y re-testeado |

**PRs:** #115 (FEFO y `InventarioPtService`/`InventarioPtRepository`), #116 (persistencia JDBC y
DTOs), #117 (servicio de listado y alta), #118 (servicio de edición/eliminación y neteo de la
cancelación), #119 (controladores y seguridad) y #120 (fix del mensaje de stock en carrera),
mergeados en `HU-17/productos-pedido`; esta documentación va como PR6 sobre `HU-17/pr6-docs`.

## Pendientes / deuda

- **Frontend:** no existe pantalla de detalle de pedido. El manual de consumo es el contrato
  para Angular: selector de producto con stock, previsualización FEFO, cantidad con máximo
  orientativo, edición en `PENDIENTE`/`EN_PREPARACION` y manejo del `409` por carrera.
- **CP-22 no verificable por API:** queda cubierto por la prueba automatizada de rollback; si
  QA necesita evidencia manual, requiere una prueba de integración, no una petición HTTP.
- **Datos de QA:** la colección crea usuarios, cliente, productos, lotes y pedidos en el entorno
  local; CP-08 necesita envejecer un lote por SQL porque HU-19 no admite vencimientos pasados.
  No son datos de negocio ni viajan con el repositorio.
- **HU-18:** la devolución posterior a la entrega no reutiliza este subrecurso. `ENTREGADO` es
  terminal para el detalle; HU-18 debe registrar su propio documento y mover stock con su origen.
