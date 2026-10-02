# HU-02 — Gestión de usuarios · Manual de consumo

> **Estado: entregado.** Endpoints de consulta, edición y estado de usuarios; requiere
> rol `ADMIN`. La creación de usuarios es HU-01 (`HU-01-registro-usuarios.md`).

## Autenticación

Igual que HU-01: JWT con rol `ADMIN` en el header de cada request.

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "correo": "admin@productionfood.local", "password": "Admin123*" }
```

```http
Authorization: Bearer {{token}}
```

En Postman, la carpeta `00 - Setup` de
`ProductionFood-HU02.postman_collection.json` guarda el token en la variable `token`.

## Endpoints

| Método | Ruta | Requiere | Códigos |
|---|---|---|---|
| GET | `/api/v1/usuarios` | rol ADMIN | 200, 400, 401, 403 |
| GET | `/api/v1/usuarios/{id}` | rol ADMIN | 200, 400, 401, 403, 404 |
| PUT | `/api/v1/usuarios/{id}` | rol ADMIN | 200, 400, 401, 403, 404, 409 |
| PATCH | `/api/v1/usuarios/{id}/estado` | rol ADMIN | 200, 400, 401, 403, 404, 409 |
| DELETE | `/api/v1/usuarios/{id}` | — | 405 (no usar; CA-05) |

### GET /api/v1/usuarios — listado

Parámetros query (todos opcionales):

| Parámetro | Valores | Default | Error |
|---|---|---|---|
| `busqueda` | prefijo de nombre o correo (literal: `%`/`_` no son comodines) | — | — |
| `idRol` | id de rol existente (1 ADMIN, 2 PRODUCCION, 3 COMPRAS, 4 VENTAS, 5 CONSULTA) | — | — |
| `activo` | `true` / `false` | — | — |
| `page` | >= 0 | 0 | 400 `PARAMETRO_INVALIDO` |
| `size` | 1..100 | 20 | 400 `PARAMETRO_INVALIDO` (no se recorta) |
| `sort` | `nombre`, `correo`, `id` + `,asc`/`,desc` | `nombre,asc` | 400 `CAMPO_ORDEN_INVALIDO` |

```http
GET /api/v1/usuarios?busqueda=blanco&idRol=4&activo=true&sort=nombre,asc
```

```json
{"content":[{"idUsuario":2,"nombre":"Blanco Test Editado","correo":"blanco-test@productionfood.local","activo":true,"rol":{"idRol":4,"nombre":"VENTAS"}}],"page":0,"size":20,"totalElements":1,"totalPages":1,"first":true,"last":true}
```

La respuesta es `PageResponse` (§4 de `04-CONTRATO-API.md`); `content` nunca incluye
`password` ni `estado` (el campo de estado es `activo`, boolean).

### GET /api/v1/usuarios/{id} — detalle

```json
{"idUsuario":2,"nombre":"Blanco Test Editado","correo":"blanco-test@productionfood.local","activo":true,"rol":{"idRol":4,"nombre":"VENTAS"}}
```

- `404 RECURSO_NO_ENCONTRADO` si el id no existe.
- `400 PARAMETRO_INVALIDO` si el id no es numérico.

### PUT /api/v1/usuarios/{id} — editar

```http
PUT /api/v1/usuarios/2
Content-Type: application/json

{
  "nombre": "Blanco Test Editado",
  "correo": "blanco-test@productionfood.local",
  "idRol": 4
}
```

Solo existen esos tres campos en el cuerpo: `password` y `activo`, si llegan, **se
ignoran** (la contraseña y el estado no cambian por este endpoint).

Validaciones: `nombre` obligatorio máx 100; `correo` obligatorio, formato válido, máx
100; `idRol` obligatorio y existente.

### PATCH /api/v1/usuarios/{id}/estado — activar/desactivar

```http
PATCH /api/v1/usuarios/2/estado
Content-Type: application/json

{ "activo": false }
```

Idempotente: desactivar un usuario ya inactivo (o activar uno ya activo) responde 200.

## Errores

| Código HTTP | `code` | Cuándo | Qué hace el frontend |
|---|---|---|---|
| 400 | `VALIDACION_FALLIDA` | Reglas de campo incumplidas | Marcar campos con `fieldErrors` |
| 400 | `PARAMETRO_INVALIDO` | `page`/`size` fuera de rango, id no numérico | Corregir el parámetro, no reintentar |
| 400 | `CAMPO_ORDEN_INVALIDO` | `sort` con campo no permitido | Quitar el sort o usar uno de la lista |
| 401 | `NO_AUTENTICADO` | Sin token o expirado | Volver a login |
| 403 | `SIN_PERMISO` | Rol distinto de ADMIN | Bloquear la vista |
| 403 | `USUARIO_INACTIVO` | Token de usuario desactivado (CP-14) | Cerrar sesión, mensaje «usuario desactivado» |
| 404 | `RECURSO_NO_ENCONTRADO` | Id inexistente | Mostrar «no encontrado» |
| 405 | `METODO_NO_PERMITIDO` | `DELETE` u otro método no soportado | No enviar; el borrado físico no existe (CA-05) |
| 409 | `CORREO_DUPLICADO` | Correo de otro usuario en `PUT` | Aviso en el campo correo |
| 409 | `ROL_INEXISTENTE` | `idRol` inexistente | Refrescar el catálogo de roles |
| 409 | `AUTO_DEGRADACION` | `PUT` cambiando el propio rol (CP-20) | No ofrecer cambiar el rol propio |
| 409 | `ULTIMO_ADMIN` | Dejar al sistema sin admin activo (CP-16/CP-19) | Avisar: debe quedar al menos un ADMIN |
| 409 | `AUTO_DESACTIVACION` | `PATCH` desactivándose a uno mismo (CP-15) | No ofrecer desactivar la propia cuenta |

## Casos límite

- Los cambios de rol y estado se reflejan **en la siguiente petición** (R-06): el backend
  reconstruye el principal desde la base; no hace falta esperar a que expire el token.
- Un usuario desactivado con token vigente recibe `403 USUARIO_INACTIVO` de inmediato.
- El correo se normaliza a minúsculas y sin espacios antes de comparar; la colación
  `utf8mb4_0900_ai_ci` lo hace además insensible a mayúsculas y tildes.
- `password` no se envía en el `PUT` y jamás aparece en ninguna respuesta.

## Limitaciones conocidas

- `CP-19`/`CP-16` (dejar sin admin vía API en modo secuencial) no son alcanzables:
  `AUTO_DEGRADACION`/`AUTO_DESACTIVACION` responde primero; la guarda cubre el caso
  concurrente. Ver `../../Hechos/HU-02-gestion-usuarios.md`.
- La bitácora de operaciones no registra estos cambios aún (HU-04).
