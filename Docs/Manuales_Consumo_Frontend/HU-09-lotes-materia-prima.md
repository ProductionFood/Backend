# HU-09 — Lotes de materia prima · Manual de consumo para el frontend

Contrato práctico del registro y la consulta de lotes de materia prima. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-09-lotes-materia-prima.md`](../Hechos/HU-09-lotes-materia-prima.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085` mientras el 8080 esté
  ocupado por pgadmin).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura `ADMIN`, `PRODUCCION`, `COMPRAS`, `CONSULTA`; escritura `ADMIN`,
  `PRODUCCION`, `COMPRAS`. `VENTAS` **no** participa en este módulo.

> **Estado:** backend verificado en caliente contra MySQL el 2026-10-23 (20 CP + 15 SEC). El
> consumo desde Angular sigue pendiente. Los valores de ejemplo de saldos y de `diasParaVencer`
> dependen de la base y de la fecha de ejecución.

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

El frontend debe guardar el token y enviarlo en cada llamada. Un `401 NO_AUTENTICADO`
significa token ausente, malformado o expirado: forzar el login de nuevo.

La matriz de roles de este módulo:

| Endpoint | ADMIN | PRODUCCION | COMPRAS | CONSULTA | VENTAS |
|---|---|---|---|---|---|
| `GET /lotes-materia-prima` | sí | sí | sí | sí | 403 |
| `GET /lotes-materia-prima/{id}` | sí | sí | sí | sí | 403 |
| `POST /lotes-materia-prima` | sí | sí | sí | 403 | 403 |

---

## 2. Endpoints

| Método | Ruta | Rol mínimo | Códigos |
|---|---|---|---|
| GET | `/lotes-materia-prima` | CONSULTA / COMPRAS / PRODUCCION / ADMIN | 200, 400, 401, 403 |
| GET | `/lotes-materia-prima/{id}` | CONSULTA / COMPRAS / PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |
| POST | `/lotes-materia-prima` | COMPRAS / PRODUCCION / ADMIN | 201, 400, 401, 403, 404, 409 |

No hay `DELETE`, `PUT` ni `PATCH`: un lote no se edita ni se borra. La cantidad del lote es la
cantidad **con la que ingresó** y no cambia después.

### 2.1. Listar lotes

```http
GET /api/v1/lotes-materia-prima?idMateriaPrima=5&soloVencidos=false&proximosAVencer=7&page=0&size=20&sort=fechaVencimiento,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `idMateriaPrima` | entero | — | Filtra por una materia prima concreta |
| `soloVencidos` | booleano | — | Solo filtra cuando vale `true`. **`false` equivale a no filtrar**, igual que omitirlo |
| `proximosAVencer` | entero | — | Días de la ventana de vencimiento próximo. Debe ser **≥ 1**; `0` o negativo → `400 PARAMETRO_INVALIDO` |
| `page` | entero ≥ 0 | `0` | |
| `size` | entero 1…100 | `20` | |
| `sort` | `campo,dirección` | `fechaVencimiento,asc` | Campos válidos: `codigoLote`, `fechaProduccion`, `fechaVencimiento`, `cantidad`, `id`. `id` se traduce a `idLote`. Dirección `asc`/`desc` |

`proximosAVencer` trae los lotes cuya `fechaVencimiento` cae entre hoy y hoy más esos días,
**ambos extremos incluidos**. Los filtros se pueden combinar.

Respuesta `200 OK`:

```json
{
  "content": [
    {
      "idLote": 14,
      "codigoLote": "H-2026-0912",
      "materiaPrima": { "idMateriaPrima": 5, "nombre": "Harina de trigo", "unidad": "kg" },
      "fechaProduccion": "2026-09-10",
      "fechaVencimiento": "2027-03-10",
      "cantidad": 100.00,
      "diasParaVencer": 138,
      "vencido": false
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

- `materiaPrima.unidad` es la **abreviatura** de la unidad (`kg`, `g`, `L`…), no su nombre.
- `diasParaVencer` y `vencido` son de **solo lectura**.
- `inventarioActualizado` **no aparece** en el listado: una lectura no mueve stock.

### 2.2. Obtener un lote

```http
GET /api/v1/lotes-materia-prima/14
```

Devuelve el mismo objeto que un elemento de `content`, también **sin**
`inventarioActualizado`. Responde `404 RECURSO_NO_ENCONTRADO` si el id no existe **o si
corresponde a un lote de producto terminado** (HU-19), que no es de esta historia.

### 2.3. Registrar un lote

Hay dos formas de alta y la diferencia es crítica para el inventario.

**a) Alta manual (sin `idDetalleCompra`): sí suma al inventario.**

```http
POST /api/v1/lotes-materia-prima
Content-Type: application/json

{ "codigoLote": "H-2026-0912",
  "idMateriaPrima": 5,
  "fechaProduccion": "2026-09-10",
  "fechaVencimiento": "2027-03-10",
  "cantidad": 100.00 }
```

`201 Created` + `Location: /api/v1/lotes-materia-prima/14`:

```json
{
  "idLote": 14,
  "codigoLote": "H-2026-0912",
  "materiaPrima": { "idMateriaPrima": 5, "nombre": "Harina de trigo", "unidad": "kg" },
  "fechaProduccion": "2026-09-10",
  "fechaVencimiento": "2027-03-10",
  "cantidad": 100.00,
  "diasParaVencer": 138,
  "vencido": false,
  "inventarioActualizado": { "anterior": 12.50, "posterior": 112.50 }
}
```

**b) Lote nacido de una compra recibida (con `idDetalleCompra`): NO suma.**

```http
POST /api/v1/lotes-materia-prima
Content-Type: application/json

{ "codigoLote": "H-2026-0913",
  "idMateriaPrima": 5,
  "idDetalleCompra": 42,
  "fechaProduccion": "2026-09-10",
  "fechaVencimiento": "2027-03-10",
  "cantidad": 100.00 }
```

`201 Created` con el mismo objeto **sin la clave `inventarioActualizado`**. El stock ya entró
con la recepción de la compra (HU-13); volver a sumarlo marcaría el doble de existencias sin
ningún error visible (R-02).

**Reglas del alta:**

- **`inventarioActualizado` solo aparece en el POST manual, sin `idDetalleCompra`.** Con
  `idDetalleCompra` la clave se omite porque el inventario no se toca.
- **`idDetalleCompra` debe referenciar una fila real de `detalle_compra`.** Si el id no existe,
  la FK responde `409 CONFLICTO_INTEGRIDAD`. Es el comportamiento real, no el del contrato
  ideal: el frontend no debe interpretarlo como código duplicado.
- **`diasParaVencer` se calcula contra la fecha de hoy y puede ser negativo.** En un lote ya
  vencido es negativo y `vencido` vale `true`; el registro **no se bloquea** y el lote **sí
  suma** (R-05). `vencido` es informativo.
- **El código se recorta y se guarda en mayúsculas.** Un duplicado con distinta capitalización
  (`h-2026-0912` existiendo `H-2026-0912`) devuelve `409 CODIGO_LOTE_DUPLICADO` (R-06).
- **El vencimiento puede ser igual a la producción**, pero nunca anterior (`400
  FECHAS_INVALIDAS`).
- **`cantidad` debe ser mayor que cero y admite máximo 2 decimales.** Cero o negativo devuelve
  `400 CANTIDAD_INVALIDA`.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-23T11:21:25.038", "status": 409,
  "error": "Conflict", "code": "CODIGO_LOTE_DUPLICADO",
  "message": "Ya existe un lote con el código H-2026-0912.",
  "path": "/api/v1/lotes-materia-prima", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Falta un campo, el código supera 30 caracteres, una fecha no tiene formato ISO o la cantidad trae más de 2 decimales | Pintar `fieldErrors[]` junto a cada campo |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100, `proximosAVencer < 1` o id no numérico | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo fuera de `{codigoLote, fechaProduccion, fechaVencimiento, cantidad, id}` | Usar solo la lista blanca |
| `FECHAS_INVALIDAS` | 400 | `fechaVencimiento` anterior a `fechaProduccion` | Corregir las fechas (iguales sí se permiten) |
| `CANTIDAD_INVALIDA` | 400 | `cantidad` menor o igual a cero | Exigir un número mayor que cero |
| `CODIGO_LOTE_DUPLICADO` | 409 | Ya existe un lote con ese código, sin distinguir mayúsculas | Pedir otro código |
| `MATERIA_PRIMA_INEXISTENTE` | 409 | `idMateriaPrima` no existe | Recargar el catálogo de materias primas (HU-08) |
| `CONFLICTO_INTEGRIDAD` | 409 | `idDetalleCompra` no existe en `detalle_compra` u otra restricción de la base | Revisar el detalle de compra; **no** es un duplicado de código |
| `RECURSO_NO_ENCONTRADO` | 404 | El id del lote no existe o es de producto terminado | Volver al listado |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (p. ej. `VENTAS` o `CONSULTA` en el POST) | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |
| `METODO_NO_PERMITIDO` | 405 | `DELETE`, `PUT` o `PATCH` sobre un lote | No hay edición ni borrado |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto.

---

## 4. Casos límite que el frontend debe validar antes de llamar

| Campo | Regla | Mensaje de servidor |
|---|---|---|
| `codigoLote` | requerido, 1…30; se recorta y se guarda en mayúsculas | "El código del lote es obligatorio" / "El código del lote no puede superar 30 caracteres" |
| `idMateriaPrima` | requerido y existente; puede estar desactivada | "La materia prima es obligatoria" / `MATERIA_PRIMA_INEXISTENTE` |
| `fechaProduccion` | requerida, ISO `yyyy-MM-dd` | "La fecha de producción es obligatoria" |
| `fechaVencimiento` | requerida, ISO `yyyy-MM-dd`, **≥** producción (igual permitido) | "La fecha de vencimiento es obligatoria" / `FECHAS_INVALIDAS` |
| `cantidad` | requerida, > 0, máx. 8 enteros y 2 decimales | "La cantidad es obligatoria" / `CANTIDAD_INVALIDA` |
| `idDetalleCompra` | opcional; si se envía, debe existir | `CONFLICTO_INTEGRIDAD` |
| `proximosAVencer` | entero ≥ 1 | `PARAMETRO_INVALIDO` |
| `soloVencidos` | `true` filtra; `false` equivale a no filtrar | — |
| `sort` | solo `codigoLote`, `fechaProduccion`, `fechaVencimiento`, `cantidad`, `id` | `CAMPO_ORDEN_INVALIDO` |

- El contador del código debe mostrarse sobre 30.
- El selector de materia prima es el catálogo de HU-08. Si está vacío, enlazar a HU-08 en lugar
  de permitir guardar.
- La cantidad se captura como texto y se envía como número. `0` **no** es válido.
- **El formulario de alta manual no debe enviar `idDetalleCompra`.** Ese campo solo lo usa la
  recepción de compras (HU-13); si se envía por error con un id inexistente, el alta falla con
  `409 CONFLICTO_INTEGRIDAD` y el inventario no se mueve.

---

## 5. Limitaciones conocidas

- **No hay edición ni borrado.** Un lote registrado no se corrige por API; si hay un error, se
  registra otro lote o se ajusta el inventario por el mecanismo de HU-10.
- **No hay trazabilidad por lote en el consumo.** `lotes.cantidad` es la cantidad de ingreso y
  no disminuye; al producir (HU-22) se descuenta del total de `inventario`, no de un lote
  concreto. La trazabilidad completa exigiría saldo por lote y consumo FEFO.
- **`idDetalleCompra` no se puede crear desde el frontend todavía.** La fila de
  `detalle_compra` la creará HU-13 (recepción de compras). Mientras tanto, un valor inventado
  responde `409 CONFLICTO_INTEGRIDAD`.
- **`diasParaVencer` usa la fecha del servidor.** El frontend puede mostrarlo tal cual o
  recalcularlo, pero no debe asumir la zona horaria del navegador.
- **Backend verificado en caliente contra MySQL el 2026-10-23** (CP-01…CP-20 y SEC-01…05).
  Los valores de saldos y días de los ejemplos son de contrato y cambian con la base y la fecha
  de ejecución; confírmalos al integrar.
- Si una materia prima se insertó por fuera de HU-08 y no tiene fila de `inventario`, el alta
  manual responde `404 RECURSO_NO_ENCONTRADO`. El alta normal de HU-08 siempre crea la fila en
  `0`, así que no debería ocurrir en el flujo de la aplicación.
