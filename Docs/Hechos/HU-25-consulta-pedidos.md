# HU-25 — Consulta de pedidos · Backend verificado

| | |
|---|---|
| Fecha de implementación | 2026-11-05 |
| Commits / PR | PRs #144, #145, #147, #148 y #150 a `HU-25/consulta-pedidos`; este cambio añade la documentación |
| Colección Postman | `Docs/Postman/ProductionFood-HU25.postman_collection.json` (`Preparación`, `Listado con filtros (CP-…)`, `Resumen`, `Por-cliente`, `Productos más vendidos`, `Escrituras reservadas (405)`, `Seguridad (SEC-01…SEC-05)`) |
| Estado QA | **QA ciego verificado 2026-11-05 · CP-01…CP-25 pasan, SEC-01…SEC-05 pasan con dos reservas (SEC-03 y SEC-05, ver Evidencia); DEF-H25-001 reparado en el momento · frontend pendiente** |

> Esta ficha documenta el backend de la historia. La verificación en caliente se ejecutó el
> 2026-11-05 contra la API real en `:8086` y MySQL, en modo ciego (solo la especificación, los
> casos de QA y el contrato transversal): CP-01…CP-25 pasaron y la batería SEC-01…SEC-05
> sobre los 4 endpoints pasó con dos reservas. El único defecto real, DEF-H25-001 (el ranking
> contaba pedidos en curso), se reparó en el momento en el PR #150. El consumo desde el
> frontend sigue pendiente. Los ejemplos salen de la especificación, de los DTO reales y de la
> ejecución de QA; los ids dependen de la base. El contrato de consumo está en el manual
> [`../Manuales_Consumo_Frontend/HU-25-consulta-pedidos.md`](../Manuales_Consumo_Frontend/HU-25-consulta-pedidos.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/pedidos` | ADMIN, VENTAS, PRODUCCION, CONSULTA | 200/400/401/403/404 | Listado paginado con filtros `AND` (cliente, rango, estado), `sort` de lista blanca, importes `totalBruto`/`devoluciones`/`totalNeto` e `incluirDetalle`; el `idCliente` inexistente responde `404 CLIENTE_INEXISTENTE` |
| GET | `/api/v1/pedidos/resumen` | ADMIN, VENTAS | 200/400/401/403 | Resumen del período por situación de venta: `entregados`, `enCurso`, `cancelados`, `ventaBruta`, `devoluciones`, `ventaNeta` y `ticketPromedio` |
| GET | `/api/v1/pedidos/por-cliente` | ADMIN, VENTAS | 200/400/401/403 | Ventas agrupadas por cliente, ordenadas por total y con `porcentaje` |
| GET | `/api/v1/pedidos/productos-mas-vendidos` | ADMIN, VENTAS | 200/400/401/403 | Ranking de productos por unidades, solo con pedidos `ENTREGADO` |
| POST, PUT, PATCH, DELETE | `/api/v1/pedidos/resumen`, `/api/v1/pedidos/por-cliente`, `/api/v1/pedidos/productos-mas-vendidos` | — | 405 | Paths reservados: `escriturasNoPermitidas` responde `405 METODO_NO_PERMITIDO` (R-01) |

**Roles.** El listado lo leen `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`. Los tres reportes los
reserva la matriz a `ADMIN` y `VENTAS`: `PRODUCCION`, `CONSULTA` y `COMPRAS` reciben
`403 SIN_PERMISO` en esos tres (y `COMPRAS` también en el listado). La pantalla de tablero se
muestra solo a los roles que el backend admite; ocultar la pestaña no sustituye al `403`.

Piezas internas:

| Pieza | Rol |
|---|---|
| `PedidoResponse` | Se amplía con `totalBruto`, `devoluciones`, `totalNeto` y el campo opcional `detalle`; `totalPedido` se conserva tal como lo entregó HU-16 |
| `ResumenPedidosResponse` | DTO del resumen: `Periodo` (omitido si no se enviaron fechas), el bloque `TotalesEstado` de cada situación (`entregados`, `enCurso`, `cancelados`) y las cifras derivadas |
| `VentasClienteResponse` | Fila de `/por-cliente`: `cliente` (`idCliente`, `nombre`), `cantidadPedidos`, `total` y `porcentaje` (escala 2 con `HALF_UP`) |
| `ProductoVendidoResponse` | Fila del ranking: `producto`, `unidadesVendidas`, `importe` y `pedidos` |
| `ResumenPedidoEstadoFila` / `ResumenPedidoEstadoFilaRowMapper` | Fila de `resumenPorPeriodo`: cantidad, total y devoluciones aprobadas agrupados por estado en una sola consulta |
| `VentaClienteFila` / `VentaClienteFilaRowMapper` | Fila de `ventasPorCliente`: pedidos no cancelados, ordenados por total descendente y por nombre a igual total |
| `ProductoVendidoFila` / `ProductoVendidoFilaRowMapper` | Fila de `productosMasVendidos`: solo `ENTREGADO`, ordenada por unidades, importe y nombre |
| `PedidoDetalleFila` / `PedidoDetalleFilaRowMapper` | Fila plana cabecera + línea del `LEFT JOIN` de `buscarConDetalle`, con la bandera `conDetalle()` para distinguir «pedido sin líneas» (responde `detalle: []`) de «detalle no pedido» |
| `PedidoRepository.buscarConDetalle` | Una sola consulta con `LEFT JOIN` a `detalle_pedido`: la página de cabeceras se pagina **antes** de unir el detalle y la agrupación es en memoria con `LinkedHashMap`, que preserva el orden de primera aparición — nunca una consulta por pedido |
| `ClienteInexistenteFiltroException` | `404 CLIENTE_INEXISTENTE` del filtro `idCliente` (solo existencia, no actividad): distinto del mismo `code` de las altas de HU-16, que ahí es `409` porque es un conflicto de negocio |
| `PedidoController.escriturasNoPermitidas` | `405 METODO_NO_PERMITIDO` para `POST`/`PUT`/`PATCH`/`DELETE` sobre los tres paths de reporte, sin `@PreAuthorize` porque no toca datos |
| Whitelist `ORDEN` (`PedidoService`) | `fechaPedido → p.fecha_pedido`, `fechaEntrega → p.fecha_entrega`, `estado → p.estado`, `total → total_pedido` (alias del `SELECT`, el bruto) e `id → p.id_pedido`; por defecto `fechaPedido,desc` y desempate por `id` descendente. `PageRequest.of` rechaza cualquier campo fuera de la lista |
| `TAMANO_MAXIMO_CON_DETALLE = 20` | Tope de página cuando el listado se pide con detalle (R-06): el recorte va antes de `PageRequest.of` y la respuesta refleja el tamaño recortado, sin responder `400` |

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| R-01 | **Solo lectura.** Amplía los filtros del listado de HU-16 y agrega tres reportes; el estado de un pedido se sigue cambiando en HU-16. Los paths de reporte están reservados: cualquier método de escritura responde `405 METODO_NO_PERMITIDO` en lugar de caer en el `PUT /pedidos/{id}` de HU-16 (que respondería `400 PARAMETRO_INVALIDO` al no poder convertir «resumen» a entero) |
| R-02 | **Importes siempre al precio histórico.** Todo total es `SUM(cantidad × precio_unitario)` sobre `detalle_pedido`, recalculado en cada consulta; nunca `productos.precio`. Cambiar el precio del catálogo no mueve ningún total ya vendido (CP-14) |
| R-03 | **Cancelados fuera de la venta, comprometido aparte.** El resumen informa `entregados`, `enCurso` (`PENDIENTE` + `EN_PREPARACION`, venta comprometida) y `cancelados` en bloques separados; `ventaBruta` es solo `entregados.total`, sin sumar lo comprometido. El ranking cuenta únicamente `ENTREGADO` y `/por-cliente` excluye los `CANCELADO` |
| R-04 | **Devoluciones aprobadas descuentan.** `devoluciones` es la suma de `cantidad_devuelta × precio_unitario` de las devoluciones `APROBADA` (las `PENDIENTE` y `RECHAZADA` no cuentan); `totalNeto = totalBruto − devoluciones` por pedido y `ventaNeta = ventaBruta − devoluciones` en el resumen, donde las devoluciones son solo las de pedidos entregados. Se muestran bruto, devoluciones y neto para no ocultar el volumen de devoluciones |
| R-05 | **Rango inclusivo en los dos extremos.** `fecha_pedido` es `DATE` y la comparación es `>= fechaInicio AND <= fechaFin`. Un rango invertido se rechaza con `400 RANGO_FECHAS_INVALIDO` antes de tocar la base, igual en los cuatro endpoints; un rango de un solo día o con un solo límite es válido |
| R-06 | **Sin N+1.** `incluirDetalle=true` resuelve cabecera y líneas en una única consulta con `LEFT JOIN` y agrupa en memoria; la página, el detalle y `totalElements` se leen dentro de la misma `@Transactional(readOnly = true)`. Con detalle, `size` se recorta en silencio a 20 (ver «Comportamientos conocidos») |
| R-07 | **Ranking por unidades, solo entregados.** Se ordena por `unidadesVendidas` descendente, luego por `importe` descendente y por nombre; el importe se expone como columna adicional y `pedidos` cuenta pedidos distintos. Cuentan solo los `ENTREGADO`: un pedido `PENDIENTE`, `EN_PREPARACION` o `CANCELADO` no suma (DEF-H25-001) |

## Modelo de datos

**Sin migración Flyway nueva.** La historia solo lee el esquema existente: V1 ya crea `pedidos`,
`detalle_pedido` y `devoluciones`; V2 añadió `detalle_pedido.precio_unitario` (el precio
histórico de R-02) y el índice `idx_pedidos_fecha (fecha_pedido, estado)`, que cubre los
filtros de fecha y estado del listado.

`totalPedido`, `totalBruto`, `devoluciones`, `totalNeto` y `cantidadItems` **no son columnas de
`pedidos`**: el listado los calcula con subconsultas correlacionadas sobre `detalle_pedido` y
`devoluciones` (con `COALESCE` para los pedidos sin líneas). El alias `total_pedido` del `SELECT`
es la única forma en que `sort=total` ordena, y ordena por el bruto: no existe una columna de
total que se desactualice.

## Contrato resumido

**GET /api/v1/pedidos?idCliente=7&fechaInicio=2026-09-01&fechaFin=2026-09-18&estado=ENTREGADO → 200**
(`PageResponse<PedidoResponse>`):

```json
{
  "content": [
    { "idPedido": 45, "cliente": { "idCliente": 7, "nombre": "Tienda La Esquina" },
      "fechaPedido": "2026-09-16", "fechaEntrega": "2026-09-16",
      "estado": "ENTREGADO", "totalPedido": 8400.00,
      "totalBruto": 8400.00, "devoluciones": 0.00, "totalNeto": 8400.00,
      "cantidadItems": 2, "editable": false }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

Con `incluirDetalle=true` cada pedido trae además `"detalle": [...]` con las líneas
(`idDetallePedido`, `producto`, `cantidad`, `precioUnitario` histórico y `subtotal`; los campos
nulos `lotesConsumidos` y `totalPedido` de la línea no se serializan); sin el parámetro el campo
**no se serializa** y con él la página se recorta a 20. Una página fuera de rango responde
`200` con `content: []`, no un `404`.

**GET /api/v1/pedidos/resumen?fechaInicio=2026-09-01&fechaFin=2026-09-30 → 200**:

```json
{
  "periodo": { "desde": "2026-09-01", "hasta": "2026-09-30" },
  "entregados": { "cantidad": 24, "total": 1840000.00 },
  "enCurso":    { "cantidad":  6, "total":  320000.00 },
  "cancelados": { "cantidad":  2, "total":   45000.00 },
  "ventaBruta":      1840000.00,
  "devoluciones":      62000.00,
  "ventaNeta":       1778000.00,
  "ticketPromedio":    76666.67
}
```

Período sin pedidos → `200` con las cantidades en `0` y los totales en `0.00`, **sin el campo
`ticketPromedio`**. Sin fechas, `periodo` no se serializa.

**GET /api/v1/pedidos/por-cliente?fechaInicio=2026-09-01&fechaFin=2026-09-30 → 200**
(array, sin paginación):

```json
[
  { "cliente": { "idCliente": 7, "nombre": "Supermercado Central" },
    "cantidadPedidos": 9, "total": 842000.00, "porcentaje": 40.31 },
  { "cliente": { "idCliente": 3, "nombre": "Panadería El Trigo" },
    "cantidadPedidos": 5, "total": 521000.00, "porcentaje": 24.94 }
]
```

Ordenado por `total` descendente (a igual total, por nombre); los porcentajes suman 100,00 con
residuo de redondeo. Sin pedidos en el rango → `200` con `[]`.

**GET /api/v1/pedidos/productos-mas-vendidos?fechaInicio=2026-09-01&fechaFin=2026-09-30 → 200**
(array, sin paginación):

```json
[
  { "producto": "Pan francés", "unidadesVendidas": 1240.00,
    "importe": 1488000.00, "pedidos": 18 },
  { "producto": "Croissant", "unidadesVendidas": 320.00,
    "importe": 800000.00, "pedidos": 12 }
]
```

Ordenado por unidades (R-07). Sin entregados en el rango → `200` con `[]`.

**Errores:** `RANGO_FECHAS_INVALIDO` (400, en los cuatro endpoints); `PARAMETRO_INVALIDO` (400,
`estado` no canónico, `size` fuera de `1..100`, `page` inválido o fecha mal formada) y
`CAMPO_ORDEN_INVALIDO` (400, campo de `sort` fuera de la whitelist); `CLIENTE_INEXISTENTE` (404
**en el filtro** —el mismo `code` en el alta de HU-16 es `409`, porque ahí es un conflicto de
negocio—); `METODO_NO_PERMITIDO` (405, escritura sobre los tres paths de reporte);
`NO_AUTENTICADO` (401); `SIN_PERMISO` y `USUARIO_INACTIVO` (403).

## Qué no cambia del contrato de HU-16

- **Rutas, métodos y códigos** del alta (`POST /pedidos`), la edición (`PUT /pedidos/{id}`), el
  cambio de estado (`PATCH /pedidos/{id}/estado`) y la lectura por id (`GET /pedidos/{id}`).
- **`totalPedido`** sigue en la respuesta, con el mismo valor que antes; `totalBruto` es su
  gemelo, igual a él. Se añaden campos, no se quita ninguno.
- **Los filtros del listado** (`idCliente`, `estado`, `fechaInicio`, `fechaFin`), la paginación
  (`1 ≤ size ≤ 100`) y el orden por defecto `fechaPedido,desc` con desempate por `id`.
- **La whitelist de `sort`** solo crece: `total` se suma a `fechaPedido`, `fechaEntrega`,
  `estado` e `id`.
- **`CLIENTE_INEXISTENTE` sigue siendo `409`** en el alta y la edición de HU-16; el `404` es solo
  del filtro del listado.
- **`GET /pedidos/{id}`** comparte `PedidoResponse`: trae los campos de importe y no trae
  `detalle`.

## Evidencia (verificación backend)

- **QA ciego del 2026-11-05** contra la API real en `:8086` y MySQL: **CP-01…CP-25 pasan**.
- **Batería de seguridad SEC-01…SEC-05** sobre los 4 endpoints: **pasa con dos reservas**.
  SEC-03 (token expirado) no fue ejecutable sin el secreto JWT para firmar un token caducado;
  SEC-05 quedó sin probar en el login directo de un usuario que ya estaba inactivo.
- **CP-23:** el `EXPLAIN` sobre `idx_pedidos_fecha` lo comprobó QA (el plan usa el índice); no
  quedó como prueba automática.
- **DEF-H25-001 (D-01):** el ranking contaba también pedidos en curso; reparado en el PR #150 y
  re-testeado en el momento: el ranking cuenta solo `ENTREGADO`.
- El estado de QA va en la fila `Estado QA` de la cabecera, no aquí.

## Desviaciones vs especificación

- **`size` con `incluirDetalle=true` se recorta a 20 en vez de responder `400`.** R-06 lo
  permite; la cabecera de la respuesta refleja el tamaño recortado. Sin detalle, la cota sigue
  siendo la habitual de 100 y fuera de rango responde `400 PARAMETRO_INVALIDO`.
- **`ticketPromedio` no se serializa cuando no hay pedidos entregados.** El ejemplo de la
  especificación solo muestra el caso con ventas; con cero entregados el campo es `null` y la API
  omite los nulos.
- **`/por-cliente` suma entregados y en curso, en bruto.** Excluye solo los `CANCELADO` y no
  descuenta devoluciones; la especificación no lo precisa (ver «Comportamientos conocidos»).
- **`CLIENTE_INEXISTENTE` del filtro es `404` por existencia, no por actividad:** un cliente
  desactivado conserva su historial y se puede consultar.

## Defectos y cierre

| ID | Hallazgo | Estado |
|---|---|---|
| DEF-H25-001 (D-01) | El ranking de `productos-mas-vendidos` contaba los pedidos en curso (y no solo los `ENTREGADO`), inflando las unidades de los productos con pedidos abiertos | **Corregido en PR #150**: el ranking cuenta solo `ENTREGADO` (R-07); reparado y re-testeado en el momento |

Ninguno quedó abierto como crítico o alto.

**PRs:** #144, #145, #147, #148 y #150, todos mergeados en `HU-25/consulta-pedidos`; esta
documentación va como un PR más sobre la misma rama.

## Comportamientos conocidos / puntos a confirmar

Cosas que el backend hace hoy y que conviene saber o confirmar; ninguna es un defecto abierto
crítico.

- **(a) `ticketPromedio` no se serializa sin entregados.** En un rango sin pedidos entregados el
  resumen responde las cifras en `0` y **sin** el campo `ticketPromedio` (es `null` y la API omite
  los nulos). El frontend debe tratar su ausencia como «sin ticket», no como `0`.
- **(b) El recorte de `size` con detalle es silencioso.** Con `incluirDetalle=true` un `size`
  mayor que 20 se recorta a 20 sin avisar, en lugar de responder `400`. R-06 lo permite, a
  diferencia del tope de 100 sin detalle, que sí rechaza con `400`.
- **(c) `/por-cliente` suma entregados y en curso, en bruto y sin descontar devoluciones.** La
  especificación no precisa qué estados cuenta. **A confirmar con el analista:** el diseño sugiere
  que sus totales cuadran con la venta neta del resumen, y hoy no lo hacen (incluye lo comprometido
  y no resta devoluciones).
- **(d) Sin rango de fechas, los reportes devuelven todo el histórico.** `/resumen`,
  `/por-cliente` y el ranking sin `fechaInicio`/`fechaFin` agregan toda la tabla de pedidos.
- **(e) Transversal fuera de alcance.** El mensaje del `401` sin token dice «sesión expirada» y el
  `Content-Type` de las respuestas no lleva `charset`; son comportamientos de la capa de seguridad
  global, no de esta historia.
- **(f) El `EXPLAIN` no es una prueba automática.** QA comprobó (CP-23) que la consulta por fecha y
  estado usa `idx_pedidos_fecha`, pero esa verificación no quedó como prueba que corra con la suite.

## Pendientes / deuda

- **Confirmar con el analista el alcance de `/por-cliente`** (punto c): estados que cuenta y si
  debe descontar devoluciones.
- **Frontend:** no existe pantalla de consulta de pedidos; el manual de consumo es el contrato
  para Angular, que debe pintar las tarjetas (resumen), las ventas por cliente, el ranking y la
  tabla con filtros `AND`, cargando el detalle al expandir la fila. Nota de diseño: mostrar `—` y
  no `$ 0` en devoluciones cuando son 0.
- **Integración con HU-22:** los README de `Docs` se actualizan al integrar con esa historia.
- **Datos de QA en la base:** la colección crea usuarios, clientes, productos y pedidos de prueba
  en el entorno local; no son datos de negocio ni viajan con el repositorio.
