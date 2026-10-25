# HU-10 — Consulta de inventario de materia prima · Manual de consumo para el frontend

Contrato práctico de la consulta del inventario de materia prima. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-10-consulta-inventario.md`](../Hechos/HU-10-consulta-inventario.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085` mientras el 8080 esté
  ocupado por pgadmin).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura del listado y del detalle `ADMIN`, `PRODUCCION`, `COMPRAS`, `CONSULTA`; el
  kardex lo pueden ver `ADMIN`, `PRODUCCION` y `COMPRAS`. `VENTAS` **no** participa en este
  módulo.

> **Estado:** backend implementado el 2026-10-23. La verificación en caliente contra MySQL y el
> consumo desde Angular siguen **pendientes**. Los valores de cantidad, saldo y fecha de los
> ejemplos son de contrato y cambian con la base.

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

El frontend debe guardar el token y enviarlo en cada llamada. Un `401 NO_AUTENTICADO` significa
token ausente, malformado o expirado: forzar el login de nuevo.

La matriz de roles de este módulo:

| Endpoint | ADMIN | PRODUCCION | COMPRAS | CONSULTA | VENTAS |
|---|---|---|---|---|---|
| `GET /inventario` | sí | sí | sí | sí | 403 |
| `GET /inventario/{idMateriaPrima}` | sí | sí | sí | sí | 403 |
| `GET /inventario/{idMateriaPrima}/movimientos` | sí | sí | sí | 403 | 403 |

`CONSULTA` es un rol de solo lectura de catálogo: no ve el kardex porque expone quién movió el
stock y con qué documento.

---

## 2. Endpoints

| Método | Ruta | Rol mínimo | Códigos |
|---|---|---|---|
| GET | `/inventario` | CONSULTA / COMPRAS / PRODUCCION / ADMIN | 200, 400, 401, 403 |
| GET | `/inventario/{idMateriaPrima}` | CONSULTA / COMPRAS / PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |
| GET | `/inventario/{idMateriaPrima}/movimientos` | COMPRAS / PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |

No hay `POST`, `PUT`, `PATCH` ni `DELETE`: la historia es de **solo lectura** (R-01). El stock se
mueve registrando lotes (HU-09), recibiendo compras (HU-13) o consumiendo en producción (HU-23).
Cualquier intento de escritura sobre estas rutas responde `405 METODO_NO_PERMITIDO`.

### 2.1. Listar el inventario

```http
GET /api/v1/inventario?idMateriaPrima=5&soloStockBajo=true&incluirInactivas=true&page=0&size=20&sort=nombre,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `idMateriaPrima` | entero | — | Filtra por una materia prima. Si el id no existe devuelve `200` con `content: []` |
| `soloStockBajo` | booleano | `false` | Solo `true` filtra: deja las que están `BAJO` o `AGOTADO`. `false` y omitirlo equivalen a no filtrar |
| `incluirInactivas` | booleano | `true` | **Las inactivas se muestran por defecto** (R-04). Solo `false` las excluye |
| `page` | entero ≥ 0 | `0` | |
| `size` | entero 1…100 | `20` | |
| `sort` | `campo,dirección` | `nombre,asc` | Campos válidos: `nombre`, `cantidadDisponible`, `faltante`, `fechaActualizacion`. Dirección `asc`/`desc` |

Los filtros se pueden combinar. Respuesta `200 OK`:

```json
{
  "content": [
    {
      "idMateriaPrima": 5,
      "nombre": "Harina de trigo",
      "unidad": "kg",
      "cantidadDisponible": 12.50,
      "stockMinimo": 50.00,
      "faltante": 37.50,
      "estadoStock": "BAJO",
      "activo": true,
      "fechaActualizacion": "2026-09-18T09:12:44"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

- `unidad` es la **abreviatura** de la unidad (`kg`, `g`, `L`…), no su nombre.
- `cantidadDisponible`, `stockMinimo` y `faltante` vienen con dos decimales.
- `activo` es `false` en una materia prima desactivada; **sigue apareciendo** porque conserva
  stock (R-04).
- `fechaActualizacion` puede faltar de la respuesta solo si faltara la fila de `inventario`
  (red de seguridad); en el flujo normal siempre viene con valor.
- No se expone `costoUnitario`.

### 2.2. Obtener el inventario de una materia prima

```http
GET /api/v1/inventario/5
```

```json
{
  "idMateriaPrima": 5,
  "nombre": "Harina de trigo",
  "unidad": "kg",
  "cantidadDisponible": 12.50,
  "stockMinimo": 50.00,
  "faltante": 37.50,
  "estadoStock": "BAJO",
  "activo": true,
  "fechaActualizacion": "2026-09-18T09:12:44"
}
```

Es el mismo objeto de un elemento de `content`, sin envoltura de página. Responde
`404 RECURSO_NO_ENCONTRADO` si la materia prima no existe; una materia prima **inactiva sí se
devuelve**, marcada con `activo: false`.

### 2.3. Kardex de una materia prima

```http
GET /api/v1/inventario/5/movimientos?fechaInicio=2026-09-01&fechaFin=2026-09-30&page=0&size=20
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `fechaInicio` | fecha `YYYY-MM-DD` | — | Primer día **incluido** del rango |
| `fechaFin` | fecha `YYYY-MM-DD` | — | Último día **incluido** del rango |
| `page` | entero ≥ 0 | `0` | |
| `size` | entero 1…100 | `20` | |

**No acepta `sort`.** El orden es siempre fijo: del movimiento más reciente al más antiguo
(`id_movimiento DESC`). Un formato de fecha inválido responde `400 PARAMETRO_INVALIDO`; si
`fechaInicio` es posterior a `fechaFin`, `400 RANGO_FECHAS_INVALIDO`.

Respuesta `200 OK`:

```json
{
  "content": [
    {
      "fecha": "2026-09-18T09:12:44",
      "tipo": "SALIDA_PRODUCCION",
      "cantidad": 100.000,
      "saldoAnterior": 112.50,
      "saldoPosterior": 12.50,
      "origen": { "tabla": "consumo_materia_prima", "id": 31 },
      "usuario": "Ismael Batalla"
    },
    {
      "fecha": "2026-09-12T08:05:11",
      "tipo": "ENTRADA_LOTE",
      "cantidad": 100.000,
      "saldoAnterior": 12.50,
      "saldoPosterior": 112.50,
      "origen": { "tabla": "lotes", "id": 14 },
      "usuario": "Carlos Cochero"
    }
  ],
  "page": 0, "size": 20, "totalElements": 2, "totalPages": 1,
  "first": true, "last": true
}
```

- `tipo` sale del catálogo de V2: `ENTRADA_COMPRA`, `ENTRADA_LOTE`, `SALIDA_PRODUCCION`,
  `AJUSTE_POSITIVO`, `AJUSTE_NEGATIVO`, `BAJA_VENCIMIENTO`.
- `cantidad` conserva **tres decimales** (escala de `decimal(10,3)`); los saldos van con dos.
- `origen` es el documento que produjo el movimiento y **la clave se omite** en un ajuste sin
  documento (la inclusión `non_null` de Jackson no serializa el objeto vacío). Cuando existe
  alguno de los dos datos se devuelve el objeto y el campo ausente también se omite.
- `usuario` es `"Sistema"` cuando el movimiento no tiene usuario asociado (por ejemplo, un proceso
  automático). No es un error ni un usuario inexistente.
- `saldoPosterior` del primer elemento (el más reciente) debe coincidir con la
  `cantidadDisponible` del detalle (R-03).
- **Una materia prima sin movimientos devuelve `200` con `content: []` y `totalElements: 0`**,
  no 404. Una materia prima inexistente sí responde 404.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-23T11:21:25.038", "status": 400,
  "error": "Bad Request", "code": "RANGO_FECHAS_INVALIDO",
  "message": "La fecha de inicio no puede ser posterior a la fecha de fin.",
  "path": "/api/v1/inventario/5/movimientos", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100, id no numérico o fecha con formato distinto de `YYYY-MM-DD` | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo fuera de `{nombre, cantidadDisponible, faltante, fechaActualizacion}` | Usar solo la lista blanca |
| `RANGO_FECHAS_INVALIDO` | 400 | `fechaInicio` posterior a `fechaFin` en el kardex | Corregir el rango; los extremos son inclusivos |
| `RECURSO_NO_ENCONTRADO` | 404 | La materia prima no existe (detalle o kardex) | Volver al listado |
| `METODO_NO_PERMITIDO` | 405 | `POST`, `PUT`, `PATCH` o `DELETE` sobre inventario | No existe escritura: usar HU-09/HU-13/HU-23 |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (`VENTAS` en todo el módulo; `CONSULTA` en el kardex) | Ocultar la consulta |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto; en esta historia suele venir
vacío porque no hay cuerpos de escritura.

---

## 4. Casos límite que el frontend debe conocer

| Situación | Comportamiento |
|---|---|
| `soloStockBajo=false` u omitido | No filtra: devuelve también las `OK` |
| `incluirInactivas` omitido | Equivale a `true`: las inactivas aparecen (R-04) |
| `stockMinimo = 0` | Nunca se marca `BAJO`; con `disponible = 0` es `AGOTADO` y con `disponible > 0` es `OK` (R-05) |
| `disponible = stockMinimo` | Es `OK`, **no** `BAJO`; no aparece en `soloStockBajo=true` |
| `faltante` | `stockMinimo - disponible` cuando falta, y `0` cuando el stock alcanza o supera el mínimo |
| `idMateriaPrima` inexistente en el listado | `200` con `content: []`; no es un 404 |
| Kardex sin movimientos | `200` con `content: []`; no es un 404 |
| `fechaInicio`/`fechaFin` | Ambos extremos se incluyen; no hace falta sumar un día |
| `sort` en el kardex | Se ignora/no se admite el parámetro; el orden siempre es `id_movimiento DESC` |
| `origen` | Puede faltar; `origen.tabla` u `origen.id` pueden faltar por separado |
| `fechaActualizacion` | Puede faltar solo si falta la fila de `inventario`; no asumir que siempre existe al formatear |

- Para pintar el semáforo de stock usar `estadoStock`, **no** recalcular con `faltante`: la regla
  vive en la vista y con `stockMinimo = 0` el resultado no es evidente.
- El listado incluye inactivas por defecto; si la pantalla quiere solo activas, enviar
  `incluirInactivas=false` explícitamente.
- La paginación es base 0: la primera página es `page=0`.

---

## 5. Limitaciones conocidas

- **No hay escritura en esta historia.** No existe forma de ajustar el stock por API desde
  inventario. Los movimientos llegan de HU-09 (lotes), HU-13 (recepción de compras) y HU-23
  (consumo de producción). El kardex no se puede editar ni borrar.
- **El kardex no se puede ordenar por otro campo.** Solo el orden natural (más reciente primero),
  para aprovechar el índice de la tabla.
- **No hay filtro por nombre ni por estado en el listado.** Solo por `idMateriaPrima`,
  `soloStockBajo` e `incluirInactivas`; el buscador por nombre, si se necesita, debe hacerse en el
  cliente sobre la página o pedirse como historia nueva.
- **No se expone `costoUnitario`.** Si una pantalla lo necesita, no está en este contrato.
- **Backend implementado, verificación en caliente pendiente.** El estado de QA de la historia no
  es «verificado en caliente»; confirma los ejemplos al integrar.
- **CP-14 depende de que todos los módulos usen `InventarioService`.** Si algún módulo futuro
  actualizara `inventario` sin registrar el movimiento, el kardex dejaría de explicar el saldo.
