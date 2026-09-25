# HU-01 — Registro de usuarios · HECHA

| | |
|---|---|
| Fecha de entrega | 2026-09-25 |
| Commits / PR | #1–#8 (todos mergeados en `main`) |
| Colección Postman | `Docs/Postman/ProductionFood-HU01.postman_collection.json` |
| Estado QA | batería `tarea-qa.md` (CP-01..CP-15 + SEC-01..05) pendiente con equipo QA |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| POST | `/api/v1/auth/login` | 200/400/409 | Login, emite JWT Bearer con vigencia de 1 hora |
| POST | `/api/v1/usuarios` | 201/400/409 | Crear usuario (requiere ADMIN) |
| GET | `/api/v1/roles` | 200/401/403 | Listar roles (requiere ADMIN) |

Reglas de negocio implementadas:

- Correo y nombre se normalizan con `trim`; el correo se guarda y se busca en minúsculas.
- Contraseña con hash BCrypt; nunca se devuelve en ninguna respuesta.
- El usuario nace con `activo: true`.
- Login con correo desconocido, contraseña incorrecta **o** usuario inactivo devuelve el
  mismo 409 `CREDENCIALES_INVALIDAS` (no revela cuál de los tres falló).
- JWT HS384 con vigencia de 1 h (`app.jwt.expiration-ms=3600000`); sin refresh token
  (alcance de HU-01 según `docs/00-base/04-CONTRATO-API.md` §2).
- Contrato de errores de seguridad: 401 `NO_AUTENTICADO`, 403 `SIN_PERMISO` y
  403 `USUARIO_INACTIVO` con los mensajes oficiales de `03-MATRIZ-ROLES.md`.
- Esquema gestionado por Flyway (`V1`–`V3`) con `ddl-auto: validate`; la app migra una
  base de datos en blanco al arrancar.

## Contrato resumido

Ejemplos reales (ejecución del 2026-09-25, token truncado):

**POST /api/v1/auth/login → 200**

```json
{
  "token": "eyJhbGciOiJIUzM4NCJ9…[truncado]",
  "tipo": "Bearer",
  "expiresIn": 3600,
  "correo": "admin@productionfood.local",
  "nombre": "Administrador Inicial",
  "rol": "ADMIN"
}
```

**POST /api/v1/usuarios → 201**

```json
{"idUsuario": 9, "nombre": "Hechos Doc", "correo": "hechos.doc@productionfood.local",
 "activo": true, "rol": {"idRol": 5, "nombre": "CONSULTA"}}
```

**POST /api/v1/usuarios → 409 (correo repetido)**

```json
{"status": 409, "code": "CORREO_DUPLICADO",
 "message": "Ya existe un usuario registrado con el correo indicado.",
 "path": "/api/v1/usuarios", "fieldErrors": []}
```

**POST /api/v1/usuarios → 400 (validación)**

```json
{"status": 400, "code": "VALIDACION_FALLIDA",
 "message": "Los datos enviados no son válidos.",
 "fieldErrors": [
   {"field": "nombre", "message": "El nombre es obligatorio"},
   {"field": "password", "message": "La contraseña debe tener entre 8 y 72 caracteres"},
   {"field": "correo", "message": "Debe ser un correo electrónico válido"}]}
```

**GET /api/v1/roles sin token → 401**

```json
{"status": 401, "code": "NO_AUTENTICADO",
 "message": "Su sesión ha expirado. Inicie sesión nuevamente.",
 "path": "/api/v1/roles", "fieldErrors": []}
```

**GET /api/v1/roles con rol VENTAS → 403**

```json
{"status": 403, "code": "SIN_PERMISO",
 "message": "No tiene permisos para realizar esta acción.",
 "path": "/api/v1/roles", "fieldErrors": []}
```

**GET /api/v1/roles con token de usuario dado de baja → 403**

```json
{"status": 403, "code": "USUARIO_INACTIVO",
 "message": "Su usuario está desactivado. Contacte al administrador.",
 "path": "/api/v1/roles", "fieldErrors": []}
```

## Evidencia (verificación backend)

- `mvn test`: **33/33** en verde (6 suites, 2026-09-25).
- newman `ProductionFood-HU01.postman_collection.json`: **7/7 requests, 12/12 asserts, 0 fallos**.
- Verificación en runtime con `curl` sobre la app levantada:
  - sin token → 401 `NO_AUTENTICADO`; token inválido → 401 `NO_AUTENTICADO`.
  - token de usuario VENTAS sobre `/roles` → 403 `SIN_PERMISO`.
  - baja de usuario (estado=0) con token vigente → 403 `USUARIO_INACTIVO`.
  - admin sobre `/roles` → 200 (regresión).
- Fases A–C y §5 verificadas en `../../plans/Reestructuracion-HU-01.md` (PR #6).

## Desviaciones vs especificación

- En alcance: ninguna. El código previo respondía 403 vacío ante falta de token (entry
  point por defecto de Spring Security) y seguía aceptando tokens de usuarios dados de
  baja; se corrigió al contrato de `04-CONTRATO-API.md` §6 con PR #8.

## Pendientes / deuda

- Batería de QA (`tarea-qa.md` CP-01..CP-15 + SEC-01..05): la corre el equipo de QA,
  no el Backend.
- Refresh token, logout server-side, corte por inactividad y revocación de sesiones:
  fuera del alcance de HU-01 (`04-CONTRATO-API.md` §2); requiere HU nueva con cambio
  de contrato.
- Endpoint para dar de baja/activar usuarios (PATCH estado): aún no existe; la baja se
  hace desde la BD hasta que se entregue HU-03.
