# HU-25 — Consulta de pedidos · Manual de consumo para el frontend

Contrato práctico para consultar pedidos con filtros combinables, pedir el detalle de cada
pedido en el listado y consumir los tres reportes: el resumen del período, las ventas por cliente
y el ranking de productos más vendidos. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md`, el manual de la cabecera en
[`HU-16-creacion-pedidos.md`](HU-16-creacion-pedidos.md), el de las líneas en
[`HU-17-productos-pedido.md`](HU-17-productos-pedido.md), el de las devoluciones en
[`HU-18-registro-devoluciones.md`](HU-18-registro-devoluciones.md) y la ficha
[`../Hechos/HU-25-consulta-pedidos.md`](../Hechos/HU-25-consulta-pedidos.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Solo lectura:** esta historia no tiene escritura (R-01). Cambiar el estado de un pedido es
  HU-16; agregar o editar líneas es HU-17; las devoluciones son HU-18.
- **Roles:** el listado lo leen `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`; el resumen, las
  ventas por cliente y el ranking los reserva la matriz a `ADMIN` y `VENTAS` — el resto recibe
  `403 SIN_PERMISO` en esos tres.

> **Estado:** backend verificado en caliente contra MySQL el 2026-11-05 (QA ciego: CP-01…CP-25 y
> SEC-01…SEC-05 en los 4 endpoints, con dos reservas de seguridad que constan en la ficha). El
> consumo desde Angular sigue pendiente. Los ids, los nombres y los totales dependen de la base.

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
| `GET /pedidos/resumen` | sí | sí | 403 | 403 | 403 |
| `GET /pedidos/por-cliente` | sí | sí | 403 | 403 | 403 |
| `GET /pedidos/productos-mas-vendidos` | sí | sí | 403 | 403 | 403 |

`PRODUCCION` y `CONSULTA` ven el listado, pero **no** los reportes: las tarjetas, las ventas por
cliente y el ranking se muestran solo a `ADMIN` y `VENTAS`. Ocultar el bloque no sustituye al
`403`, que el backend aplica igual.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/pedidos` | ADMIN / VENTAS / PRODUCCION / CONSULTA | 200, 400, 401, 403, 404 |
| GET | `/pedidos/resumen` | ADMIN / VENTAS | 200, 400, 401, 403 |
| GET | `/pedidos/por-cliente` | ADMIN / VENTAS | 200, 400, 401, 403 |
| GET | `/pedidos/productos-mas-vendidos` | ADMIN / VENTAS | 200, 400, 401, 403 |

`POST`, `PUT`, `PATCH` y `DELETE` sobre `/pedidos/resumen`, `/pedidos/por-cliente` y
`/pedidos/productos-mas-vendidos` responden `405 METODO_NO_PERMITIDO`: los paths están
reservados (R-01). No hay escritura en esta historia.

### 2.1. Listado de pedidos

```http
GET /api/v1/pedidos?idCliente=7&fechaInicio=2026-09-01&fechaFin=2026-09-18&estado=ENTREGADO
```

Parámetros — todos opcionales:

| Parámetro | Formato | Defecto | Regla |
|---|---|---|---|
| `idCliente` | entero | — | Si el id no existe: `404 CLIENTE_INEXISTENTE` (por existencia, no por actividad: un cliente desactivado se puede consultar) |
| `fechaInicio` / `fechaFin` | `yyyy-MM-dd` | — | Ambos inclusivos sobre `fechaPedido`; invertidos: `400 RANGO_FECHAS_INVALIDO` |
| `estado` | `PENDIENTE` \| `EN_PREPARACION` \| `ENTREGADO` \| `CANCELADO` | — | Literal no canónico (p. ej. `DESPACHADO`): `400 PARAMETRO_INVALIDO` |
| `page` | entero ≥ 0 | `0` | |
| `size` | entero | `20` | `1..100`; con `incluirDetalle=true` se **recorta a 20** si lo excede |
| `sort` | `fechaPedido` \| `fechaEntrega` \| `estado` \| `total` \| `id` | `fechaPedido,desc` | Formato `campo,asc\|desc`; otro campo: `400 CAMPO_ORDEN_INVALIDO`. El desempate es por `id` descendente |
| `incluirDetalle` | `true` \| `false` | `false` | Con `true`, cada pedido trae su array `detalle` |

```json
// 200 OK — respuesta del contrato con los filtros de arriba
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

- **Los filtros se combinan con `AND`**: cliente, rango y estado se aplican a la vez. Sin
  ninguno, el listado es el historial completo paginado. No filtres en el cliente sobre una
  página de 20.
- **El rango es inclusivo en los dos extremos** (R-05): `fechaInicio=2026-09-01` y
  `fechaFin=2026-09-18` incluyen pedidos del día 01 y del día 18. Como `fechaPedido` es `DATE`
  (sin hora), no se pierde nada por la medianoche. El rango se aplica sobre `fechaPedido`, no
  sobre `fechaEntrega`.
- **Tres importes por pedido** (R-04): `totalBruto` (suma de líneas al precio histórico),
  `devoluciones` (solo las devoluciones `APROBADA`) y `totalNeto = totalBruto − devoluciones`.
  Una devolución `PENDIENTE` o `RECHAZADA` no descuenta.
- **`totalPedido` se conserva de HU-16** y vale lo mismo que `totalBruto`: el contrato anterior
  no se rompe. Para pintar la venta usa `totalNeto`; para el volumen devuelto, `devoluciones`.
- **`total` ordena por el bruto** derivado del detalle, no por una columna guardada ni por el
  neto.
- **Los importes los calcula el backend** con `SUM(cantidad × precio_unitario)` sobre
  `detalle_pedido` (R-02): cada pedido se valora con el precio de cuando se vendió, y editar el
  precio del catálogo no los mueve. No sumes líneas en el cliente.
- **`fechaEntrega` es opcional:** un pedido sin fecha de entrega no trae la propiedad.
- **Una página fuera de rango responde `200` con `content: []`** (`last: true`), no un `404`.

**Con detalle (`incluirDetalle=true`):**

```json
// 200 OK — GET /api/v1/pedidos?incluirDetalle=true&idCliente=7&fechaInicio=2026-09-18&fechaFin=2026-09-18
{
  "content": [
    { "idPedido": 47,
      "cliente": { "idCliente": 7, "nombre": "Tienda La Esquina" },
      "fechaPedido": "2026-09-18", "fechaEntrega": "2026-09-18",
      "estado": "ENTREGADO", "totalPedido": 51000.00,
      "totalBruto": 51000.00, "devoluciones": 3600.00, "totalNeto": 47400.00,
      "cantidadItems": 2, "editable": false,
      "detalle": [
        { "idDetallePedido": 91,
          "producto": { "idProducto": 4, "nombre": "Pan francés", "unidad": "und" },
          "cantidad": 30.00, "precioUnitario": 1200.00, "subtotal": 36000.00 },
        { "idDetallePedido": 92,
          "producto": { "idProducto": 6, "nombre": "Croissant", "unidad": "und" },
          "cantidad": 6.00, "precioUnitario": 2500.00, "subtotal": 15000.00 }
      ] }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

- El campo se llama **`detalle`** (no `detalles`); cada línea trae `producto`, `cantidad`,
  `precioUnitario` histórico y `subtotal = cantidad × precioUnitario` a escala 2. Los lotes
  consumidos y el total de la línea **no** viajan aquí (no se serializan): son de
  `GET /pedidos/{id}/detalles` (HU-17).
- **Sin `incluirDetalle`, el campo `detalle` no se serializa** (no es `null`, simplemente no está).
  Un pedido pedido con detalle y sin líneas responde `"detalle": []`.
- **`size` con detalle se limita a 20 aunque pidas más:** `?incluirDetalle=true&size=100`
  responde `size: 20` en la cabecera. No es un error; sin detalle, la cota sigue en 100 y fuera
  de rango responde `400 PARAMETRO_INVALIDO`. Usa el `size` de la respuesta como fuente de verdad.
- El detalle se carga **al expandir la fila** (R-06): no pidas `incluirDetalle=true` para el
  listado completo de un año.

### 2.2. Resumen del período

```http
GET /api/v1/pedidos/resumen?fechaInicio=2026-09-01&fechaFin=2026-09-30
```

`fechaInicio` y `fechaFin` son opcionales (los dos, ninguno, o solo uno); mismo formato
`yyyy-MM-dd` y la misma regla de rango invertido que el listado.

```json
// 200 OK
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

Cómo interpretar cada campo:

| Campo | Qué es |
|---|---|
| `periodo.desde` / `periodo.hasta` | Rango efectivo aplicado. Si no enviaste fechas, `periodo` **no se serializa**; si enviaste solo una, la otra no aparece |
| `entregados.cantidad` / `.total` | Pedidos `ENTREGADO` del rango y su suma de líneas: venta consumada |
| `enCurso.cantidad` / `.total` | Pedidos `PENDIENTE` y `EN_PREPARACION` juntos: venta comprometida |
| `cancelados.cantidad` / `.total` | Pedidos `CANCELADO`: se informan, pero **no son venta** |
| `ventaBruta` | **Solo** `entregados.total` (R-03); no suma lo `enCurso` |
| `devoluciones` | Devoluciones `APROBADA` de los pedidos entregados (R-04) |
| `ventaNeta` | `ventaBruta − devoluciones` |
| `ticketPromedio` | `ventaBruta / entregados.cantidad`, escala 2; **ausente** si no hay entregados |

- **Los cancelados quedan fuera de la venta** (R-03): no se vendió nada. Aparecen en su bloque
  con cantidad y total para explicar el período completo.
- **Comprometido y consumado van separados a propósito**: no sumes `entregados` y `enCurso` para
  pintar «ventas del mes»; la cifra no sería ni lo facturado ni lo prometido.
- **Bruta, devoluciones y neta** (R-04): informar solo la bruta sobrevalora las ventas; solo la
  neta oculta el volumen de devoluciones. Pinta las tres.
- **Los totales salen siempre del precio histórico** (R-02) recalculado en la consulta.
- **Período sin pedidos → `200` con todo en cero**: cantidades `0` y totales `0.00`, **sin el
  campo `ticketPromedio`**. No es un `404`. Ver «Comportamientos conocidos».
- **Rango invertido → `400 RANGO_FECHAS_INVALIDO`** (mismo `code` que en los otros tres).
- No hay paginación: la respuesta es un único objeto.

### 2.3. Ventas por cliente

```http
GET /api/v1/pedidos/por-cliente?fechaInicio=2026-09-01&fechaFin=2026-09-30
```

Mismos parámetros de fecha que el resumen. La respuesta es un **array** (no un objeto paginado):

```json
// 200 OK
[
  { "cliente": { "idCliente": 7, "nombre": "Supermercado Central" },
    "cantidadPedidos": 9, "total": 842000.00, "porcentaje": 40.31 },
  { "cliente": { "idCliente": 3, "nombre": "Panadería El Trigo" },
    "cantidadPedidos": 5, "total": 521000.00, "porcentaje": 24.94 }
]
```

| Campo | Qué es |
|---|---|
| `cliente.idCliente` / `cliente.nombre` | Cliente agrupado |
| `cantidadPedidos` | Pedidos **distintos** del cliente en el rango, sin los `CANCELADO` |
| `total` | `SUM(cantidad × precio_unitario)` de sus líneas, **en bruto**, escala 2 |
| `porcentaje` | Peso de ese `total` sobre la suma del período, a escala 2 (ej. `40.31` = 40,31 %) |

- **Cuenta entregados y en curso, no solo entregados**, y **no descuenta devoluciones**: el total
  es bruto. Excluye únicamente los `CANCELADO`. La especificación no lo precisa y está por
  confirmar (ver «Comportamientos conocidos»): no lo cuadres contra la `ventaNeta` del resumen.
- **Orden por `total` descendente**; a igual total, por nombre.
- **Los porcentajes suman 100,00 con residuo de redondeo**: se redondean a escala 2 con `HALF_UP`
  sin forzar el último. Suma la columna con tolerancia y no esperes exactamente `100.00`.
- **Período sin pedidos → `200` con `[]`** (no un error); pinta «sin ventas en el período».

### 2.4. Productos más vendidos

```http
GET /api/v1/pedidos/productos-mas-vendidos?fechaInicio=2026-09-01&fechaFin=2026-09-30
```

Mismos parámetros de fecha. La respuesta es un **array**:

```json
// 200 OK
[
  { "producto": "Pan francés", "unidadesVendidas": 1240.00,
    "importe": 1488000.00, "pedidos": 18 },
  { "producto": "Croissant", "unidadesVendidas": 320.00,
    "importe": 800000.00, "pedidos": 12 }
]
```

| Campo | Qué es |
|---|---|
| `producto` | Nombre del producto agrupado |
| `unidadesVendidas` | Suma de `cantidad` de sus líneas, escala 2 |
| `importe` | `SUM(cantidad × precio_unitario)`, al precio histórico, escala 2 |
| `pedidos` | Pedidos **distintos** en que aparece el producto |

- **Orden por unidades, no por importe** (R-07): un producto barato vendido muchas veces va
  antes que uno caro vendido pocas. El importe es una columna adicional; a igual cantidad
  desempata por importe y luego por nombre.
- **Solo cuentan los pedidos `ENTREGADO`**: los `PENDIENTE`, `EN_PREPARACION` y `CANCELADO` no
  suman. Es la diferencia con `/por-cliente`, que sí incluye los pedidos en curso.
- **Período sin entregados → `200` con `[]`.**
- No hay `limit`: devuelve todos los productos vendidos. Para un «top N», corta en el cliente.

### 2.5. Flujo recomendado: las cuatro llamadas del panel

Las cuatro consultas iniciales son independientes y se lanzan en paralelo con los mismos filtros:

1. `GET /pedidos/resumen?fechaInicio=&fechaFin=` → tarjetas de venta neta, comprometido,
   devoluciones y ticket promedio.
2. `GET /pedidos/por-cliente?fechaInicio=&fechaFin=` → barras de ventas por cliente.
3. `GET /pedidos/productos-mas-vendidos?fechaInicio=&fechaFin=` → ranking de productos.
4. `GET /pedidos?...` con los mismos filtros → tabla de pedidos (sin detalle).
5. Al expandir una fila, pide `GET /pedidos/{id}/detalles` de HU-17 para un pedido concreto (o
   `GET /pedidos?...&incluirDetalle=true` acotado al período visible).

Mismos filtros de fecha en todas: si no, los números del panel y la tabla no cuadran. Si un
bloque falla, que falle solo ese; los demás siguen.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-11-05T10:15:22.401", "status": 400, "error": "Bad Request",
  "code": "RANGO_FECHAS_INVALIDO",
  "message": "La fecha de inicio no puede ser posterior a la fecha de fin.",
  "path": "/api/v1/pedidos", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `RANGO_FECHAS_INVALIDO` | 400 | `fechaInicio` posterior a `fechaFin` (en los 4 endpoints) | Marcar el campo de fecha final, no enviar la petición |
| `PARAMETRO_INVALIDO` | 400 | `estado` no canónico (`?estado=DESPACHADO`), `size` fuera de `1..100`, `page` negativo o fecha mal formada | Corregir el filtro; el selector de estado debe ofrecer solo los cuatro valores canónicos |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo fuera de la lista blanca (`fechaPedido`, `fechaEntrega`, `estado`, `total`, `id`) | Enviar el `sort` de la cabecera de tabla, traducido a esos nombres |
| `CLIENTE_INEXISTENTE` | 404 | El `idCliente` del filtro no existe | Recargar el catálogo de clientes (HU-05). **Ojo:** el mismo `code` en el alta de pedidos de HU-16 es `409`, porque ahí es un conflicto de negocio; en el filtro es un 404 |
| `METODO_NO_PERMITIDO` | 405 | Escritura (`POST`/`PUT`/`PATCH`/`DELETE`) sobre `/resumen`, `/por-cliente` o `/productos-mas-vendidos` | No enviar escrituras a esta historia (R-01) |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (p. ej. `PRODUCCION` pidiendo el resumen) | Ocultar el bloque del panel para ese rol |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |

`fieldErrors` es un **array** de `{ field, message }` y llega vacío en estos errores de query.
El `Content-Type` de las respuestas es `application/json` sin `charset=UTF-8`.

---

## 4. Filtros y pantalla

### Controles de filtro

| Control | Envía | Validación previa |
|---|---|---|
| Cliente | `idCliente` | Selector del catálogo (HU-05); vacío = todos |
| Estado | `estado` | Solo `PENDIENTE`, `EN_PREPARACION`, `ENTREGADO`, `CANCELADO` |
| Período | `fechaInicio`, `fechaFin` | `desde ≤ hasta` (el backend responde `400` si no); por defecto, mes en curso |
| Orden de la tabla | `sort=campo,asc\|desc` | Campos `fechaPedido`, `fechaEntrega`, `estado`, `total`, `id` |
| Expandir fila | `GET /pedidos/{id}/detalles` o `incluirDetalle=true` | Con `incluirDetalle`, acotar al período visible: la página se limita a 20 |

- Combina los filtros en **una sola** petición con `AND`: no filtres en el cliente sobre una
  página de 20.
- Recuerda la página activa al cambiar un filtro: `page` vuelve a `0` solo si cambia el filtro,
  no la ordenación.
- Los reportes (`resumen`, `por-cliente`, `productos-mas-vendidos`) solo reciben el **período**:
  no aceptan `idCliente` ni `estado`. Si el usuario filtra por cliente, el panel de reportes sigue
  siendo del período completo; indícalo en la pantalla.
- `401` → login; `403` → ocultar el bloque y avisar; `404` de cliente → recargar el catálogo.

### Estados de la pantalla

| Estado | Qué se muestra |
|---|---|
| Cargando | Tarjetas en esqueleto |
| Vacío en el período | «No hay pedidos en el período seleccionado» |
| Vacío con filtros | «Ningún pedido coincide» + «Limpiar filtros» |
| Sin devoluciones | `—` en la columna, no `$ 0` |
| Error en un bloque | Ese bloque falla; los demás siguen |

**Nota de diseño (HU-25):** mostrar `—` y no `$ 0` en devoluciones cuando son `0`, para distinguir
«no hubo» de «hubo por cero». El backend envía `0.00`; la sustitución la hace el cliente.

### Interpretación de importes

- Todos los importes son **decimales a escala 2** calculados por el backend; la respuesta JSON
  los trae como número.
- `porcentaje` es una fracción ya convertida a % (`40.31`), no `0.4031`.
- `ventaBruta` y los totales de `enCurso` van separados a propósito: no los sumes para pintar
  «total del mes», porque lo comprometido no es venta consumada.
- Para el porcentaje de devolución de una tarjeta, calcula `devoluciones / ventaBruta` en el cliente
  y protege la división cuando `ventaBruta` es `0`.

---

## 5. Qué NO está en el contrato

- **No hay escritura en HU-25** (R-01): nada de `POST`, `PUT`, `PATCH` o `DELETE` en los cuatro
  endpoints; los tres reportes responden `405`. El estado se cambia en HU-16, las líneas en HU-17
  y las devoluciones en HU-18.
- **No hay exportación a Excel ni PDF**, ni comparación de períodos entre sí: fuera de alcance
  de la historia. Para comparar dos meses, haz dos llamadas a `/resumen`.
- **Los reportes no filtran por cliente ni por estado**, solo por rango de fechas.
- **No hay paginación ni `limit` en los tres reportes**: son un objeto y dos arrays.
- **No hay filtro por producto ni por importe** en el listado.
- **No se envía ningún total al backend.** Todos los campos de esta historia son respuesta.
- **No hay un endpoint de «pedido con detalle»**: para un pedido concreto, `GET /pedidos/{id}`
  (cabecera) y `GET /pedidos/{id}/detalles` (líneas, de HU-17).

---

## 6. Qué no cambia del contrato de HU-16

- Las rutas, los métodos y los códigos de `POST /pedidos`, `PUT /pedidos/{id}`,
  `PATCH /pedidos/{id}/estado` y `GET /pedidos/{id}`.
- **`totalPedido` sigue en la respuesta** con el mismo valor; solo se **añaden** `totalBruto`,
  `devoluciones`, `totalNeto` y el opcional `detalle`. El código existente que lee `totalPedido`
  sigue funcionando.
- Los filtros y la paginación del listado de HU-16; la lista blanca de `sort` solo crece con
  `total`.
- `CLIENTE_INEXISTENTE` sigue siendo `409` en el alta y la edición; el `404` es del filtro.
- `GET /pedidos/{id}` devuelve los mismos campos de importe y no trae `detalle`.

---

## 7. Limitaciones conocidas

- **Cifras muy grandes pierden centavos al parsearse en JavaScript.** Con magnitudes superiores
  a 2^53 (~9 × 10^15) el número JSON excede la precisión de `double` y el cliente puede ver un
  total con centavos desplazados. El backend devuelve el decimal exacto de `BigDecimal`; la
  pérdida ocurre en el parser del navegador y el contrato §6 lo admite. Con las magnitudes reales
  de una pyme no se afecta.
- **El orden por `total` ordena el bruto derivado**, que cambia si se edita el detalle de un
  pedido editable: no asumas que dos refrescos seguidos dan la misma página.
- **Un `Accept` no soportado con token válido puede responder `401` engañoso**: deuda
  transversal abierta (D2 de HU-14). No envíes `Accept` exóticos.
- **Los datos de ejemplo cambian con la base.** Nombres, ids, totales y porcentajes salen de la
  especificación y de la ejecución de QA; confírmalos al integrar.
- **Backend verificado en caliente el 2026-11-05** (CP-01…CP-25 y SEC-01…05 en los 4
  endpoints). Cualquier cambio de contrato actualiza este manual en el mismo commit.

### Comportamientos conocidos / puntos a confirmar

- **`ticketPromedio` no viene cuando no hay pedidos entregados.** En un rango vacío el resumen
  responde las cifras en `0` y **sin** ese campo (es `null` y la API omite los nulos). Trata la
  ausencia como «sin ticket» y pinta `—`, no `$ 0`.
- **Con `incluirDetalle=true` el `size` se recorta en silencio a 20.** No responde `400`
  (R-06 lo permite), a diferencia del tope de 100 sin detalle, que sí rechaza. Lee el `size` de la
  respuesta, no el que enviaste.
- **`/por-cliente` suma entregados y en curso, en bruto, sin descontar devoluciones.** La
  especificación no lo precisa: **por confirmar con el analista.** El diseño sugiere que sus
  totales cuadran con la venta neta del resumen, y hoy no lo hacen; no los presentes como la
  misma cifra.
- **Sin rango de fechas, `/resumen`, `/por-cliente` y el ranking devuelven todo el histórico.**
  Envía siempre el período (por defecto, mes en curso) para no cargar toda la tabla.
- **Transversal, fuera de alcance de esta historia:** el mensaje del `401` sin token dice
  «sesión expirada» aunque nunca hubo sesión, y el `Content-Type` no lleva `charset`. No muestres
  ese `message` literalmente; decide el aviso por el `code`.
- **El `EXPLAIN` sobre `idx_pedidos_fecha` lo comprobó QA, pero no es prueba automática.** La
  consulta por fecha y estado usa el índice (CP-23); si el esquema cambia, hay que repetirlo a mano.
