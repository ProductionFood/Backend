# HU-18 — Registro de devoluciones · HECHA

| | |
|---|---|
| Fecha de implementación | 2026-11-02 |
| Commits / PR | PRs #123–#127 a `HU-18/registro-devoluciones`; este cambio añade la documentación (PR documental sobre `HU-18/pr5-docs`) |
| Colección Postman | `Docs/Postman/ProductionFood-HU18.postman_collection.json` (`Preparación`, `Registro (CP-01…CP-14)`, `Aprobación y reposición (CP-15…CP-25)`, `Consulta y filtros`, `Seguridad (SEC-01…SEC-05)`) |
| Estado QA | **QA ciego verificado 2026-11-02 · 25/25 CP y 25/25 SEC · frontend pendiente** |

> Esta ficha documenta el registro de devoluciones de una línea de pedido, su consulta
> paginada y la aprobación que repone stock en los lotes de los que salió la venta. El pedido
> y sus líneas son de HU-16 y HU-17; la devolución no reutiliza el subrecurso de detalle, crea
> su propio documento y mueve inventario con origen `devoluciones`. El contrato de consumo
> está en el manual
> [`../Manuales_Consumo_Frontend/HU-18-registro-devoluciones.md`](../Manuales_Consumo_Frontend/HU-18-registro-devoluciones.md).

## Objetivo y criterios de aceptación

**Como** vendedor, **quiero** registrar devoluciones de un pedido con motivo, descripción y
cantidad, **para que** controle las devoluciones de producto.

| CA | Enunciado | Cómo se implementó |
|---|---|---|
| CA-01 | `id_detalle_pedido` debe existir | `DetallePedidoRepository.obtenerPorIdParaActualizar`; si no existe, `409 DETALLE_PEDIDO_INEXISTENTE` |
| CA-02 | `motivo` obligatorio, máximo 50 caracteres | `@NotBlank` + `@Size(max = 50)` en `CrearDevolucionRequest`; un valor inválido responde `400 VALIDACION_FALLIDA` |
| CA-03 | `descripcion` opcional, máximo 255 caracteres | `@Size(max = 255)`; cuando falta, la respuesta conserva la clave con `null` |
| CA-04 | `cantidad_devuelta` obligatoria y no puede exceder la cantidad del pedido | Se compara contra el acumulado de `APROBADA + PENDIENTE` de la línea, no contra cada devolución; el exceso responde `422 CANTIDAD_EXCEDE_PEDIDO` |
| CA-05 | `fecha_devolucion` se registra automáticamente | El `INSERT` omite la columna y actúa `DEFAULT (curdate())` de V2 §C-07; el DTO no expone el campo |
| CA-06 | `estado` obligatorio | Enum `EstadoDevolucion` con `CHECK` en V2 §C-12; el alta lo fija en `PENDIENTE` y el cambio se hace por la máquina de estados |
| CA-07 | Al aprobarse, la cantidad vuelve al inventario de producto terminado | `InventarioPtService.registrarEntrada` con tipo `ENTRADA_DEVOLUCION`, origen `devoluciones` e `id_origen` de la devolución |

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/devoluciones` | ADMIN, VENTAS, CONSULTA | 200/400/401/403 | Listado paginado con filtros por estado, rango de fechas e id de pedido; orden por `fechaDevolucion`, `estado` o `id` |
| GET | `/api/v1/devoluciones/{id}` | ADMIN, VENTAS, CONSULTA | 200/400/401/403/404 | Devuelve una devolución con su pedido, cliente, producto y el compromiso de la línea |
| GET | `/api/v1/pedidos/{idPedido}/devoluciones` | ADMIN, VENTAS, CONSULTA | 200/400/401/403/404 | Subrecurso con las devoluciones de un pedido; página vacía si existe y no tiene ninguna |
| POST | `/api/v1/devoluciones` | ADMIN, VENTAS | 201/400/401/403/409/422 | Registra una devolución en `PENDIENTE`; `Location: /api/v1/devoluciones/{id}`; no mueve stock |
| PATCH | `/api/v1/devoluciones/{id}/estado` | ADMIN, VENTAS | 200/400/401/403/404/409 | Aprueba (repone stock en los lotes de origen) o rechaza (no toca inventario) |

**Roles.** La matriz de roles (`../../../PFDocs/00-base/03-MATRIZ-ROLES.md`, filas
`Devoluciones` y `Aprobar devolución`) **manda sobre la tabla de la especificación §2**:
lectura para `ADMIN`, `VENTAS` y `CONSULTA`; escritura —registrar y cambiar estado— solo para
`ADMIN` y `VENTAS`. `PRODUCCION` no accede al módulo ni siquiera en lectura, aunque la
especificación lo liste en los tres `GET`; `COMPRAS` tampoco participa. Es la decisión ya
acordada para la historia y es la que aplican `@PreAuthorize` y la batería de seguridad.

Piezas internas:

| Pieza | Rol |
|---|---|
| `EstadoDevolucion` | Enum `PENDIENTE`, `APROBADA`, `RECHAZADA` y la máquina de transiciones de R-04; **vive solo aquí**, el servicio delega en `puedeTransicionarA` |
| `Devolucion` / `DevolucionRowMapper` | Registro inmutable de la tabla con las proyecciones del `JOIN` (pedido, cliente, producto y cantidad pedida); el mapper lee por nombre de columna |
| `DevolucionFila` | Fila del listado con `comprometidaLinea`, el acumulado de la línea calculado en el mismo `SELECT` |
| `DevolucionRepository` | JDBC de `insertar`, `buscarPorId`, `cambiarEstado` condicionado, `buscar`, `contar`, `sumarDevueltoPorDetalle`; lista blanca `ORDEN` y `SELECT` compartido con cuatro `JOIN` |
| `DevolucionService` | Reglas R-01 a R-07: validaciones del alta, bloqueo de la línea, acumulado, máquina de estados, `UPDATE` condicionado y reparto del kardex inverso. `@Transactional` y `@Auditable` en las escrituras |
| `DevolucionController` | Los cinco endpoints con `@PreAuthorize`, Swagger, `201 + Location`; **no hay `DELETE`** y no debe añadirse |
| `CrearDevolucionRequest` | `idDetallePedido`, `motivo` (`@NotBlank`, máx. 50), `descripcion` (máx. 255) y `cantidadDevuelta`; no expone fecha ni estado |
| `CambiarEstadoDevolucionRequest` | Solo `estado`; un literal no canónico falla en la deserialización y responde 400 |
| `DevolucionResponse` | Pedido y cliente embebidos, producto como `ProductoRefResponse`, cantidades derivadas y `descripcion` con `@JsonInclude(ALWAYS)` |
| `AprobacionResponse` / `LoteRepuesto` | Estado final y `lotesRepuestos` con código, cantidad, saldo anterior y posterior de cada entrada |
| `InventarioPtService.registrarEntrada` | Único componente autorizado a modificar `inventario_producto_terminado`; escribe el saldo y el kardex en la misma transacción |
| `InventarioPtRepository.movimientosDeVentaPorOrigen` | Lee las `SALIDA_VENTA` de la línea en `id_movimiento DESC`, el orden inverso al consumo de R-02 |

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| R-01 | **El stock vuelve al aprobar, nunca al registrar.** `crear` inserta `PENDIENTE` y no toca inventario; solo `cambiarEstado` con destino `APROBADA` llama a `registrarEntrada`. Registrar devuelve al estante producto que nadie ha revisado todavía |
| R-02 | **La mercancía vuelve a los lotes del kardex, en orden inverso al consumo.** `movimientosDeVentaPorOrigen("detalle_pedido", idDetallePedido)` filtra `SALIDA_VENTA` y ordena por `id_movimiento DESC`: el último lote tomado —el de vencimiento más lejano— recibe primero. Las salidas repetidas del mismo lote se agrupan en una sola entrada sin perder la posición de su primera aparición |
| R-03 | **El límite es el acumulado de la línea, no cada devolución.** `sumarDevueltoPorDetalle` suma `APROBADA + PENDIENTE` y excluye `RECHAZADA`, que libera cantidad. Sin contar las pendientes, tres devoluciones de 10 sobre una línea de 10 repondrían 30 al aprobarse. El alta bloquea la línea con `SELECT ... FOR UPDATE` (`DetallePedidoRepository.obtenerPorIdParaActualizar`) para que dos altas simultáneas no validen contra un acumulado obsoleto |
| R-04 | **Máquina de estados mínima y terminal en ambos destinos.** `PENDIENTE → APROBADA` o `PENDIENTE → RECHAZADA`; una devolución aprobada o rechazada no admite ninguna transición más. Se valida **antes** de tocar inventario, y el `UPDATE` va condicionado al estado leído: una segunda aprobación concurrente afecta cero filas y no repone dos veces |
| R-05 | **Solo pedidos `ENTREGADO` o `EN_PREPARACION`.** Un pedido `PENDIENTE` nunca sacó la mercancía y uno `CANCELADO` ya devolvió su stock entero (HU-16 R-04); aprobar una devolución encima duplicaría la reposición |
| R-06 | **La fecha la pone la base.** El `INSERT` omite `fecha_devolucion` para que actúe `DEFAULT (curdate())` de V2 §C-07; el DTO no expone el campo, así que enviarlo no tiene efecto. El `UPDATE` de estado tampoco la toca |
| R-07 | **El motivo es texto libre de hasta 50 caracteres.** La interfaz sugiere motivos frecuentes (*Producto en mal estado*, *Error en el pedido*, *Cliente insatisfecho*, *Producto vencido*) pero no hay catálogo; normalizarlo queda como mejora posible |
| 422 | **`CANTIDAD_EXCEDE_PEDIDO` responde 422, no 409.** Los datos son sintácticamente correctos —un número positivo— pero violan una regla que depende del acumulado del sistema. `ReglaDeNegocioException` la traduce a `422 Unprocessable Entity`; el 409 queda para conflictos con algo que ya existe (detalle inexistente, pedido no devolible, transición inválida) |
| Reposición | **Si el kardex no respalda la cantidad, la transacción revierte.** `reponerStock` suma las salidas de la línea y lanza `IllegalStateException` si no alcanzan; ninguna entrada falla en silencio ni deja la devolución aprobada con stock sin reponer (CP-24) |
| Auditoría | `@Auditable(accion = "REGISTRAR_DEVOLUCION")` en el alta y `@Auditable(accion = "CAMBIAR_ESTADO_DEVOLUCION")` en la transición, sobre `devoluciones`, con HU-04 |

## Modelo de datos

**Sin migración Flyway nueva.** La historia usa el esquema de V1 y las correcciones de V2:

- `devoluciones`: `id_devolucion` PK, `id_detalle_pedido` FK, `motivo` `varchar(50)`,
  `descripcion` `varchar(255)` opcional, `cantidad_devuelta` `decimal(10,2)` con
  `CHECK (cantidad_devuelta > 0)`, `fecha_devolucion` `date NOT NULL DEFAULT (curdate())`
  (V2 §C-07) y `estado` `varchar(30)` con `CHECK` (V2 §C-12).
- `detalle_pedido`: de aquí salen la cantidad pedida, el producto y el pedido de cada
  devolución; `obtenerPorIdParaActualizar` bloquea su fila en el alta.
- `movimientos_inventario_pt`: kardex por lote. La devolución se apoya en las `SALIDA_VENTA`
  con `tabla_origen = 'detalle_pedido'` e `id_origen = id_detalle_pedido`, y escribe
  `ENTRADA_DEVOLUCION` con `tabla_origen = 'devoluciones'` e `id_origen = id_devolucion`.
- `v_stock_producto_terminado`: el stock vendible excluye lotes vencidos. Si el lote de
  destino venció, la aprobación se registra igual en el kardex pero la unidad no vuelve como
  stock vendible (HU-17 R-03).

`devueltoPreviamente`, `disponibleParaDevolver` y `comprometidaLinea` **no son columnas**: se
calculan contra el acumulado de `devoluciones` de la línea. `descripcion` viaja siempre, como
`null` cuando no hay texto, porque la inclusión global de Jackson es `non_null` y el contrato
exige ver la clave.

## Contrato resumido

**POST /api/v1/devoluciones → 201** (`Location: /api/v1/devoluciones/15`):

```json
{ "idDetallePedido": 88, "motivo": "Producto en mal estado",
  "descripcion": "Tres panes llegaron aplastados en el transporte",
  "cantidadDevuelta": 3.00 }
```

```json
{
  "idDevolucion": 15,
  "pedido": { "idPedido": 47, "cliente": "Tienda La Esquina" },
  "producto": { "idProducto": 3, "nombre": "Pan francés" },
  "cantidadPedida": 30.0,
  "cantidadDevuelta": 3.0,
  "devueltoPreviamente": 0.0,
  "disponibleParaDevolver": 27.0,
  "motivo": "Producto en mal estado",
  "descripcion": "Tres panes llegaron aplastados en el transporte",
  "fechaDevolucion": "2026-11-02",
  "estado": "PENDIENTE"
}
```

Sin descripción, la clave aparece igualmente con `null` (DEF-01):

```json
{ "idDevolucion": 16, "descripcion": null, "estado": "PENDIENTE" }
```

`devueltoPreviamente` deja fuera la propia devolución cuando cuenta en el acumulado: es lo
que la línea traía antes de este registro. `disponibleParaDevolver` se calcula siempre contra
el acumulado completo, incluidas las pendientes.

**PATCH /api/v1/devoluciones/15/estado → 200** (`{"estado": "APROBADA"}`):

```json
{
  "idDevolucion": 15,
  "estado": "APROBADA",
  "lotesRepuestos": [
    { "codigoLote": "PF-2026-1017", "cantidad": 3.0,
      "saldoAnterior": 22.0, "saldoPosterior": 25.0 }
  ]
}
```

El lote repuesto es el último del que salió la línea, no el primero. Al rechazar, el estado
es `RECHAZADA` y `lotesRepuestos` llega vacío.

**GET /api/v1/devoluciones → 200** (`PageResponse`):

```json
{
  "content": [
    {
      "idDevolucion": 15,
      "pedido": { "idPedido": 47, "cliente": "Tienda La Esquina" },
      "producto": { "idProducto": 3, "nombre": "Pan francés" },
      "cantidadPedida": 30.0,
      "cantidadDevuelta": 3.0,
      "devueltoPreviamente": 0.0,
      "disponibleParaDevolver": 27.0,
      "motivo": "Producto en mal estado",
      "descripcion": null,
      "fechaDevolucion": "2026-11-02",
      "estado": "PENDIENTE"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

**Errores del contrato:**

```json
// 422 · CANTIDAD_EXCEDE_PEDIDO
{ "timestamp": "2026-11-02T20:14:08.517206113", "status": 422,
  "error": "Unprocessable Entity", "code": "CANTIDAD_EXCEDE_PEDIDO",
  "message": "Se pidieron 30 unidades y ya hay 28 devueltas o en trámite. Solo puede devolver 2.",
  "path": "/api/v1/devoluciones", "fieldErrors": [] }
```

```json
// 409 · TRANSICION_INVALIDA
{ "timestamp": "2026-11-02T20:15:02.118402912", "status": 409, "error": "Conflict",
  "code": "TRANSICION_INVALIDA",
  "message": "No se puede pasar de APROBADA a APROBADA. Desde APROBADA no hay transiciones disponibles.",
  "path": "/api/v1/devoluciones/15/estado", "fieldErrors": [] }
```

```json
// 409 · PEDIDO_NO_DEVOLVIBLE
{ "timestamp": "2026-11-02T20:15:44.320118647", "status": 409, "error": "Conflict",
  "code": "PEDIDO_NO_DEVOLVIBLE",
  "message": "El pedido con id 47 no admite devoluciones: solo los pedidos ENTREGADO o EN_PREPARACION son devolibles.",
  "path": "/api/v1/devoluciones", "fieldErrors": [] }
```

```json
// 400 · CANTIDAD_INVALIDA
{ "timestamp": "2026-11-02T20:16:11.902441823", "status": 400, "error": "Bad Request",
  "code": "CANTIDAD_INVALIDA",
  "message": "La cantidad devuelta debe ser un número mayor que cero.",
  "path": "/api/v1/devoluciones", "fieldErrors": [] }
```

**Códigos:** `DETALLE_PEDIDO_INEXISTENTE` 409; `PEDIDO_NO_DEVOLVIBLE` y
`TRANSICION_INVALIDA` 409; `CANTIDAD_EXCEDE_PEDIDO` 422; `CANTIDAD_INVALIDA`,
`VALIDACION_FALLIDA`, `PARAMETRO_INVALIDO`, `CAMPO_ORDEN_INVALIDO` y `RANGO_FECHAS_INVALIDO`
400; `RECURSO_NO_ENCONTRADO` 404; `NO_AUTENTICADO` 401; `SIN_PERMISO` y `USUARIO_INACTIVO`
403.

## Máquina de estados, bloqueo y reposición

```
POST /devoluciones
  ├─ cantidad > 0 (CA-04 · 400 CANTIDAD_INVALIDA)
  ├─ SELECT detalle_pedido FOR UPDATE      ← serializa dos altas sobre la línea (R-03)
  ├─ el detalle existe (409 DETALLE_PEDIDO_INEXISTENTE)
  ├─ pedido ENTREGADO o EN_PREPARACION (409 PEDIDO_NO_DEVOLVIBLE, R-05)
  ├─ acumulado + nueva ≤ cantidad pedida (422 CANTIDAD_EXCEDE_PEDIDO, R-03)
  ├─ INSERT devoluciones (PENDIENTE, sin fecha)  ← la fecha la pone la base (R-06)
  └─ COMMIT: sin tocar inventario (R-01)

PATCH /devoluciones/{id}/estado
  ├─ la devolución existe (404)
  ├─ máquina de estados (409 TRANSICION_INVALIDA, R-04)   ← antes de tocar stock
  ├─ UPDATE estado WHERE estado = leído                  ← 0 filas ⇒ 409, sin reponer
  ├─ si APROBADA:
  │     SALIDA_VENTA de detalle_pedido ORDER BY id_movimiento DESC (R-02)
  │     agrupar por lote y repartir la cantidad devuelta
  │     registrarEntrada(ENTRADA_DEVOLUCION, origen devoluciones/{id})
  └─ COMMIT: estado, kardex y saldos juntos, o ninguno
```

El orden importa. Validar la transición después de reponer permitiría que una segunda
aprobación duplicara el stock antes de descubrir que era ilegal. Condicionar el `UPDATE` al
estado leído —y escribirlo **antes** de reponer— cierra la ventana de carrera: solo la
transacción que gana la fila continúa hasta el kardex. Si el kardex no respalda la cantidad,
la excepción revierte la transacción entera.

## Evidencia (pruebas backend)

- **Pruebas focalizadas ejecutadas el 2026-11-02:** `DevolucionServiceTest` 23,
  `DevolucionAprobacionTest` 10, `DevolucionControllerTest` 27 y `DevolucionSeguridadTest`
  43 → **103 pruebas, 0 fallos, 0 errores, 0 omitidas**.
- **Persistencia JDBC:** `DevolucionRepositoryTest` cubre el `DEFAULT` de la fecha, los
  `JOIN`, el `UPDATE` que no toca la fecha, los filtros con su conteo, la paginación sin
  repetir ni perder filas, la lista blanca de orden y `sumarDevueltoPorDetalle` excluyendo
  las rechazadas (9 pruebas de integración contra MySQL).
- **Cobertura de las reglas:** alta en `PENDIENTE`, cantidad no positiva, detalle
  inexistente, pedido devolible y no devolible, acumulado previo, límite exacto, rechazadas
  que liberan cantidad, transiciones válidas y prohibidas, doble aprobación, pérdida de la
  carrera, kardex vacío o insuficiente, propagación del fallo al reponer, agrupación de
  salidas del mismo lote, orden inverso al consumo, filtros, paginación, orden y la matriz
  de roles con la batería SEC-01…SEC-05 sobre los cinco endpoints.
- El estado de QA va en la fila `Estado QA` de la cabecera, no aquí.

## QA ciego (2026-11-02)

Ejecutado contra la API viva y MySQL 8.4, sin leer el código Java ni la colección Postman;
solo la especificación, la tarea de QA y el contrato.

| Batería | Resultado |
|---|---|
| CP-01…CP-25 | 25/25 PASA |
| SEC-01…SEC-05 × 5 endpoints | 25/25 PASA |
| Reposición (CP-15 a CP-18) | El stock sube solo al aprobar y vuelve al lote del kardex, en orden inverso al consumo |
| Acumulado (CP-09) | Las pendientes ocupan cantidad; 8 + 5 sobre una línea de 10 responde 422 con las 2 disponibles |
| Rechazo (CP-19) | No mueve inventario; la cantidad liberada se puede volver a devolver |
| Doble aprobación (CP-20) | 409 y una sola reposición |
| Transaccionalidad (CP-24) | Cubierto por `DevolucionAprobacionTest`: si el kardex no respalda o una entrada falla, la devolución no queda aprobada |
| Defectos críticos/altos/medios | 0 |

- **DEF-01 (Baja), corregido en el PR #127.** Una devolución sin descripción perdía la
  clave en el JSON porque la inclusión global de Jackson es `non_null`, mientras el contrato
  exige `descripcion: null` (CP-05). Se añadió `@JsonInclude(ALWAYS)` en el campo de
  `DevolucionResponse`.
- **DEF-02 (Baja), corregido en el PR #127.** `GET /pedidos/{id}/devoluciones` con un pedido
  inexistente respondía una página vacía; el contrato transversal exige `404
  RECURSO_NO_ENCONTRADO`. Ahora valida la paginación primero (400 si es inválida) y luego la
  existencia del pedido; un pedido existente sin devoluciones sigue devolviendo página vacía.
- Los datos de la prueba se limpiaron al terminar (devoluciones, pedidos, líneas, movimientos,
  lotes, productos, clientes y usuarios del sufijo de QA).

## Desviaciones vs especificación

- **Roles: manda la matriz, no la tabla de la especificación.** La especificación §2 da
  lectura a `PRODUCCION`; la matriz de roles y la implementación lo dejan fuera del módulo,
  incluso en lectura. `CONSULTA` lee pero no puede aprobar (`Aprobar devolución` en la
  matriz). Queda documentado en el controlador y verificado en `DevolucionSeguridadTest`.
- **La acción auditada es `CAMBIAR_ESTADO_DEVOLUCION`, no `APROBAR_DEVOLUCION`.** El paso 7
  de la tarea pedía una acción por aprobación, pero un mismo método atiende aprobar y
  rechazar y una anotación no puede cambiar el literal según el destino. El estado pedido
  viaja en el detalle, igual que `CAMBIAR_ESTADO` de HU-16.
- **`GET /pedidos/{id}/devoluciones` responde 404 si el pedido no existe.** La primera
  versión devolvía página vacía; el defecto DEF-02 se corrigió para alinearlo con el resto de
  subcolecciones y con `04-CONTRATO-API.md` §5.
- **`descripcion` viaja siempre, como `null`.** Es una corrección de contrato (DEF-01), no un
  cambio de modelo: la columna sigue siendo opcional.
- **`producto.unidad` no se serializa.** `DevolucionResponse` reutiliza `ProductoRefResponse`
  de HU-17/HU-19, pero la consulta de devoluciones no proyecta la unidad; el campo llega
  `null` y la configuración global lo omite.
- **No hay `DELETE`.** Una devolución es el registro de mercancía devuelta y su estado es la
  trazabilidad de la reposición; borrarla rompería la bitácora y dejaría el kardex con
  entradas sin origen.

## Archivos

| Archivo | Contenido |
|---|---|
| [`EstadoDevolucion.java`](../../src/main/java/co/edu/corposucre/productionfood/devolucion/EstadoDevolucion.java) | Enum y máquina de transiciones R-04 |
| [`Devolucion.java`](../../src/main/java/co/edu/corposucre/productionfood/devolucion/Devolucion.java) | Registro de la tabla con las proyecciones del `JOIN` |
| [`DevolucionRowMapper.java`](../../src/main/java/co/edu/corposucre/productionfood/devolucion/DevolucionRowMapper.java) | Mapeo por nombre de columna |
| [`DevolucionRepository.java`](../../src/main/java/co/edu/corposucre/productionfood/devolucion/DevolucionRepository.java) | JDBC, filtros, `FOR UPDATE` y acumulado |
| [`DevolucionService.java`](../../src/main/java/co/edu/corposucre/productionfood/devolucion/DevolucionService.java) | Reglas R-01 a R-07 y reposición del kardex |
| [`DevolucionController.java`](../../src/main/java/co/edu/corposucre/productionfood/devolucion/DevolucionController.java) | Los cinco endpoints y la seguridad por rol |
| [`dto/`](../../src/main/java/co/edu/corposucre/productionfood/devolucion/dto/) | `CrearDevolucionRequest`, `CambiarEstadoDevolucionRequest`, `DevolucionResponse`, `AprobacionResponse` |
| [`src/test/.../devolucion/`](../../src/test/java/co/edu/corposucre/productionfood/devolucion/) | Servicio, aprobación, controlador, seguridad e integración JDBC |
| [`especificacion.md`](../../../PFDocs/HU-18/especificacion.md) | Criterios, reglas y códigos de la historia |
| [`tarea-backend.md`](../../../PFDocs/HU-18/tarea-backend.md) | Pasos y puntos de cuidado |
| [`tarea-qa.md`](../../../PFDocs/HU-18/tarea-qa.md) | CP-01…CP-25 y batería SEC |
| [`diseno.md`](../../../PFDocs/HU-18/diseno.md) | Bandeja, diálogos y flujo de pantalla |

## Defectos y cierre

| ID | Hallazgo | Estado |
|---|---|---|
| DEF-01 | `descripcion` desaparecía del JSON cuando era nula (CP-05) | Cerrado en PR #127 y re-testeado |
| DEF-02 | `GET /pedidos/{id}/devoluciones` no respondía 404 con un pedido inexistente | Cerrado en PR #127 y re-testeado |

**PRs:** #123 (persistencia y DTOs), #124 (servicio de registro y consulta), #125
(aprobación con reposición en kardex), #126 (controlador y seguridad por rol) y #127 (fix de
los dos defectos bajos de QA), mergeados en `HU-18/registro-devoluciones`; esta documentación
va como PR documental sobre `HU-18/pr5-docs`.

## Pendientes / deuda

- **Frontend:** no existe pantalla de devoluciones. El manual de consumo es el contrato para
  Angular: bandeja con filtros, formulario con los tres números de la línea, diálogo de
  aprobación que advierte del lote de destino y manejo del `422` por acumulado.
- **CP-24 no es verificable por API.** Provocar un fallo después de reponer exige tocar el
  esquema o el servidor en vivo; queda cubierto por la prueba automatizada de rollback.
- **Datos de QA:** la colección crea usuarios, cliente, producto, lotes y pedidos en el
  entorno local. Los pedidos base se entregan o cancelan con la API; no son datos de negocio
  ni viajan con el repositorio.
- **Catálogo de motivos:** R-07 lo deja como texto libre. Normalizarlo en un catálogo
  mejoraría los reportes, pero no está en los criterios de aceptación ni en el esquema.
