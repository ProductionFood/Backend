# HU-06 — Gestión de proveedores · HECHA (backend)

| | |
|---|---|
| Fecha de entrega | 2026-10-19 |
| Commits / PR | Cadena apilada `HU-06/pr1-dominio` → `pr2-servicio` → `pr3-tests-servicio` → `pr4-tests-dto` → `pr5-controlador` → `pr6-docs`; sin push todavía |
| Colección Postman | `Docs/Postman/ProductionFood-HU06.postman_collection.json` (llega en este mismo PR de docs) |
| Estado QA | pendiente con equipo de QA; verificación manual propia **81/81** (ver «Evidencia») |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| GET | `/api/v1/proveedores` | 200/400/401/403 | Listado paginado con filtros `nombre` (prefijo, sin mayúsculas) y `activo` |
| GET | `/api/v1/proveedores/{id}` | 200/400/401/403/404 | Detalle de un proveedor |
| POST | `/api/v1/proveedores` | 201/400/401/403 | Alta; responde `Location` al recurso creado |
| PUT | `/api/v1/proveedores/{id}` | 200/400/401/403/404 | Reemplazo completo; no cambia el estado |
| PATCH | `/api/v1/proveedores/{id}/estado` | 200/400/401/403/404 | Activar/desactivar con `{"activo": bool}`; idempotente |

No hay `DELETE` (CA-05): `compras.id_proveedor` lo referencia y borrarlo rompería el
historial de HU-15. En la práctica responde `404 RECURSO_NO_ENCONTRADO`. La baja es el `PATCH`.

Listado: `page` desde 0, `size` de 1 a 100 (por defecto 20), `sort` = `nombre` | `id`
(por defecto `nombre,asc`); cualquier otro campo → `400 CAMPO_ORDEN_INVALIDO`.

Piezas internas:

| Pieza | Rol |
|---|---|
| `Proveedor` | Entidad JPA sobre la tabla `proveedores` |
| `ProveedorRepository` | Spring Data; `buscar` (JPQL con `LIKE ... escape`) y `existeActivo(id)` |
| `ProveedorService` | Reglas, normalización de opcionales y anotaciones `@Auditable` |
| `ProveedorController` | Los cinco endpoints con `@PreAuthorize` |
| `CrearProveedorRequest` / `ActualizarProveedorRequest` / `ProveedorResponse` | DTOs (records) |

Sin migración nueva: la tabla viene de V1 y `idx_proveedores_nombre` de V2.

## Reglas de negocio implementadas

- **Roles.** Lectura: `ADMIN`, `COMPRAS`, `CONSULTA`. Escritura: `ADMIN`, `COMPRAS`.
  `VENTAS` y `PRODUCCION` → `403 SIN_PERMISO`.
- **Nombre obligatorio**, ≤ 100, se recorta con `trim`. `contacto` ≤ 100, `telefono` ≤ 20.
- **Correo opcional**, `@Email`, ≤ 100, **sin `UNIQUE`**: varios proveedores pueden
  compartirlo. `""` o en blanco se guarda `null`.
- **Estado fijado en el alta.** `crear` asigna `activo = true` de forma explícita; el cuerpo
  no lo controla (Hibernate enviaría `NULL` y el `DEFAULT` de la base no aplicaría).
- **PUT = reemplazo completo.** `correo`, `contacto` o `telefono` en `null` borran el valor
  anterior. El estado solo cambia con el `PATCH`.
- **Desactivar no consulta compras.** Las compras `PENDIENTE` de un proveedor desactivado
  siguen su curso. `ProveedorRepository.existeActivo(id)` queda listo para HU-13, que
  rechazará compras nuevas con `PROVEEDOR_INACTIVO` (R-02); el código se valida allí.
- **Auditoría (`@Auditable`, tabla `proveedores`).**

| Acción | Detalle registrado |
|---|---|
| `CREAR` | `Proveedor creado: <nombre>` |
| `EDITAR` | `Proveedor editado: <nombre>` |
| `CAMBIAR_ESTADO` | `Proveedor <id> estado <true/false>` |
