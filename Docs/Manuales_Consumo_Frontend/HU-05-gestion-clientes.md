# HU-05 — Gestión de clientes · Manual de consumo

> **Estado: entregado.** CRUD completo de clientes: consulta con filtros y paginación, alta,
> edición y cambio de estado. **No existe `DELETE`**: un `DELETE` a `/api/v1/clientes/{id}`
> devuelve `405 METODO_NO_PERMITIDO`. La baja de un cliente se hace **desactivándolo** con
> `PATCH /api/v1/clientes/{id}/estado` y `{"activo": false}`; el cliente conserva sus pedidos.

## Autenticación

Igual que el resto de la API: `Authorization: Bearer {{token}}` con el token de
`POST /api/v1/auth/login`.

- **Leer** el módulo: `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`.
- **Escribir** el módulo (alta, edición, activar y desactivar): `ADMIN` y `VENTAS`.

En Postman, la carpeta `00 - Setup` de `ProductionFood-HU05.postman_collection.json` deja
listos los tokens de `ADMIN`, `VENTAS` y `CONSULTA`.

## Matriz de permisos

`Lee` = consultar el módulo · `Escribe` = crear, editar, activar y desactivar · sin acceso = la
petición devuelve `403 SIN_PERMISO`

| Rol | Lee | Escribe | Notas |
|---|---|---|---|
| `ADMIN` | Sí | Sí | Acceso total al módulo. |
| `VENTAS` | Sí | Sí | Es el rol que atiende a los clientes. |
| `PRODUCCION` | Sí | No | Ve el listado para saber a quién se le vende. |
| `CONSULTA` | Sí | No | Solo lectura: cualquier escritura devuelve `403 SIN_PERMISO`. |
| `COMPRAS` | **No** | No | **Sin acceso al módulo de clientes.** |

> **`COMPRAS` no tiene acceso a clientes.** Es contraintuitivo: el rol de compras trabaja con
> proveedores, no con clientes, así que la matriz de roles le da `—` en la fila «Clientes»
> (`00-base/03-MATRIZ-ROLES.md`). Cualquier llamada a `/api/v1/clientes` con un token de
> `COMPRAS` devuelve `403 SIN_PERMISO`. Si el frontend muestra el módulo de clientes, tiene
> que ocultarlo para ese rol.

## Endpoints

| Método | Ruta | Requiere | Códigos |
|---|---|---|---|
| GET | `/api/v1/clientes` | rol `ADMIN`, `VENTAS`, `PRODUCCION` o `CONSULTA` | 200, 400, 401, 403 |
| GET | `/api/v1/clientes/{id}` | rol `ADMIN`, `VENTAS`, `PRODUCCION` o `CONSULTA` | 200, 400, 401, 403, 404 |
| POST | `/api/v1/clientes` | rol `ADMIN` o `VENTAS` | 201, 400, 401, 403 |
| PUT | `/api/v1/clientes/{id}` | rol `ADMIN` o `VENTAS` | 200, 400, 401, 403, 404 |
| PATCH | `/api/v1/clientes/{id}/estado` | rol `ADMIN` o `VENTAS` | 200, 400, 401, 403, 404, 409 |

### GET /api/v1/clientes — listar con filtros y paginación

```http
GET /api/v1/clientes?nombre=Tien&activo=true&page=0&size=20&sort=nombre,asc
Authorization: Bearer {{token}}
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `nombre` | string | — | **Busca por prefijo**, no por contenido. No distingue mayúsculas. |
| `activo` | boolean | — | `true` = solo activos, `false` = solo inactivos. Sin él, ambos. |
| `page` | int | `0` | `>= 0`. |
| `size` | int | `20` | `1..100`; por encima de 100 → `400`. |
| `sort` | string | `nombre,asc` | Lista blanca: `nombre`, `id`, con `,asc` o `,desc`. |

Respuesta `200 OK` (ejecución real; se pidió `page=0&size=2`):

```json
{
  "content": [
    {
      "idCliente": 3,
      "nombre": "Almacén Central",
      "contacto": null,
      "telefono": null,
      "activo": true
    },
    {
      "idCliente": 2,
      "nombre": "Panadería El Trigo",
      "contacto": "Ana Restrepo",
      "telefono": "310 444 2211",
      "activo": true
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
- `contacto` y `telefono` **sí aparecen como `null`** cuando el cliente no los tiene (ver
  «Casos límite»).
- `activo` es un **booleano** (`true`/`false`), nunca `0`/`1`.
- El orden por defecto es `nombre,asc`: el listado sale alfabético, no por fecha de alta.

### GET /api/v1/clientes/{id} — obtener un cliente

```http
GET /api/v1/clientes/1
Authorization: Bearer {{token}}
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `id` (ruta) | int | — | `idCliente` del cliente. No numérico → `400 PARAMETRO_INVALIDO`. |

Respuesta `200 OK` (ejecución real):

```json
{
  "idCliente": 1,
  "nombre": "Tienda La Esquina",
  "contacto": null,
  "telefono": null,
  "activo": true
}
```

Puntos del contrato que el frontend debe respetar:

- El cuerpo es el cliente pelado, sin envoltura: aquí no hay `PageResponse`.
- Un `idCliente` que no existe → `404 RECURSO_NO_ENCONTRADO`.

### POST /api/v1/clientes — registrar un cliente

```http
POST /api/v1/clientes
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "nombre": "Tienda La Esquina"
}
```

| Campo | Tipo | Por defecto | Notas |
|---|---|---|---|
| `nombre` | string | — | **Obligatorio**, máximo 100 caracteres. |
| `contacto` | string | — | Opcional, máximo 100 caracteres. |
| `telefono` | string | — | Opcional, máximo 20 caracteres. **No valida formato.** |

Respuesta `201 Created` (ejecución real). La cabecera `Location` apunta al cliente creado:

```http
Location: /api/v1/clientes/1
```

```json
{
  "idCliente": 1,
  "nombre": "Tienda La Esquina",
  "contacto": null,
  "telefono": null,
  "activo": true
}
```

Con los tres campos, la respuesta trae los valores tal como se enviaron:

```json
{
  "idCliente": 2,
  "nombre": "Panadería El Trigo",
  "contacto": "Ana Restrepo",
  "telefono": "310 444 2211",
  "activo": true
}
```

Puntos del contrato que el frontend debe respetar:

- El cliente nace **activo**: `activo` siempre es `true` en la respuesta del alta, lo que se
  mande en el cuerpo.
- `contacto` y `telefono` ausentes en el cuerpo se guardan vacíos y **se devuelven como
  `null`**.
- El **teléfono no valida formato** (R-04): se guarda tal cual se escribe, porque un número
  real puede ser fijo, celular, con indicativo o con extensión.

### PUT /api/v1/clientes/{id} — editar un cliente

```http
PUT /api/v1/clientes/1
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "nombre": "Tienda La Esquina",
  "contacto": "Pedro Ruiz",
  "telefono": "300 555 1234"
}
```

| Campo | Tipo | Por defecto | Notas |
|---|---|---|---|
| `nombre` | string | — | **Obligatorio**, máximo 100 caracteres. |
| `contacto` | string | — | Opcional, máximo 100 caracteres. |
| `telefono` | string | — | Opcional, máximo 20 caracteres. **No valida formato.** |

Respuesta `200 OK` (ejecución real):

```json
{
  "idCliente": 1,
  "nombre": "Tienda La Esquina",
  "contacto": "Pedro Ruiz",
  "telefono": "300 555 1234",
  "activo": true
}
```

Puntos del contrato que el frontend debe respetar:

- **El `PUT` reemplaza, no conserva.** Un `contacto` o un `telefono` en `null` **borra** el
  valor anterior. El frontend tiene que mandar siempre los tres campos del formulario.
- El `PUT` **no cambia el estado**. `activo` sale igual que antes, aunque el cuerpo lo
  incluya. Para cambiar el estado está el `PATCH` de estado.
- Un `idCliente` que no existe → `404 RECURSO_NO_ENCONTRADO`.

### PATCH /api/v1/clientes/{id}/estado — activar o desactivar

```http
PATCH /api/v1/clientes/1/estado
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "activo": false
}
```

| Campo | Tipo | Por defecto | Notas |
|---|---|---|---|
| `activo` | boolean | — | **Obligatorio**. `true` reactiva, `false` desactiva. |

Respuesta `200 OK` (ejecución real, desactivación):

```json
{
  "idCliente": 1,
  "nombre": "Tienda La Esquina",
  "contacto": "Pedro Ruiz",
  "telefono": "300 555 1234",
  "activo": false
}
```

Puntos del contrato que el frontend debe respetar:

- La operación es **idempotente**: desactivar un cliente ya inactivo, o activar uno ya
  activo, devuelve `200` sin más efecto.
- **Desactivar no cancela pedidos.** Los pedidos ya creados siguen su curso; lo único que
  cambia es que el cliente **no puede recibir pedidos nuevos**.
- Si el cliente tiene pedidos en curso, la desactivación se rechaza con
  `409 CLIENTE_CON_PEDIDOS_ACTIVOS` (ver «Limitaciones conocidas»).
- Un `idCliente` que no existe → `404 RECURSO_NO_ENCONTRADO`.

## Errores

| Código HTTP | `code` | Cuándo | Qué hace el frontend |
|---|---|---|---|
| 400 | `VALIDACION_FALLIDA` | `nombre` vacío o de más de 100 caracteres, `contacto` de más de 100, `telefono` de más de 20, o `activo` ausente en el `PATCH` | Marcar el campo con `fieldErrors`; no reintentar |
| 400 | `CAMPO_ORDEN_INVALIDO` | `sort` fuera de la lista blanca (`nombre`, `id`) | Usar `nombre,asc` |
| 400 | `PARAMETRO_INVALIDO` | `page` negativo, `size` fuera de `1..100`, o un tipo mal formado (`id=abc`) | Ajustar el parámetro |
| 401 | `NO_AUTENTICADO` | Sin token, o token inválido/expirado | Volver al login |
| 403 | `SIN_PERMISO` | Sesión válida con rol insuficiente (por ejemplo `CONSULTA` o `COMPRAS` escribiendo, o `COMPRAS` leyendo) | Bloquear la vista o el botón |
| 403 | `USUARIO_INACTIVO` | El usuario de la sesión está desactivado | Volver al login y avisar al administrador |
| 404 | `RECURSO_NO_ENCONTRADO` | `idCliente` inexistente | Recargar el listado |
| 405 | `METODO_NO_PERMITIDO` | `DELETE` (o cualquier método no soportado) sobre un cliente | No debería ocurrir: la baja es por desactivación |
| 409 | `CLIENTE_CON_PEDIDOS_ACTIVOS` | Se desactiva un cliente con pedidos en curso | Avisar al usuario y no reintentar |
| 500 | `ERROR_INTERNO` | Error no controlado | Reintento genérico + log |

Ejemplos reales:

```json
{"status":400,"error":"Bad Request","code":"VALIDACION_FALLIDA",
 "message":"Los datos enviados no son válidos.",
 "path":"/api/v1/clientes",
 "fieldErrors":[{"field":"nombre","message":"El nombre es obligatorio"}]}
```

```json
{"status":400,"error":"Bad Request","code":"CAMPO_ORDEN_INVALIDO",
 "message":"El campo de ordenamiento 'estado' no es válido.",
 "path":"/api/v1/clientes","fieldErrors":[]}
```

```json
{"status":400,"error":"Bad Request","code":"PARAMETRO_INVALIDO",
 "message":"El parámetro 'size' debe estar entre 1 y 100.",
 "path":"/api/v1/clientes","fieldErrors":[]}
```

```json
{"status":404,"error":"Not Found","code":"RECURSO_NO_ENCONTRADO",
 "message":"No se encontró el cliente con id 9999.",
 "path":"/api/v1/clientes/9999","fieldErrors":[]}
```

```json
{"status":405,"error":"Method Not Allowed","code":"METODO_NO_PERMITIDO",
 "message":"El método 'DELETE' no está permitido para este recurso.",
 "path":"/api/v1/clientes/1","fieldErrors":[]}
```

```json
{"status":403,"error":"Forbidden","code":"SIN_PERMISO",
 "message":"No tiene permisos para realizar esta acción.",
 "path":"/api/v1/clientes","fieldErrors":[]}
```

```json
{"status":403,"error":"Forbidden","code":"USUARIO_INACTIVO",
 "message":"Su usuario está desactivado. Contacte al administrador.",
 "path":"/api/v1/clientes","fieldErrors":[]}
```

```json
{"status":409,"error":"Conflict","code":"CLIENTE_CON_PEDIDOS_ACTIVOS",
 "message":"El cliente tiene pedidos en curso y no se puede desactivar. Desactive o cancele los pedidos pendientes primero.",
 "path":"/api/v1/clientes/2/estado","fieldErrors":[]}
```

Formato completo del cuerpo de error: `../../../PFDocs/00-base/04-CONTRATO-API.md` §5.

> `PFDocs` es un repositorio git **separado** del backend. La ruta relativa de arriba solo
> resuelve si ambos repos están clonados como hermanos. En GitHub, busca el documento en el
> repositorio **PFDocs**: `00-base/04-CONTRATO-API.md` (§5 · Formato de error).

## Casos límite

- **`contacto` y `telefono` SÍ aparecen como `null`.** El módulo de clientes usa
  `@JsonInclude(ALWAYS)` en su DTO, así que un cliente sin contacto trae `"contacto": null`
  explícito. Esto es **distinto** de la bitácora, donde los nulos **desaparecen** (el
  `usuario` de una acción del sistema viene ausente). El frontend no debe aplicar la misma
  regla en los dos módulos: en clientes `fila.contacto === null` es `true`; en bitácora el
  campo ni está.
- **El `PUT` borra, no conserva.** Un `contacto` o un `telefono` en `null` en el cuerpo
  **borra** el valor anterior. El frontend debe enviar siempre los tres campos del
  formulario; mandar solo el nombre dejaría al cliente sin contacto ni teléfono.
- **El filtro por nombre es de PREFIJO, no de contenido.** `?nombre=Tien` encuentra «Tienda
  La Esquina» pero no «La Tienda Esquina». Es deliberado: es lo que el usuario espera de un
  buscador y lo único que aprovecha el índice de `clientes.nombre`.
- **El filtro trata `%` y `_` como texto literal.** `?nombre=%` no devuelve todos los
  registros: devuelve los que empiezan por un símbolo `%`, normalmente ninguno.
- **El `size` está acotado a 100.** `?size=101` → `400 PARAMETRO_INVALIDO`.
- **El `sort` solo admite `nombre` e `id`.** Cualquier otro campo → `400
  CAMPO_ORDEN_INVALIDO`. El valor se manda como `campo,asc` o `campo,desc`.
- **No hay `DELETE`.** Un `DELETE` a `/api/v1/clientes/{id}` devuelve `405
  METODO_NO_PERMITIDO`. Para dar de baja un cliente se usa
  `PATCH /api/v1/clientes/{id}/estado` con `{"activo": false}`.
- **Desactivar no cancela pedidos.** Un cliente inactivo conserva sus pedidos y no puede
  recibir pedidos nuevos. Si tiene pedidos en curso, el `PATCH` se rechaza con `409
  CLIENTE_CON_PEDIDOS_ACTIVOS`.
- **Nombres duplicados permitidos.** No hay `UNIQUE` sobre `nombre`: dos clientes pueden
  llamarse igual. Si el frontend quiere avisar, puede comparar con el listado por prefijo;
  el aviso es informativo, no bloqueante.
- **El estado se expone como booleano** (`"activo": true`), nunca como `0`/`1`.
- **Los textos se recortan y los blancos se guardan como `null`.** El `nombre` se guarda
  sin espacios al principio y al final, y conserva las mayúsculas que escribió el usuario; un
  `contacto` o un `telefono` que solo tenga espacios se guarda como `null`. La búsqueda, en
  cambio, no distingue mayúsculas.

## Limitaciones conocidas

- **La regla `CLIENTE_CON_PEDIDOS_ACTIVOS` es configurable** en el backend
  (`app.clientes.bloquear-desactivacion-con-pedidos`). Si se desactiva, el `PATCH` responde
  `200` y el frontend no verá nunca el `409`. El frontend debe tratar el `409` como un caso
  posible, no como algo que siempre ocurre.
- **La consulta que cuenta los pedidos en curso es SQL directo sobre la tabla `pedidos`**
  porque el módulo de pedidos (HU-16) todavía no existe. Cuando exista, migrará al
  repositorio de pedidos. No cambia el contrato HTTP.
- **El módulo no expone ningún endpoint para detectar nombres duplicados**: el frontend lo
  resuelve con el listado por prefijo.
