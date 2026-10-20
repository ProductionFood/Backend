# HU-06 Gestión de proveedores — plan de trabajo (solo backend)

| | |
|---|---|
| Fecha | 2026-10-19 |
| Alcance | Backend de HU-06 (CRUD de proveedores). Frontend y QA de Postman fuera de este plan salvo lo indicado |
| Estado del plan | **Propuesto** — nada implementado todavía |
| Fuentes | `PFDocs/HU-06/{especificacion,diseno,tarea-backend,tarea-qa}.md`, `PFDocs/00-base/{01,03,04,06}`, código real de `cliente/` (HU-05) |

## 1. Contexto y dependencias

- HU-06 depende de HU-05 ("mismo patrón"). PR1-PR3 de HU-05 ya están en `main`
  (`CambiarEstadoRequest` común, dominio, servicio). Falta **PR4**: `ClienteController`
  está sin commitear en `HU-05/pr4-controlador`.
- Por eso HU-06 se puede construir en paralelo **ramificando desde `main`**: todo lo que
  necesita ya existe (`PageRequest`, `PageResponse`, `CambiarEstadoRequest`, `@Auditable`,
  excepciones). Lo único que se copia de HU-05 PR4 es el controlador; se escribe con el
  mismo molde y se concilia al fusionar HU-05.
- Tabla `proveedores` ya existe en V1; V2 ya añade `idx_proveedores_nombre`.
  **No se requiere migración Flyway.**

## 2. Discrepancias detectadas (decisiones a confirmar)

| # | PFDocs dice | Realidad / decisión propuesta |
|---|---|---|
| D1 | `tarea-backend.md` y `06-ARQUITECTURA` piden `JdbcTemplate`, `RowMapper`, POJO sin JPA | El backend real usa **JPA + Spring Data** (`Cliente` con `@Entity`, `JpaRepository`, `@Query`). **Se sigue el código real de HU-05**, que es lo que pide la propia HU-06 ("copiar el patrón"). No hay `ProveedorRowMapper`. |
| D2 | CA-04 solo habla del filtro por nombre | `diseno.md` incluye filtro por estado (activos por defecto en UI) y HU-05 ya expone `activo`. Se implementa `activo` opcional, igual que clientes. |
| D3 | Respuesta de ejemplo muestra `correo` y demás campos | Igual que `ClienteResponse`: `@JsonInclude(ALWAYS)` para que `contacto`, `telefono` y `correo` salgan como `null` (CP-01, CP-06). |
| D4 | `especificacion` R-02: inactivo no admite compras | Se valida en HU-13, no aquí. Aquí solo se expone `existeActivo(id)`. **No** hay regla de "compras en curso" al desactivar (a diferencia de clientes): las compras PENDIENTE siguen su curso. |
| D5 | Roles | Lectura: ADMIN, COMPRAS, CONSULTA. Escritura: ADMIN, COMPRAS (matriz §3). VENTAS y PRODUCCION → 403. |

## 3. Diseño

Paquete `co.edu.corposucre.productionfood.proveedor`:

```
proveedor/
├── Proveedor.java               @Entity tabla proveedores (idProveedor Integer, nombre, contacto, telefono, correo, estado)
├── ProveedorRepository.java     JpaRepository + buscar(nombre, activo, Pageable) con countQuery idéntico + existeActivo(id)
├── ProveedorService.java        crear / listar / obtener / actualizar / cambiarEstado
├── ProveedorController.java     GET, GET/{id}, POST, PUT/{id}, PATCH/{id}/estado  (sin DELETE)
└── dto/{CrearProveedorRequest,ActualizarProveedorRequest,ProveedorResponse}.java
```

Reglas clave:

- `nombre`: `@NotBlank` + `@Size(max=100)`. `contacto` ≤100, `telefono` ≤20,
  `correo` ≤100 con `@Email` (acepta null/vacío).
- **Correo vacío → `null`** en el servicio (`textoOpcional`), CP-07. Sin `UNIQUE` (CP-08).
- `estado` se fija en `TRUE` explícitamente al crear (lección de HU-01: el `DEFAULT` no
  aplica ante un NULL que envía Hibernate).
- `ORDEN = {nombre → nombre, id → idProveedor}`; cualquier otro campo
  → `400 CAMPO_ORDEN_INVALIDO` (CP-13, incluye `correo`).
- Filtro por prefijo, minúsculas con `Locale.ROOT`, escape de `\`, `%`, `_`
  (reutilizar la lógica de `ClienteService.patronPrefijo`; ver tarea 0 sobre extraerla).
- `@Transactional` / `readOnly = true` según corresponda; `@Auditable` en CREAR, EDITAR,
  CAMBIAR_ESTADO con `tabla = "proveedores"` (accesores de record como propiedad en SpEL,
  ver comentario de `ClienteService`).
- `PATCH .../estado` idempotente, reutiliza `common.dto.CambiarEstadoRequest`.
- `@PreAuthorize` con `hasAnyRole` en cada método; Swagger con `@Operation` y `@ApiResponses`.
- `existeActivo(Integer id)` en el repositorio (para HU-13).

## 4. Tareas y PRs (cada uno < ~250 líneas, commits atómicos con prefijo `feat(HU-06):`)

Rama base: `main`. Ramas: `HU-06/pr1-dominio`, `HU-06/pr2-servicio`, `HU-06/pr3-controlador`,
`HU-06/pr4-docs` (misma convención que HU-04/HU-05).

### Tarea 0 — Decisión previa (opcional, antes de PR2)
- [ ] Extraer `patronPrefijo` de `ClienteService` a un helper común (p. ej.
  `common/pagination/FiltroTexto`) para no duplicar la lógica de escape. Si se prefiere no
  tocar HU-05 mientras está abierta, duplicar en `ProveedorService` y dejar el refactor para
  HU-07/HU-08.

### PR1 — Dominio (`HU-06/pr1-dominio`)
- [ ] `Proveedor` (entidad).
- [ ] `ProveedorRepository`: `buscar` + `countQuery` con el mismo `WHERE`; `existeActivo`.
- [ ] DTOs: `CrearProveedorRequest`, `ActualizarProveedorRequest`, `ProveedorResponse`
  (con `@JsonInclude(ALWAYS)`).
- [ ] Verificar que compila (`./mvnw -q -DskipTests compile`).

### PR2 — Servicio + pruebas unitarias (`HU-06/pr2-servicio`)
- [ ] `ProveedorService` con las reglas de §3 y `@Auditable`.
- [ ] `ProveedorServiceTest` (Mockito, como `UsuarioServiceTest`):
  - crear: nombre recortado, correo `""` → null, estado activo;
  - listar: prefijo/escape (`%` no devuelve todo), `sort=correo` → `CAMPO_ORDEN_INVALIDO`,
    `size=101` → `PARAMETRO_INVALIDO`;
  - obtener/actualizar inexistente → `RecursoNoEncontradoException`;
  - cambiarEstado idempotente y que no toque otros campos;
  - `existeActivo` verdadero/falso.
- [ ] `CrearProveedorRequestTest` (validaciones: nombre vacío, 101 chars, correo inválido,
  correo omitido/vacío válidos) siguiendo `CrearUsuarioRequestTest`.

### PR3 — Controlador (`HU-06/pr3-controlador`)
- [ ] `ProveedorController` con roles de la matriz y Swagger completo. **Sin DELETE** (CA-05).
- [ ] Prueba de autorización: ADMIN/COMPRAS escriben; VENTAS → 403; CONSULTA solo lee
  (cubre SEC-04; confirma que `@EnableMethodSecurity` está activo).
- [ ] Prueba manual contra BD local (ver §5).

### PR4 — Documentación y Postman (`HU-06/pr4-docs`)
- [ ] `Docs/Hechos/HU-06-gestion-proveedores.md` (qué quedó hecho, decisiones, desviaciones D1-D5).
- [ ] `Docs/Manuales_Consumo_Frontend/HU-06-gestion-proveedores.md` (contrato para el frontend).
- [ ] `Docs/Postman/ProductionFood-HU06.postman_collection.json` con CP-01…CP-20 y SEC-01…05.
- [ ] Actualizar índices en `Docs/README.md` y los `README.md` de Hechos/Manuales.

## 5. Verificación (criterios de cierre)

Contra `PFDocs/HU-06/tarea-qa.md`:

- [ ] CP-01…CP-20 ejecutados; negativos con HTTP y `code` esperados
  (400 `VALIDACION_FALLIDA`, 400 `CAMPO_ORDEN_INVALIDO`, 404, 403 `SIN_PERMISO`).
- [ ] CP-17: `DELETE /proveedores/{id}` → 404/405.
- [ ] SEC-01…SEC-05 en los 5 endpoints.
- [ ] SQL: `SELECT ... WHERE correo = ''` → 0 filas; `EXPLAIN` usa `idx_proveedores_nombre`.
- [ ] Bitácora registra CREAR / EDITAR / CAMBIAR_ESTADO con `tabla_afectada = proveedores`.
- [ ] Sin `System.out.println` ni código comentado; Swagger con ejemplos.
- [ ] `./mvnw test` en verde.

## 6. Riesgos y orden recomendado

| Riesgo | Mitigación |
|---|---|
| Conflictos con HU-05 PR4 (controlador aún sin commitear) | Ramificar de `main`; los archivos de `proveedor/` no se cruzan con `cliente/`. Si se extrae el helper (Tarea 0), hacerlo **después** de fusionar HU-05 PR4. |
| Que el frontend de HU-06 dependa de campos aún no fijos | El contrato queda en el manual de consumo (PR4) y en Swagger desde PR3. |
| Copiar la regla de "pedidos en curso" de clientes por inercia | D4: proveedores no la tiene; test explícito de que desactivar no consulta compras. |

**Orden:** HU-05 PR4 (usuario) ‖ HU-06 PR1 → PR2 → PR3 → PR4. HU-06 no bloquea HU-05;
HU-13 (compras) queda desbloqueada cuando HU-06 PR3 se fusione, gracias a `existeActivo`.
