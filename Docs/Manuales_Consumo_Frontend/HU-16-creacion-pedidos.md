# HU-16 — Creación de pedidos · Manual de consumo para el frontend

Contrato práctico para crear, consultar, editar y cambiar de estado la cabecera de un pedido.
Complementa `../../../PFDocs/00-base/04-CONTRATO-API.md`, el manual de clientes en
[`HU-05-gestion-clientes.md`](HU-05-gestion-clientes.md) y la ficha
[`../Hechos/HU-16-creacion-pedidos.md`](../Hechos/HU-16-creacion-pedidos.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura para `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`; escritura (alta, edición
  y cambio de estado) solo para `ADMIN` y `VENTAS`. `COMPRAS` recibe `403 SIN_PERMISO` en los
  cinco endpoints.
- **Detalle del pedido:** las líneas son de HU-17. Esta historia expone solo la cabecera y los
  totales derivados del detalle.

> **Estado:** backend verificado en caliente contra MySQL el 2026-10-30. El consumo desde Angular
> sigue pendiente. Los ids, los nombres y los totales dependen de la base. El detalle del pedido
> y la devolución posterior a la entrega (HU-18) no forman parte de este contrato.

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
| `GET /pedidos` | sí | sí | sí | sí | 403 |
| `GET /pedidos/{id}` | sí | sí | sí | sí | 403 |
| `POST /pedidos` | sí | sí | 403 | 403 | 403 |
| `PUT /pedidos/{id}` | sí | sí | 403 | 403 | 403 |
| `PATCH /pedidos/{id}/estado` | sí | sí | 403 | 403 | 403 |

`PRODUCCION` y `CONSULTA` consultan pero no crean, editan ni cambian estados. `COMPRAS` no
participa del módulo de pedidos. Ocultar una acción en la pantalla no sustituye al `403`, que el
backend aplica igual.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/pedidos` | ADMIN / VENTAS / PRODUCCION / CONSULTA | 200, 400, 401, 403 |
| GET | `/pedidos/{id}` | ADMIN / VENTAS / PRODUCCION / CONSULTA | 200, 400, 401, 403, 404 |
| POST | `/pedidos` | ADMIN / VENTAS | 201, 400, 401, 403, 409 |
| PUT | `/pedidos/{id}` | ADMIN / VENTAS | 200, 400, 401, 403, 404, 409 |
| PATCH | `/pedidos/{id}/estado` | ADMIN / VENTAS | 200, 400, 401, 403, 404, 409 |

No hay `DELETE`: un pedido no se borra. El cierre correcto es cancelarlo, que además devuelve el
stock comprometido al inventario.

### 2.1. Crear un pedido

```http
POST /api/v1/pedidos
Authorization: Bearer <token>
Content-Type: application/json

{ "idCliente": 7, "fechaEntrega": "2026-11-02" }
```

| Campo | Tipo | Obligatorio | Regla |
|---|---|---|---|
| `idCliente` | entero | sí | Debe existir (`409 CLIENTE_INEXISTENTE`) y estar activo (`409 CLIENTE_INACTIVO`, CA-01) |
| `fechaEntrega` | `yyyy-MM-dd` | no | Si se informa, debe ser igual o posterior a hoy (`400 FECHA_ENTREGA_INVALIDA`, R-06) |

- **No se envía `fechaPedido`.** La registra la base con su `DEFAULT (curdate())` (R-01); si el
  cuerpo la incluye, se ignora.
- **No se envía `estado`.** El pedido nace siempre `PENDIENTE` (R-02/CA-04); si el cuerpo lo
  incluye, se ignora.
- `fechaEntrega` puede omitirse o enviarse en `null`: el resultado es el mismo pedido sin fecha
  de entrega.

```json
// 201 Created · Location: /api/v1/pedidos/45
{
  "idPedido": 45,
  "cliente": { "idCliente": 7, "nombre": "Tienda La Esquina" },
  "fechaPedido": "2026-10-30",
  "fechaEntrega": "2026-11-02",
  "estado": "PENDIENTE",
  "totalPedido": 0.00,
  "cantidadItems": 0,
  "editable": true
}
```

Sin `fechaEntrega`, **la propiedad no aparece** en el JSON (la API omite los campos nulos); no
llega como `"fechaEntrega": null`. El pedido recién creado siempre tiene `totalPedido` `0.00` y
`cantidadItems` `0` porque el detalle llega en HU-17.

**Ejemplo de error:**

```json
// 409 Conflict
{ "timestamp": "2026-10-30T10:15:22.401", "status": 409, "error": "Conflict",
  "code": "CLIENTE_INACTIVO",
  "message": "El cliente con id 7 está inactivo; no puede recibir pedidos.",
  "path": "/api/v1/pedidos", "fieldErrors": [] }
```

### 2.2. Listar pedidos

```http
GET /api/v1/pedidos?idCliente=7&estado=PENDIENTE&fechaInicio=2026-10-01&fechaFin=2026-10-31&page=0&size=20&sort=fechaPedido,desc
```

Parámetros — todos opcionales:

| Parámetro | Formato | Defecto | Regla |
|---|---|---|---|
| `idCliente` | entero | — | Acota a los pedidos de ese cliente |
| `estado` | `PENDIENTE` \| `EN_PREPARACION` \| `ENTREGADO` \| `CANCELADO` | — | Literal no canónico: `400 PARAMETRO_INVALIDO` |
| `fechaInicio` / `fechaFin` | `yyyy-MM-dd` | — | Ambos inclusivos sobre `fechaPedido`; invertidos: `400 RANGO_FECHAS_INVALIDO` |
| `page` | entero ≥ 0 | `0` | |
| `size` | entero | `20` | `1..100`; fuera de rango: `400 PARAMETRO_INVALIDO` |
| `sort` | `fechaPedido` \| `fechaEntrega` \| `estado` \| `id` | `fechaPedido,desc` | Formato `campo,asc\|desc`; otro campo o dirección: `400 CAMPO_ORDEN_INVALIDO` |

```json
// 200 OK
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

- **Los filtros se combinan con `AND`.** Sin ninguno, el listado es el historial completo
  paginado. No filtres en el cliente sobre una página de 20.
- **El rango es inclusivo en los dos extremos** y se aplica sobre `fechaPedido`, no sobre la
  fecha de entrega.
- **`totalPedido` y `cantidadItems` los calcula el backend** con `SUM(cantidad × precio_unitario)`
  y `COUNT(*)` sobre `detalle_pedido`. No sumes líneas en el cliente.
- **Un pedido sin fecha de entrega omite la propiedad `fechaEntrega`** en cada fila.
- **Una página fuera de rango responde `200` con `content: []`**, no un `404`.
- El orden por defecto es `fechaPedido,desc` y el desempate es por `id` descendente.

### 2.3. Obtener un pedido

```http
GET /api/v1/pedidos/45
```

```json
// 200 OK
{
  "idPedido": 45,
  "cliente": { "idCliente": 7, "nombre": "Tienda La Esquina" },
  "fechaPedido": "2026-10-30",
  "fechaEntrega": "2026-11-02",
  "estado": "PENDIENTE",
  "totalPedido": 0.00,
  "cantidadItems": 0,
  "editable": true
}
```

- `editable` es `true` **solo** en estado `PENDIENTE` (R-05). La pantalla debe usar esta bandera,
  no reimplementar la máquina de estados.
- **No devuelve las líneas del pedido.** El detalle es de HU-17; hasta entonces esta respuesta
  solo trae la cabecera y los totales derivados.
- Un id inexistente responde `404 RECURSO_NO_ENCONTRADO`; un id no numérico,
  `400 PARAMETRO_INVALIDO`.

### 2.4. Editar la cabecera

```http
PUT /api/v1/pedidos/45
Authorization: Bearer <token>
Content-Type: application/json

{ "idCliente": 7, "fechaEntrega": "2026-11-05" }
```

El cuerpo reemplaza el cliente y la fecha de entrega; `fechaEntrega` puede quedar vacía con
`null`. No acepta `estado` ni `fechaPedido`.

```json
// 200 OK — el pedido releído de la base
{
  "idPedido": 45,
  "cliente": { "idCliente": 7, "nombre": "Tienda La Esquina" },
  "fechaPedido": "2026-10-30",
  "fechaEntrega": "2026-11-05",
  "estado": "PENDIENTE",
  "totalPedido": 0.00,
  "cantidadItems": 0,
  "editable": true
}
```

- **Solo un pedido `PENDIENTE` es editable** (R-05). En cualquier otro estado el backend responde
  `409 PEDIDO_NO_EDITABLE`. El frontend debe deshabilitar el formulario cuando `editable` es
  `false`.
- El cliente nuevo debe existir y estar activo (`409 CLIENTE_INEXISTENTE` o `CLIENTE_INACTIVO`).
- La fecha de entrega, si se informa, no puede ser anterior a hoy (`400 FECHA_ENTREGA_INVALIDA`).
- El estado no se cambia por aquí: tiene su propio `PATCH`.

### 2.5. Cambiar el estado

```http
PATCH /api/v1/pedidos/45/estado
Authorization: Bearer <token>
Content-Type: application/json

{ "estado": "EN_PREPARACION" }
```

```json
// 200 OK — el pedido releído en su estado nuevo
{
  "idPedido": 45,
  "cliente": { "idCliente": 7, "nombre": "Tienda La Esquina" },
  "fechaPedido": "2026-10-30",
  "fechaEntrega": "2026-11-02",
  "estado": "EN_PREPARACION",
  "totalPedido": 0.00,
  "cantidadItems": 0,
  "editable": false
}
```

Transiciones permitidas (R-02):

| Desde | Hacia | Resultado |
|---|---|---|
| `PENDIENTE` | `EN_PREPARACION` | `200` |
| `PENDIENTE` | `CANCELADO` | `200`; devuelve stock si hay líneas vendidas |
| `EN_PREPARACION` | `ENTREGADO` | `200` |
| `EN_PREPARACION` | `CANCELADO` | `200`; devuelve stock si hay líneas vendidas |
| `ENTREGADO` | cualquier estado | `409 TRANSICION_INVALIDA` |
| `CANCELADO` | cualquier estado | `409 TRANSICION_INVALIDA` |
| `PENDIENTE` | `ENTREGADO` | `409 TRANSICION_INVALIDA` (salto) |

- **`ENTREGADO` es terminal.** Una devolución posterior no se hace con este endpoint: es HU-18.
- **El `PATCH` se despacha por destino.** `EN_PREPARACION`, `ENTREGADO` y `CANCELADO` tienen
  transición propia; `PENDIENTE` no es destino de nadie y responde `409`.
- **El estado debe ser el literal canónico.** Un valor como `"EN PREPARACION"` o `"en_preparacion"`
  falla en la deserialización con `400 VALIDACION_FALLIDA`.
- **Un pedido inexistente responde `404 RECURSO_NO_ENCONTRADO`.**

### 2.6. Cancelar y devolución de stock

Cancelar es un `PATCH` a `CANCELADO`. El backend, en una sola transacción:

1. valida que la transición sea `PENDIENTE → CANCELADO` o `EN_PREPARACION → CANCELADO`;
2. cambia el estado;
3. por cada `SALIDA_VENTA` del kardex ligada a una línea del pedido, repone la cantidad al lote
   del que salió con un movimiento `ENTRADA_DEVOLUCION` cuyo origen es
   `tabla_origen = "pedidos"`, `id_origen = idPedido`.

```http
PATCH /api/v1/pedidos/45/estado
{ "estado": "CANCELADO" }
```

- **La devolución es por lote, no por producto.** Si una línea consumió de dos lotes, se repone
  a cada uno; el diálogo de cancelación debe mostrar esa información cuando HU-17 exponga el
  detalle.
- **Los movimientos quedan en el kardex de producto terminado** y se pueden consultar con
  `GET /api/v1/lotes-producto/{idLote}/movimientos` (HU-19), filtrando por `origen.tabla =
  "pedidos"` y `origen.id = idPedido`.
- **Un pedido sin líneas vendidas se cancela con `200` y no genera movimientos** (CP-18).
- **Un pedido `ENTREGADO` o ya `CANCELADO` no se cancela**: `409 TRANSICION_INVALIDA`. No se
  devuelve stock dos veces.
- El backend condiciona el `UPDATE` al estado leído; dos cancelaciones simultáneas no reponen
  el stock dos veces.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-30T10:15:22.401", "status": 400, "error": "Bad Request",
  "code": "FECHA_ENTREGA_INVALIDA",
  "message": "La fecha de entrega no puede ser anterior a la fecha del pedido.",
  "path": "/api/v1/pedidos", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Cuerpo inválido, `idCliente` ausente o `estado` no canónico | Marcar el campo con `fieldErrors` y no reenviar |
| `FECHA_ENTREGA_INVALIDA` | 400 | `fechaEntrega` anterior a hoy | Marcar el campo de fecha; el selector debe tener `min = hoy` |
| `CLIENTE_INEXISTENTE` | 409 | `idCliente` no existe (alta o edición) | Recargar el catálogo de clientes (HU-05) |
| `CLIENTE_INACTIVO` | 409 | El cliente está desactivado (alta o edición) | Ofrecer solo clientes activos en el selector |
| `PEDIDO_NO_EDITABLE` | 409 | `PUT` sobre un pedido que no está `PENDIENTE` | Deshabilitar la edición si `editable` es `false` |
| `TRANSICION_INVALIDA` | 409 | Salto de estado o destino prohibido (R-02) | Refrescar el pedido y ofrecer solo las transiciones válidas |
| `RECURSO_NO_ENCONTRADO` | 404 | El pedido no existe | Volver al listado |
| `PARAMETRO_INVALIDO` | 400 | `estado` no canónico, fecha mal formada o `size` fuera de rango | Corregir el filtro antes de reenviar |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` fuera de `fechaPedido`, `fechaEntrega`, `estado`, `id` | Enviar el campo de la cabecera de tabla traducido a esos nombres |
| `RANGO_FECHAS_INVALIDO` | 400 | `fechaInicio` posterior a `fechaFin` | Marcar el campo de fecha final |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (por ejemplo `COMPRAS`) | Ocultar la acción y avisar |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |

`fieldErrors` es un **array** de `{ field, message }`; en los errores de consulta llega vacío. El
`Content-Type` de las respuestas es `application/json` sin `charset=UTF-8`.

---

## 4. Notas para el frontend

### Formulario de alta

| Campo | Envía | Validación previa |
|---|---|---|
| Cliente | `idCliente` | Obligatorio; selector **solo con clientes activos** (HU-05) |
| Fecha de entrega | `fechaEntrega` | Opcional; `min = hoy` |
| Fecha de pedido | — | No se envía: la pone el servidor (R-01). Se puede mostrar como informativa |
| Estado | — | No se envía: el pedido nace `PENDIENTE` |

- **No incluyas campos de fecha de pedido ni de estado en el formulario.** El cuerpo del `POST`
  solo tiene `idCliente` y `fechaEntrega`.
- La fecha de pedido se puede mostrar como nota informativa («se registrará automáticamente»),
  sin ofrecerla como editable.
- Deshabilita el botón de envío mientras la petición está en vuelo y muestra los `fieldErrors`
  junto al campo correspondiente.
- Si el selector de clientes se queda vacío, enlaza a HU-05 para registrar uno.

### Edición

- Usa `editable` de la respuesta. Si es `false`, no abras el formulario; el backend responde
  `409 PEDIDO_NO_EDITABLE` igual.
- Tras editar, refresca la fila con la respuesta del `PUT`: el pedido releído trae los totales
  actualizados.

### Acciones de estado

- Ofrece solo las transiciones de la tabla de §2.5 según el `estado` actual.
- Tras un `PATCH`, usa el pedido devuelto para refrescar estado y `editable`; no asumas el
  resultado en el cliente.
- Un `409 TRANSICION_INVALIDA` significa que otro usuario cambió el pedido o que la pantalla
  estaba desactualizada: refresca y vuelve a decidir.

### Cancelación

- **Qué mostrar al cancelar:** que la operación devuelve al inventario los productos vendidos,
  lote por lote. Cuando HU-17 exponga el detalle, el diálogo puede listar producto, cantidad y
  lote de destino; hasta entonces, muestra un aviso general y no inventes líneas.
- **El pedido quedará `CANCELADO` y no se reactiva.** Confirma antes de enviar.
- **No hay `DELETE`.** Cancelar es el cierre correcto; no ofrezcas «eliminar pedido».
- Un pedido `ENTREGADO` no se cancela: la devolución posterior es HU-18.

### Listado

- Combina los filtros en **una sola** petición con `AND`. Recuerda reiniciar `page` a `0` cuando
  cambie un filtro, no cuando solo cambie la ordenación.
- Para la alerta visual de entrega, compara `fechaEntrega` con la fecha local: una entrega
  anterior a hoy está vencida; la de hoy se resalta. La propiedad puede no venir si el pedido no
  tiene fecha de entrega.
- `401` → login; `403` → ocultar la acción y avisar; `404` → volver al listado.

### Interpretación de importes

- `totalPedido` es un decimal a escala 2 calculado por el backend y el JSON lo trae como número.
- `cantidadItems` cuenta las líneas del pedido, no las unidades vendidas.

---

## 5. Qué NO está en el contrato

- **No hay endpoint de detalle del pedido.** Las líneas, su precio y el descuento de stock son de
  HU-17. Esta historia no expone `POST/PUT/DELETE` sobre `/pedidos/{id}/detalles`.
- **No hay `DELETE` de pedidos.** El cierre es `PATCH` a `CANCELADO`.
- **No se puede cambiar el estado a `PENDIENTE`.** No es destino de ninguna transición.
- **No se puede editar `fechaPedido`.** Es inmutable una vez registrada.
- **No hay filtro por texto de cliente ni por importe**, ni orden por `total` o `cantidadItems`:
  la lista blanca es `fechaPedido`, `fechaEntrega`, `estado` e `id`.
- **No hay devolución posterior a la entrega.** `ENTREGADO` es terminal; eso es HU-18.
- **No se envían totales al backend.** `totalPedido` y `cantidadItems` son siempre respuesta.

---

## 6. Limitaciones conocidas

- **El detalle todavía no existe en la API.** Hasta HU-17 un pedido recién creado siempre muestra
  `totalPedido: 0.00` y `cantidadItems: 0`, y la pantalla de cancelación no puede listar los
  productos desde el backend.
- **`fechaEntrega` nula no se serializa.** La API omite los campos nulos; trata la ausencia de la
  propiedad como «sin fecha de entrega», no como un error.
- **La validación de `fechaEntrega` usa la fecha del servidor de aplicación.** La fecha del
  pedido la pone MySQL (R-01). Con la configuración por defecto ambas están en
  `America/Bogota`; si el despliegue separa las zonas horarias, cerca de la medianoche una
  entrega «de hoy» podría comportarse de forma distinta. No envíes fechas calculadas con la zona
  del navegador: usa la fecha local del servidor.
- **CP-21 requiere configuración.** Desactivar un cliente con pedidos en curso responde
  `409 CLIENTE_CON_PEDIDOS_ACTIVOS` si la regla de HU-05 está habilitada (valor por defecto). El
  pedido existente sigue su curso (R-07), pero para reproducir el caso de QA hay que arrancar la
  API con `app.clientes.bloquear-desactivacion-con-pedidos=false`.
- **Los datos de ejemplo cambian con la base.** Nombres, ids y totales salen de la
  especificación y de la ejecución en caliente; confírmalos al integrar.
- **Backend verificado en caliente el 2026-10-30.** Cualquier cambio de contrato actualiza este
  manual en el mismo commit.
