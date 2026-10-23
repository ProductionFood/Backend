# HU-08 — Materias primas · HECHA (backend)

| | |
|---|---|
| Fecha de entrega | 2026-10-22 |
| Commits / PR | PRs `pr0`–`pr6` de la cadena `HU-08/pr*` sobre la rama `HU-08/gestion-materias-primas`; ver «Entregado en» |
| Colección Postman | `Docs/Postman/ProductionFood-HU08.postman_collection.json` (pendiente de publicar en el PR siguiente) |
| Estado QA | pendiente con el equipo de QA |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| GET | `/api/v1/materias-primas` | 200/400/401/403 | Listado paginado con filtros `nombre` y `activo` y orden de lista blanca |
| GET | `/api/v1/materias-primas/{id}` | 200/400/401/403/404 | Obtener una materia prima con su unidad y su cantidad disponible |
| POST | `/api/v1/materias-primas` | 201/400/401/403/409 | Registrar una materia prima y, en la misma transacción, su fila de inventario con stock 0 |
| PUT | `/api/v1/materias-primas/{id}` | 200/400/401/403/404/409 | Reemplazar nombre, unidad de medida, stock mínimo y costo unitario |
| PATCH | `/api/v1/materias-primas/{id}/estado` | 200/400/401/403/404/409 | Activar o desactivar; desactivar conserva el inventario |

**No existe `DELETE`** (R-05): `DELETE /api/v1/materias-primas/5` responde
`405 METODO_NO_PERMITIDO`. La baja es la desactivación por `PATCH`: `inventario`, `lotes`,
`detalle_compra`, `detalle_receta` y `consumo_materia_prima` referencian la materia prima.

**Roles.** Lectura: `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA`. **`VENTAS` no lee materias
primas** (`403 SIN_PERMISO`), a diferencia de HU-07, donde los cinco roles leen el catálogo:
la matriz de la especificación no incluye VENTAS en la lectura de este módulo. Escritura:
solo `ADMIN`.

Piezas internas:

| Pieza | Rol |
|---|---|
| `MateriaPrima` | Entidad JPA sobre la tabla `materias_primas` (`id_materia_prima`, `nombre`, `id_unidad`, `stock_minimo`, `costo_unitario`, `estado`) |
| `MateriaPrimaRepository` | Spring Data; consultas nativas `listarConDetalle` y `buscarConDetalle` (LEFT JOIN inventario + `COALESCE`), `contarRecetasActivas` y `existeActiva` (para HU-12, HU-14 y HU-22) |
| `MateriaPrimaConDetalle` | Proyección de interfaz para las consultas con el detalle embebido |
| `MateriaPrimaService` | R-01, validación de unidad, lista blanca de orden, `MATERIA_PRIMA_EN_USO` configurable y anotaciones `@Auditable` |
| `MateriaPrimaController` | Los cinco endpoints con `@PreAuthorize` y documentación Swagger de los 409 |
| `Inventario` / `InventarioRepository` | Entidad sobre la tabla `inventario` y `crearFilaInicial`: insert nativo con `cantidad_disponible` 0, dentro de la transacción del alta |
| `FiltroTexto` | Helper de `common/pagination` con el patrón de prefijo escapado y `Locale.ROOT` (extraído de los servicios de HU-05/06) |
| DTOs | `CrearMateriaPrimaRequest` y `ActualizarMateriaPrimaRequest` (validaciones espejo), `MateriaPrimaResponse` (siete campos, `@JsonInclude(ALWAYS)` para que `cantidadDisponible` nunca desaparezca del payload) y `UnidadRefResponse` (records) |

Sin migración Flyway: `materias_primas` e `inventario` ya existen con las correcciones de V2
(`estado` con `DEFAULT 1`, `CHECK (>= 0)` en las tres columnas decimales e índice
`idx_mp_nombre`).

## Reglas de negocio implementadas

- **CA-01 · nombre obligatorio, máx. 100.** `@NotBlank` + `@Size(max = 100)` en
  `CrearMateriaPrimaRequest` y `ActualizarMateriaPrimaRequest`; si falta, `400
  VALIDACION_FALLIDA` con el campo marcado en `fieldErrors`. El texto se recorta con `trim`.
- **CA-02 · la unidad de medida debe existir.** El servicio valida `idUnidad` contra
  `UnidadMedidaRepository` con `findById` (reutiliza la entidad para la respuesta en vez de
  hacer una segunda consulta); si no existe, `409 UNIDAD_INEXISTENTE`. Se valida igual en el
  alta y en la edición: el `PUT` es un reemplazo completo y puede cambiar la unidad.
- **CA-03 · stock mínimo y costo unitario obligatorios, `BigDecimal` nunca `double`** (R-02).
  `@NotNull` + `@DecimalMin("0.00")` inclusivo + `@Digits(8, 2)`, que replica el dominio de las
  columnas `decimal(10,2)`: 8 dígitos enteros y 2 decimales. El costo unitario es un valor de
  referencia editable, no el costo real de compra (R-03): ese vive en
  `detalle_compra.precio_unitario` (HU-14) y no se actualiza solo al registrar una compra.
- **CA-04 · nace activa.** El servicio fija `estado` a `true` en el alta, porque la columna es
  `NOT NULL` y con JPA un campo sin asignar se insertaría como `NULL`; el `DEFAULT 1` de V2 es
  la red de seguridad para las inserciones hechas fuera de la aplicación, no el mecanismo.
- **CA-05 · listado paginado con filtros.** Filtro `nombre` por prefijo sin distinguir
  mayúsculas (`FiltroTexto.patronPrefijo`, con los comodines de SQL escapados), filtro
  `activo` booleano, ambos opcionales y combinables. Orden de lista blanca `{nombre,
  stockMinimo, costoUnitario, id}` con default `nombre,asc`; cualquier otro campo responde
  `400 CAMPO_ORDEN_INVALIDO`.
- **R-01 · el alta crea la fila de inventario en la misma transacción.** Es la regla más
  importante de la historia: `saveAndFlush` de la materia prima seguido de
  `crearFilaInicial` (insert nativo con `0`). Si el insert falla, el alta entera se revierte y
  no queda una materia prima «fantasma» sin fila de inventario, el defecto que reventaría
  HU-10, HU-23 y HU-27 semanas después.
- **R-04 · desactivar no borra el stock.** El `PATCH` de estado solo cambia `estado`; el
  inventario y el historial de movimientos del kardex se conservan. El cambio es idempotente.
- **R-05 · no se elimina.** No hay `DELETE` ni endpoint de borrado.
- **R-06 · stock mínimo cero es válido** (CP-09): `@DecimalMin` es inclusivo por defecto y el
  `CHECK (>= 0)` de V2 admite exactamente ese caso. Significa «no alertar por esta materia
  prima».
- **`MATERIA_PRIMA_EN_USO` es un 409 configurable.** Desactivar una materia prima que sigue
  en recetas activas se rechaza con `409 MATERIA_PRIMA_EN_USO` y el conteo de recetas en el
  `message`. La comprobación es configurable con
  `app.materias-primas.bloquear-desactivacion-con-recetas` (`true` por defecto en
  `application.yml`), con el mismo molde que `app.clientes.bloquear-desactivacion-con-pedidos`:
  con el flag en `false` la desactivación se acepta tal cual.
- **Auditoría.** `@Auditable` en `CREAR`, `EDITAR` y `CAMBIAR_ESTADO` sobre `materias_primas`.
- **`existeActiva(id)`** queda expuesto en el servicio para que HU-12, HU-14 y HU-22 validen
  la materia prima sin reimplementar la consulta.

## Contrato resumido

**POST /api/v1/materias-primas → 201** (con cabecera
`Location: /api/v1/materias-primas/5`):

```json
// petición
{ "nombre": "Harina de trigo", "idUnidad": 1,
  "stockMinimo": 50.00, "costoUnitario": 3200.00 }
```

```json
// respuesta
{ "idMateriaPrima": 5, "nombre": "Harina de trigo",
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "stockMinimo": 50.00, "costoUnitario": 3200.00, "activo": true,
  "cantidadDisponible": 0.00 }
```

La unidad viaja embebida como `UnidadRefResponse` (id, nombre y abreviatura), de modo que el
consumidor no necesita una llamada extra a HU-07 para mostrar las cantidades con su
abreviatura.

**GET /api/v1/materias-primas → 200** — `PageResponse` paginado (los ejemplos del listado son
**ilustrativos**: la forma del payload la fija la batería HTTP con MockMvc y la captura en
caliente contra el MySQL local es de QA):

```json
{ "content": [
    { "idMateriaPrima": 5, "nombre": "Harina de trigo",
      "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
      "stockMinimo": 50.00, "costoUnitario": 3200.00, "activo": true,
      "cantidadDisponible": 0.00 } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true }
```

**409 · unidad inexistente** (CP-05, en `POST` y en `PUT`):

```json
{"timestamp":"2026-10-22T18:41:09.112384502","status":409,"error":"Conflict","code":"UNIDAD_INEXISTENTE","message":"La unidad de medida indicada no existe.","path":"/api/v1/materias-primas","fieldErrors":[]}
```

**409 · en uso por recetas activas** (`PATCH` de estado, con la regla activada):

```json
{"timestamp":"2026-10-22T18:41:09.240117866","status":409,"error":"Conflict","code":"MATERIA_PRIMA_EN_USO","message":"La materia prima aparece en 2 receta(s) activa(s) y no se puede desactivar. Desactive o elimine esas recetas primero.","path":"/api/v1/materias-primas/5/estado","fieldErrors":[]}
```

**400 · validación fallida** (CP-03, CP-06, CP-07…):

```json
{"timestamp":"2026-10-22T18:41:09.198776013","status":400,"error":"Bad Request","code":"VALIDACION_FALLIDA","message":"Los datos enviados no son válidos.","path":"/api/v1/materias-primas","fieldErrors":[{"field":"nombre","message":"El nombre es obligatorio"}]}
```

## Evidencia (verificación backend)

- **Suite unitaria integrada:** `mvn clean test` → **365 tests, 0 fallos**. Son los 266 tests
  que había al inicio de la HU más 99 nuevos. Desglose de los ficheros de prueba nuevos:

  | Fichero de prueba | Pruebas | Qué cubre |
  |---|---|---|
  | `FiltroTextoTest` | 4 | Helper del patrón de prefijo: filtro nulo o en blanco, escapado de comodines y normalización con `Locale.ROOT` |
  | `MateriaPrimaServiceTest` | 18 | Reglas del servicio: R-01 (inventario creado con el id nuevo y stock 0), `UNIDAD_INEXISTENTE` en alta y edición, lista blanca de orden, 409 `MATERIA_PRIMA_EN_USO` con y sin el flag, desactivación que no toca el inventario, idempotencia |
  | `CrearMateriaPrimaRequestTest` | 9 | Contrato de validación del DTO de alta |
  | `ActualizarMateriaPrimaRequestTest` | 9 | Contrato de validación del DTO de edición |
  | `MateriaPrimaControllerTest` | 25 | Pruebas HTTP funcionales: página con la unidad embebida, traspaso de filtros y orden, validaciones 400 con `fieldErrors`, shape de `ErrorResponse` (7 campos), CP-09 cero válido, 409 de negocio, `Location` del 201, ausencia de `DELETE` (405) y matriz de roles |
  | `MateriaPrimaSeguridadTest` | 34 | Batería de seguridad SEC-01..SEC-05 sobre los cinco endpoints |
  | **Total nuevos** | **99** | |

- **Compilación:** incluida en la corrida (`BUILD SUCCESS`), sin errores.
- **Matriz de seguridad:** SEC-01..SEC-05 sobre los cinco endpoints, **34 ejecuciones**.

  | Caso | GET colección | GET por id | POST | PUT | PATCH estado | Ejecuciones |
  |---|---|---|---|---|---|---|
  | SEC-01 sin cabecera `Authorization` | 401 | 401 | 401 | 401 | 401 | 5 |
  | SEC-02 token malformado | 401 | 401 | 401 | 401 | 401 | 5 |
  | SEC-03 token expirado | 401 | 401 | 401 | 401 | 401 | 5 |
  | SEC-04 rol sin permiso | 403 | 403 | 403 | 403 | 403 | 14 |
  | SEC-05 token de usuario desactivado | 403 | 403 | 403 | 403 | 403 | 5 |
  | **Total** | | | | | | **34** |

  SEC-04 es el caso crítico y aquí es más denso que en HU-07: además de los cuatro roles
  no-ADMIN en cada endpoint de escritura (12 ejecuciones), la lectura también tiene un rol
  excluido, `VENTAS` (2 ejecuciones). Si devolviera 200, la autorización por método estaría
  desactivada. Los casos positivos de los roles con permiso viven en
  `MateriaPrimaControllerTest` (por ejemplo, COMPRAS listando).
- **No se ha corrido en caliente contra el MySQL local**: el arranque de la aplicación con
  `spring.jpa.hibernate.ddl-auto=validate` y la verificación de CP-01 y CP-02 contra la base
  real son de QA — los `@Query` nativos solo se parsean al arrancar, las pruebas unitarias no
  los validan. El SQL de conciliación está en «Pendientes / deuda».
- **Colección de Postman: pendiente de publicar** en el PR siguiente (mismo punto de la cadena
  que HU-07 en su ficha de entrega).

## Desviaciones vs especificación

| # | Desviación | Justificación |
|---|---|---|
| D1 | `tarea-backend.md` pide `JdbcTemplate`, `RowMapper` y `MateriaPrimaRowMapper` | El backend real usa JPA + Spring Data desde HU-01; se sigue el código real, que es lo que pide la propia HU («copiar el patrón de HU-05»). Misma desviación ya aceptada en HU-05, HU-06 y HU-07. No hay `MateriaPrimaRowMapper`. |
| D2 | La especificación describe `MATERIA_PRIMA_EN_USO` como advertencia | Se implementa como `409` **configurable** con `app.materias-primas.bloquear-desactivacion-con-recetas` (por decisión del usuario), con el molde de `app.clientes.bloquear-desactivacion-con-pedidos`: con el flag activo (default) la desactivación se bloquea; apagado, se acepta. |
| D3 | La spec pide crear `MateriaPrimaRowMapper` con un JOIN para la respuesta embebida | La respuesta (`unidad` + `cantidadDisponible`) se resuelve con las proyecciones nativas `listarConDetalle` / `buscarConDetalle` (LEFT JOIN inventario + `COALESCE`, el mismo patrón de la vista `v_inventario_materia_prima`): el `COALESCE` tapa el hueco y el `LEFT JOIN` respeta que una fila pueda faltar. |
| D4 | `patronPrefijo` estaba triplicado en `ClienteService`, `ProveedorService` y `UsuarioService` | Se extrajo a `common/pagination/FiltroTexto` (patrón `patronPrefijo`), los tres servicios delegan y los tres duplicados se borraron. `UsuarioService` pasó de `toLowerCase()` sin locale a `Locale.ROOT`. Deuda técnica cerrada en este plan (en la ficha de HU-07 quedaba registrada como pendiente). |
| D5 | — | El método `buscar` (JPQL del repositorio, añadido en el PR de dominio) quedó sin uso: el servicio consulta `listarConDetalle` / `buscarConDetalle`. Candidato a limpieza futura. |

Sin migración Flyway: las tablas ya existían con las correcciones de V2, así que esta HU no
añade ningún fichero bajo `db/migration`.

## Entregado en

| PR | Unidad | Contenido |
|---|---|---|
| `pr0` | Helper de filtro | `common/pagination/FiltroTexto` con `patronPrefijo`: escapado de comodines y `Locale.ROOT` |
| `pr0b` | Refactor | `ClienteService`, `ProveedorService` y `UsuarioService` delegan en el helper; los tres duplicados borrados y `UsuarioService` unificado a `Locale.ROOT` |
| `pr1` | Dominio | `MateriaPrima`, `MateriaPrimaRepository`, `MateriaPrimaConDetalle`, `Inventario`, `InventarioRepository.crearFilaInicial` y los DTOs de alta, edición y respuesta |
| `pr2` | Servicio | `MateriaPrimaService`: R-01, listado con `FiltroTexto` y lista blanca de orden, `UNIDAD_INEXISTENTE` y `MATERIA_PRIMA_EN_USO` configurables, relectura tras escribir, `existeActiva()` y auditoría |
| `pr3` | Pruebas de contrato | 9 + 9 de los dos DTOs de petición |
| `pr4` | Controlador | `MateriaPrimaController` + Swagger con los 409 + `@PreAuthorize` |
| `pr5` | Pruebas HTTP | 25 pruebas funcionales del controlador |
| `pr6` | Batería de seguridad | 34 ejecuciones de SEC-01..SEC-05 sobre los cinco endpoints |
| — | Ficha de entrega | Esta ficha y las filas de HU-08 en los índices de `Docs/` |

## Pendientes / deuda

- **CP-02 (la fila de inventario) requiere base de datos real** y no se pudo verificar aquí.
  QA debe ejecutar tras cada alta la conciliación de `../../../PFDocs/HU-08/tarea-qa.md` §3:

  ```sql
  -- 🔴 CP-02: el caso que más importa de esta historia.
  -- Toda materia prima DEBE tener su fila de inventario (R-01).
  SELECT mp.id_materia_prima, mp.nombre, i.cantidad_disponible
  FROM materias_primas mp
  LEFT JOIN inventario i ON i.id_materia_prima = mp.id_materia_prima
  WHERE i.id_inventario IS NULL;
  -- DEBE devolver 0 filas. Cualquier fila aquí es un defecto crítico:
  -- esa materia prima romperá HU-10, HU-23 y HU-27.

  -- CP-08: el CHECK de V2 debe estar activo
  INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, costo_unitario)
  VALUES ('Prueba', 1, -5, 100);
  -- DEBE fallar con error 3819

  -- CP-12: el DEFAULT 1 añadido en V2 §C-06
  SHOW CREATE TABLE materias_primas\G
  -- Se espera: `estado` tinyint(1) NOT NULL DEFAULT '1'

  -- CP-18: desactivar no toca el inventario
  SELECT mp.estado, i.cantidad_disponible
  FROM materias_primas mp JOIN inventario i USING (id_materia_prima)
  WHERE mp.id_materia_prima = 5;
  ```

- **Los `@Query` nativos solo se validan al arrancar contra MySQL real** (las pruebas
  unitarias no los parsean): verificar el arranque de la aplicación y los casos CP-01 y CP-02
  con la app local antes de dar la HU por cerrada.
- **La colección de Postman está pendiente de publicar** en el PR siguiente.
- **La verificación de QA (CP-01..CP-22 y la batería SEC) la ejecuta QA**: esta ficha es la
  entrega del backend.
- **Frontend de HU-08 pendiente** (sprint siguiente): catálogo paginado con filtros, alta,
  edición y cambio de estado.
- **`MateriaPrimaRepository.buscar` (JPQL) quedó sin uso** y es candidato a eliminarse en la
  primera limpieza que toque el repositorio.
- **Deuda cerrada en este plan:** `patronPrefijo` ya no está duplicado (ver D4).
