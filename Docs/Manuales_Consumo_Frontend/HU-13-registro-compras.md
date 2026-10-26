# HU-13 — Registro de compras · Manual de consumo para el frontend

Contrato práctico del registro, la consulta, la edición y la recepción de las compras a
proveedor. Complementa `../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-13-registro-compras.md`](../Hechos/HU-13-registro-compras.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085` mientras el 8080 esté
  ocupado por pgadmin).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura `ADMIN`, `COMPRAS`, `PRODUCCION` y `CONSULTA`; escritura (alta, edición y
  transiciones) solo `ADMIN` y `COMPRAS`. `VENTAS` **no** participa en este módulo y recibe `403`
  en los cinco endpoints.

> **Estado:** backend verificado en caliente contra MySQL el 2026-10-26 (22 CP + 25 SEC). El
> consumo desde Angular sigue pendiente. Los totales, los ids y los saldos dependen de la base.

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

El frontend guarda el token y lo envía en cada llamada. Un `401 NO_AUTENTICADO` significa token
ausente, malformado o expirado: forzar el login de nuevo.

| Endpoint | ADMIN | COMPRAS | PRODUCCION | VENTAS | CONSULTA |
|---|---|---|---|---|---|
| `GET /compras` | sí | sí | sí | 403 | sí |
| `GET /compras/{id}` | sí | sí | sí | 403 | sí |
| `POST /compras` | sí | sí | 403 | 403 | 403 |
| `PUT /compras/{id}` | sí | sí | 403 | 403 | 403 |
| `PATCH /compras/{id}/estado` | sí | sí | 403 | 403 | 403 |

`PRODUCCION` y `CONSULTA` ven listado y detalle, pero no pueden escribir: la pantalla oculta
«Registrar», «Editar», «Recibir» y «Cancelar» a esos roles, y todo el módulo a `VENTAS`. El
backend aplica la autorización real, así que ocultar el botón no sustituye al `403`.

---

## 2. Endpoints

| Método | Ruta | Rol mínimo | Códigos |
|---|---|---|---|
| GET | `/compras` | CONSULTA / PRODUCCION / COMPRAS / ADMIN | 200, 400, 401, 403 |
| GET | `/compras/{id}` | CONSULTA / PRODUCCION / COMPRAS / ADMIN | 200, 400, 401, 403, 404 |
| POST | `/compras` | COMPRAS / ADMIN | 201, 400, 401, 403, 409 |
| PUT | `/compras/{id}` | COMPRAS / ADMIN | 200, 400, 401, 403, 404, 409 |
| PATCH | `/compras/{id}/estado` | COMPRAS / ADMIN | 200, 400, 401, 403, 404, 409 |

No hay `DELETE`: responde `405 METODO_NO_PERMITIDO` tanto en la colección como en el recurso. Una
compra no se borra; si se registró mal y sigue `PENDIENTE`, se cancela.

### 2.1. Listar compras

```http
GET /api/v1/compras?estado=PENDIENTE&idProveedor=19&fechaInicio=2026-10-01&fechaFin=2026-10-26&page=0&size=2&sort=fecha,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `idProveedor` | entero | — | Filtra por un proveedor concreto |
| `estado` | texto | — | `PENDIENTE`, `RECIBIDA` o `CANCELADA`. Otro valor → `400 PARAMETRO_INVALIDO` |
| `fechaInicio` | fecha ISO | — | Primer día incluido del rango |
| `fechaFin` | fecha ISO | — | Último día incluido del rango |
| `page` | entero ≥ 0 | `0` | Base 0: la primera página es `0` |
| `size` | entero 1…100 | `20` | |
| `sort` | `campo` o `campo,dirección` | `fecha,desc` | Campos válidos: `fecha`, `estado`, `id`. Dirección `asc`/`desc` |

Los filtros se combinan. El rango de fechas es inclusivo en **ambos** extremos; si
`fechaInicio` es posterior a `fechaFin` responde `400 RANGO_FECHAS_INVALIDO` en lugar de una
página vacía. `sort=fecha,` (coma sin dirección) y `sort=fecha,asc,extra` (más de dos partes)
también son `400 CAMPO_ORDEN_INVALIDO`: la dirección, si se escribe, debe ser `asc` o `desc`.

```json
// 200 OK — respuesta real con estado=PENDIENTE&idProveedor=19&fechaInicio=2026-10-01
// &fechaFin=2026-10-26&sort=fecha,asc&size=2
{ "content": [
    { "idCompra": 22, "proveedor": { "idProveedor": 19,
        "nombre": "QA-H09 Proveedor 1792780001" },
      "fecha": "2026-10-05", "estado": "PENDIENTE",
      "totalCompra": 0.00, "cantidadItems": 0, "editable": true },
    { "idCompra": 23, "proveedor": { "idProveedor": 19,
        "nombre": "QA-H09 Proveedor 1792780001" },
      "fecha": "2026-10-11", "estado": "PENDIENTE",
      "totalCompra": 30.00, "cantidadItems": 2, "editable": true }
  ],
  "page": 0, "size": 2, "totalElements": 17, "totalPages": 9,
  "first": true, "last": false }
```

- El `id` de `sort` se traduce a `id_compra`; se ordena por id real, no por el de la posición.
- `totalCompra` y `cantidadItems` son **derivados** del detalle y valen `0.00`/`0` mientras la
  compra no tenga líneas.
- `proveedor` viene embebido: no hace falta una llamada extra para mostrar el nombre.

### 2.2. Obtener una compra

```http
GET /api/v1/compras/30
```

```json
// 200 OK
{ "idCompra": 30,
  "proveedor": { "idProveedor": 19, "nombre": "QA-H09 Proveedor 1792780001" },
  "fecha": "2026-10-26", "estado": "RECIBIDA",
  "totalCompra": 250.00, "cantidadItems": 1, "editable": false }
```

Devuelve el mismo objeto que un elemento de `content`. Responde `404 RECURSO_NO_ENCONTRADO` si el
id no existe y `400 PARAMETRO_INVALIDO` si no es numérico (`GET /compras/abc`). **No incluye las
líneas de detalle**: eso es HU-14.

### 2.3. Registrar una compra

```http
POST /api/v1/compras
Content-Type: application/json

{ "idProveedor": 19, "fecha": "2026-10-26", "estado": "PENDIENTE" }
```

`201 Created` + `Location: /api/v1/compras/28`:

```json
{ "idCompra": 28,
  "proveedor": { "idProveedor": 19, "nombre": "QA-H09 Proveedor 1792780001" },
  "fecha": "2026-10-26", "estado": "PENDIENTE",
  "totalCompra": 0.00, "cantidadItems": 0, "editable": true }
```

**Reglas del alta:**

- **`idProveedor` es obligatorio** y debe existir (`409 PROVEEDOR_INEXISTENTE`) y estar activo
  (`409 PROVEEDOR_INACTIVO`). Un `idProveedor` ausente o `null` responde `400 VALIDACION_FALLIDA`.
- **`estado` es obligatorio y solo admite `PENDIENTE`.** Sin el campo o con `null` responde `400
  VALIDACION_FALLIDA` con `fieldErrors[0] = { "field": "estado", "message": "El estado es
  obligatorio" }`. Un literal no canónico (`"ENVIADA"`, `"pendiente"`) responde `400
  VALIDACION_FALLIDA` con `{ "field": "estado", "message": "El valor del campo no es válido." }`, y
  `"RECIBIDA"`/`"CANCELADA"` —canónicos— también se rechazan con `400`: una compra solo nace
  `PENDIENTE`, porque crear una `RECIBIDA` saltaría la recepción y no sumaría stock.
- **`fecha` es opcional: si se omite, se registra con la fecha de hoy.** Un `null` explícito en
  cambio es un error del cliente y responde `400 VALIDACION_FALLIDA` en el campo `fecha`. El
  frontend no debe enviar `fecha: null`: o envía una fecha ISO o no envía la propiedad.
- **La fecha no puede ser futura** (`400 FECHA_FUTURA`). Las fechas pasadas sí se admiten y
  responden `201`.
- **La compra nace con `totalCompra` 0 y `cantidadItems` 0** y `editable: true`: las líneas llegan
  después (HU-14).

### 2.4. Editar la cabecera

```http
PUT /api/v1/compras/4
Content-Type: application/json

{ "idProveedor": 19, "fecha": "2026-10-20" }
```

```json
// 200 OK — la compra releída de la base
{ "idCompra": 4,
  "proveedor": { "idProveedor": 19, "nombre": "QA-H09 Proveedor 1792780001" },
  "fecha": "2026-10-20", "estado": "PENDIENTE",
  "totalCompra": 0.00, "cantidadItems": 0, "editable": true }
```

- **Solo se edita una compra `PENDIENTE`.** En cualquier otro estado responde `409
  COMPRA_NO_EDITABLE` sin tocar los datos.
- **`idProveedor` y `fecha` son obligatorios** en el cuerpo del `PUT`: a diferencia del alta, la
  fecha no se puede omitir (`400 VALIDACION_FALLIDA`, `fieldErrors[0].field = "fecha"`).
- **El proveedor nuevo debe existir** (`409 PROVEEDOR_INEXISTENTE`), pero **puede estar
  desactivado**: la compra ya registrada no se bloquea por desactivar a su proveedor.
- **La fecha no puede ser futura** (`400 FECHA_FUTURA`).
- **El `estado` no se cambia por aquí.** Si se envía en el cuerpo se ignora; el estado solo se
  mueve con el `PATCH` de la sección siguiente.
- `404 RECURSO_NO_ENCONTRADO` si la compra no existe.

### 2.5. Cambiar de estado: recibir o cancelar

```http
PATCH /api/v1/compras/30/estado
Content-Type: application/json

{ "estado": "RECIBIDA" }
```

La recepción responde `200` con el estado final, el total y **los movimientos de inventario que
acaba de generar**:

```json
{ "idCompra": 30, "estado": "RECIBIDA", "totalCompra": 250.00,
  "movimientosGenerados": [
    { "materiaPrima": "QA-H10-1792970345-CP02", "cantidad": 25.00,
      "saldoAnterior": 50.00, "saldoPosterior": 75.00 }
  ] }
```

La cancelación responde `200` con la compra en la forma de `CompraResponse` (la de
`GET /compras/{id}`):

```json
{ "idCompra": 12,
  "proveedor": { "idProveedor": 19, "nombre": "QA-H09 Proveedor 1792780001" },
  "fecha": "2026-10-16", "estado": "CANCELADA",
  "totalCompra": 20.00, "cantidadItems": 1, "editable": false }
```

**Máquina de estados:**

```
PENDIENTE ──▶ RECIBIDA      (irreversible)
    │
    └───────▶ CANCELADA
```

| Desde | Hacia | Resultado |
|---|---|---|
| `PENDIENTE` | `RECIBIDA` | `200` · suma el stock de cada línea y genera un `ENTRADA_COMPRA` por línea |
| `PENDIENTE` | `CANCELADA` | `200` · no toca el inventario |
| `PENDIENTE` | `PENDIENTE` | `409 TRANSICION_INVALIDA` |
| `RECIBIDA` | `PENDIENTE` o `CANCELADA` | `409 TRANSICION_INVALIDA` · `RECIBIDA` es terminal |
| `CANCELADA` | `PENDIENTE` o `RECIBIDA` | `409 TRANSICION_INVALIDA` · `CANCELADA` es terminal |

Mensaje de ejemplo al intentar volver atrás:

```json
// 409 — PATCH {"estado":"PENDIENTE"} sobre una PENDIENTE (mismo estado)
{ "timestamp": "2026-10-26T14:58:55.921", "status": 409, "error": "Conflict",
  "code": "TRANSICION_INVALIDA",
  "message": "No se puede pasar de PENDIENTE a PENDIENTE. Desde PENDIENTE las transiciones disponibles son: CANCELADA, RECIBIDA.",
  "path": "/api/v1/compras/4/estado", "fieldErrors": [] }
```

Sobre una compra ya `RECIBIDA`, el mensaje acaba en `«Desde RECIBIDA no hay transiciones
disponibles»`. El frontend no necesita interpretar el texto: con `code = TRANSICION_INVALIDA`
basta para refrescar el detalle y deshabilitar las acciones.

**Reglas de la recepción:**

- **Una compra sin líneas no se puede recibir**: `409 COMPRA_SIN_DETALLE`.
- **Recibir dos veces no suma dos veces**: el segundo `PATCH` sobre una `RECIBIDA` responde `409
  TRANSICION_INVALIDA` y el inventario no cambia. Aun así, el botón debe deshabilitarse mientras la
  petición está en vuelo para no mostrar el `409` por un doble clic.
- **La recepción es transaccional**: o entran todas las líneas o no entra ninguna. Si el servidor
  responde un error, el inventario quedó intacto.
- **La respuesta trae `movimientosGenerados`** con `saldoAnterior` y `saldoPosterior` por línea:
  es la confirmación visible de qué entró y cuánto sumó. Una recepción de una compra con líneas
  siempre devuelve al menos un movimiento.
- **El `estado` solo admite los tres valores canónicos.** `{"estado": "ENVIADA"}` → `400
  VALIDACION_FALLIDA`; `{}` → `400 VALIDACION_FALLIDA` con `El estado es obligatorio`.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-26T14:58:47.901", "status": 409, "error": "Conflict",
  "code": "PROVEEDOR_INEXISTENTE", "message": "El proveedor con id 9999 no existe.",
  "path": "/api/v1/compras", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Falta `idProveedor` o `estado`, `fecha: null` explícita, una fecha no ISO, un `estado` no canónico o distinto de `PENDIENTE` al crear | Pintar `fieldErrors[]` junto a cada campo |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100, `estado` de filtro desconocido o id de ruta no numérico | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo fuera de la lista blanca, dirección vacía tras la coma o más de dos partes | Usar `fecha`, `estado` o `id` y dirección `asc`/`desc` |
| `FECHA_FUTURA` | 400 | `fecha` posterior a hoy, al crear o al editar | Bloquear el envío; el datepicker debe impedirlo |
| `RANGO_FECHAS_INVALIDO` | 400 | `fechaInicio` posterior a `fechaFin` | Corregir el rango antes de enviar |
| `PROVEEDOR_INEXISTENTE` | 409 | `idProveedor` no existe | Recargar el catálogo de proveedores (HU-06) |
| `PROVEEDOR_INACTIVO` | 409 | Al crear con un proveedor desactivado | Pedir otro proveedor; el `PUT` **no** lo exige |
| `TRANSICION_INVALIDA` | 409 | Transición no permitida por la máquina de estados | Refrescar el detalle y deshabilitar la acción |
| `COMPRA_NO_EDITABLE` | 409 | `PUT` sobre una compra que no está `PENDIENTE` | Ocultar «Editar» fuera de `PENDIENTE` |
| `COMPRA_SIN_DETALLE` | 409 | Recepción de una compra sin líneas | Agregar líneas (HU-14) antes de recibir |
| `RECURSO_NO_ENCONTRADO` | 404 | El id no existe; también una ruta inexistente | Volver al listado |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |
| `METODO_NO_PERMITIDO` | 405 | `DELETE` sobre la colección o sobre una compra | No hay borrado |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. En los errores de
deserialización, `field` identifica el campo cuando Jackson puede determinarlo; un literal de
enum inválido llega con el mensaje genérico `"El valor del campo no es válido."`. El
`Content-Type` de las respuestas es `application/json` sin `charset=UTF-8`: si el cliente necesita
el charset explícito, lo aporta en la petición y decodifica como UTF-8.

---

## 4. Formulario y pantalla

### Campos del alta

| Campo | Regla | Mensaje / código |
|---|---|---|
| `idProveedor` | Requerido y existente; debe estar **activo** al crear | "El proveedor es obligatorio" / `PROVEEDOR_INEXISTENTE` / `PROVEEDOR_INACTIVO` |
| `fecha` | Opcional, ISO `yyyy-MM-dd`, **≤ hoy**; omitida = hoy; nunca `null` explícito | `FECHA_FUTURA` / `VALIDACION_FALLIDA` en `fecha` |
| `estado` | Requerido y **siempre `PENDIENTE`** | "El estado es obligatorio" / `VALIDACION_FALLIDA` |

- El selector de proveedor es el catálogo de HU-06 con los **activos** (`GET
  /api/v1/proveedores?activo=true`). Si está vacío, enlazar al alta de proveedor en lugar de
  permitir guardar.
- El `estado` del formulario de alta no es un desplegable: se envía fijo `"PENDIENTE"`. Ofrecer
  «Recibida» o «Cancelada» en el alta solo produce un `400`.
- El `MatDatepicker` de la fecha lleva `max` = hoy, así se evita el `FECHA_FUTURA` antes de enviar.
- La fecha se captura como fecha local del negocio; no se debe convertir a UTC al serializar, o el
  día puede desplazarse.
- Los errores del servidor se muestran junto al campo con `fieldErrors[].field`, no en un toast
  genérico.

### Campos de la edición

Mismos campos que el alta (`idProveedor` y `fecha`), con dos diferencias: la **fecha es
obligatoria** y el proveedor **puede estar inactivo** (la compra ya existe). El estado no se
edita: se muestra como texto, no como campo.

### Botones por estado

| Estado | Acciones disponibles | `editable` |
|---|---|---|
| `PENDIENTE` | Editar · Recibir · Cancelar | `true` |
| `RECIBIDA` | Ninguna (estado terminal) | `false` |
| `CANCELADA` | Ninguna (estado terminal) | `false` |

Usar el campo `editable` de la respuesta como fuente de verdad del botón «Editar», en lugar de
comparar `estado` en el cliente: el backend lo deriva de la misma máquina de estados. «Recibir» y
«Cancelar» se muestran solo con `estado === 'PENDIENTE'`. Ambos botones piden confirmación: la
recepción mueve inventario y la cancelación es irreversible.

### Diálogo de recepción

Al pulsar «Recibir»:

1. Abrir un diálogo de confirmación con el proveedor, la fecha, el total y el número de líneas
   (`cantidadItems`).
2. Si `cantidadItems === 0`, no permitir la acción y explicar que la compra no tiene líneas
   (evita el `409 COMPRA_SIN_DETALLE`); enlazar al detalle de HU-14 cuando exista.
3. Al confirmar, enviar `PATCH /compras/{id}/estado` con `{ "estado": "RECIBIDA" }` y deshabilitar
   el botón mientras la petición está en vuelo.
4. Con el `200`, mostrar el resultado con `movimientosGenerados`: materia prima, cantidad y salto
   de saldo (`saldoAnterior → saldoPosterior`). Es la prueba visible de qué entró al inventario.
5. Refrescar el listado y el detalle: la compra pasa a `RECIBIDA` y `editable` se vuelve `false`.

### Refresco del listado

- Tras un `201`, un `200` de `PUT` o un `200` del `PATCH`, recargar **el listado con los filtros y
  la página actuales**, no reiniciar a la primera página: el usuario pierde la posición.
- El `totalElements` puede cambiar con el alta o con los filtros; no calcularlo en el cliente.
- Si la página actual queda fuera de rango después de un filtro, el backend responde `200` con
  `content: []` (`last: true`): mostrar «Ningún resultado» y no un error.

### Estados de la pantalla

| Estado | Qué se muestra |
|---|---|
| Cargando | Esqueleto de tabla + `MatProgressBar` |
| Vacío sin filtros | "No hay compras registradas" + "Registrar la primera" (solo `ADMIN`/`COMPRAS`) |
| Vacío con filtros | "Ninguna compra coincide con los filtros" + botón para limpiarlos |
| Error de red / 5xx | Mensaje + "Reintentar" conservando los filtros |
| 401 | Redirigir al login |
| 403 | Ocultar la acción y mostrar "No tienes permiso para esta operación" |

Flujo mínimo en Angular: listar al entrar, cargar el catálogo de proveedores activos para el alta
y el filtro, navegar al detalle con `idCompra`, y tras la recepción mostrar los movimientos antes
de cerrar el diálogo. El filtro por defecto del listado es `sort=fecha,desc`: la compra más
reciente arriba.

---

## 5. Qué NO está en el contrato

- **No hay detalle de líneas en esta historia.** `GET /compras/{id}` devuelve solo la cabecera con
  los totales derivados. Agregar, editar y listar líneas es HU-14, con su propia ruta
  (`GET /api/v1/compras/{id}/detalles`). Hasta que exista, una compra recién creada no tiene
  líneas y no se puede recibir.
- **No hay `DELETE`.** `DELETE /compras` y `DELETE /compras/{id}` responden `405`. El ciclo de vida
  se cierra con `CANCELADA`, no borrando.
- **El estado no se edita por `PUT`.** Solo cambia por `PATCH /compras/{id}/estado`, que aplica la
  máquina de transiciones.
- **No hay contadores agregados** (pendientes, recibidas del mes, total comprado): se calculan en
  el cliente con el listado y sus filtros, o con `totalElements`.
- **No hay filtro por texto ni por proveedor inactivo.** El listado filtra por proveedor concreto,
  estado y rango de fechas.
- **No hay endpoint para recibir varias compras a la vez.** La recepción es una por compra.

---

## 6. Limitaciones conocidas

- **Una página fuera de rango no es un error.** `GET /compras?page=99` responde `200` con
  `content: []`, `first: false` y `last: true`; no hay `404`. El frontend debe tratarlo como
  «sin resultados» y no como fallo.
- **El mensaje del `401` es siempre genérico:** «Su sesión ha expirado. Inicie sesión
  nuevamente.», también cuando falta el token o el token está malformado. No se puede distinguir
  el caso por el mensaje; cualquier `401` se trata igual: forzar login.
- **Rutas con `;` en el path** (por ejemplo `/compras/2;drop`) responden `401` en lugar de `404` o
  `400`. Es deuda transversal de la API, no de esta historia; no afecta al consumo normal porque
  el id viaja como número.
- **`securitySchemes` está vacío en `/v3/api-docs`**: Swagger no muestra el candado de
  autenticación. El token se sigue enviando igual con `Authorization: Bearer`.
- **Recibir una compra de un proveedor ya desactivado es válido** y responde `200`: la mercancía
  ya fue pedida. No es un error que el frontend deba bloquear.
- **Los movimientos generados quedan con `id_usuario` nulo** en el kardex: se muestran como
  «Sistema». No afecta a la respuesta de la recepción.
- **Los datos de ejemplo cambian con la base.** Los totales y saldos son de contrato y de la
  ejecución de QA; confírmalos al integrar.
- **Backend verificado en caliente el 2026-10-26** (CP-01…CP-22 y SEC-01…05 en los 5 endpoints).
  Cualquier cambio de contrato actualiza este manual en el mismo commit.
