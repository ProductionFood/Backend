# HU-18 — Registro de devoluciones · Manual de consumo para el frontend

Contrato práctico para listar devoluciones, registrar una devolución sobre una línea de
pedido y aprobarla o rechazarla. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md`, el manual de pedidos en
[`HU-16-creacion-pedidos.md`](HU-16-creacion-pedidos.md), el detalle del pedido en
[`HU-17-productos-pedido.md`](HU-17-productos-pedido.md) y la ficha
[`../Hechos/HU-18-registro-devoluciones.md`](../Hechos/HU-18-registro-devoluciones.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura para `ADMIN`, `VENTAS` y `CONSULTA`; escritura (registrar, aprobar y
  rechazar) solo para `ADMIN` y `VENTAS`. `PRODUCCION` y `COMPRAS` reciben `403 SIN_PERMISO`
  en los cinco endpoints; `PRODUCCION` tampoco lee devoluciones, a diferencia de los pedidos.
- **Dependencias:** la devolución apunta a una línea de pedido de HU-17, ya descontada del
  inventario de producto terminado. Sin línea vendida no hay nada que devolver.

> **Estado:** backend verificado en caliente contra MySQL el 2026-11-02 (QA ciego:
> 25/25 CP y 25/25 SEC). El consumo desde Angular sigue pendiente. Los ids, nombres, fechas y
> cantidades dependen de la base.

---

## 1. Autenticación

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "correo": "admin@productionfood.local", "password": "Admin123*" }
```

```json
{ "token": "eyJhbGciOiJIUzM4NCJ9...",
  "usuario": { "idUsuario": 1, "nombre": "Administrador Inicial", "rol": "ADMIN" },
  "expiraEn": 3600 }
```

| Endpoint | ADMIN | VENTAS | PRODUCCION | CONSULTA | COMPRAS |
|---|---|---|---|---|---|
| `GET /devoluciones` | sí | sí | 403 | sí | 403 |
| `GET /devoluciones/{id}` | sí | sí | 403 | sí | 403 |
| `GET /pedidos/{id}/devoluciones` | sí | sí | 403 | sí | 403 |
| `POST /devoluciones` | sí | sí | 403 | 403 | 403 |
| `PATCH /devoluciones/{id}/estado` | sí | sí | 403 | 403 | 403 |

Ocultar una acción en la pantalla no sustituye al `403`, que el backend aplica igual. La
matriz de roles manda sobre la tabla de la especificación: `PRODUCCION` no accede al módulo
ni en lectura.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/devoluciones` | ADMIN / VENTAS / CONSULTA | 200, 400, 401, 403 |
| GET | `/devoluciones/{id}` | ADMIN / VENTAS / CONSULTA | 200, 400, 401, 403, 404 |
| GET | `/pedidos/{idPedido}/devoluciones` | ADMIN / VENTAS / CONSULTA | 200, 400, 401, 403, 404 |
| POST | `/devoluciones` | ADMIN / VENTAS | 201, 400, 401, 403, 409, 422 |
| PATCH | `/devoluciones/{id}/estado` | ADMIN / VENTAS | 200, 400, 401, 403, 404, 409 |

### 2.1. Listar devoluciones

```http
GET /api/v1/devoluciones?estado=PENDIENTE&fechaInicio=2026-11-01&fechaFin=2026-11-30&page=0&size=20&sort=fechaDevolucion,desc
Authorization: Bearer <token>
```

| Parámetro | Tipo | Por defecto | Regla |
|---|---|---|---|
| `estado` | texto | sin filtro | `PENDIENTE`, `APROBADA` o `RECHAZADA`; otro literal responde `400 PARAMETRO_INVALIDO` |
| `fechaInicio` | fecha ISO | sin límite inferior | `yyyy-MM-dd`, incluida; una fecha mal formada responde `400 PARAMETRO_INVALIDO` |
| `fechaFin` | fecha ISO | sin límite superior | `yyyy-MM-dd`, incluida; si es anterior a `fechaInicio`, `400 RANGO_FECHAS_INVALIDO` |
| `idPedido` | entero | sin filtro | Acota a las devoluciones de las líneas de ese pedido |
| `page` | entero | `0` | Base cero; negativo responde `400 PARAMETRO_INVALIDO` |
| `size` | entero | `20` | Entre 1 y 100 |
| `sort` | texto | `fechaDevolucion,desc` | `campo,direccion`; campos: `fechaDevolucion`, `estado`, `id`; otro campo responde `400 CAMPO_ORDEN_INVALIDO` |

```json
// 200 OK
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
      "descripcion": "Tres panes llegaron aplastados en el transporte",
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

- **Es un `PageResponse`**: la lista va en `content` y el total en `totalElements`, no en el
  tamaño de la página.
- **El orden por defecto es `fechaDevolucion,desc`.** El listado desempata por
  `id_devolucion DESC`, así que dos devoluciones del mismo día no bailan entre páginas.
- **`producto.unidad` no viaja.** La respuesta reutiliza la referencia de producto de HU-17,
  pero la consulta no proyecta la unidad; el campo llega nulo y se omite.
- **`descripcion` siempre está presente**, aunque sea `null`.
- Un `sort` con un campo fuera de la lista blanca responde `400 CAMPO_ORDEN_INVALIDO`; no se
  debe reintentar con el mismo valor.

### 2.2. Obtener una devolución

```http
GET /api/v1/devoluciones/15
Authorization: Bearer <token>
```

```json
// 200 OK
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
```

- **`devueltoPreviamente` es lo que la línea traía antes de esta devolución**, no el
  acumulado total: deja fuera la propia fila cuando está `PENDIENTE` o `APROBADA`. Una
  `RECHAZADA` no se informa como devuelta.
- **`disponibleParaDevolver` sí es el saldo real de la línea**, calculado contra todas las
  devoluciones `PENDIENTE` y `APROBADA`, incluida esta.
- Una devolución inexistente responde `404 RECURSO_NO_ENCONTRADO`; un id no numérico,
  `400 PARAMETRO_INVALIDO`.

### 2.3. Devoluciones de un pedido

```http
GET /api/v1/pedidos/47/devoluciones?page=0&size=20&sort=id,asc
Authorization: Bearer <token>
```

```json
// 200 OK
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

- **Solo acepta `page`, `size` y `sort`.** El pedido ya acota el conjunto: no se envían
  `estado` ni rango de fechas.
- **Un pedido existente sin devoluciones responde `content: []` y `totalElements: 0`.**
- **Un pedido inexistente responde `404 RECURSO_NO_ENCONTRADO`**, no una página vacía.
- La paginación se valida antes que la existencia: un `page` o `sort` inválido responde `400`
  aunque el pedido tampoco exista.

### 2.4. Registrar una devolución

```http
POST /api/v1/devoluciones
Authorization: Bearer <token>
Content-Type: application/json

{ "idDetallePedido": 88, "motivo": "Producto en mal estado",
  "descripcion": "Tres panes llegaron aplastados en el transporte",
  "cantidadDevuelta": 3.00 }
```

| Campo | Tipo | Obligatorio | Regla |
|---|---|---|---|
| `idDetallePedido` | entero | sí | Debe existir (`409 DETALLE_PEDIDO_INEXISTENTE`) y su pedido estar `ENTREGADO` o `EN_PREPARACION` (`409 PEDIDO_NO_DEVOLVIBLE`, R-05) |
| `motivo` | texto | sí | Máximo 50 caracteres (`400 VALIDACION_FALLIDA`) |
| `descripcion` | texto | no | Máximo 255 caracteres (`400 VALIDACION_FALLIDA`); si se omite, responde `null` |
| `cantidadDevuelta` | decimal | sí | Mayor que cero (`400 CANTIDAD_INVALIDA`) y sin superar lo disponible en la línea (`422 CANTIDAD_EXCEDE_PEDIDO`, R-03) |

```json
// 201 Created · Location: /api/v1/devoluciones/15
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

Sin descripción, la clave viaja igualmente:

```json
{ "idDevolucion": 16, "descripcion": null, "estado": "PENDIENTE" }
```

- **Registrar no mueve stock.** La devolución nace `PENDIENTE` y el inventario no cambia
  hasta que se aprueba (R-01). No refresques el stock después de este `201`.
- **El cuerpo no lleva `estado` ni `fechaDevolucion`.** El estado lo fija el servidor
  (`PENDIENTE`) y la fecha la pone la base; si envías una fecha, se ignora.
- **La cantidad se compara contra el acumulado**, no contra la línea en abstracto. Si la
  línea de 30 ya tiene 28 devueltas o en trámite, el máximo es 2 y el `422` lo dice en el
  mensaje.
- **Las `RECHAZADA` liberan cantidad**: una devolución rechazada permite registrar otra por
  la misma cantidad.
- **El pedido debe estar `ENTREGADO` o `EN_PREPARACION`.** Un pedido `PENDIENTE` nunca sacó
  la mercancía y uno `CANCELADO` ya devolvió su stock entero.
- **No envíes el total ni el producto:** `pedido`, `producto` y las cantidades derivadas son
  de respuesta.

### 2.5. Aprobar o rechazar

```http
PATCH /api/v1/devoluciones/15/estado
Authorization: Bearer <token>
Content-Type: application/json

{ "estado": "APROBADA" }
```

```json
// 200 OK · el stock volvió
{
  "idDevolucion": 15,
  "estado": "APROBADA",
  "lotesRepuestos": [
    { "codigoLote": "PF-2026-1017", "cantidad": 3.0,
      "saldoAnterior": 22.0, "saldoPosterior": 25.0 }
  ]
}
```

Al rechazar:

```http
{ "estado": "RECHAZADA" }
```

```json
// 200 OK · no se tocó inventario
{ "idDevolucion": 15, "estado": "RECHAZADA", "lotesRepuestos": [] }
```

- **Aprobar repone el stock** en los lotes de los que salió la línea, en **orden inverso al
  consumo**: el último lote tomado —el de vencimiento más lejano— recibe primero (R-01,
  R-02). `lotesRepuestos` informa cada entrada con su saldo anterior y posterior.
- **Rechazar no toca inventario** y devuelve `lotesRepuestos: []`.
- **La máquina de estados es corta:** desde `PENDIENTE` se puede pasar a `APROBADA` o
  `RECHAZADA`; los dos son terminales. Repetir la aprobación o intentar volver a
  `PENDIENTE` responde `409 TRANSICION_INVALIDA` y **no repone stock otra vez**.
- **El estado se envía como literal canónico.** `aprobada` en minúsculas falla en la
  deserialización y responde `400 VALIDACION_FALLIDA`.
- **Usa la respuesta para refrescar la fila.** No vuelvas a consultar el kardex: los saldos
  por lote ya vienen en `lotesRepuestos`.
- Una devolución inexistente responde `404 RECURSO_NO_ENCONTRADO`.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-11-02T20:14:08.517206113", "status": 422,
  "error": "Unprocessable Entity", "code": "CANTIDAD_EXCEDE_PEDIDO",
  "message": "Se pidieron 30 unidades y ya hay 28 devueltas o en trámite. Solo puede devolver 2.",
  "path": "/api/v1/devoluciones", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `DETALLE_PEDIDO_INEXISTENTE` | 409 | `idDetallePedido` no existe | Refrescar el detalle del pedido y volver a elegir la línea |
| `PEDIDO_NO_DEVOLVIBLE` | 409 | El pedido está `PENDIENTE` o `CANCELADO` (R-05) | Ocultar la acción de devolver y explicar por qué |
| `TRANSICION_INVALIDA` | 409 | La devolución ya está `APROBADA` o `RECHAZADA` (R-04) | Refrescar el estado; no reintentar la aprobación |
| `CANTIDAD_EXCEDE_PEDIDO` | 422 | La cantidad supera lo disponible contando las previas (R-03) | Mostrar el mensaje del servidor y actualizar el máximo |
| `CANTIDAD_INVALIDA` | 400 | Cantidad cero, negativa o que redondea a cero | Marcar el campo cantidad; no reenviar |
| `VALIDACION_FALLIDA` | 400 | Falta un campo, el motivo supera 50, la descripción 255 o el estado no es canónico | Pintar `fieldErrors` junto al campo |
| `PARAMETRO_INVALIDO` | 400 | Id no numérico, estado o fecha mal formados, `page`/`size` fuera de rango | Corregir la navegación o el filtro; no reintentar igual |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo fuera de la lista blanca | Volver al orden por defecto |
| `RANGO_FECHAS_INVALIDO` | 400 | `fechaInicio` posterior a `fechaFin` | Corregir el rango en el selector |
| `RECURSO_NO_ENCONTRADO` | 404 | Devolución o pedido inexistente | Volver al listado |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (`PRODUCCION` y `COMPRAS` en todo; `CONSULTA` en escritura) | Ocultar la acción y avisar |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |

`fieldErrors` es un **array** de `{ field, message }`; en los errores de regla de negocio
llega vacío y el texto útil está en `message`.

**422 no es 409.** `CANTIDAD_EXCEDE_PEDIDO` es un dato sintácticamente correcto que viola una
regla dependiente del acumulado; el `409` queda para conflictos con algo que ya existe —el
detalle, el estado del pedido o una transición ya cerrada.

---

## 4. Casos límite

### El stock vuelve solo al aprobar

- **Registrar una devolución no cambia el inventario** (R-01). Si la pantalla muestra stock,
  no lo refresques tras el `201`: sigue igual hasta el `PATCH` a `APROBADA`.
- La bandeja de trabajo usa `PENDIENTE`: son las devoluciones que esperan revisión física de
  la mercancía.
- Aprobar es irreversible: una devolución aprobada por error se corrige con un ajuste de
  inventario, no revirtiendo el estado.

### `disponibleParaDevolver` es orientativo en el formulario

- Úsalo como `max` de la cantidad, junto con `cantidadPedida` y `devueltoPreviamente`, que
  son los tres números que explican el límite.
- Entre la consulta y el `POST`, otra persona puede registrar una devolución pendiente. El
  `422` del servidor es la autoridad: muestra su mensaje y vuelve a consultar la línea.
- El `max` del cliente no es una garantía; no bloquees el envío solo por él.

### Acumulado y rechazos

- Las `PENDIENTE` **ocupan cantidad**: aunque todavía no hayan repuesto stock, cuentan para
  el límite de R-03.
- Las `RECHAZADA` **liberan cantidad**: la línea vuelve a tener disponible lo que se rechazó
  y se puede registrar otra devolución.
- Una línea con `disponibleParaDevolver = 0` no admite más devoluciones; oculta el
  formulario y muestra "esta línea ya fue devuelta por completo".

### Lote de destino vencido

- Si el lote al que vuelve la mercancía ya venció, **la aprobación se registra igual** y
  queda en el kardex para trazabilidad, pero la unidad **no vuelve como stock vendible**
  porque la vista de stock excluye vencidos (HU-17 R-03).
- Advertirlo en el diálogo de aprobación: el stock puede no subir aunque la devolución quede
  aprobada.

### Doble aprobación y concurrencia

- Dos clics en "Aprobar" no duplican la reposición: el segundo responde `409
  TRANSICION_INVALIDA` y el stock sube una sola vez.
- Deshabilita el botón mientras la petición está en vuelo y refresca con la respuesta.
- El alta también está protegida: dos registros simultáneos sobre la misma línea se
  serializan y el segundo valida contra el acumulado ya actualizado.

### Pedido no devolvible

- Solo `ENTREGADO` y `EN_PREPARACION` admiten devoluciones. Un pedido `PENDIENTE` no ha
  sacado la mercancía y uno `CANCELADO` ya devolvió su stock entero.
- No reutilices el subrecurso de detalle de HU-17 para devolver: la devolución es su propio
  documento y mueve inventario con origen `devoluciones`.

### Listado y filtros

- El rango de fechas es **inclusivo** en los dos extremos y se aplica sobre
  `fechaDevolucion`, que es la fecha de registro, no la de aprobación.
- Un filtro de estado no canónico responde `400 PARAMETRO_INVALIDO`; no lo confundas con un
  listado vacío.
- El orden por defecto es `fechaDevolucion,desc`. Si necesitas la bandeja de trabajo, filtra
  por `PENDIENTE` y ordena por `fechaDevolucion,asc`.

---

## 5. Limitaciones conocidas

- **No hay `DELETE` de devoluciones.** Una devolución es el registro de mercancía devuelta y
  su estado es la trazabilidad de la reposición; borrarla rompería la bitácora y dejaría el
  kardex con entradas sin origen. Para corregir se rechaza antes de aprobar o se ajusta el
  inventario.
- **No se elige el lote de destino.** La reposición sigue el kardex en orden inverso al
  consumo; el frontend solo visualiza `lotesRepuestos` y advierte si el lote venció.
- **El motivo es texto libre.** No hay catálogo: la interfaz sugiere motivos frecuentes y
  permite escribir otro (máximo 50 caracteres).
- **CP-24 (rollback ante fallo interno) no se puede probar por API.** Está cubierto por una
  prueba automatizada; no hay endpoint para forzarlo desde Angular.
- **Una línea sin kardex aborta la aprobación.** Si una fila de `detalle_pedido` se sembró
  por SQL sin sus movimientos de venta, aprobar responde `500 ERROR_INTERNO` y la
  transacción revierte. El flujo real siempre escribe línea y kardex juntos; es una defensa
  deliberada.
- **Los datos de ejemplo cambian con la base.** Nombres, ids y cantidades salen de la
  ejecución en caliente; confírmalos al integrar.
- **Frontend pendiente.** No existe pantalla de devoluciones; este manual es el contrato
  para construirla. Cualquier cambio de contrato actualiza este documento en el mismo commit.
