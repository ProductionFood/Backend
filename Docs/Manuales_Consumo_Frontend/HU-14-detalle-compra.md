# HU-14 — Detalle de compra · Manual de consumo para el frontend

Contrato práctico para listar, agregar, editar y borrar las líneas de una compra, y para
interpretar el comparativo de costo. Complementa `../../../PFDocs/00-base/04-CONTRATO-API.md`,
el manual de la cabecera en [`HU-13-registro-compras.md`](HU-13-registro-compras.md) y la ficha
[`../Hechos/HU-14-detalle-compra.md`](../Hechos/HU-14-detalle-compra.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura `ADMIN`, `COMPRAS`, `PRODUCCION` y `CONSULTA`; escritura (alta, edición y
  borrado) solo `ADMIN` y `COMPRAS`. `VENTAS` **no** participa en el subrecurso y recibe `403`
  en los cuatro endpoints, **también en el `GET`**.

> **Estado:** backend verificado en caliente contra MySQL el 2026-10-28 (22 CP + 20 SEC en los
> 4 endpoints). El consumo desde Angular sigue pendiente. Los ids, los nombres y los totales
> dependen de la base.

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
| `GET /compras/{id}/detalles` | sí | sí | sí | 403 | sí |
| `POST /compras/{id}/detalles` | sí | sí | 403 | 403 | 403 |
| `PUT /compras/{id}/detalles/{id}` | sí | sí | 403 | 403 | 403 |
| `DELETE /compras/{id}/detalles/{id}` | sí | sí | 403 | 403 | 403 |

`PRODUCCION` y `CONSULTA` solo leen: la pantalla oculta «Agregar», «Editar» y «Quitar» a esos
roles y todo el subrecurso a `VENTAS`. El backend aplica la autorización real, así que ocultar
el botón no sustituye al `403`.

---

## 2. Endpoints

| Método | Ruta | Rol mínimo | Códigos |
|---|---|---|---|
| GET | `/compras/{idCompra}/detalles` | CONSULTA / PRODUCCION / COMPRAS / ADMIN | 200, 401, 403, 404 |
| POST | `/compras/{idCompra}/detalles` | COMPRAS / ADMIN | 201, 400, 401, 403, 404, 409 |
| PUT | `/compras/{idCompra}/detalles/{idDetalle}` | COMPRAS / ADMIN | 200, 400, 401, 403, 404, 409 |
| DELETE | `/compras/{idCompra}/detalles/{idDetalle}` | COMPRAS / ADMIN | 204, 400, 401, 403, 404, 409 |

### 2.1. Listar el detalle de una compra

```http
GET /api/v1/compras/12/detalles
```

```json
// 200 OK
{ "idCompra": 12, "estado": "PENDIENTE", "editable": true,
  "detalles": [
    { "idDetalleCompra": 28,
      "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo", "unidad": "kg" },
      "cantidad": 100.00, "precioUnitario": 3200.00, "subtotal": 320000.00,
      "costoReferencia": 3200.00, "diferenciaCosto": 0.00 },
    { "idDetalleCompra": 29,
      "materiaPrima": { "idMateriaPrima": 2, "nombre": "Azúcar", "unidad": "kg" },
      "cantidad": 50.00, "precioUnitario": 3200.00, "subtotal": 160000.00,
      "costoReferencia": 3000.00, "diferenciaCosto": 200.00 }
  ],
  "totalCompra": 480000.00, "cantidadItems": 2 }
```

- **La compra existe pero no tiene líneas:** `"detalles": []`, `"totalCompra": 0.00` y
  `"cantidadItems": 0`. Es el estado válido de una compra recién creada (HU-13), no un error.
- **`editable` es la fuente de verdad** de los controles de la pantalla: es `true` solo mientras
  la compra está `PENDIENTE`. Una compra `RECIBIDA` o `CANCELADA` responde `200` con
  `editable: false` y sus líneas en modo lectura (el detalle histórico sí se puede consultar).
- **La compra no existe:** `404 COMPRA_INEXISTENTE` (el `code` distingue que el que falta es el
  documento padre, no una ruta genérica).
- `materiaPrima.unidad` es la **abreviatura** (`"kg"`, `"L"`), no el nombre completo.
- En el listado cada línea va **sin** `totalCompra`: el total agregado viaja una sola vez en la
  cabecera. El campo `null` ni siquiera se serializa.
- **El total siempre lo manda el backend.** No sumes los subtotales en el cliente: la cifra
  oficial es `totalCompra`, recalculada con `SUM(cantidad × precio_unitario)` y redondeada a
  escala 2 con `HALF_UP` (R-03). Puedes mostrar un total en vivo mientras se escribe, pero el
  guardado es el del servidor.

### 2.2. Agregar un ítem

```http
POST /api/v1/compras/12/detalles
Content-Type: application/json

{ "idMateriaPrima": 1, "cantidad": 100.00, "precioUnitario": 3200.00 }
```

`201 Created` + `Location: /api/v1/compras/12/detalles/28`:

```json
{ "idDetalleCompra": 28,
  "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo", "unidad": "kg" },
  "cantidad": 100.00, "precioUnitario": 3200.00, "subtotal": 320000.00,
  "totalCompra": 320000.00,
  "costoReferencia": 3200.00, "diferenciaCosto": 0.00 }
```

**Reglas del alta:**

- **`idMateriaPrima`, `cantidad` y `precioUnitario` son obligatorios.** Un cuerpo ausente o con
  `null` responde `400 VALIDACION_FALLIDA` con `fieldErrors[]`.
- **La materia prima debe existir y estar activa** (`409 MATERIA_PRIMA_INEXISTENTE`): el
  selector debe venir del catálogo de materias primas activas (HU-08).
- **Una materia prima no puede repetirse en la misma compra** (`409 MATERIA_PRIMA_DUPLICADA`):
  lo correcto es **editar la línea existente** (sección 2.3), no crear otra. El `UNIQUE` de la
  base lo respalda, así que tampoco se «cuele» por doble clic o por dos sesiones a la vez.
- **La cantidad debe superar cero** (`400 CANTIDAD_INVALIDA`). **El precio puede ser cero**
  (`201`, subtotal `0.00`): hay muestras y bonificaciones. Un precio negativo es
  `400 VALIDACION_FALLIDA` en el campo `precioUnitario`.
- **La compra debe estar `PENDIENTE`** (`409 COMPRA_NO_EDITABLE`); si no existe,
  `404 COMPRA_INEXISTENTE`.
- **El `Content-Type` debe ser `application/json`**; otro valor responde
  `415 TIPO_CONTENIDO_NO_SOPORTADO` (no un `500`).
- La respuesta trae el `subtotal` de la línea, el `totalCompra` ya recalculado y el comparativo
  de costo: úsalos para refrescar la tabla sin una llamada extra al `GET`.

### 2.3. Editar la cantidad y el precio de un ítem

```http
PUT /api/v1/compras/12/detalles/28
Content-Type: application/json

{ "cantidad": 150.00, "precioUnitario": 3200.00 }
```

```json
// 200 OK — misma forma que el POST, con subtotal y total ya recalculados
{ "idDetalleCompra": 28,
  "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo", "unidad": "kg" },
  "cantidad": 150.00, "precioUnitario": 3200.00, "subtotal": 480000.00,
  "totalCompra": 480000.00,
  "costoReferencia": 3200.00, "diferenciaCosto": 0.00 }
```

- **Solo se editan cantidad y precio.** El `PUT` **no** lleva materia prima: la de la línea no
  se reemplaza, porque una materia prima repetida en la compra está prohibida (R-02). Para
  «cambiar de materia prima» se elimina la línea y se agrega otra.
- Si el cuerpo trae `idMateriaPrima` se **ignora**: la línea conserva su materia prima original.
- Mismas reglas de forma que el alta: cantidad positiva (`CANTIDAD_INVALIDA` si no) y precio no
  negativo (`VALIDACION_FALLIDA` si no).
- La compra debe estar `PENDIENTE` (`409 COMPRA_NO_EDITABLE`) y la línea debe pertenecer a esa
  compra; una línea de otra compra responde `404 RECURSO_NO_ENCONTRADO`.
- La respuesta trae el `totalCompra` de la compra completa, ya con el cambio aplicado.

### 2.4. Quitar un ítem

```http
DELETE /api/v1/compras/12/detalles/28
```

`204 No Content`, **sin cuerpo**. Después recarga el listado: el `totalCompra` y
`cantidadItems` de la cabecera bajan solos.

- La compra debe estar `PENDIENTE` (`409 COMPRA_NO_EDITABLE`): una compra `RECIBIDA` no admite
  perder líneas, porque su total debe corresponder al stock que entró.
- La línea debe pertenecer a la compra (`404 RECURSO_NO_ENCONTRADO`).
- El borrado es físico: no queda marca de «eliminado». Pide confirmación antes de enviar.

### 2.5. Flujo recomendado: agregar → refrescar

1. `POST /compras/{id}/detalles` con la línea nueva.
2. Con el `201`, inserta la línea devuelta en la tabla y toma `totalCompra` de la respuesta.
3. Recarga con `GET /compras/{id}/detalles` si prefieres una fuente única: el total y la lista
   salen siempre del servidor, nunca de un acumulado en memoria.
4. Guarda el `idDetalleCompra` del `201` (o de la fila del `GET`): es la clave del `PUT` y del
   `DELETE`.

---

## 3. El comparativo de costo (R-04)

Cada línea expone dos campos de solo lectura:

| Campo | Qué es |
|---|---|
| `costoReferencia` | `materias_primas.costo_unitario` vigente (HU-08): el valor de referencia con el que se costean las recetas |
| `diferenciaCosto` | `precioUnitario − costoReferencia`, a escala 2 con `HALF_UP` |

- **Positivo:** esta compra salió **más cara** de lo previsto (flecha ↑).
- **Negativo:** salió **más barata** (flecha ↓).
- **Cero:** al precio de referencia (=).
- **Ambos campos pueden no venir** (materia prima sin costo definido): no se serializan y la
  fila debe pintar el comparativo como «sin referencia», no como cero.

**Registrar o editar una compra nunca actualiza el costo de referencia.** El precio pagado es
un hecho puntual de esta compra; sobrescribir `materias_primas.costo_unitario` con él arruinaría
el costeo de recetas con un precio que puede ser atípico por volumen o negociación (verificado:
comprar a precio distinto del costo no cambia la materia prima). La UI pinta ambos valores para
que quien compra juzgue la diferencia; la corrección del costo de referencia, si hace falta, es
otra operación (HU-08).

---

## 4. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-28T10:15:22.401", "status": 409, "error": "Conflict",
  "code": "MATERIA_PRIMA_DUPLICADA",
  "message": "La materia prima indicada ya está en la compra; edite la línea existente.",
  "path": "/api/v1/compras/12/detalles", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Falta un campo obligatorio o el precio es negativo | Pintar `fieldErrors[]` junto a cada campo |
| `CANTIDAD_INVALIDA` | 400 | `cantidad` menor o igual a cero (la rechaza el servicio, no la forma) | Mostrar el mensaje **junto al campo de cantidad**, no en un toast |
| `COMPRA_INEXISTENTE` | 404 | El `idCompra` de la ruta no existe | Volver al listado de compras |
| `RECURSO_NO_ENCONTRADO` | 404 | El `idDetalle` no existe o pertenece a otra compra | Refrescar el listado: la línea ya no está |
| `COMPRA_NO_EDITABLE` | 409 | La compra no está `PENDIENTE` (agregar, editar o borrar) | **Ocultar los controles de edición** y recargar el detalle: otra persona recibió o canceló la compra |
| `MATERIA_PRIMA_INEXISTENTE` | 409 | La materia prima no existe o está inactiva | Recargar el catálogo de materias primas activas |
| `MATERIA_PRIMA_DUPLICADA` | 409 | La materia prima ya es un ítem de la compra | **Sugerir editar la línea existente** (abrir su `PUT`); no reintentar el `POST` |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (p. ej. `VENTAS` o `CONSULTA` escribiendo) | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |
| `TIPO_CONTENIDO_NO_SOPORTADO` | 415 | `Content-Type` distinto de `application/json` | Corregir la cabecera de la petición |
| `PARAMETRO_INVALIDO` | 400 | Id de ruta no numérico (`/compras/abc/detalles`) | Corregir la navegación |

`fieldErrors` es un **array** de `{ field, message }`. El `Content-Type` de las respuestas es
`application/json` sin `charset=UTF-8`.

---

## 5. Formulario y pantalla

### Campos del formulario de línea

| Campo | Regla | Mensaje / código |
|---|---|---|
| `idMateriaPrima` | Requerido, existente y **activo**; no repetido en la compra | `MATERIA_PRIMA_INEXISTENTE` / `MATERIA_PRIMA_DUPLICADA` |
| `cantidad` | Requerida, **> 0**, escala 2 (máximo dos decimales) | `CANTIDAD_INVALIDA` |
| `precioUnitario` | Requerido, **≥ 0**, escala 2; puede ser `0` | `VALIDACION_FALLIDA` si es negativo |

- El selector de materia prima es el catálogo de HU-10/HU-08 filtrado por **activas**; si está
  vacío, enlazar al alta de materia prima.
- El formulario de edición **no** incluye materia prima: se muestra como texto de solo lectura.
  Para cambiarla: quitar la línea y agregar otra.
- Decimales con coma o más de dos dígitos decimales se manejan en el cliente: el contrato usa
  punto decimal y escala 2.

### Controles por estado de la compra

| Estado | `editable` | Acciones |
|---|---|---|
| `PENDIENTE` | `true` | Agregar · Editar · Quitar |
| `RECIBIDA` | `false` | Ninguna (solo consulta) |
| `CANCELADA` | `false` | Ninguna (solo consulta) |

Usa el campo `editable` del `GET` como fuente de verdad, en lugar de comparar `estado` en el
cliente. Si un `409 COMPRA_NO_EDITABLE` llega igual (carrera con otra sesión), recarga el
detalle y oculta los controles.

### Refresco del listado

- Tras un `201`, un `200` de `PUT` o un `204` de `DELETE`, recarga el detalle: el total y la
  lista salen del servidor.
- El botón de envío se deshabilita mientras la petición está en vuelo (evita duplicados por
  doble clic; el backend responde `409 MATERIA_PRIMA_DUPLICADA` si la carrera llega al `UNIQUE`).
- `401` → login; `403` → ocultar la acción y avisar; `404` de la compra → volver al listado.

---

## 6. Qué NO está en el contrato

- **No se edita la materia prima de una línea.** Para cambiarla, `DELETE` + `POST`.
- **No hay paginación ni filtros en el listado de detalles:** una compra se carga entera. El
  total de líneas lo da `cantidadItems`.
- **No hay `DELETE` de la compra ni de sus líneas fuera de `PENDIENTE`.** El ciclo de vida de la
  cabecera es HU-13 (recibir o cancelar).
- **El total no se envía nunca al backend.** Los clientes no calculan ni envían `totalCompra`
  ni `subtotal`: son respuestas, no entradas.
- **No hay endpoint de «actualizar costo de referencia».** Es fuera de alcance (R-04); el
  costo se lee, no se escribe, desde HU-14.

---

## 7. Limitaciones conocidas

- **El mensaje del `401` es genérico** («Su sesión ha expirada…») también sin token o con token
  malformado: es deuda transversal de la API (mismo alcance que HU-13 D7). Cualquier `401` se
  trata igual: forzar login.
- **Un `Accept` no soportado con token válido puede responder `401` engañoso** en lugar de un
  `406`: deuda transversal abierta (D2). No envíes `Accept` exóticos.
- **Un decimal fuera de rango (más allá de `decimal(10,2)`) responde `409 CONFLICTO_INTEGRIDAD`**
  en lugar de un `400` (D5). Valida la escala 2 en el formulario para no llegar ahí.
- **Dos `DELETE` concurrentes de la misma línea pueden responder `204` y `204`** (el secuencial
  responde `204` y después `404`). No corrompe nada; la segunda petición simplemente no
  encuentre la fila.
- **Jackson admite variantes laxas** (cantidades como texto, notación científica): el contrato
  formal es JSON numérico con punto decimal; el frontend debe enviar eso.
- **Los datos de ejemplo cambian con la base.** Nombres, unidades, `costoReferencia` y totales
  son de contrato y de la ejecución de QA; confírmalos al integrar.
- **Backend verificado en caliente el 2026-10-28** (CP-01…CP-22 y SEC-01…05 en los 4
  endpoints). Cualquier cambio de contrato actualiza este manual en el mismo commit.
