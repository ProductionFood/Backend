# HU-07 — Unidades de medida · HECHA (backend)

| | |
|---|---|
| Fecha de entrega | 2026-10-20 |
| Commits / PR | PRs #47–#56 (cadena `HU-07/pr*` sobre la rama `HU-07/gestion-unidades`); ver «Entregado en» |
| Colección Postman | `Docs/Postman/ProductionFood-HU07.postman_collection.json` (`Preparación`, `CRUD (CP-01…CP-17)`, `Roles (matriz)`, `Seguridad (SEC-01…05)`) — 50 peticiones |
| Estado QA | pendiente con el equipo de QA |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| GET | `/api/v1/unidades-medida` | 200/401/403 | Listar el catálogo completo (array plano, sin paginación) |
| GET | `/api/v1/unidades-medida/{id}` | 200/400/401/403/404 | Obtener una unidad con su conteo de usos |
| POST | `/api/v1/unidades-medida` | 201/400/401/403/409 | Registrar una unidad |
| PUT | `/api/v1/unidades-medida/{id}` | 200/400/401/403/404/409 | Editar una unidad |

**No existe `DELETE`** (R-03): `DELETE /api/v1/unidades-medida/{id}` responde
`405 METODO_NO_PERMITIDO`. Tampoco hay `PATCH` de estado, porque la tabla `unidades_medida`
no tiene columna `estado`: una unidad creada por error se corrige editándola.

**Roles.** Lectura: los cinco roles autenticados (`ADMIN`, `PRODUCCION`, `COMPRAS`, `VENTAS`,
`CONSULTA`), porque los roles operativos necesitan el catálogo para poblar los selectores de
unidades de sus formularios. Escritura: solo `ADMIN`.

Piezas internas:

| Pieza | Rol |
|---|---|
| `UnidadMedida` | Entidad JPA sobre la tabla `unidades_medida` (`id_unidad`, `nombre`, `abreviatura`; sin columna `estado`) |
| `UnidadMedidaRepository` | Spring Data; 2 consultas nativas con los conteos de uso y 2 métodos derivados de unicidad de abreviatura |
| `UnidadMedidaService` | Reglas, recorte de texto, unicidad de abreviatura y anotaciones `@Auditable` |
| `UnidadMedidaController` | Los cuatro endpoints con `@PreAuthorize` |
| `UnidadMedidaConUsos` | Proyección de interfaz para la consulta nativa con los conteos |
| DTOs | `CrearUnidadRequest`, `ActualizarUnidadRequest`, `UnidadMedidaResponse` y `UnidadEnUsoResponse` (records) |

Sin migración nueva: la tabla viene de V1 y las 9 unidades de ejemplo las siembra V3.

## Reglas de negocio implementadas

- **CA-01 · nombre y abreviatura obligatorios, máx. 50.** `@NotBlank` + `@Size(max = 50)` en
  `CrearUnidadRequest` y `ActualizarUnidadRequest`; si falta alguno, `400 VALIDACION_FALLIDA`
  con el campo marcado en `fieldErrors`. El texto se recorta con `trim`.
- **CA-02 · la abreviatura es única.** La columna tiene `UNIQUE KEY abreviatura` desde V1; el
  servicio lo comprueba antes de guardar y la violación de la base se traduce al mismo 409 de
  negocio (`DuplicateKeyException` atrapada en alta y en edición).
- **CA-03 · listar todas las unidades disponibles.** `GET /api/v1/unidades-medida` devuelve el
  catálogo completo, ordenado por nombre.
- **R-01 · sin paginación, y es deliberado.** Es la única excepción a la regla de paginación
  del contrato: el catálogo es pequeño y este endpoint alimenta los desplegables de HU-08,
  HU-11 y HU-12, donde una página incompleta obligaría a pedir más de una vez lo mismo.
- **R-02 · la abreviatura se normaliza antes de comparar.** Se recorta y **se guarda tal como
  se escribe**, respetando mayúsculas y minúsculas. La unicidad, en cambio, no distingue
  mayúsculas: la columna usa la collation `utf8mb4_0900_ai_ci`, así que `kg` y `KG` son la
  misma abreviatura y la segunda se rechaza con `409 ABREVIATURA_DUPLICADA`. El mensaje de
  error explica la collation, para que el rechazo se entienda aunque en pantalla los dos
  valores se vean distintos.
- **R-03 · nunca se elimina ni se desactiva.** No hay `DELETE` ni `PATCH` de estado: la tabla
  no tiene columna `estado` y `materias_primas`, `productos` y `recetas` la referencian con
  claves foráneas `ON DELETE NO ACTION`.
- **R-04 · cambiar la abreviatura de una unidad en uso exige confirmación explícita.** El
  servicio comprueba el conteo de referencias en las tres tablas que la usan: si la unidad está
  en uso y se cambia su abreviatura, la petición se rechaza con `409 UNIDAD_EN_USO` y el
  `message` lleva el conteo por tabla; para aplicar el cambio hay que repetir la misma
  petición con el parámetro de consulta `?confirmar=true`. Editar **solo el nombre** de una
  unidad en uso sí se permite, y una abreviatura que no cambia no puede reportarse como
  duplicada contra sí misma.
- **R-05 · catálogo inicial.** La semilla `V3__datos_semilla.sql` deja 9 unidades con ids 1..9:
  kg, g, L, mL, und, doc, paq, bol, bdj.

## Contrato resumido

**GET /api/v1/unidades-medida → 200** — array plano, **no** un `PageResponse`:

```json
[
  { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg",
    "usos": { "enMateriasPrimas": 12, "enProductos": 5, "enRecetas": 0, "total": 17 } },
  { "idUnidad": 2, "nombre": "Gramo", "abreviatura": "g",
    "usos": { "enMateriasPrimas": 8, "enProductos": 0, "enRecetas": 0, "total": 8 } }
]
```

Los conteos de `usos` de este ejemplo son **ilustrativos**, no una captura real: la base local
todavía no tiene filas en `materias_primas`, `productos` ni `recetas`, porque esas historias
todavía no están implementadas. La captura real contra el MySQL 8.4 local devuelve las 9
unidades de la semilla V3, ordenadas por nombre y con todos los conteos a cero:

```json
[
  { "idUnidad": 9, "nombre": "Bandeja", "abreviatura": "bdj",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } },
  { "idUnidad": 8, "nombre": "Bolsa", "abreviatura": "bol",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } },
  { "idUnidad": 6, "nombre": "Docena", "abreviatura": "doc",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } },
  { "idUnidad": 2, "nombre": "Gramo", "abreviatura": "g",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } },
  { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } },
  { "idUnidad": 3, "nombre": "Litro", "abreviatura": "L",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } },
  { "idUnidad": 4, "nombre": "Mililitro", "abreviatura": "mL",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } },
  { "idUnidad": 7, "nombre": "Paquete", "abreviatura": "paq",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } },
  { "idUnidad": 5, "nombre": "Unidad", "abreviatura": "und",
    "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } }
]
```

**POST /api/v1/unidades-medida → 201** (con cabecera `Location: /api/v1/unidades-medida/10`):

```json
// petición
{ "nombre": "Media docena", "abreviatura": "1/2doc" }
```

```json
// respuesta
{ "idUnidad": 10, "nombre": "Media docena", "abreviatura": "1/2doc",
  "usos": { "enMateriasPrimas": 0, "enProductos": 0, "enRecetas": 0, "total": 0 } }
```

**PUT /api/v1/unidades-medida/{id} → 200** — el cuerpo es un reemplazo puro de nombre y
abreviatura; la confirmación de R-04 viaja como parámetro de consulta (`?confirmar=true`), no
como campo del cuerpo.

**409 · abreviatura duplicada** (CP-07, CP-08, CP-09):

```json
{"timestamp":"2026-10-20T14:18:17.727302699","status":409,"error":"Conflict","code":"ABREVIATURA_DUPLICADA","message":"Ya existe una unidad de medida con la abreviatura 'KG'. Las abreviaturas no se repiten aunque cambien las mayúsculas o minúsculas: la comparación no distingue mayúsculas de minúsculas porque la columna abreviatura usa la collation utf8mb4_0900_ai_ci.","path":"/api/v1/unidades-medida","fieldErrors":[]}
```

**400 · validación fallida** (CP-05, CP-06):

```json
{"timestamp":"2026-10-20T14:18:17.794093577","status":400,"error":"Bad Request","code":"VALIDACION_FALLIDA","message":"Los datos enviados no son válidos.","path":"/api/v1/unidades-medida","fieldErrors":[{"field":"abreviatura","message":"La abreviatura es obligatoria"},{"field":"nombre","message":"El nombre es obligatorio"}]}
```

**404 · unidad inexistente** (CP-17):

```json
{"timestamp":"2026-10-20T14:18:17.644906838","status":404,"error":"Not Found","code":"RECURSO_NO_ENCONTRADO","message":"No se encontró la unidad de medida con id 999.","path":"/api/v1/unidades-medida/999","fieldErrors":[]}
```

**405 · el DELETE que no existe** (CP-13):

```json
{"timestamp":"2026-10-20T14:18:17.864905348","status":405,"error":"Method Not Allowed","code":"METODO_NO_PERMITIDO","message":"El método 'DELETE' no está permitido para este recurso.","path":"/api/v1/unidades-medida/1","fieldErrors":[]}
```

## Evidencia (verificación backend)

- **Suite unitaria integrada:** `mvn -o test` → **266 tests, 0 fallos**. Son los 200 tests
  que había al inicio de la HU más 66 nuevos. Desglose de los ficheros de prueba nuevos:

  | Fichero de prueba | Pruebas | Qué cubre |
  |---|---|---|
  | `UnidadMedidaServiceTest` | 16 | Reglas del servicio: recorte de texto, conservación de mayúsculas en la abreviatura, duplicados por abreviatura, `UNIDAD_EN_USO`, captura de `DuplicateKeyException` |
  | `CrearUnidadRequestTest` | 5 | Contrato de validación del DTO de alta |
  | `ActualizarUnidadRequestTest` | 5 | Contrato de validación del DTO de edición |
  | `UnidadMedidaAuditoriaTest` | 6 | Contrato de auditoría: anotaciones `@Auditable` de crear y editar |
  | `UnidadMedidaControllerTest` | 16 | Pruebas HTTP funcionales: listado plano, alta 201 + `Location`, validaciones, duplicado, ausencia de `DELETE`, edición con y sin `?confirmar=true`, matriz de roles |
  | `UnidadMedidaSeguridadTest` | 18 | Batería de seguridad SEC-01..SEC-05 sobre los cuatro endpoints |
  | **Total nuevos** | **66** | |

- **Compilación:** `mvn -o -DskipTests compile` → `BUILD SUCCESS`, sin errores.
- **Validación en caliente contra el MySQL 8.4 local** (2026-10-20, aplicación arrancada con
  `spring.jpa.hibernate.ddl-auto=validate`):
  - Arranque sin quejas de validación de esquema: `UnidadMedida` mapea sobre la tabla real
    `unidades_medida`, que no tiene columna `estado`.
  - `listarConUsos()` devuelve las **9 unidades** de la semilla V3 con sus conteos, ordenadas
    por nombre (los tres conteos a cero, porque `materias_primas`, `productos` y `recetas`
    siguen vacías).
  - `buscarConUsos(999)` → vacío: el `GET` responde `404 RECURSO_NO_ENCONTRADO`.
  - `existsByAbreviatura("KG")` **encuentra** `kg` (collation `utf8mb4_0900_ai_ci`,
    case-insensitive): el `POST` con abreviatura `KG` responde `409 ABREVIATURA_DUPLICADA`.
  - `existsByAbreviaturaAndIdUnidadNot("kg", 1)` **no** reporta la unidad 1 como duplicada de
    sí misma: el `PUT` de la unidad 1 conservando la abreviatura `kg` responde `200`.
  - **R-04 (`UNIDAD_EN_USO`) no se pudo comprobar en caliente**: no hay ninguna referencia real
    en `materias_primas`, `productos` ni `recetas` (esas historias no están implementadas), así
    que ninguna unidad está «en uso». El camino está cubierto por pruebas unitarias con el
    repositorio simulado (`UnidadMedidaServiceTest` y `UnidadMedidaControllerTest`).
- **Matriz de seguridad:** SEC-01..SEC-05 sobre los cuatro endpoints, **18 ejecuciones**.

  | Caso | GET colección | GET por id | POST | PUT | Ejecuciones |
  |---|---|---|---|---|---|
  | SEC-01 sin cabecera `Authorization` | 401 | 401 | 401 | 401 | 4 |
  | SEC-02 token malformado | 401 | 401 | 401 | 401 | 4 |
  | SEC-03 token expirado | 401 | 401 | 401 | 401 | 4 |
  | SEC-04 rol sin permiso de escritura | — | — | 403 | 403 | 2 |
  | SEC-05 token de usuario desactivado | 403 | 403 | 403 | 403 | 4 |
  | **Total** | | | | | **18** |

  SEC-04 se aplica solo a los endpoints de escritura: los cinco roles leen el catálogo, así
  que en los de lectura no existe un rol al que se le pueda negar el permiso. Es el caso
  crítico de la batería: si devolviera 200, la autorización por método estaría desactivada.
- **Colección de Postman:** publicada en `Docs/Postman/ProductionFood-HU07.postman_collection.json`
  (50 peticiones). **No se ha ejecutado** contra la aplicación: la corrida en caliente la
  hace el equipo de QA. Tres casos necesitan preparación manual y lo documentan en la
  propia petición: CP-11, CP-12 y CP-12b (una unidad en uso, hoy imposible porque
  `materias_primas`, `productos` y `recetas` están vacías), SEC-03 (token caducado) y
  SEC-05 (token de usuario desactivado).

## Desviaciones vs especificación

| # | Desviación | Justificación |
|---|---|---|
| D1 | `tarea-backend.md` pide `UnidadMedidaRowMapper` y `JdbcTemplate` | El backend real usa JPA + Spring Data desde HU-01; se sigue el código real, que es lo que pide la propia HU («copiar el patrón de HU-05»). No hay `RowMapper`. |
| D2 | `especificacion.md` §4 muestra el array con `{idUnidad, nombre, abreviatura}` | Cada elemento añade `usos` con el conteo por tabla: `diseno.md` exige la columna «En uso» del catálogo — se llama **«En uso»**, no «En uno» — y `tarea-backend.md` pide el DTO `UnidadEnUsoResponse`. Se documenta en el manual de consumo. |
| D3 | `tarea-backend.md` pide «exige confirmación explícita (parámetro `confirmar=true`)» sin decir dónde | Vive como parámetro de consulta `?confirmar=true` del `PUT`, no como campo del cuerpo: el body sigue siendo un reemplazo puro de nombre y abreviatura. |
| D4 | R-04 pide devolver el conteo de referencias | El cuerpo de error del contrato (`ErrorResponse`) es un record cerrado de 7 campos, así que los conteos van en el cuerpo de la respuesta y el 409 los lleva en el `message` (mismo precedente que `CLIENTE_CON_PEDIDOS_ACTIVOS`). |
| D5 | `especificacion.md` §2 no lista roles de lectura | La lectura se abre a los cinco roles autenticados: es el primer `hasAnyRole` con cinco roles del repo, y los roles operativos necesitan el catálogo para sus formularios. La escritura es solo ADMIN, según la matriz de roles. |
| D6 | R-03 dice «no se implementa ningún `DELETE`» | Confirmado: no hay `DELETE` ni `PATCH` de estado, y `DELETE` responde `405 METODO_NO_PERMITIDO`. |

## Entregado en

| PR | Unidad | Contenido |
|---|---|---|
| #47 | Dominio | `UnidadMedida`, `UnidadMedidaRepository` (consulta nativa con los conteos de uso), `UnidadMedidaConUsos` y los cuatro DTOs |
| #48 | Servicio | `UnidadMedidaService`: unicidad de la abreviatura, confirmación de R-04 y auditoría |
| #49 | Pruebas del servicio | 16 pruebas unitarias |
| #50 | Pruebas de contrato | 5 + 5 de los DTOs + 6 del contrato de auditoría |
| #51 | Controlador | `UnidadMedidaController` + Swagger + `@PreAuthorize` |
| #52 | Pruebas HTTP | 16 pruebas funcionales del controlador |
| #53 | Batería de seguridad | 18 ejecuciones de SEC-01..SEC-05 sobre los cuatro endpoints |
| #54 | Ficha de entrega | Esta ficha y las filas de HU-07 en los índices de `Docs/` |
| #55 | Manual de consumo | Contrato para el frontend + fila en el índice de manuales |
| #56 | Postman | Colección con 50 peticiones: preparación, CP-01…CP-17, matriz de roles y SEC-01…SEC-05 |

## Pendientes / deuda

- **Frontend de HU-07 pendiente** (sprint siguiente): catálogo, formulario de alta y edición
  y advertencia de unidad en uso.
- **La colección de Postman está publicada (50 peticiones) y aún no se ha ejecutado** contra la
  aplicación: la verificación en caliente es de QA.
- **La verificación de QA (CP-01..CP-17 y la batería SEC) la ejecuta QA**: esta ficha es la
  entrega del backend.
- **Deuda ajena a esta HU, ya registrada:** `patronPrefijo` está duplicado entre
  `ClienteService` y `ProveedorService` y no tiene helper común. HU-07 no lo necesita (no
  filtra por texto) y no lo ha tocado.
