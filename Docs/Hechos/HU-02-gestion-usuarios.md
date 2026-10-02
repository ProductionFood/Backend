# HU-02 — Gestión de usuarios · HECHA

| | |
|---|---|
| Fecha de entrega | 2026-10-01 |
| Commits / PR | rama `feat/HU-02-T1` (17 commits, bloques A1–A5, B1–B2, C1–C4, D1–D3, E1–E3); PRs por abrir |
| Colección Postman | `Docs/Postman/ProductionFood-HU02.postman_collection.json` (carpetas `Consultar`, `Editar`, `Estado`) |
| Estado QA | batería `../../../docs/HU-02/tarea-qa.md` (CP-01..CP-28 + SEC) pendiente con equipo QA |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| GET | `/api/v1/usuarios` | 200/400/401/403 | Listado paginado con búsqueda por prefijo, filtros `idRol`/`activo` y orden por `nombre`/`correo`/`id` |
| GET | `/api/v1/usuarios/{id}` | 200/400/401/403/404 | Usuario con su rol en una sola query |
| PUT | `/api/v1/usuarios/{id}` | 200/400/401/403/404/409 | Editar nombre, correo y rol (CA-03) |
| PATCH | `/api/v1/usuarios/{id}/estado` | 200/400/401/403/404/409 | Activar/desactivar (CA-04) |
| DELETE | `/api/v1/usuarios/{id}` | 405 | No existe a propósito: borrado físico prohibido (CA-05) |

Los cuatro métodos nuevos exigen `@PreAuthorize("hasRole('ADMIN')")`.

Reglas de negocio implementadas:

- **Paginación**: `size` 1..100 y `page >= 0`; fuera de rango → `400 PARAMETRO_INVALIDO`
  (no se recorta). Orden por lista blanca (`nombre`, `correo`, `id→idUsuario`); cualquier
  otro campo → `400 CAMPO_ORDEN_INVALIDO`. Sin `sort` → `nombre,asc`.
- **Búsqueda por prefijo** con `\`, `%` y `_` escapados (comodín SQL literal), sobre
  `nombre` y `correo`, con `join fetch` del rol y `countQuery` con el mismo `where`.
- **R-02 (único admin)**: sola guarda `validarQuedaAdmin` con bloqueo pesimista
  (`SELECT ... FOR UPDATE`), compartida por `PUT` de rol y `PATCH` de estado. Protege
  también las desactivaciones cruzadas concurrentes.
- **R-03**: `PUT` no acepta `password` ni `activo` (no existen en el DTO; Jackson los
  ignora). CP-12 lo verifica end-to-end.
- **R-04**: unicidad de correo excluyendo el propio id (`existsByCorreoAndIdUsuarioNot`)
  más `saveAndFlush` + `DataIntegrityViolationException` para la carrera contra el `UNIQUE`.
- **R-06**: el rol y el estado del principal se reconstruyen desde la base en cada
  petición (`JwtAuthenticationFilter`); los cambios de rol/estado se reflejan de
  inmediato, sin esperar a que expire el JWT (CP-14, CP-21).
- **R-07**: la bitácora de operaciones queda diferida a HU-04, como anota el plan.

## Contrato resumido

Ejemplos reales (ejecución del 2026-10-01 sobre MySQL 8.4):

**GET /api/v1/usuarios?page=0&size=2 → 200**

```json
{"content":[{"idUsuario":1,"nombre":"Administrador Inicial","correo":"admin@productionfood.local","activo":true,"rol":{"idRol":1,"nombre":"ADMIN"}},{"idUsuario":2,"nombre":"Blanco Test Editado","correo":"blanco-test@productionfood.local","activo":true,"rol":{"idRol":4,"nombre":"VENTAS"}}],"page":0,"size":2,"totalElements":16,"totalPages":8,"first":true,"last":false}
```

**PUT /api/v1/usuarios/2 → 200**

```json
{"idUsuario":2,"nombre":"Blanco Test Editado","correo":"blanco-test@productionfood.local","activo":true,"rol":{"idRol":4,"nombre":"VENTAS"}}
```

**GET /api/v1/usuarios/99999 → 404**

```json
{"status":404,"error":"Not Found","code":"RECURSO_NO_ENCONTRADO",
 "message":"No se encontró el usuario con id 99999.",
 "path":"/api/v1/usuarios/99999","fieldErrors":[]}
```

**GET /api/v1/usuarios?size=101 → 400**

```json
{"status":400,"error":"Bad Request","code":"PARAMETRO_INVALIDO",
 "message":"El parámetro 'size' debe estar entre 1 y 100.",
 "path":"/api/v1/usuarios","fieldErrors":[]}
```

**DELETE /api/v1/usuarios/1 → 405 (CA-05)**

```json
{"status":405,"error":"Method Not Allowed","code":"METODO_NO_PERMITIDO",
 "message":"El método 'DELETE' no está permitido para este recurso.",
 "path":"/api/v1/usuarios/1","fieldErrors":[]}
```

**PUT /api/v1/usuarios/1 cambiando el propio rol → 409 (CP-20)**

```json
{"status":409,"error":"Conflict","code":"AUTO_DEGRADACION",
 "message":"No puede cambiar su propio rol.",
 "path":"/api/v1/usuarios/1","fieldErrors":[]}
```

**PATCH /api/v1/usuarios/1/estado desactivándose → 409 (CP-15)**

```json
{"status":409,"error":"Conflict","code":"AUTO_DESACTIVACION",
 "message":"No puede desactivar su propio usuario.",
 "path":"/api/v1/usuarios/1/estado","fieldErrors":[]}
```

**PUT /api/v1/usuarios/1 con datos inválidos → 400**

```json
{"status":400,"error":"Bad Request","code":"VALIDACION_FALLIDA",
 "message":"Los datos enviados no son válidos.",
 "path":"/api/v1/usuarios/1",
 "fieldErrors":[{"field":"correo","message":"Debe ser un correo electrónico válido"},{"field":"idRol","message":"El rol es obligatorio"},{"field":"nombre","message":"El nombre es obligatorio"}]}
```

## Evidencia (verificación backend)

- `mvn test`: **67/67** en verde (7 suites, 2026-10-01).
- newman `ProductionFood-HU02.postman_collection.json`: **29 requests, 66 asserts, 0 fallos**
  (Consultar + Editar + Estado: CP-01..CP-12, CP-13..CP-15, CP-17, CP-20, CA-05).
- newman `ProductionFood-HU01.postman_collection.json`: **7 requests, 12 asserts, 0 fallos**
  (sin regresiones desde HU-01).
- Verificación en runtime con `curl` sobre la app levantada: los ejemplos anteriores.

## Desviaciones vs especificación

- `PARAMETRO_INVALIDO`, `CAMPO_ORDEN_INVALIDO` y `METODO_NO_PERMITIDO` no figuran en la
  tabla de códigos de `04-CONTRATO-API.md` §4–§5; los introduce el plan de esta HU y
  quedan como deuda de documentación transversal.
- **CP-19 y CP-16 son inalcanzables vía API en modo secuencial**: para ejecutar `PUT`/
  `PATCH` sobre el único admin activo, el solicitante tendría que ser ese mismo admin, y
  `AUTO_DEGRADACION`/`AUTO_DESACTIVACION` responde primero. El bloqueo pesimista existe
  precisamente para el caso concurrente real (dos admins desactivándose en cruce), que
  está cubierto por la prueba unitaria; el propio plan contempla «o vía BD» para CP-16.

## Pendientes / deuda

- Batería de QA (`../../../docs/HU-02/tarea-qa.md` CP-01..CP-28 + SEC): la corre el
  equipo de QA, no el Backend.
- Operaciones auditadas en bitácora: diferido a HU-04 (R-07), como anota el plan.
- `UsuarioService.crear` (HU-01) captura `DuplicateKeyException`, pero Hibernate puede
  lanzar `DataIntegrityViolationException` en la carrera; el plan lo deja para un commit
  aparte con test demostratorio (misma técnica ya aplicada en `actualizar`).
