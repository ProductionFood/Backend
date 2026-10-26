# HU-19 — Lotes de producto terminado · Manual de consumo para el frontend

Contrato práctico del registro, la consulta y el kardex de los lotes de producto terminado.
Complementa `../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-19-lotes-producto-terminado.md`](../Hechos/HU-19-lotes-producto-terminado.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085` mientras el 8080 esté
  ocupado por pgadmin).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura `ADMIN`, `VENTAS`, `PRODUCCION`, `CONSULTA`; alta `ADMIN`, `PRODUCCION`;
  kardex `ADMIN`, `PRODUCCION`, `VENTAS`. `COMPRAS` **no** participa en este módulo.

> **Estado:** backend verificado en caliente contra MySQL el 2026-10-25 (24 CP + 20 SEC). El
> consumo desde Angular sigue pendiente. Los saldos y los días dependen de la base y de la
> fecha de ejecución.

---

## 1. Autenticación

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "correo": "admin@productionfood.local", "password": "Admin123*" }
```

```json
{ "token": "eyJhbGciOiJIUzM4NCJ9...",
  "usuario": { "idUsuario": 1, "nombre": "Administrador Inicial", "rol": "ADMIN" } }
```

El frontend guarda el token y lo envía en cada llamada. Un `401 NO_AUTENTICADO` significa token
ausente, malformado o expirado: forzar el login de nuevo.

| Endpoint | ADMIN | PRODUCCION | VENTAS | CONSULTA | COMPRAS |
|---|---|---|---|---|---|
| `GET /lotes-producto` | sí | sí | sí | sí | 403 |
| `GET /lotes-producto/{id}` | sí | sí | sí | sí | 403 |
| `POST /lotes-producto` | sí | sí | 403 | 403 | 403 |
| `GET /lotes-producto/{id}/movimientos` | sí | sí | sí | 403 | 403 |

`CONSULTA` ve listado y detalle, pero no el kardex. La pantalla oculta el alta a `VENTAS`,
`CONSULTA` y `COMPRAS`, y la pestaña de kardex a `CONSULTA` y `COMPRAS`; el backend aplica la
autorización real, así que ocultar el botón no sustituye al `403`.

---

## 2. Endpoints

| Método | Ruta | Rol mínimo | Códigos |
|---|---|---|---|
| GET | `/lotes-producto` | CONSULTA / VENTAS / PRODUCCION / ADMIN | 200, 400, 401, 403 |
| GET | `/lotes-producto/{id}` | CONSULTA / VENTAS / PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |
| POST | `/lotes-producto` | PRODUCCION / ADMIN | 201, 400, 401, 403, 404, 409 |
| GET | `/lotes-producto/{id}/movimientos` | VENTAS / PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |

No hay `PUT`, `PATCH` ni `DELETE`: responden `405 METODO_NO_PERMITIDO`. Un lote no se edita ni se
borra; si hay un error de captura, se registra otro lote.

### 2.1. Listar lotes

```http
GET /api/v1/lotes-producto?idProducto=3&soloVencidos=false&proximosAVencer=7&conStock=true&page=0&size=20&sort=fechaVencimiento,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `idProducto` | entero | — | Filtra por un producto concreto |
| `soloVencidos` | booleano | — | Solo filtra con `true`. **`false` equivale a no filtrar**, igual que omitirlo |
| `proximosAVencer` | entero | — | Días de la ventana. Debe ser **≥ 1**; `0` o negativo → `400 PARAMETRO_INVALIDO` |
| `conStock` | booleano | — | Solo filtra con `true` (saldo del lote > 0). **`false` equivale a no filtrar** |
| `page` | entero ≥ 0 | `0` | Base 0: la primera página es `0` |
| `size` | entero 1…100 | `20` | |
| `sort` | `campo,dirección` | `fechaVencimiento,asc` | Campos válidos: `codigoLote`, `fechaProduccion`, `fechaVencimiento`, `id`. `id` se traduce a `idLote`. Dirección `asc`/`desc` |

`proximosAVencer` trae los lotes cuya `fechaVencimiento` cae entre hoy y hoy más esos días,
**ambos extremos incluidos**. Los filtros se combinan. El listado nunca mezcla lotes de materia
prima: esta ruta solo devuelve filas con producto.

```json
// 200 OK
{ "content": [
    { "idLote": 30, "codigoLote": "PF-2026-1018",
      "producto": { "idProducto": 3, "nombre": "Croissant", "unidad": "kg" },
      "fechaProduccion": "2026-10-20", "fechaVencimiento": "2026-11-01",
      "cantidadIngresada": 25.00, "cantidadDisponible": 15.00,
      "diasParaVencer": 7, "vencido": false, "stockTotalProducto": 90.50 }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true }
```

- `producto.unidad` es la **abreviatura** de la unidad (`und`, `kg`, `L`…), no su nombre.
- `cantidadIngresada` es lo que entró al lote y **no cambia nunca**; `cantidadDisponible` es lo
  que queda. La diferencia es lo vendido.
- `stockTotalProducto` suma los lotes **vigentes** del producto y excluye los vencidos; no es la
  suma de la columna `cantidadDisponible` de la página.
- `diasParaVencer` y `vencido` son de **solo lectura** y se calculan contra la fecha del
  servidor. `diasParaVencer = 0` significa que vence hoy. Una lectura no mueve stock.

### 2.2. Obtener un lote

```http
GET /api/v1/lotes-producto/30
```

Devuelve el mismo objeto que un elemento de `content`. Responde `404 RECURSO_NO_ENCONTRADO` si el
id no existe **o si corresponde a un lote de materia prima** (HU-09), que no es de esta historia.

### 2.3. Registrar un lote

```http
POST /api/v1/lotes-producto
Content-Type: application/json

{ "codigoLote": "PF-2026-1018",
  "idProducto": 3,
  "fechaProduccion": "2026-10-20",
  "fechaVencimiento": "2026-11-01",
  "cantidad": 25.00 }
```

`201 Created` + `Location: /api/v1/lotes-producto/30`:

```json
{ "idLote": 30, "codigoLote": "PF-2026-1018",
  "producto": { "idProducto": 3, "nombre": "Croissant", "unidad": "kg" },
  "fechaProduccion": "2026-10-20", "fechaVencimiento": "2026-11-01",
  "cantidadIngresada": 25.00, "cantidadDisponible": 25.00,
  "diasParaVencer": 7, "vencido": false, "stockTotalProducto": 90.50 }
```

El alta hace tres escrituras en una sola transacción: el lote, su saldo inicial en
`inventario_producto_terminado` y el movimiento `ENTRADA_PRODUCCION` del kardex. Las tres o
ninguna. Ese saldo es lo que convierte el lote en **stock vendible**: sin él, HU-17 no puede
vender el producto.

**Reglas del alta:**

- **El código se recorta y se guarda en mayúsculas.** Es único en toda la tabla `lotes`,
  compartida con los lotes de materia prima de HU-09: un código que ya usa un lote de materia
  prima devuelve `409 CODIGO_LOTE_DUPLICADO`.
- **El producto debe existir.** Puede estar desactivado; el alta sigue siendo válida.
- **El vencimiento no puede ser anterior a la producción** ni **anterior a hoy**. Vencimiento
  igual a producción sí se permite, y un lote que **vence hoy sí se registra**.
- **La cantidad debe ser mayor que cero** y admite máximo 2 decimales. Cero o negativo devuelve
  `400 CANTIDAD_INVALIDA`, no `VALIDACION_FALLIDA`.
- **Al crear, `cantidadIngresada` y `cantidadDisponible` coinciden.** Después divergen con las
  ventas y devoluciones.
- **`stockTotalProducto` ya incluye el lote recién creado**, porque la respuesta se relee después
  del alta.

### 2.4. Kardex del lote

```http
GET /api/v1/lotes-producto/30/movimientos?page=0&size=20&sort=id,desc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `page` | entero ≥ 0 | `0` | |
| `size` | entero 1…100 | `20` | |
| `sort` | `campo,dirección` | `id,desc` | Campos válidos: `fecha`, `tipo`, `cantidad`, `saldoAnterior`, `saldoPosterior`, `id`. Dirección `asc`/`desc` |

```json
// 200 OK
{ "content": [
    { "fecha": "2026-10-25T20:52:11", "tipo": "ENTRADA_PRODUCCION",
      "cantidad": 25.00, "saldoAnterior": 0.00, "saldoPosterior": 25.00,
      "origen": { "tabla": "lotes", "id": 30 }, "usuario": "Sistema" }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true }
```

- `tipo` es `ENTRADA_PRODUCCION`, `SALIDA_VENTA`, `ENTRADA_DEVOLUCION`, `AJUSTE_POSITIVO`,
  `AJUSTE_NEGATIVO` o `BAJA_VENCIMIENTO`.
- `cantidad` es la cantidad movida, siempre positiva: el signo lo da el tipo.
- `saldoAnterior` y `saldoPosterior` permiten reconstruir cómo se llegó al saldo actual.
- `origen` puede ser `null` si el movimiento no tiene documento; en el alta es
  `{ "tabla": "lotes", "id": <idLote> }`.
- `usuario` es `"Sistema"` cuando no hay autor humano; nunca es cadena vacía.
- Un lote existente sin movimientos devuelve `content: []`, **no** un 404.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-25T20:53:01.123", "status": 409,
  "error": "Conflict", "code": "CODIGO_LOTE_DUPLICADO",
  "message": "Ya existe un lote con el código PF-2026-1018.",
  "path": "/api/v1/lotes-producto", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Falta un campo, el código supera 30 caracteres, una fecha no es ISO o la cantidad trae más de 2 decimales | Pintar `fieldErrors[]` junto a cada campo |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100, `proximosAVencer < 1` o id de ruta no numérico | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo fuera de la lista blanca del listado o del kardex | Usar solo los campos permitidos |
| `FECHAS_INVALIDAS` | 400 | `fechaVencimiento` anterior a `fechaProduccion` | Corregir las fechas (iguales sí se permiten) |
| `LOTE_YA_VENCIDO` | 400 | `fechaVencimiento` anterior a hoy | Bloquear el envío; el datepicker debe impedirlo |
| `CANTIDAD_INVALIDA` | 400 | `cantidad` menor o igual a cero | Exigir un número mayor que cero |
| `CODIGO_LOTE_DUPLICADO` | 409 | Ya existe un lote con ese código, sin distinguir mayúsculas y también entre lotes de materia prima | Pedir otro código |
| `PRODUCTO_INEXISTENTE` | 409 | `idProducto` no existe | Recargar el catálogo de productos (HU-11) |
| `RECURSO_NO_ENCONTRADO` | 404 | El id no existe o es de materia prima; también una ruta inexistente | Volver al listado |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |
| `METODO_NO_PERMITIDO` | 405 | `PUT`, `PATCH` o `DELETE` sobre un lote | No hay edición ni borrado |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. En los errores de
deserialización, `field` identifica el campo cuando Jackson puede determinarlo (`cantidad`,
`fechaProduccion`…); un JSON truncado no permite identificar campo y llega con el array vacío. El
`Content-Type` de las respuestas es `application/json` sin `charset=UTF-8`: si el cliente necesita
el charset explícito, lo aporta en la petición y decodifica como UTF-8.

---

## 4. Formulario y pantalla

| Campo | Regla | Mensaje / código |
|---|---|---|
| `codigoLote` | Requerido, 1…30; se recorta y se guarda en mayúsculas | "El código del lote es obligatorio" / "El código del lote no puede superar 30 caracteres" / `CODIGO_LOTE_DUPLICADO` |
| `idProducto` | Requerido y existente; puede estar desactivado | "El producto es obligatorio" / `PRODUCTO_INEXISTENTE` |
| `fechaProduccion` | Requerida, ISO `yyyy-MM-dd` | "La fecha de producción es obligatoria" |
| `fechaVencimiento` | Requerida, ISO `yyyy-MM-dd`, **≥ producción** y **≥ hoy** | "La fecha de vencimiento es obligatoria" / `FECHAS_INVALIDAS` / `LOTE_YA_VENCIDO` |
| `cantidad` | Requerida, > 0, máximo 2 decimales (8 enteros) | "La cantidad es obligatoria" / `CANTIDAD_INVALIDA` |

- El contador del código se muestra sobre 30.
- El selector de producto es el catálogo de HU-11 (`GET /api/v1/productos?activo=true`). Si está
  vacío, enlazar al alta de producto en lugar de permitir guardar.
- El `MatDatepicker` de vencimiento lleva `min` = máximo(producción, hoy), así se evita el `400`
  antes de enviar.
- La cantidad se captura como texto y se envía como número. `0` **no** es válido.
- Los errores del servidor se muestran junto al campo con `fieldErrors[].field`, no en un toast
  genérico.

### Semáforo de vencimiento

Los umbrales de producto terminado son más estrictos que los de materia prima porque dura menos:

| Estado | Regla | Color |
|---|---|---|
| Vencido | `vencido = true` (`diasParaVencer < 0`) | 🔴 rojo |
| Vence en ≤ 3 días | `0 ≤ diasParaVencer ≤ 3` | 🟠 naranja |
| Vence en ≤ 7 días | `4 ≤ diasParaVencer ≤ 7` | 🟡 amarillo |
| Resto | `diasParaVencer > 7` | 🟢 verde |

El estado más importante es **vencido con stock**: `vencido = true` y `cantidadDisponible > 0`.
Es producto que sigue en el estante y hay que retirarlo; la pantalla debe destacarlo, no
ocultarlo. La tarjeta "vencidos con stock" se calcula en el cliente, porque el backend no expone
un contador agregado.

### Columnas Ingresó y Disponible

La tabla muestra **dos columnas separadas**: **Ingresó** = `cantidadIngresada` (histórico del
lote) y **Disponible** = `cantidadDisponible` (saldo actual). Mostrar solo una hace que alguien
interprete la cantidad de ingreso como stock actual; la diferencia es lo vendido/devolucionado y
se explica en el kardex.

### Estados de la pantalla

| Estado | Qué se muestra |
|---|---|
| Cargando | Esqueleto de tarjetas y tabla + `MatProgressBar` |
| Vacío sin filtros | "No hay lotes registrados" + "Registrar el primero. Sin lotes no se puede vender." |
| Vacío con filtros | "Ningún lote coincide con los filtros" + botón para limpiarlos |
| Error de red / 5xx | Mensaje + "Reintentar" conservando los filtros |
| 401 | Redirigir al login |
| 403 | Ocultar la acción y mostrar "No tienes permiso para esta operación" |
| Vencidos con stock | Aviso destacado: "N lotes vencidos aún tienen unidades en el estante" |

Flujo mínimo en Angular: listar al entrar, cargar el catálogo de productos activos para filtro y
formulario, navegar al detalle/kardex con `idLote`, deshabilitar el botón de alta mientras la
petición está en vuelo y, tras un `201`, refrescar listado y `stockDisponible` del producto. El
kardex usa `sort=id,desc` por defecto; no asumir que la primera fila es siempre una entrada.

---

## 5. Qué NO está en el contrato

- **No existe `GET /productos/{id}/stock`.** Es de HU-17 y todavía no está implementada. Para
  comprobar el stock vendible se usa `GET /api/v1/productos/{id}` y su campo `stockDisponible`,
  que agrega los lotes vigentes de esta historia y excluye los vencidos.
- **No hay edición ni borrado de lotes.** `PUT`, `PATCH` y `DELETE` responden `405`.
- **No hay endpoint para mover el vencimiento ni para retirar un lote vencido.** La baja por
  vencimiento es de una historia posterior; hoy el retiro es físico y no genera movimiento.
- **No hay contadores agregados** (vigentes, próximos a vencer, vencidos con stock): se calculan
  en el cliente con el listado y sus filtros.
- **No hay filtro por código de lote.** El listado filtra por producto y estado de vencimiento.

---

## 6. Limitaciones conocidas

- **HU-17 todavía no existe como API.** Sin ventas reales, el kardex solo mostrará
  `ENTRADA_PRODUCCION`; `SALIDA_VENTA` y `ENTRADA_DEVOLUCION` aparecerán con HU-17/HU-18.
  CP-17 se verificó con una venta simulada por SQL y no es un flujo de la aplicación.
- **Un lote vencido no se puede registrar**, a diferencia de HU-09. Para probar el semáforo rojo
  hay que dejar que un lote expire o manipular la fecha en base; la pantalla debe soportar
  `vencido = true` en lotes ya existentes.
- **El stock total excluye los vencidos.** `stockTotalProducto` puede ser menor que la suma de
  los `cantidadDisponible` visibles si hay lotes vencidos; no es un error.
- **`diasParaVencer` usa la fecha del servidor.** Se puede mostrar tal cual o recalcular para el
  semáforo, pero no se debe asumir la zona horaria del navegador.
- **Los datos de ejemplo cambian con la base.** Los saldos son de contrato y de la ejecución de
  QA; confírmalos al integrar.
- **Backend verificado en caliente el 2026-10-25** (CP-01…CP-24 y SEC-01…05 en los 4
  endpoints). Cualquier cambio de contrato actualiza este manual en el mismo commit.
