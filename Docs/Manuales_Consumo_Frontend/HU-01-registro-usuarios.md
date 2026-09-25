# HU-01 — Registro de usuarios · Manual de consumo

> **Estado: en curso.** El endpoint de creación tiene un defecto abierto (409 falso) —
> ver [Limitaciones conocidas](#limitaciones-conocidas) y `../../plans/Reestructuracion-HU-01.md`.

## Autenticación

El módulo requiere JWT con rol `ADMIN`:

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "correo": "admin@productionfood.local", "password": "Admin123*" }
```

```json
{ "token": "eyJhbGciOi...", "nombre": "Administrador Inicial", "correo": "admin@productionfood.local" }
```

Todo request autenticado lleva el header:

```http
Authorization: Bearer {{token}}
```

El token vence en 1 hora (`app.jwt.expiration-ms: 3600000`). En Postman, la carpeta
`00 - Setup` guarda el token automáticamente en la variable `token`.

## Endpoints

| Método | Ruta | Requiere | Códigos |
|---|---|---|---|
| POST | `/api/v1/auth/login` | público | 200, 401 |
| POST | `/api/v1/usuarios` | rol ADMIN | 201, 400, 403, 409 |
| GET | `/api/v1/roles` | rol ADMIN | 200, 401, 403 |

### POST /api/v1/usuarios — crear usuario

```http
POST /api/v1/usuarios
Authorization: Bearer {{token}}
Content-Type: application/json

{
  "nombre": "María Gómez",
  "correo": "maria@productionfood.local",
  "password": "Clave2026*",
  "idRol": 4
}
```

Respuesta `201 Created` con header `Location: /api/v1/usuarios/{id}`:

```json
{
  "idUsuario": 2,
  "nombre": "María Gómez",
  "correo": "maria@productionfood.local",
  "estado": true,
  "rol": { "idRol": 4, "nombre": "VENTAS" }
}
```

Validaciones del request (si fallan → `400 VALIDACION_FALLIDA` con `fieldErrors`):

| Campo | Regla |
|---|---|
| `nombre` | obligatorio, máx 100 |
| `correo` | obligatorio, formato válido, máx 100 |
| `password` | obligatoria, 8–72 caracteres |
| `idRol` | obligatorio, debe existir en `roles` |

### GET /api/v1/roles — listar roles

```json
[
  { "idRol": 1, "nombre": "ADMIN", "descripcion": "Administrador del sistema. Acceso total." },
  { "idRol": 2, "nombre": "PRODUCCION", "descripcion": "..." }
]
```

Los ids de rol son fijos: 1 ADMIN, 2 PRODUCCION, 3 COMPRAS, 4 VENTAS, 5 CONSULTA
(semilla `V3`; no se reordenan).

## Errores

| Código HTTP | `code` | Cuándo | Qué hace el frontend |
|---|---|---|---|
| 400 | `VALIDACION_FALLIDA` | Reglas de campo incumplidas | Marcar campos con `fieldErrors` |
| 403 | (Spring Security) | Token válido pero sin rol ADMIN | Bloquear la vista, no reintentar |
| 409 | `CORREO_DUPLICADO` | Correo ya registrado | Mostrar aviso en el campo correo |
| 409 | `ROL_INEXISTENTE` | `idRol` no existe | Refrescar el catálogo de roles |
| 500 | `ERROR_INTERNO` | Error no controlado | Reintento genérico + log |

Formato completo del cuerpo de error: `../../../docs/00-base/04-CONTRATO-API.md` §5.

## Casos límite

- El correo se normaliza a minúsculas y sin espacios antes de comparar — el frontend puede
  enviar mayúsculas, pero conviene mostrarlo ya normalizado.
- La colación `utf8mb4_0900_ai_ci` hace la comparación de correo insensible a mayúsculas
  y tildes: `Admin@...` y `admin@...` son el mismo correo.
- Password nunca se devuelve en ninguna respuesta.

## Limitaciones conocidas

- **[ABIERTO] 409 falso al crear usuario**: `UsuarioService.crear` no asigna `estado`,
  la inserción falla con `Column 'estado' cannot be null` y el `catch` lo re-etiqueta como
  `CORREO_DUPLICADO`. Cualquier creación falla, aunque el correo sea nuevo.
  Diagnóstico completo y plan: `../../plans/Reestructuracion-HU-01.md`.
- La colección Postman aún tiene el correo de prueba hardcodeado (corridas repetidas
  colisionan); pendiente de parametrizar con `{{correo}}`.
