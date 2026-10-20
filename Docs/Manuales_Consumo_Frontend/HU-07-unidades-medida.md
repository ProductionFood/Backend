# HU-07 — Unidades de medida · Manual de consumo

> **Estado: entregado.** Cuatro endpoints bajo `/api/v1/unidades-medida`: listado, detalle,
> alta y edición. **No existe `DELETE`**: la tabla `unidades_medida` no tiene columna `estado`
> y `materias_primas`, `productos` y `recetas` la referencian con claves foráneas
> `ON DELETE NO ACTION` (R-03), así que una unidad no se elimina ni se desactiva: se corrige
> editándola. **Tampoco existe `PATCH` de estado.** **El listado no se pagina** (R-01):
> devuelve un array plano, no un `PageResponse`.

## Autenticación y roles

Igual que el resto de la API: `Authorization: Bearer {{token}}` con el token de
`POST /api/v1/auth/login` (HU-03). Base URL: `/api/v1`.

| Endpoint | ADMIN | PRODUCCION | COMPRAS | VENTAS | CONSULTA |
|---|---|---|---|---|---|
| `GET /unidades-medida`, `GET /unidades-medida/{id}` | sí | sí | sí | sí | sí |
| `POST /unidades-medida`, `PUT /unidades-medida/{id}` | sí | 403 | 403 | 403 | 403 |

Los **cinco roles leen el catálogo**: los roles operativos lo necesitan para poblar los
selectores de unidades de sus formularios aunque no puedan modificarlo. La escritura es
**solo de `ADMIN`**: a diferencia de los módulos de clientes (HU-05) y proveedores (HU-06),
este no delega la escritura en los roles operativos.

Qué debe ocultar la UI (`*siRol`): el botón «Nueva unidad» y la acción «Editar» se muestran
solo a `ADMIN`; los demás roles ven el catálogo en solo lectura. Ocultar no sustituye al
backend: este responde `403 SIN_PERMISO`.

## Modelo TypeScript

```ts
interface UnidadMedida {
  idUnidad: number;
  nombre: string;
  abreviatura: string;
  usos: UsosUnidad;
}

interface UsosUnidad {
  enMateriasPrimas: number;
  enProductos: number;
  enRecetas: number;
  total: number;
}

// Mismo cuerpo para POST y PUT.
interface UnidadRequest {
  nombre: string;       // obligatorio, máx. 50
  abreviatura: string;  // obligatorio, máx. 50
}
```

`usos` **siempre viene en el JSON**, con los tres conteos a cero si la unidad todavía no está
en uso en ninguna tabla. El DTO lleva `@JsonInclude(ALWAYS)` para garantizarlo: la
configuración global de la API omite los nulos, y aquí el campo nunca debe desaparecer.

El listado devuelve **`UnidadMedida[]` directamente, no un `PageResponse`**: no hay `content`,
ni `totalElements`, ni `totalPages`, ni paginador. El catálogo entra entero en memoria.

`usos.total` es la suma de los tres conteos por tabla. La columna «En uso» de la pantalla se
pinta con ese valor (y no con el conteo de una sola tabla).

## Endpoints

| Método | Ruta | Requiere | Códigos |
|---|---|---|---|
| GET | `/api/v1/unidades-medida` | los 5 roles | 200, 401, 403 |
| GET | `/api/v1/unidades-medida/{id}` | los 5 roles | 200, 400, 401, 403, 404 |
| POST | `/api/v1/unidades-medida` | ADMIN | 201, 400, 401, 403, 409 |
| PUT | `/api/v1/unidades-medida/{id}` | ADMIN | 200, 400, 401, 403, 404, 409 |

### GET /api/v1/unidades-medida — listado

Devuelve el **catálogo completo**, sin paginación, sin filtros y **ordenado por nombre**.

```http
GET /api/v1/unidades-medida
Authorization: Bearer {{token}}
```

```json
[
  { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg",
    "usos": { "enMateriasPrimas": 12, "enProductos": 5, "enRecetas": 0, "total": 17 } },
  { "idUnidad": 2, "nombre": "Gramo", "abreviatura": "g",
    "usos": { "enMateriasPrimas": 8, "enProductos": 0, "enRecetas": 0, "total": 8 } }
]
```

Los conteos de este ejemplo son **ilustrativos**: en una base recién sembrada las nueve
unidades de la semilla V3 salen con los tres conteos a cero, porque `materias_primas`,
`productos` y `recetas` todavía están vacías. Lo que el frontend tiene que leer bien es la
**forma**, que no cambia: `idUnidad`, `nombre`, `abreviatura` y `usos` con sus tres conteos y
el total.

Un catálogo vacío devuelve `200` con `[]`, no un error.

### GET /api/v1/unidades-medida/{id} — detalle

```http
GET /api/v1/unidades-medida/1
Authorization: Bearer {{token}}
```

Respuesta `200 OK`: un `UnidadMedida` como el del listado.

```json
{
  "idUnidad": 1,
  "nombre": "Kilogramo",
  "abreviatura": "kg",
  "usos": { "enMateriasPrimas": 12, "enProductos": 5, "enRecetas": 0, "total": 17 }
}
```

Un `id` que no existe devuelve `404 RECURSO_NO_ENCONTRADO`; un `id` no numérico
(`/unidades-medida/abc`) devuelve `400 PARAMETRO_INVALIDO`.

### POST /api/v1/unidades-medida — alta

```http
POST /api/v1/unidades-medida
Authorization: Bearer {{token}}
Content-Type: application/json

{ "nombre": "Media docena", "abreviatura": "1/2doc" }
```

Respuesta `201 Created` con cabecera `Location: /api/v1/unidades-medida/10`:

```json
{
  "idUnidad": 10,
  "nombre": "Media docena",
  "abreviatura": "1/2doc",
  "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 }
}
```

La abreviatura **no se repite**, y la comparación **no distingue mayúsculas de minúsculas**:
`kg` y `KG` son la misma abreviatura. El `409 ABREVIATURA_DUPLICADA` llega aunque en pantalla
los dos valores se vean distintos; el mensaje del error explica que la comparación no
distingue mayúsculas.

### PUT /api/v1/unidades-medida/{id} — edición

**Reemplaza todo**: el cuerpo es un reemplazo puro de `nombre` y `abreviatura`. Si la
abreviatura no cambia, se manda la actual y no pasa nada.

```http
PUT /api/v1/unidades-medida/1
Authorization: Bearer {{token}}
Content-Type: application/json

{ "nombre": "Kilogramo", "abreviatura": "kgs" }
```

Si la unidad está en uso (`usos.total > 0`) y se cambia su abreviatura, la respuesta es
`409 UNIDAD_EN_USO`:

```json
{
  "timestamp": "2026-10-20T14:18:17.727302699",
  "status": 409,
  "error": "Conflict",
  "code": "UNIDAD_EN_USO",
  "message": "La unidad 'Kilogramo (kg)' se usa en 12 materias primas, 5 productos y 0 recetas. Cambiar su abreviatura modificaría cómo se interpretan esas cantidades. Repita la petición con ?confirmar=true para confirmar el cambio.",
  "path": "/api/v1/unidades-medida/1",
  "fieldErrors": []
}
```

Cuerpo compuesto con los conteos del listado de arriba: el mensaje lo arma el backend con
ese mismo formato (`La unidad '<nombre> (<abreviatura>)' se usa en <n> materias primas,
<n> productos y <n> recetas...`), y los conteos salen de la base, no del cuerpo de la
petición.

Para aplicar el cambio hay que **repetir la misma petición** con el parámetro de consulta
`?confirmar=true`:

```http
PUT /api/v1/unidades-medida/1?confirmar=true
Authorization: Bearer {{token}}
Content-Type: application/json

{ "nombre": "Kilogramo", "abreviatura": "kgs" }
```

Respuesta `200 OK` con la unidad actualizada y sus conteos de uso recalculados.

El parámetro `confirmar` va **en la query, no en el cuerpo**: el body sigue siendo solo
`nombre` y `abreviatura`. Si la abreviatura no cambia, el parámetro es irrelevante y la
petición se aplica sin él.

## Errores

| `code` | HTTP | Qué debe hacer el frontend |
|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Nombre o abreviatura vacíos o de más de 50 caracteres. El detalle viene en `fieldErrors`: marcar cada campo con su mensaje |
| `ABREVIATURA_DUPLICADA` | 409 | Ya existe una unidad con esa abreviatura (sin distinguir mayúsculas). Ir al campo abreviatura y avisar |
| `UNIDAD_EN_USO` | 409 | Se cambia la abreviatura de una unidad ya referenciada. El mensaje trae el conteo por tabla: pedir confirmación al usuario y reenviar con `?confirmar=true` |
| `RECURSO_NO_ENCONTRADO` | 404 | El id no existe. Volver al listado |
| `NO_AUTENTICADO` | 401 | Token ausente, inválido o expirado. Volver al login |
| `SIN_PERMISO` | 403 | Rol insuficiente. Bloquear la acción |
| `USUARIO_INACTIVO` | 403 | Usuario desactivado. Cerrar sesión e informar |
| `METODO_NO_PERMITIDO` | 405 | Se intentó un `DELETE`: no existe. No reintentar |

Formato del cuerpo de error, tal cual lo devuelve la API:

```json
{"timestamp":"2026-10-20T14:18:17.794093577","status":400,"error":"Bad Request",
 "code":"VALIDACION_FALLIDA","message":"Los datos enviados no son válidos.",
 "path":"/api/v1/unidades-medida",
 "fieldErrors":[{"field":"abreviatura","message":"La abreviatura es obligatoria"},
                {"field":"nombre","message":"El nombre es obligatorio"}]}
```

Mensajes de `fieldErrors` (`field` → `message`):

| `field` | Mensajes |
|---|---|
| `nombre` | `El nombre es obligatorio` · `El nombre no puede superar 50 caracteres` |
| `abreviatura` | `La abreviatura es obligatoria` · `La abreviatura no puede superar 50 caracteres` |

> **`code` es estable; el `message` no.** El frontend decide con el `code`, nunca con el texto
> del mensaje: los mensajes cambian (el de `ABREVIATURA_DUPLICADA` explica la collation, el de
> `UNIDAD_EN_USO` trae los conteos) y están pensados para leerse, no para compararse.

## Reglas que afectan a la interfaz

- **El listado no se pagina**: el catálogo entra entero en memoria. Son unas 15 unidades, así
  que el frontend no necesita ni paginador ni scroll infinito.
- **La abreviatura es única sin distinguir mayúsculas**: el frontend puede avisar si coincide
  con alguna de la lista ya cargada, pero la autoridad es el `409 ABREVIATURA_DUPLICADA`.
- **`usos.total > 0` significa que la unidad sostiene datos reales**, y su abreviatura es
  delicada: la interfaz debe advertir antes de editarla, como en el diseño («Esta unidad se
  usa en 12 materias primas y 5 productos»).
- **Editar el nombre de una unidad en uso es siempre válido**; lo que exige confirmación es
  cambiar la abreviatura.
- **Si la abreviatura no cambia, no hace falta mandar `confirmar`.**
- **El catálogo alimenta los desplegables de HU-08, HU-11 y HU-12**: tras crear o editar hay
  que invalidar la caché del catálogo, o la unidad nueva no aparecerá hasta recargar.
- **Nombre y abreviatura: 50 caracteres como máximo**, sin espacios al inicio o al final (el
  backend los recorta). La abreviatura se guarda tal como se escribe, respetando mayúsculas y
  minúsculas.

## Estados de pantalla

- **Cargando:** `MatProgressBar` en la cabecera de la tabla y controles deshabilitados
  mientras dura la petición del catálogo.
- **Vacío:** es poco probable, porque la semilla V3 deja nueve unidades. Si ocurre, mensaje
  explicativo con acción sugerida («Reintentar» o revisar los permisos del usuario).
- **Error:** mensaje en español con botón «Reintentar»; los errores de campo, junto al campo.
- **Formulario:** el botón de envío deshabilitado mientras la petición está en vuelo, para
  que no se pulse dos veces.

## Checklist de integración

- [ ] Token Bearer en cada llamada; `401` lleva al login.
- [ ] Catálogo leído con los cinco roles: `ADMIN`, `PRODUCCION`, `COMPRAS`, `VENTAS` y
      `CONSULTA`.
- [ ] «Nueva unidad» y «Editar» ocultas para quien no sea `ADMIN`; el resto ve el catálogo
      en solo lectura.
- [ ] Sin paginador: se espera un array plano, no un `PageResponse`.
- [ ] Columna «En uso» pintada con `usos.total`.
- [ ] `usos` siempre presente, con los tres conteos aunque valgan cero.
- [ ] Caché del catálogo invalidada tras crear o editar, para que los desplegables de HU-08,
      HU-11 y HU-12 vean la unidad nueva.
- [ ] Cambio de abreviatura de una unidad en uso: diálogo de advertencia con el conteo por
      tabla y reenvío con `?confirmar=true`.
- [ ] Botón de envío deshabilitado en vuelo; `fieldErrors` mostrados junto a cada campo.
- [ ] Nada de `DELETE`: una unidad creada por error se corrige editándola.

## Limitaciones conocidas

- **Los conteos de `usos` salen de SQL nativo** sobre `materias_primas`, `productos` y
  `recetas`, porque esas tres tablas todavía no tienen entidad JPA (HU-08, HU-11 y HU-12 no
  están implementadas). Cuando existan, el conteo migrará a su repositorio; el contrato que
  ve el frontend no cambia.
- **Mientras esas tablas estén vacías, `usos` siempre valdrá cero.** Es el estado real de la
  base, no un defecto: ninguna unidad está referenciada todavía.
- **No hay forma de desactivar ni de eliminar una unidad** (R-03). Si el negocio necesita
  retirar una unidad, hay que resolver antes qué pasa con las referencias existentes.
- **La abreviatura no se puede reutilizar** aunque se edite la unidad que la ocupa: la
  restricción `UNIQUE` es de la columna.
- La colección de Postman de esta historia (`Docs/Postman/ProductionFood-HU07.postman_collection.json`)
  está publicada pero **aún no se ha ejecutado** contra la aplicación en caliente: la
  verificación de la batería la ejecuta el equipo de QA.
