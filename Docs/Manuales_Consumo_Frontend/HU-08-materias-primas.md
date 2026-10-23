# HU-08 — Gestión de materias primas · Manual de consumo

> **Estado: entregado.** Cinco endpoints bajo `/api/v1/materias-primas`: listado paginado con
> filtros, detalle, alta, edición y cambio de estado. **No existe `DELETE`** (R-05): la baja de
> una materia prima se hace **desactivándola** con `PATCH /{id}/estado` y `{"activo": false}`;
> el stock y el historial de movimientos se conservan (R-04). El alta **crea sola la fila de
> inventario en 0** (R-01): el frontend no debe llamar a ningún endpoint de inventario.

## Autenticación y roles

Igual que el resto de la API: `Authorization: Bearer {{token}}` con el token de
`POST /api/v1/auth/login` (HU-03). Base URL: `/api/v1`.

| Endpoint | ADMIN | PRODUCCION | COMPRAS | CONSULTA | VENTAS |
|---|---|---|---|---|---|
| `GET /materias-primas`, `GET /materias-primas/{id}` | sí | sí | sí | sí | 403 |
| `POST /materias-primas`, `PUT /materias-primas/{id}`, `PATCH /materias-primas/{id}/estado` | sí | 403 | 403 | 403 | 403 |

**El rol `VENTAS` no participa en este módulo**: la matriz de roles (`00-base/03-MATRIZ-ROLES.md`)
lo excluye de materias primas, cuyo consumo cubren `COMPRAS` (compra) y `PRODUCCION`
(producción). Cualquier llamada con un token de `VENTAS` devuelve `403 SIN_PERMISO`. La
escritura es **solo de `ADMIN`**, como en el catálogo de unidades (HU-07).

Qué debe ocultar la UI (`*siRol`): el botón «Nueva materia prima», la acción «Editar» y el
interruptor de estado se muestran solo a `ADMIN`; `PRODUCCION`, `COMPRAS` y `CONSULTA` ven el
módulo en solo lectura. Ocultar no sustituye al backend: este responde `403 SIN_PERMISO`.

## Modelo TypeScript

```ts
interface MateriaPrima {
  idMateriaPrima: number;
  nombre: string;
  unidad: UnidadRef;
  stockMinimo: number;
  costoUnitario: number;
  activo: boolean;
  cantidadDisponible: number;
}

interface UnidadRef {
  idUnidad: number;
  nombre: string;
  abreviatura: string;
}

// Mismo cuerpo para POST y PUT. El PUT no admite 'activo'.
interface MateriaPrimaRequest {
  nombre: string;        // obligatorio, máx. 100
  idUnidad: number;      // obligatorio, debe existir en el catálogo de HU-07
  stockMinimo: number;   // obligatorio, >= 0, máx. 8 enteros y 2 decimales
  costoUnitario: number; // obligatorio, >= 0, máx. 8 enteros y 2 decimales
}

interface CambiarEstadoRequest { activo: boolean; }

interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
```

La unidad de medida viaja **embebida como objeto** (`idUnidad`, `nombre`, `abreviatura`), no
solo como id: el frontend pinta la abreviatura junto al stock sin una llamada extra a HU-07.
Es una referencia ligera: **no trae el bloque `usos`** del catálogo de unidades.

`cantidadDisponible` **siempre viene en el JSON** (el DTO lleva `@JsonInclude(ALWAYS)`), aun
cuando valga `0`. Es el stock actual de inventario, **informativo**: se lee aquí, pero se mueve
con los movimientos de inventario (HU-10), no con este módulo.

## Endpoints

| Método | Ruta | Requiere | Códigos |
|---|---|---|---|
| GET | `/api/v1/materias-primas` | ADMIN, PRODUCCION, COMPRAS o CONSULTA | 200, 400, 401, 403 |
| GET | `/api/v1/materias-primas/{id}` | ADMIN, PRODUCCION, COMPRAS o CONSULTA | 200, 400, 401, 403, 404 |
| POST | `/api/v1/materias-primas` | ADMIN | 201, 400, 401, 403, 409 |
| PUT | `/api/v1/materias-primas/{id}` | ADMIN | 200, 400, 401, 403, 404, 409 |
| PATCH | `/api/v1/materias-primas/{id}/estado` | ADMIN | 200, 400, 401, 403, 404, 409 |

### GET /api/v1/materias-primas — listado con filtros y paginación

```http
GET /api/v1/materias-primas?nombre=Har&activo=true&page=0&size=20&sort=nombre,asc
Authorization: Bearer {{token}}
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `nombre` | string | — | **Busca por prefijo**, no por contenido. Sin distinguir mayúsculas. `%` y `_` se tratan como texto literal. |
| `activo` | boolean | — | `true` = solo activas, `false` = solo inactivas. Sin él, ambas. |
| `page` | int | `0` | `>= 0`. |
| `size` | int | `20` | `1..100`; por encima de 100 → `400`. |
| `sort` | string | `nombre,asc` | Lista blanca: `nombre`, `stockMinimo`, `costoUnitario`, `id`, con `,asc` o `,desc`. |

Respuesta `200 OK` (se pidió `page=0&size=2`):

```json
{
  "content": [
    {
      "idMateriaPrima": 5,
      "nombre": "Harina de trigo",
      "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
      "stockMinimo": 50.00,
      "costoUnitario": 3200.00,
      "activo": true,
      "cantidadDisponible": 120.50
    },
    {
      "idMateriaPrima": 7,
      "nombre": "Harina de centeno",
      "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
      "stockMinimo": 10.00,
      "costoUnitario": 4100.00,
      "activo": true,
      "cantidadDisponible": 0.00
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 3,
  "totalPages": 2,
  "first": true,
  "last": false
}
```

Puntos del contrato que el frontend debe respetar:

- El envelope de colección es el común de la API (`content`, `page`, `size`, `totalElements`,
  `totalPages`, `first`, `last`).
- `content` puede venir vacío (`[]`) sin que sea un error: es un filtro que no coincide con
  nadie.
- `unidad` es un objeto completo (`idUnidad`, `nombre`, `abreviatura`), no un id pelado.
- `stockMinimo` y `costoUnitario` **admiten `0`**; son decimales con máximo 2 cifras después
  del punto.
- `activo` es un **booleano** (`true`/`false`), nunca `0`/`1`.
- El orden por defecto es `nombre,asc`: el listado sale alfabético, no por fecha de alta.

### GET /api/v1/materias-primas/{id} — detalle

```http
GET /api/v1/materias-primas/5
Authorization: Bearer {{token}}
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `id` (ruta) | int | — | `idMateriaPrima` de la materia prima. No numérico → `400 PARAMETRO_INVALIDO`. |

Respuesta `200 OK`: una `MateriaPrima` como la del listado.

```json
{
  "idMateriaPrima": 5,
  "nombre": "Harina de trigo",
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "stockMinimo": 50.00,
  "costoUnitario": 3200.00,
  "activo": true,
  "cantidadDisponible": 120.50
}
```

Puntos del contrato que el frontend debe respetar:

- El cuerpo es la materia prima pelada, sin envoltura: aquí no hay `PageResponse`.
- El detalle coincide fila a fila con el listado (mismos `join`, misma unidad, mismo stock).
- Un `idMateriaPrima` que no existe → `404 RECURSO_NO_ENCONTRADO`.

### POST /api/v1/materias-primas — alta

```http
POST /api/v1/materias-primas
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "nombre": "Harina de trigo",
  "idUnidad": 1,
  "stockMinimo": 50.00,
  "costoUnitario": 3200.00
}
```

| Campo | Tipo | Por defecto | Notas |
|---|---|---|---|
| `nombre` | string | — | **Obligatorio**, 1..100 caracteres. Se guarda **recortado** (sin espacios al inicio o al final). |
| `idUnidad` | int | — | **Obligatorio**. Debe existir en el catálogo de HU-07; si no → `409 UNIDAD_INEXISTENTE`. |
| `stockMinimo` | number | — | **Obligatorio**, `>= 0`, máximo 8 dígitos enteros y **2 decimales**. `0` es válido. |
| `costoUnitario` | number | — | **Obligatorio**, `>= 0`, máximo 8 dígitos enteros y **2 decimales**. `0` es válido. |

Respuesta `201 Created` con cabecera `Location: /api/v1/materias-primas/8`:

```json
{
  "idMateriaPrima": 8,
  "nombre": "Harina de trigo",
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "stockMinimo": 50.00,
  "costoUnitario": 3200.00,
  "activo": true,
  "cantidadDisponible": 0.00
}
```

Puntos del contrato que el frontend debe respetar:

- La materia prima nace **activa**: `activo` siempre es `true` en la respuesta del alta, lo que
  se mande en el cuerpo (que no debe llevar `activo`).
- **La fila de inventario se crea sola, en la misma transacción, con cantidad 0** (R-01): el
  frontend **no debe** llamar a ningún endpoint de inventario tras el alta. Por eso
  `cantidadDisponible` sale en `0.00`.
- No envíes `activo` ni ningún campo de stock de inventario: no se aceptan ni se usan.
- `stockMinimo` y `costoUnitario` pueden ser `0`: un costo sin cargar se modela con `0`, no
  con vacío.
- Si `idUnidad` no existe en el catálogo de unidades, la respuesta es `409 UNIDAD_INEXISTENTE`.

### PUT /api/v1/materias-primas/{id} — edición

**Reemplaza todo**: el cuerpo es un reemplazo completo de `nombre`, `idUnidad`, `stockMinimo`
y `costoUnitario`. **No lleva `activo`**: el estado no se toca con el `PUT`, solo con el
`PATCH` de estado.

```http
PUT /api/v1/materias-primas/8
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "nombre": "Harina de trigo fuerte",
  "idUnidad": 1,
  "stockMinimo": 75.00,
  "costoUnitario": 3350.00
}
```

Los cuatro campos son **obligatorios** y se validan igual que en el alta (mismos mensajes de
`fieldErrors`).

Respuesta `200 OK`:

```json
{
  "idMateriaPrima": 8,
  "nombre": "Harina de trigo fuerte",
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "stockMinimo": 75.00,
  "costoUnitario": 3350.00,
  "activo": true,
  "cantidadDisponible": 120.50
}
```

Puntos del contrato que el frontend debe respetar:

- **El `PUT` borra lo que no se mande**: los cuatro campos son obligatorios; el formulario
  tiene que enviarlos siempre, no solo los que cambiaron.
- El `PUT` **no cambia el estado**: `activo` sale igual que antes, aunque el cuerpo lo incluya.
- El `PUT` **no toca el stock**: `cantidadDisponible` se vuelve a leer de inventario para la
  respuesta, con su valor actual.
- La unidad de medida se **revalida siempre**, aunque no haya cambiado: si `idUnidad` no existe
  → `409 UNIDAD_INEXISTENTE`.
- Un `idMateriaPrima` que no existe → `404 RECURSO_NO_ENCONTRADO`.

### PATCH /api/v1/materias-primas/{id}/estado — activar o desactivar

```http
PATCH /api/v1/materias-primas/8/estado
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "activo": false
}
```

| Campo | Tipo | Por defecto | Notas |
|---|---|---|---|
| `activo` | boolean | — | **Obligatorio**. `true` reactiva, `false` desactiva. |

Respuesta `200 OK` (desactivación):

```json
{
  "idMateriaPrima": 8,
  "nombre": "Harina de trigo fuerte",
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "stockMinimo": 75.00,
  "costoUnitario": 3350.00,
  "activo": false,
  "cantidadDisponible": 120.50
}
```

Puntos del contrato que el frontend debe respetar:

- La operación es **idempotente**: desactivar una materia prima ya inactiva, o activar una ya
  activa, devuelve `200` sin más efecto.
- **Desactivar NO borra el stock ni el historial de movimientos** (R-04): la fila de inventario
  y el kardex se conservan para que la trazabilidad no se rompa. Solo se impide usar la materia
  prima en operaciones nuevas.
- Si la materia prima **aparece en recetas activas** y la regla está habilitada en
  configuración (por defecto lo está), la desactivación se rechaza con `409
  MATERIA_PRIMA_EN_USO`; el mensaje trae el conteo de recetas afectadas. La **activación** no
  pasa por esa comprobación: solo se valida al desactivar.
- No existe `DELETE`: la baja de una materia prima creada por error es desactivarla.
- Un `idMateriaPrima` que no existe → `404 RECURSO_NO_ENCONTRADO`.

## Errores

| Código HTTP | `code` | Cuándo | Qué hace el frontend |
|---|---|---|---|
| 400 | `VALIDACION_FALLIDA` | `nombre` vacío o de más de 100 caracteres; `idUnidad`, `stockMinimo`, `costoUnitario` o `activo` ausentes; valores negativos o con más de 2 decimales; cuerpo ilegible | Marcar el campo con `fieldErrors`; no reintentar |
| 400 | `CAMPO_ORDEN_INVALIDO` | `sort` fuera de la lista blanca (`nombre`, `stockMinimo`, `costoUnitario`, `id`) | Usar `nombre,asc` |
| 400 | `PARAMETRO_INVALIDO` | `page` negativo, `size` fuera de `1..100`, o un tipo mal formado (`id=abc`, `activo=si`) | Ajustar el parámetro |
| 401 | `NO_AUTENTICADO` | Sin token, o token inválido/expirado | Volver al login |
| 403 | `SIN_PERMISO` | Rol insuficiente (por ejemplo `VENTAS` en cualquier endpoint, o `PRODUCCION` escribiendo) | Bloquear la vista o el botón |
| 403 | `USUARIO_INACTIVO` | El usuario de la sesión está desactivado | Volver al login y avisar al administrador |
| 404 | `RECURSO_NO_ENCONTRADO` | `idMateriaPrima` inexistente | Recargar el listado |
| 405 | `METODO_NO_PERMITIDO` | `DELETE` (o cualquier método no soportado) sobre una materia prima | No debería ocurrir: la baja es por desactivación |
| 409 | `UNIDAD_INEXISTENTE` | El `idUnidad` del cuerpo no existe en el catálogo de HU-07 | Ir al selector de unidades y avisar |
| 409 | `MATERIA_PRIMA_EN_USO` | Se desactiva una materia prima que sigue en recetas activas | Mostrar el conteo del mensaje; no reintentar |
| 500 | `ERROR_INTERNO` | Error no controlado | Reintento genérico + log |

Ejemplos:

```json
{
  "timestamp": "2026-10-22T09:41:12.483920115",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDACION_FALLIDA",
  "message": "Los datos enviados no son válidos.",
  "path": "/api/v1/materias-primas",
  "fieldErrors": [
    { "field": "nombre", "message": "El nombre es obligatorio" },
    { "field": "costoUnitario", "message": "El costo unitario no puede ser negativo" }
  ]
}
```

```json
{
  "timestamp": "2026-10-22T09:41:12.501883270",
  "status": 400,
  "error": "Bad Request",
  "code": "CAMPO_ORDEN_INVALIDO",
  "message": "El campo de ordenamiento 'estado' no es válido.",
  "path": "/api/v1/materias-primas",
  "fieldErrors": []
}
```

```json
{
  "timestamp": "2026-10-22T09:43:07.112004861",
  "status": 409,
  "error": "Conflict",
  "code": "UNIDAD_INEXISTENTE",
  "message": "La unidad de medida indicada no existe.",
  "path": "/api/v1/materias-primas",
  "fieldErrors": []
}
```

```json
{
  "timestamp": "2026-10-22T09:44:55.908337412",
  "status": 409,
  "error": "Conflict",
  "code": "MATERIA_PRIMA_EN_USO",
  "message": "La materia prima aparece en 2 receta(s) activa(s) y no se puede desactivar. Desactive o elimine esas recetas primero.",
  "path": "/api/v1/materias-primas/8/estado",
  "fieldErrors": []
}
```

```json
{
  "timestamp": "2026-10-22T09:45:30.334110027",
  "status": 404,
  "error": "Not Found",
  "code": "RECURSO_NO_ENCONTRADO",
  "message": "No se encontró la materia prima con id 9999.",
  "path": "/api/v1/materias-primas/9999",
  "fieldErrors": []
}
```

Formato del cuerpo de error, tal cual lo devuelve la API (siete campos, siempre):

```json
{"timestamp":"2026-10-22T09:41:12.483920115","status":400,"error":"Bad Request",
 "code":"VALIDACION_FALLIDA","message":"Los datos enviados no son válidos.",
 "path":"/api/v1/materias-primas",
 "fieldErrors":[{"field":"idUnidad","message":"La unidad de medida es obligatoria"}]}
```

Mensajes de `fieldErrors` (`field` → `message`):

| `field` | Mensajes |
|---|---|
| `nombre` | `El nombre es obligatorio` · `El nombre no puede superar 100 caracteres` |
| `idUnidad` | `La unidad de medida es obligatoria` |
| `stockMinimo` | `El stock mínimo es obligatorio` · `El stock mínimo no puede ser negativo` · `El stock mínimo admite máximo 8 dígitos enteros y 2 decimales` |
| `costoUnitario` | `El costo unitario es obligatorio` · `El costo unitario no puede ser negativo` · `El costo unitario admite máximo 8 dígitos enteros y 2 decimales` |
| `activo` | `El estado es obligatorio` |

> **`code` es estable; el `message` no.** El frontend decide con el `code`, nunca con el texto
> del mensaje: los mensajes cambian (el de `MATERIA_PRIMA_EN_USO` trae el conteo de recetas) y
> están pensados para leerse, no para compararse.

## Reglas que afectan a la interfaz

- **El selector de unidades usa el catálogo de HU-07** (`GET /unidades-medida`): el formulario
  solo manda `idUnidad`, y el nombre y la abreviatura que se muestran vienen del catálogo.
  Tras crear o editar unidades en HU-07 hay que invalidar la caché del catálogo, o la unidad
  nueva no aparecerá en el desplegable.
- **`stockMinimo` y `costoUnitario` admiten `0`** (la validación es inclusiva en `0.00`). Un
  costo sin cargar se modela con `0`, no con vacío ni con `null`.
- **`cantidadDisponible` es informativa**: se muestra en el listado y el detalle, pero no se
  edita en este módulo; el stock se mueve con los movimientos de inventario (HU-10).
- **El alta crea sola la fila de inventario en 0** (R-01): no hay ninguna llamada extra que
  hacer tras el `POST`.
- **La baja es la desactivación** (R-05, sin `DELETE`): desactivar conserva stock e historial
  (R-04) y solo impide usar la materia prima en operaciones nuevas.
- **El filtro por nombre es de PREFIJO, no de contenido.** `?nombre=Har` encuentra «Harina de
  trigo» pero no «Trigo harinado». `%` y `_` se tratan como texto literal.
- **El `sort` admite cuatro campos**: `nombre`, `stockMinimo`, `costoUnitario` e `id`. El valor
  se manda como `campo,asc` o `campo,desc`; sin dirección, el backend ordena ascendente.
- **Nombres duplicados permitidos.** No hay restricción única sobre `nombre`: dos materias
  primas pueden llamarse igual. Si el frontend quiere avisar, puede comparar con el listado por
  prefijo; el aviso es informativo, no bloqueante.
- **El nombre se guarda recortado** (sin espacios al inicio y al final), conservando las
  mayúsculas que escribió el usuario; la búsqueda, en cambio, no distingue mayúsculas.

## Estados de pantalla

- **Cargando:** `MatProgressBar` en la cabecera de la tabla y controles deshabilitados mientras
  dura la petición.
- **Vacío:** la tabla sin filas con «No hay materias primas que coincidan con la búsqueda»
  cuando hay filtros activos, o «Aún no hay materias primas registradas» con acción «Nueva
  materia prima» (solo `ADMIN`) cuando la base está vacía.
- **Error:** mensaje en español con botón «Reintentar»; los errores de campo, junto al campo.
- **Formulario:** el botón de envío deshabilitado mientras la petición está en vuelo, para que
  no se pulse dos veces. El selector de unidades se carga desde HU-07; si el catálogo falla,
  avisar antes de poder enviar.

## Checklist de integración

- [ ] Token Bearer en cada llamada; `401` lleva al login.
- [ ] Módulo oculto del todo para `VENTAS`; en solo lectura para `PRODUCCION`, `COMPRAS` y
      `CONSULTA`.
- [ ] «Nueva materia prima», «Editar» e interruptor de estado solo para `ADMIN`; el backend
      responde `403 SIN_PERMISO` aunque la UI los oculte.
- [ ] Listado con envelope `PageResponse` (`content`, `page`, `size`, `totalElements`,
      `totalPages`, `first`, `last`), no un array plano.
- [ ] `sort` limitado a la lista blanca (`nombre`, `stockMinimo`, `costoUnitario`, `id`);
      cualquier otro campo produce `400 CAMPO_ORDEN_INVALIDO`.
- [ ] Selector de unidades alimentado con `GET /unidades-medida` (HU-07); el formulario solo
      envía `idUnidad`.
- [ ] `stockMinimo` y `costoUnitario` aceptan `0`; máximo 2 decimales validados en pantalla.
- [ ] Tras el alta no se llama a ningún endpoint de inventario: la fila nace sola en 0 y la
      respuesta ya trae `cantidadDisponible: 0`.
- [ ] El formulario de edición manda los cuatro campos siempre (el `PUT` reemplaza) y no
      incluye `activo`.
- [ ] La baja se hace con `PATCH /{id}/estado` y `{"activo": false}`; no hay `DELETE`.
- [ ] `409 MATERIA_PRIMA_EN_USO` mostrado con el conteo del mensaje, sin reintentar.
- [ ] `cantidadDisponible` pintada en modo solo lectura, junto a la abreviatura de la unidad.
- [ ] Botón de envío deshabilitado en vuelo; `fieldErrors` mostrados junto a cada campo.

## Limitaciones conocidas

- **La regla `MATERIA_PRIMA_EN_USO` es configurable** en el backend
  (`app.materias-primas.bloquear-desactivacion-con-recetas`, por defecto `true`). Si se
  desactiva, el `PATCH` de desactivación responde `200` aunque la materia prima esté en recetas
  activas, y el frontend no verá nunca el `409`. El frontend debe tratar el `409` como un caso
  posible, no como algo que siempre ocurre.
- **El conteo de recetas activas es SQL nativo** sobre `detalle_receta` y `recetas`, porque
  esas tablas todavía no tienen entidad JPA (HU-12). Cuando exista, migrará al repositorio de
  recetas. No cambia el contrato HTTP.
- **No hay endpoint para detectar nombres duplicados**: no hay restricción única sobre
  `nombre`; si el frontend quiere avisar, lo resuelve con el listado por prefijo.
- **No existe `DELETE`** (R-05) y no debe añadirse: un `DELETE` a
  `/api/v1/materias-primas/{id}` devuelve `405 METODO_NO_PERMITIDO`.
- **Este módulo no administra el stock**: `cantidadDisponible` se lee de la fila de inventario
  que crea el alta (R-01); los movimientos son de HU-10. Si la fila falta (caso transitorio),
  el backend la reporta como `0`, no como `null`.
- **Aún no hay colección de Postman para HU-08** en `Docs/Postman/`: la verificación de la
  batería la ejecuta el equipo de QA.
