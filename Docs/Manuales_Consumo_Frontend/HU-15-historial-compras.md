# HU-15 — Historial de compras · Manual de consumo para el frontend

Contrato práctico para consultar el historial de compras con filtros combinables, pedir el
detalle de cada compra en el listado y consumir los dos agregados: el resumen del período y el
gasto por proveedor. Complementa `../../../PFDocs/00-base/04-CONTRATO-API.md`, el manual de la
cabecera en [`HU-13-registro-compras.md`](HU-13-registro-compras.md), el de las líneas en
[`HU-14-detalle-compra.md`](HU-14-detalle-compra.md) y la ficha
[`../Hechos/HU-15-historial-compras.md`](../Hechos/HU-15-historial-compras.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Solo lectura:** esta historia no tiene escritura (R-01). Cambiar el estado de una compra es
  HU-13; agregar o editar líneas es HU-14.
- **Roles:** el listado lee `ADMIN`, `COMPRAS`, `PRODUCCION` y `CONSULTA`; el resumen y el gasto
  por proveedor los reserva la matriz a `ADMIN` y `COMPRAS` — `PRODUCCION`, `CONSULTA` y
  `VENTAS` reciben `403 SIN_PERMISO` en esos dos.

> **Estado:** backend verificado en caliente contra MySQL el 2026-10-29 (20/21 CP al primer
> pase —CP-21 reparada después en el fix del 405— más SEC-01…SEC-05 en los 3 endpoints). El
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

| Endpoint | ADMIN | COMPRAS | PRODUCCION | VENTAS | CONSULTA |
|---|---|---|---|---|---|
| `GET /compras` | sí | sí | sí | 403 | sí |
| `GET /compras/resumen` | sí | sí | 403 | 403 | 403 |
| `GET /compras/por-proveedor` | sí | sí | 403 | 403 | 403 |

`PRODUCCION` y `CONSULTA` ven el historial, pero **no** los agregados: la pantalla de tablero
(resumen y ranking de proveedores) se muestra solo a `ADMIN` y `COMPRAS`. Ocultar la pestaña no
sustituye al `403`, que el backend aplica igual.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/compras` | ADMIN / COMPRAS / PRODUCCION / CONSULTA | 200, 400, 401, 403, 404 |
| GET | `/compras/resumen` | ADMIN / COMPRAS | 200, 400, 401, 403 |
| GET | `/compras/por-proveedor` | ADMIN / COMPRAS | 200, 400, 401, 403 |

`POST`, `PUT`, `PATCH` y `DELETE` sobre `/compras/resumen` y sobre
`/compras/por-proveedor` responden `405 METODO_NO_PERMITIDO`: los paths están reservados
(R-01). No hay escritura en esta historia.

### 2.1. Listado del historial

```http
GET /api/v1/compras?idProveedor=3&fechaInicio=2026-09-01&fechaFin=2026-09-18&estado=RECIBIDA
```

Parámetros — todos opcionales:

| Parámetro | Formato | Defecto | Regla |
|---|---|---|---|
| `idProveedor` | entero | — | Si el id no existe: `404 PROVEEDOR_INEXISTENTE` |
| `fechaInicio` / `fechaFin` | `yyyy-MM-dd` | — | Ambos inclusivos; invertidos: `400 RANGO_FECHAS_INVALIDO` |
| `estado` | `PENDIENTE` \| `RECIBIDA` \| `CANCELADA` | — | Literal no canónico (p. ej. `ENVIADA`): `400 PARAMETRO_INVALIDO` |
| `page` | entero ≥ 0 | `0` | |
| `size` | entero | `20` | `1..100`; con `incluirDetalle=true` se **recorta a 20** si lo excede (CP-12) |
| `sort` | `fecha` \| `estado` \| `total` \| `id` | `fecha,desc` | Formato `campo,asc\|desc`; otro campo: `400 CAMPO_ORDEN_INVALIDO` |
| `incluirDetalle` | `true` \| `false` | `false` | Con `true`, cada compra trae su array `detalle` |

```json
// 200 OK — respuesta del contrato con los filtros de arriba
{
  "content": [
    { "idCompra": 11, "proveedor": { "idProveedor": 3, "nombre": "Harinas del Caribe" },
      "fecha": "2026-09-15", "estado": "RECIBIDA",
      "cantidadItems": 4, "totalCompra": 1240000.00, "editable": false }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

- **Los filtros se combinan con `AND`** (R-02): proveedor, rango y estado se aplican a la vez.
  Sin ninguno, el listado es el historial completo paginado.
- **El rango es inclusivo en los dos extremos** (R-03): `fechaInicio=2026-09-01` y
  `fechaFin=2026-09-18` incluyen compras del día 01 y del día 18. Como `compras.fecha` es
  `DATE` (sin hora), la comparación es `BETWEEN` y no se pierde nada por la medianoche.
- **`total` ordena por el total derivado** del detalle (el alias `total_compra`), no por una
  columna guardada: es el campo nuevo de esta historia.
- **`totalCompra` y `cantidadItems` los calcula el backend** con `SUM(cantidad × precio_unitario)`
  y `COUNT(*)` sobre `detalle_compra` (R-05). No sumes subtotales en el cliente.
- **Una página fuera de rango responde `200` con `content: []`** (`last: true`), no un `404`.

**Con detalle (`incluirDetalle=true`):**

```json
// 200 OK — GET /api/v1/compras?incluirDetalle=true&fechaInicio=2026-09-15&fechaFin=2026-09-15
{
  "content": [
    { "idCompra": 11,
      "proveedor": { "idProveedor": 3, "nombre": "Harinas del Caribe" },
      "fecha": "2026-09-15", "estado": "RECIBIDA",
      "totalCompra": 1240000.00, "cantidadItems": 2, "editable": false,
      "detalle": [
        { "idDetalleCompra": 28,
          "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo", "unidad": "kg" },
          "cantidad": 100.00, "precioUnitario": 3200.00, "subtotal": 320000.00,
          "costoReferencia": 3200.00, "diferenciaCosto": 0.00 },
        { "idDetalleCompra": 29,
          "materiaPrima": { "idMateriaPrima": 2, "nombre": "Azúcar", "unidad": "kg" },
          "cantidad": 50.00, "precioUnitario": 3200.00, "subtotal": 160000.00,
          "costoReferencia": 3000.00, "diferenciaCosto": 200.00 }
      ] }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

- El campo se llama **`detalle`** (no `detalles`), y es la misma forma de línea de HU-14:
  `subtotal = cantidad × precioUnitario` a escala 2 y el comparativo `costoReferencia` /
  `diferenciaCosto` cuando la materia prima tiene costo.
- **Sin `incluirDetalle`, el campo `detalle` no se serializa** (no es `null` en el JSON, simplemente
  no está). Una compra pedida con detalle y sin líneas responde `"detalle": []`.
- **`size` con detalle se limita a 20 aunque pidas más:** `?incluirDetalle=true&size=100`
  responde `size: 20` en la cabecera. No es un error (CP-12); sin detalle, la cota sigue en 100.
- El `totalCompra` de cada línea (`totalCompra` dentro de `detalle`) va en `null` y por eso no
  aparece: el total agregado viaja una sola vez en la cabecera de la compra.
- El detalle se carga **al expandir la fila** (R-06): no pidas `incluirDetalle=true` para el
  listado completo de un año; con él, la página se recorta a 20 a propósito.

### 2.2. Resumen del período

```http
GET /api/v1/compras/resumen?fechaInicio=2026-09-01&fechaFin=2026-09-30
```

`fechaInicio` y `fechaFin` son opcionales (los dos, ninguno, o solo uno); mismo formato
`yyyy-MM-dd` y la misma regla de rango invertido que el listado.

```json
// 200 OK
{
  "periodo": { "desde": "2026-09-01", "hasta": "2026-09-30" },
  "recibidas":  { "cantidad": 8, "total": 4850000.00 },
  "pendientes": { "cantidad": 2, "total":  620000.00 },
  "canceladas": { "cantidad": 1, "total":   85000.00 },
  "gastoEjecutado":  4850000.00,
  "gastoComprometido": 620000.00
}
```

Cómo interpretar cada campo:

| Campo | Qué es |
|---|---|
| `periodo.desde` / `periodo.hasta` | Rango efectivo aplicado. Si no enviaste fechas, los campos vienen en `null` y **no se serializan**: `"periodo": {}` |
| `recibidas.cantidad` / `.total` | Compras `RECIBIDA` del rango y su suma de líneas |
| `pendientes.cantidad` / `.total` | Compras `PENDIENTE` del rango (gasto comprometido) |
| `canceladas.cantidad` / `.total` | Compras `CANCELADA` del rango: se informan, pero **no financian ningún gasto** |
| `gastoEjecutado` | **Solo** `recibidas.total`: dinero que ya salió (R-04) |
| `gastoComprometido` | **Solo** `pendientes.total`: pedido, todavía no pagado (R-04) |

- **Las canceladas quedan fuera de los dos gastos** (R-04): nunca se pagó. Sí aparecen en su
  bloque `canceladas` con su cantidad y su total, para explicar el período completo.
- **Los totales salen siempre del detalle** (R-05): cada `total` es
  `SUM(cantidad × precio_unitario)` recalculado en la consulta; una compra sin líneas cuenta en
  `cantidad` con total `0.00`.
- **Período sin compras → `200` con todo en cero** (CP-17): cantidades `0` y totales `0.00`, con
  escala 2. No es un `404`.
- **Rango invertido → `400 RANGO_FECHAS_INVALIDO`** (mismo `code` que en el listado y en
  por-proveedor).
- No hay paginación: la respuesta es un único objeto.

### 2.3. Gasto por proveedor

```http
GET /api/v1/compras/por-proveedor?fechaInicio=2026-09-01&fechaFin=2026-09-30
```

Mismos parámetros de fecha que el resumen. La respuesta es un **array** (no un objeto paginado):

```json
// 200 OK
[
  { "proveedor": "Harinas del Caribe S.A.S.", "cantidadCompras": 4,
    "total": 2340000.00, "porcentaje": 48.25 },
  { "proveedor": "Distribuidora Lácteos", "cantidadCompras": 3,
    "total": 1810000.00, "porcentaje": 37.32 }
]
```

| Campo | Qué es |
|---|---|
| `proveedor` | Nombre del proveedor agrupado |
| `cantidadCompras` | Compras **distintas** `RECIBIDA` de ese proveedor en el rango |
| `total` | `SUM(cantidad × precio_unitario)` de sus líneas, escala 2 |
| `porcentaje` | Peso de ese `total` sobre la suma del período, a escala 2 (ej. `48.25` = 48,25 %) |

- **Solo cuenta el gasto ejecutado:** entran únicamente compras `RECIBIDA`. Las `PENDIENTE` y
  las `CANCELADA` quedan fuera, y un proveedor sin recibidas en el rango **no aparece**.
- **Orden por `total` descendente** (CP-15): el primer elemento es el proveedor que más vendió
  en el período.
- **Los porcentajes suman 100,00 con residuo de redondeo ±0,01** (CP-16): se redondean a escala
  2 con `HALF_UP` sin forzar el último. Suma la columna en el cliente con tolerancia y no
  esperes exactamente `100.00`.
- **Período sin recibidas → `200` con `[]`** (no un error); pinta «sin gasto en el período».
- Un `total` en `0.00` con `porcentaje` `0.00` es posible (recibidas a precio cero).

### 2.4. Flujo recomendado: las tres llamadas del tablero

1. `GET /compras/resumen?fechaInicio=&fechaFin=` → tarjetas de gasto ejecutado y comprometido.
2. `GET /compras/por-proveedor?fechaInicio=&fechaFin=` → ranking con barras y porcentajes.
3. `GET /compras?...` con los mismos filtros → tabla del historial (sin detalle).
4. Al expandir una fila, pide `GET /compras?...&incluirDetalle=true` acotado al período visible
   (o `GET /compras/{id}/detalles` de HU-14 para una compra concreta).

Mismos filtros de fecha en las tres llamadas: si no, los números del tablero y la tabla no
cuadran.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-29T10:15:22.401", "status": 400, "error": "Bad Request",
  "code": "RANGO_FECHAS_INVALIDO",
  "message": "La fecha de inicio no puede ser posterior a la fecha de fin.",
  "path": "/api/v1/compras", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `RANGO_FECHAS_INVALIDO` | 400 | `fechaInicio` posterior a `fechaFin` (en los 3 endpoints) | Marcar el campo de fecha final, no enviar la petición |
| `PARAMETRO_INVALIDO` | 400 | `estado` no canónico (`?estado=ENVIADA`), `size` fuera de rango o fecha mal formada | Corregir el filtro; el selector de estado debe ofrecer solo los tres valores canónicos |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo fuera de la lista blanca (`fecha`, `estado`, `total`, `id`) | Enviar el `sort` de la cabecera de tabla, traducido a esos nombres |
| `PROVEEDOR_INEXISTENTE` | 404 | El `idProveedor` del filtro no existe | Recargar el catálogo de proveedores (HU-06). **Ojo:** el mismo `code` en el alta de HU-13 es `409`, porque ahí es un conflicto de negocio; en el filtro es un 404 |
| `METODO_NO_PERMITIDO` | 405 | Escritura (`POST`/`PUT`/`PATCH`/`DELETE`) sobre `/resumen` o `/por-proveedor` | No enviar escrituras a esta historia (R-01) |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (p. ej. `CONSULTA` pidiendo el resumen) | Ocultar la pestaña del tablero para ese rol |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |

`fieldErrors` es un **array** de `{ field, message }` y llega vacío en estos errores de query.
El `Content-Type` de las respuestas es `application/json` sin `charset=UTF-8`.

---

## 4. Filtros y pantalla

### Controles de filtro

| Control | Envía | Validación previa |
|---|---|---|
| Proveedor | `idProveedor` | Selector del catálogo (HU-06); vacío = todos |
| Estado | `estado` | Solo `PENDIENTE`, `RECIBIDA`, `CANCELADA` |
| Desde / hasta | `fechaInicio`, `fechaFin` | `desde ≤ hasta` (el backend responde `400` si no) |
| Orden de la tabla | `sort=campo,asc\|desc` | Campos `fecha`, `estado`, `total`, `id` |
| Expandir fila | `incluirDetalle=true` | Acotar al período visible: la página se limita a 20 |

- Combina los filtros en **una sola** petición con `AND`: no filtres en el cliente sobre una
  página de 20.
- Recuerda la página activa al cambiar un filtro: `page` vuelve a `0` solo si cambia el filtro,
  no la ordenación.
- `401` → login; `403` → ocultar la acción y avisar; `404` de proveedor → recargar el catálogo.

### Interpretación de importes

- Todos los totales son **decimales a escala 2** calculados por el backend; la respuesta JSON
  los trae como número.
- `porcentaje` es una fracción ya convertida a % (`48.25`), no `0.4825`.
- `gastoEjecutado` y `gastoComprometido` van separados a propósito: no los sumes para pintar
  «total del mes», porque las canceladas quedarían fuera y las pendientes no son dinero salido.

---

## 5. Qué NO está en el contrato

- **No hay escritura en HU-15** (R-01): nada de `POST`, `PUT`, `PATCH` o `DELETE` en los tres
  endpoints; los de agregado responden `405`. El estado se cambia en HU-13 y las líneas en HU-14.
- **No hay exportación a Excel ni PDF**, ni comparación de períodos entre sí: fuera de alcance
  de la historia. Para comparar dos meses, haz dos llamadas a `/resumen`.
- **No hay filtro por texto de proveedor ni por importe**, ni filtro por materia prima dentro
  del historial.
- **No hay paginación en `/resumen` ni en `/por-proveedor`**: son un objeto y un array.
- **No se envía ningún total al backend.** Todos los campos de esta historia son respuesta.
- **No hay totales agregados en el listado de HU-13**: las cifras del tablero salen de
  `/resumen`, no de sumar `content` en el cliente.

---

## 6. Limitaciones conocidas

- **Cifras muy grandes pierden centavos al parsearse en JavaScript.** Con magnitudes superiores
  a 2^53 (~9 × 10^15) el número JSON excede la precisión de `double` y el cliente puede ver un
  total con centavos desplazados (p. ej. una compra de ~1 × 10^16). El backend devuelve el
  decimal exacto de `BigDecimal`; la pérdida ocurre en el parser del navegador y el contrato
  §6 lo admite. Con las magnitudes reales de una pyme no se afecta.
- **Con `incluirDetalle=true` la página es como máximo 20** aunque pidas más (CP-12): es una
  cota de rendimiento (R-06), no un error. Usa la cabecera `size` de la respuesta como fuente
  de verdad.
- **El orden por `total` ordena el total derivado**, que cambia si se edita el detalle: no
  asumas que dos refrescos seguros dan la misma página.
- **Un `Accept` no soportado con token válido puede responder `401` engañoso**: deuda
  transversal abierta (D2 de HU-14). No envíes `Accept` exóticos.
- **Los datos de ejemplo cambian con la base.** Nombres, ids, totales y porcentajes salen de la
  especificación y de la ejecución de QA; confírmalos al integrar.
- **Backend verificado en caliente el 2026-10-29** (CP-01…CP-21 y SEC-01…05 en los 3
  endpoints). Cualquier cambio de contrato actualiza este manual en el mismo commit.
