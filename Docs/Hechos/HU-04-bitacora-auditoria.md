# HU-04 — Bitácora de auditoría · HECHA

| | |
|---|---|
| Fecha de entrega | 2026-10-12 |
| Commits / PR | PRs #22–#28 (cadena `HU-04/*`) + fix transversal de 404; ver «Entregado en» |
| Colección Postman | `Docs/Postman/ProductionFood-HU04.postman_collection.json` (`00 - Setup`, `Consulta`, `Auditoría de escrituras`, `Errores`, `Autorización`) |
| Estado QA | pendiente con equipo de QA; batería de humo propia **63/63** (ver «Evidencia») |

## Qué se construyó

Componente **transversal**: se implementa una vez y lo activan los servicios anotándolo.

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| GET | `/api/v1/bitacora` | 200/400/401/403 | Consulta con filtros y paginación (ADMIN) |
| GET | `/api/v1/bitacora/acciones` | 200/401/403 | Catálogo de acciones registradas (ADMIN) |

Piezas internas:

| Pieza | Rol |
|---|---|
| `Auditable` | Anotación de método (`accion`, `tabla`, `detalle` SpEL opcional) |
| `AuditoriaAspect` | `@AfterReturning` sobre `@annotation(auditable)`; resuelve el detalle y llama al servicio |
| `BitacoraService` | `registrar()` (con `REQUIRES_NEW`, recorte a 255, fallo absorbido) + `listar()` / `listarAcciones()` |
| `BitacoraRepository` | JDBC (`NamedParameterJdbcTemplate`), `LEFT JOIN usuarios` |
| `BitacoraController` | Los dos `GET`, `@PreAuthorize("hasRole('ADMIN')")` |

Se escribió **una** bitácora: la tabla `bitacora` ya existía desde el esquema base; no hubo
migración nueva (V2 ya la dejó nullable y con índice).

## Reglas de negocio implementadas

- **R-01 · `id_usuario` nulo = sistema.** `NULL` para login fallido sin identidad probada y
  para la semilla. Al filtrar/leer, el autor se omite en JSON (política `non_null` global).
- **R-02 · se audita después del éxito.** `@AfterReturning`, no `@Before`: auditar antes
  registraría cambios que después fallaron y se revirtieron. Hay una prueba con un proxy
  real de AspectJ que verifica que un método que lanza **no** deja registro.
- **R-03 · un fallo de auditoría no tumba el negocio.** `BitacoraService.registrar` atrapa y
  loguea sin relanzar; `REQUIRES_NEW` es lo que lo hace real (sin ella, un fallo del `INSERT`
  marcaría la transacción de negocio como `rollback-only`). El aspecto y `AuthService`
  añaden su propio `catch` como red para la excepción que Spring lanza al cerrar el proxy.
- **R-04 · recorte de `detalle` a 255.** Se recorta **antes** del `INSERT`: con `sql_mode`
  estricto, un texto más largo haría *fallar* el `INSERT` y —por R-03— la acción quedaría sin
  auditar justo en los casos más ricos en detalle.
- **R-05 · sin datos sensibles.** El detalle lleva correo y rol, nunca contraseña/hash/token.
  El frontend lo ve en `Docs/Manuales_Consumo_Frontend/HU-04-bitacora-auditoria.md`.
- **R-06 · acciones auditadas.** `CREAR`, `EDITAR`, `CAMBIAR_ESTADO` (altas/ediciones de
  usuarios) y `LOGIN` / `LOGIN_FALLIDO`. Se anotó solo `UsuarioService` (único servicio con
  escrituras en este alcance); `RolService` solo lee.

## Contrato resumido

Ejemplos reales (ejecución del 2026-10-12 sobre el backend local, `SERVER_PORT=9211`):

**GET /api/v1/bitacora (ADMIN) → 200**

```json
{
  "content": [
    { "idBitacora": 23,
      "usuario": { "idUsuario": 1, "nombre": "Administrador Inicial" },
      "accion": "LOGIN", "tablaAfectada": "usuarios",
      "detalle": "Inicio de sesión: admin@productionfood.local",
      "fecha": "2026-10-12T17:42:29" },
    { "idBitacora": 1, "accion": "SEED", "tablaAfectada": "usuarios",
      "detalle": "Creación del administrador inicial por script de semilla V3",
      "fecha": "2026-10-12T17:48:47" }
  ],
  "page": 0, "size": 2, "totalElements": 23, "totalPages": 12,
  "first": true, "last": false
}
```

La segunda fila **no trae `usuario`**: es una acción del sistema (`id_usuario NULL`).

**GET /api/v1/bitacora/acciones (ADMIN) → 200**

```json
["CAMBIAR_ESTADO","CREAR","LOGIN","LOGIN_FALLIDO","SEED"]
```

**400 · rango invertido**

```json
{"status":400,"error":"Bad Request","code":"RANGO_FECHAS_INVALIDO",
 "message":"La fecha de inicio no puede ser posterior a la fecha de fin.",
 "path":"/api/v1/bitacora","fieldErrors":[]}
```

**403 · rol distinto de ADMIN**

```json
{"status":403,"error":"Forbidden","code":"SIN_PERMISO",
 "message":"No tiene permisos para realizar esta acción.",
 "path":"/api/v1/bitacora","fieldErrors":[]}
```

**405 · escritura denegada (CA-04)**

```json
{"status":405,"error":"Method Not Allowed","code":"METODO_NO_PERMITIDO",
 "message":"El método 'POST' no está permitido para este recurso.",
 "path":"/api/v1/bitacora","fieldErrors":[]}
```

## Criterios de aceptación cubiertos

| CA | Enunciado | Dónde |
|---|---|---|
| CA-01 | Se registra automáticamente al crear/editar/eliminar | Aspecto AOP sobre `@Auditable`; verificado creando un usuario y encontrando su fila `CREAR` |
| CA-02 | Se guardan `id_usuario`, `accion`, `tabla_afectada`, `detalle`, `fecha` | Tabla `bitacora` sin cambios de columnas |
| CA-03 | `fecha` por defecto `current_timestamp()` | El `INSERT` no envía `fecha`; la aporta `DEFAULT CURRENT_TIMESTAMP` |
| CA-04 | La bitácora es de solo lectura | Solo hay `GET`; `POST`/`DELETE` → 405 |

## Evidencia (verificación backend)

- **Suite unitaria integrada:** `mvn -o test` → **132/132 en verde** (todo HU-04 + el fix de
  HU-04 transversal), tras mergear los siete PRs en una rama de integración local.
- **Batería de humo con `curl` contra el backend arrancado** (MySQL real, Flyway, `9211`):
  **63/63 comprobaciones en verde**. Cubre: login (200/401), no regresión de `/perfil`,
  creación auditada (aspecto), los dos `GET` de bitácora, los cuatro filtros, rango inclusivo
  de un solo día, paginación y coherencia de `totalPages`, `RANGO_FECHAS_INVALIDO`,
  `CAMPO_ORDEN_INVALIDO`, `PARAMETRO_INVALIDO` (size y page), fecha mal formada, `401`, `403`
  con rol `VENTAS`, `405` en `POST`, `404` en ruta inexistente, y las dos atribuciones de
  `LOGIN_FALLIDO` (sistema vs. usuario inactivo con id real).
- **Verificación R-05 en runtime:** el `detalle` del `CREAR` contiene el correo y el rol del
  usuario creado y **no** la contraseña enviada.
- **Sin test de integración automatizado contra MySQL.** El `LEFT JOIN`, el alias
  `usuario_nombre` y el `LIMIT/OFFSET` con parámetros nombrados quedan verificados por
  compilación, por diseño y por la batería `curl` manual, no por un test de suite.

## Entregado en

Unidad por unidad (cada PR escrito por un subagente y revisado antes de abrirse):

| PR | Unidad | Contenido |
|---|---|---|
| #22 | Scaffolding | `Auditable`, `Bitacora`, `BitacoraRowMapper`, `BitacoraRepository.insertar`, `spring-boot-starter-aspectj` |
| #23 | Servicio | `BitacoraService.registrar` (recorte, R-03) |
| #24 | Aspecto | `AuditoriaAspect` + pruebas con proxy AspectJ; corrección del ejemplo SpEL del JavaDoc |
| #25 | Consulta (datos) | `BitacoraFiltros`, `buscar/contar/listarAcciones`, DTO, `listar` |
| #26 | Consulta (HTTP) | `BitacoraController` + Swagger |
| #27 | Anotaciones | `@Auditable` en `UsuarioService` + guarda de R-05 |
| #28 | Login | `LOGIN` / `LOGIN_FALLIDO` en `AuthService` |
| fix | Transversal | `404 RECURSO_NO_ENCONTRADO` en rutas sin controlador (ver «Desviaciones») |

## Desviaciones vs especificación

- **`spring-boot-starter-aop` no existe en Spring Boot 4.** La `tarea-backend.md` lo pide con
  ese nombre; el BOM 4.1.1 lo renombró a **`spring-boot-starter-aspectj`**, que es el añadido.
- **`ACCESO_DENEGADO_BITACORA` no se implementó.** La especificación §5 define ese `403`
  específico; se usa el código transversal **`SIN_PERMISO`**, que es el que ya devuelve toda
  la API para un rol insuficiente. Decisión acordada: añadir un código solo para el mensaje
  no aportaba y rompía la uniformidad que el frontend ya maneja.
- **`usuario` se omite en JSON, no viene `null`.** La especificación muestra
  `"usuario": null`; la configuración global `default-property-inclusion: non_null` elimina
  los campos nulos (afecta a toda la API). El manual describe cómo debe leerlo el frontend.
- **Se retiró un campo `idRol` especulativo** del modelo `Bitacora` que se había añadido en el
  scaffolding y que ni el contrato ni el diseño consumían.
- **El offset de paginación se calcula en `long`.** `page * size` como `int` desbordaba con un
  `page` grande y llevaba un `OFFSET` inválido al SQL (500 en vez de página vacía).

## Fix transversal incluido

Durante la batería `curl` apareció que **una ruta sin controlador respondía `500`** con una
sesión válida: `NoResourceFoundException` caía en el `@ExceptionHandler(Exception.class)`
genérico. No era de HU-04 (el handler es anterior), pero impedía cerrar con la API
respondiendo bien. Se corrigió en su propia unidad: `404 RECURSO_NO_ENCONTRADO`, con prueba.
Con token ausente o inválido no se veía porque la cadena de seguridad corta antes con `401`.

## Pendientes / deuda

- Batería de QA formal (`tarea-qa.md`) y corrida de la colección con `newman`: las ejecuta QA.
- **La auditoría se difiere para el resto de módulos.** Compras, pedidos, devoluciones… se
  auditan al escribir sus servicios (basta anotar `@Auditable`). Hoy solo está `UsuarioService`.
- **Ventana de rollback posterior** (documentada en `AuditoriaAspect`): una escritura que
  revierte al cerrar la transacción de negocio deja fila en la bitácora. Aceptado; el kardex
  es la fuente transaccional del inventario.
- **La bitácora puede tener huecos** por R-03 (fallo de auditoría absorbido). Aceptado.
- Bloqueo por intentos fallidos de login: sigue pendiente y es de HU-03, no de aquí.
