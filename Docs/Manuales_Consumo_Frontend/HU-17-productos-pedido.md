# HU-17 — Productos del pedido · Manual de consumo para el frontend

Contrato práctico para consultar el stock de un producto terminado y para listar, agregar,
editar y quitar las líneas de un pedido. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md`, el manual de pedidos en
[`HU-16-creacion-pedidos.md`](HU-16-creacion-pedidos.md) y la ficha
[`../Hechos/HU-17-productos-pedido.md`](../Hechos/HU-17-productos-pedido.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura para `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`; escritura (agregar,
  editar y quitar) solo para `ADMIN` y `VENTAS`. `COMPRAS` recibe `403 SIN_PERMISO` en los cinco
  endpoints.
- **Dependencias:** el pedido es de HU-16 y el stock de producto terminado nace de los lotes de
  HU-19. Sin lotes vigentes no hay stock que vender.

> **Estado:** backend verificado en caliente contra MySQL el 2026-10-30 (QA ciego:
> 23/24 CP y 25/25 SEC). El consumo desde Angular sigue pendiente. Los ids, nombres, precios y
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
| `GET /pedidos/{id}/detalles` | sí | sí | sí | sí | 403 |
| `GET /productos/{id}/stock` | sí | sí | sí | sí | 403 |
| `POST /pedidos/{id}/detalles` | sí | sí | 403 | 403 | 403 |
| `PUT /pedidos/{id}/detalles/{idDetalle}` | sí | sí | 403 | 403 | 403 |
| `DELETE /pedidos/{id}/detalles/{idDetalle}` | sí | sí | 403 | 403 | 403 |

Ocultar una acción en la pantalla no sustituye al `403`, que el backend aplica igual.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/productos/{id}/stock` | ADMIN / VENTAS / PRODUCCION / CONSULTA | 200, 400, 401, 403, 404 |
| GET | `/pedidos/{idPedido}/detalles` | ADMIN / VENTAS / PRODUCCION / CONSULTA | 200, 400, 401, 403, 404 |
| POST | `/pedidos/{idPedido}/detalles` | ADMIN / VENTAS | 201, 400, 401, 403, 404, 409 |
| PUT | `/pedidos/{idPedido}/detalles/{idDetalle}` | ADMIN / VENTAS | 200, 400, 401, 403, 404, 409 |
| DELETE | `/pedidos/{idPedido}/detalles/{idDetalle}` | ADMIN / VENTAS | 204, 400, 401, 403, 404, 409 |

### 2.1. Consultar el stock de un producto

```http
GET /api/v1/productos/872/stock
Authorization: Bearer <token>
```

```json
// 200 OK
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

- **`cantidadDisponible` es la suma vendible**, no el inventario bruto: excluye lotes vencidos y
  saldos en cero (R-03). Es el `max` orientativo de la cantidad en el formulario.
- **`lotes` ya viene en orden FEFO** (vence antes primero). No lo reordenes en Angular: el orden
  es la política de salida y el desempate es por `id_lote`.
- **`proximoVencimiento`** es la fecha del primer lote; sirve para el aviso ámbar de vencimiento.
  Si no hay lotes vendibles, la propiedad se omite.
- **El producto puede estar desactivado y aun así responder 200.** Su stock sigue existiendo;
  solo se bloquea agregarlo a un pedido.
- Un producto inexistente responde `404 RECURSO_NO_ENCONTRADO`; un id no numérico,
  `400 PARAMETRO_INVALIDO`.

### 2.2. Listar el detalle de un pedido

```http
GET /api/v1/pedidos/658/detalles
Authorization: Bearer <token>
```

```json
// 200 OK
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

- **No es un `PageResponse`.** Devuelve el objeto completo con `detalles`, sin `content` ni
  paginación. El detalle de un pedido es un conjunto acotado.
- **`editable` es la bandera de R-09:** `true` en `PENDIENTE` y `EN_PREPARACION`; `false` en
  `ENTREGADO` y `CANCELADO`. Úsala para habilitar o bloquear los controles de la tabla. No
  reimplementes la regla.
- **`unidad` es la abreviatura** (`kg`, `und`, `L`), no el nombre completo.
- **`lotesConsumidos` se reconstruye del kardex** y muestra el neto por lote: tras una
  reducción solo aparece lo que sigue descontado. Una línea sin lotes aún puede tener cantidad
  (por ejemplo, datos sembrados por SQL), pero en el flujo normal siempre hay al menos uno.
- **`totalPedido` se recalcula en el servidor** con `SUM(cantidad × precioUnitario)`. No sumes
  los subtotales en Angular.
- **`precioUnitario` es histórico:** se copió al agregar la línea y no sigue al catálogo.
- Un pedido inexistente responde `404 PEDIDO_INEXISTENTE`.

### 2.3. Agregar un producto

```http
POST /api/v1/pedidos/658/detalles
Authorization: Bearer <token>
Content-Type: application/json

{ "idProducto": 872, "cantidad": 30.00 }
```

| Campo | Tipo | Obligatorio | Regla |
|---|---|---|---|
| `idProducto` | entero | sí | Debe existir (`409 PRODUCTO_INEXISTENTE`) y estar activo (`409 PRODUCTO_INACTIVO`, CA-02) |
| `cantidad` | decimal | sí | Mayor que cero (`400 CANTIDAD_INVALIDA`, CA-03); máximo 2 decimales útiles |

```json
// 201 Created · Location: /api/v1/pedidos/658/detalles/287
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

- **El precio lo pone el backend.** El cuerpo solo lleva producto y cantidad; `precioUnitario` y
  `subtotal` son de respuesta.
- **`lotesConsumidos` hace visible el FEFO** y es el comprobante de trazabilidad. Úsalo en el
  diálogo de confirmación para mostrar de qué lotes sale la mercancía.
- **El pedido debe estar `PENDIENTE` o `EN_PREPARACION`** (`409 PEDIDO_NO_EDITABLE` en otro
  estado, R-09).
- **El producto no puede repetirse en el pedido** (`409 PRODUCTO_DUPLICADO`). Si ya está, edita
  la cantidad de la línea existente.
- **No envíes el total del pedido:** lo recalcula el servidor y llega en la respuesta.

### 2.4. Cambiar la cantidad

```http
PUT /api/v1/pedidos/658/detalles/287
Authorization: Bearer <token>
Content-Type: application/json

{ "cantidad": 6.00 }
```

```json
// 200 OK
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

- **Solo cambia la cantidad.** El producto y el precio de la línea no se reemplazan; para cambiar
  de producto se elimina la línea y se agrega otra.
- **Aumentar descuenta solo la diferencia** y puede fallar por stock (`409 STOCK_INSUFICIENTE`).
  **Reducir devuelve solo la diferencia** a los lotes de origen. Repetir la misma cantidad no
  mueve inventario.
- **La respuesta trae los lotes netos y el total recalculado.** Refresca la fila con ella, no
  recalcules en el cliente.
- Una línea que no existe o pertenece a otro pedido responde `404 RECURSO_NO_ENCONTRADO`.

### 2.5. Quitar una línea

```http
DELETE /api/v1/pedidos/658/detalles/287
Authorization: Bearer <token>
```

```http
HTTP/1.1 204 No Content
```

- **Devuelve toda la cantidad neta** de la línea a los lotes de origen con movimientos
  `ENTRADA_DEVOLUCION` (R-08). Si antes se había reducido, solo vuelve lo que aún estaba
  descontado.
- **Sin cuerpo de respuesta.** Tras el `204`, vuelve a pedir el listado para refrescar
  `totalPedido` y `cantidadItems`.
- La línea debe pertenecer al pedido de la ruta; si no, `404 RECURSO_NO_ENCONTRADO`.
- El pedido debe seguir `PENDIENTE` o `EN_PREPARACION`; si no, `409 PEDIDO_NO_EDITABLE`.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-30T18:51:17.063947045", "status": 400, "error": "Bad Request",
  "code": "CANTIDAD_INVALIDA",
  "message": "La cantidad debe ser un número mayor que cero.",
  "path": "/api/v1/pedidos/658/detalles", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `PEDIDO_INEXISTENTE` | 404 | El pedido no existe | Volver al listado de pedidos |
| `PEDIDO_NO_EDITABLE` | 409 | El pedido está `ENTREGADO` o `CANCELADO` (R-09) | Poner la tabla en modo lectura con `editable=false` y refrescar |
| `PRODUCTO_INEXISTENTE` | 409 | `idProducto` no existe | Recargar el catálogo de productos (HU-11) |
| `PRODUCTO_INACTIVO` | 409 | El producto está desactivado | Ofrecer solo productos activos en el selector |
| `PRODUCTO_DUPLICADO` | 409 | El producto ya está en el pedido (R-06) | Enfocar la línea existente y ofrecer editar su cantidad |
| `STOCK_INSUFICIENTE` | 409 | No hay unidades vendibles suficientes (CA-04) | Mostrar el mensaje, refrescar el stock y actualizar el `max` |
| `CANTIDAD_INVALIDA` | 400 | Cantidad cero, negativa o que redondea a cero | Marcar el campo cantidad; no reenviar |
| `RECURSO_NO_ENCONTRADO` | 404 | Línea inexistente o de otro pedido; producto inexistente en stock | Refrescar el detalle o volver al catálogo |
| `PARAMETRO_INVALIDO` | 400 | Id no numérico en la ruta | Corregir la navegación; no reintentar igual |
| `VALIDACION_FALLIDA` | 400 | Cuerpo inválido (por ejemplo, falta `idProducto`) | Pintar `fieldErrors` junto al campo |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (por ejemplo, `COMPRAS` o `CONSULTA` en escritura) | Ocultar la acción y avisar |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |

`fieldErrors` es un **array** de `{ field, message }`; en los errores de regla de negocio llega
vacío y el texto útil está en `message`.

---

## 4. Casos límite

### FEFO y previsualización

- El backend descuenta primero del lote que vence antes, **aunque se haya creado después**. Al
  cambiar la cantidad en el diálogo, recalcula la previsualización en el cliente con `lotes` del
  endpoint de stock; el resultado definitivo es el `lotesConsumidos` del `POST`.
- **No envíes el lote elegido.** El cuerpo solo tiene producto y cantidad: la política FEFO es
  del servidor y no se negocia desde el formulario.
- Una línea puede consumir **varios lotes**; muéstralos con `MatChip` y resalta en ámbar el que
  vence pronto (`proximoVencimiento` o `vence` de cada lote).

### El stock del cliente es orientativo

- Entre `GET /productos/{id}/stock` y el `POST`/`PUT`, otro vendedor puede tomar unidades. El
  `409 STOCK_INSUFICIENTE` del servidor es la autoridad (R-05).
- Cuando llegue ese `409`, muestra el mensaje del backend (`"Solo hay N unidades disponibles de
  …"`), vuelve a consultar el stock y actualiza el `max` del campo; no reintentes a ciegas.
- El `max` del formulario es una ayuda visual, no una garantía.

### Pedido no editable

- Usa `editable` del listado. Si es `false`, la tabla va en modo lectura y no se muestran
  botones de agregar, editar ni quitar.
- `ENTREGADO` y `CANCELADO` no admiten cambios en el detalle. Una devolución posterior a la
  entrega es HU-18, no una edición.

### Producto inactivo o sin stock

- El selector de productos debe mostrar solo activos **con stock** (`cantidadDisponible > 0`).
  Un producto inactivo responde `409 PRODUCTO_INACTIVO` y uno sin stock
  `409 STOCK_INSUFICIENTE`.
- Consultar el stock de un producto inactivo **sí funciona** (`200`): úsalo para pantallas de
  inventario, no para habilitar el alta.

### Línea de otro pedido

- El `PUT` y el `DELETE` validan que la línea pertenezca al pedido de la ruta. Una línea de otro
  pedido responde `404 RECURSO_NO_ENCONTRADO`, igual que si no existiera: no se filtra la
  existencia de documentos ajenos.

### Cancelación del pedido

- Cancelar el pedido (HU-16) devuelve el stock de las líneas **netas** del kardex. Si una línea
  se redujo antes, solo se repone lo que sigue descontado; no se devuelve stock de más.
- Tras cancelar, `editable` pasa a `false` y el detalle queda como histórico.

---

## 5. Limitaciones conocidas

- **No hay paginación en el detalle.** El listado devuelve todas las líneas del pedido en un
  solo objeto. Para pedidos con muchas líneas, la tabla debe virtualizar o paginar en el
  cliente.
- **El precio es histórico y no se puede editar desde el detalle.** Para cambiar el producto o
  su precio se elimina la línea y se agrega otra con el precio vigente del catálogo.
- **No se puede elegir lote.** FEFO es una decisión del servidor; el frontend solo visualiza y
  advierte.
- **Los datos de ejemplo cambian con la base.** Nombres, ids y cantidades salen de la ejecución
  en caliente; confírmalos al integrar.
- **El CP-22 (rollback ante fallo interno) no se puede probar por API.** Está cubierto por una
  prueba automatizada; no hay endpoint para forzarlo desde Angular.
- **Frontend pendiente.** No existe pantalla de detalle de pedido; este manual es el contrato
  para construirla. Cualquier cambio de contrato actualiza este documento en el mismo commit.
