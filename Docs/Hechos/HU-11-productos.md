# HU-11 — Productos · HECHA (backend)

| | |
|---|---|
| Fecha de entrega | 2026-10-23 |
| Commits / PR | Rama `HU-11/gestion-productos`; ver «Entregado en» |
| Colección Postman | `Docs/Postman/ProductionFood-HU11.postman_collection.json` (`Preparación`, `CRUD (CP-01…CP-21)`, `Roles (matriz)`, `Seguridad (SEC-01…05)`, `SQL de verificación`) |
| Estado QA | pendiente con el equipo de QA |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| GET | `/api/v1/productos` | 200/400/401/403 | Listado paginado con filtros `nombre` y `activo`, orden de lista blanca y `tieneReceta`/`stockDisponible` calculados |
| GET | `/api/v1/productos/{id}` | 200/400/401/403/404 | Obtener un producto con su unidad de venta, si tiene receta y su stock agregado |
| POST | `/api/v1/productos` | 201/400/401/403/409 | Registrar un producto activo (**sin** crear fila de inventario) |
| PUT | `/api/v1/productos/{id}` | 200/400/401/403/404/409 | Reemplazar nombre, descripción, precio y unidad de venta |
| PATCH | `/api/v1/productos/{id}/estado` | 200/400/401/403/404 | Activar o desactivar; desactivar con existencias devuelve `advertencia` sin bloquear |

**No existe `DELETE`** (R-05): `DELETE /api/v1/productos/1` responde `405 METODO_NO_PERMITIDO`.
La baja es la desactivación por `PATCH`: `detalle_pedido`, `lotes`, `recetas` y
`detalle_plan_produccion` referencian el producto.

**Roles.** Lectura: `ADMIN`, `VENTAS`, `PRODUCCION` y `CONSULTA`. **`COMPRAS` no lee productos**
(`403 SIN_PERMISO`) — es el espejo invertido de HU-08, donde quien quedaba fuera era VENTAS.
Escritura: solo `ADMIN`.

Piezas internas:

| Pieza | Rol |
|---|---|
| `Producto` | Entidad JPA sobre `productos` (`id_producto`, `nombre`, `descripcion`, `precio`, `id_unidad`, `estado`) |
| `ProductoRepository` | Spring Data; `listarConDetalle` y `buscarConDetalle` (nativas con `LEFT JOIN recetas` y `LEFT JOIN v_stock_producto_terminado`) y `existeActivo` (para HU-12, HU-17, HU-19 y HU-21) |
| `ProductoConDetalle` | Proyección de interfaz con `idReceta` y `stockDisponible` |
| `ProductoService` | Validación de unidad, redondeo del precio, lista blanca de orden, advertencia de stock y `@Auditable` |
| `ProductoController` | Los cinco endpoints con `@PreAuthorize` y Swagger |
| DTOs | `CrearProductoRequest`, `ActualizarProductoRequest`, `ProductoResponse` y `UnidadRefResponse` (movido a `common.dto`) |

Sin migración Flyway: `productos` ya existe con las correcciones de V2 (`estado DEFAULT 1`,
`CHECK (precio >= 0)`) y la vista `v_stock_producto_terminado` (§C-02).

## Reglas de negocio implementadas

- **CA-01 · nombre obligatorio, máx. 100.** `@NotBlank` + `@Size(max = 100)`; el texto se
  recorta con `trim`. Falta → `400 VALIDACION_FALLIDA` con `fieldErrors`.
- **CA-02 · descripción opcional, máx. 150.** `@Size(max = 150)` sin `@NotBlank`. El servicio
  recorta y convierte el blanco en `null`: hay una sola representación de «sin descripción».
- **CA-03 · precio obligatorio y no negativo.** `@NotNull` + `@DecimalMin("0.00")` +
  `@DecimalMax("99999999.99")` (el dominio de `decimal(10,2)`). `BigDecimal`, nunca `double`.
- **CA-04 · la unidad de venta debe existir.** Validada contra `UnidadMedidaRepository`; si no,
  `409 UNIDAD_INEXISTENTE`, en el alta y en la edición.
- **CA-05 · nace activo.** El servicio fija `estado = true`; el `DEFAULT 1` de V2 es la red de
  seguridad para inserciones externas.
- **CA-06 · listado paginado con filtro por nombre.** Prefijo sin distinguir mayúsculas
  (`FiltroTexto.patronPrefijo`, con comodines escapados), filtro `activo` opcional y
  combinable. Orden de lista blanca `{nombre, precio, id}` con default `nombre,asc`; cualquier
  otro campo → `400 CAMPO_ORDEN_INVALIDO`.
- **R-01 · cambiar el precio no afecta a pedidos ya registrados.** `PUT` solo escribe
  `productos.precio`; las líneas de `detalle_pedido` guardan su propio
  `precio_unitario` y no se recalculan (se verifica de punta a punta en HU-17).
- **R-02 · un producto sin receta no se puede planificar.** El listado y el detalle traen
  `tieneReceta` (derivado del `idReceta`), para que el hueco sea visible sin navegar a HU-12.
- **R-03 · desactivar no retira el stock.** El `PATCH` solo cambia `estado`; si el producto aún
  tiene existencias, la respuesta 200 incluye `advertencia` con la cantidad conservada y la
  operación **no se bloquea**. El cambio es idempotente.
- **R-04 · la unidad del producto es la de venta.** Se guarda tal cual en `productos.id_unidad`;
  la unidad de producción es la de la receta (HU-12) y pueden diferir.
- **R-05 · no se elimina.** No hay `DELETE` ni endpoint de borrado.
- **R-06 · precio cero es válido.** Sirve para muestras y promociones; el `CHECK (>= 0)` lo
  admite.
- **Asimetría con HU-08: el alta NO crea inventario.** El stock de producto terminado es por
  lote (`inventario_producto_terminado`, HU-19) y se agrega en `v_stock_producto_terminado`.
  `ProductoService` ni siquiera depende de un repositorio de inventario.
- **Redondeo del precio (CP-10).** Tres decimales no se rechazan: el servicio aplica
  `setScale(2, HALF_UP)` antes de guardar, de modo que `1200.555` queda en `1200.56` sin
  depender de que MySQL devuelva la fila redondeada.
- **Auditoría.** `@Auditable` en `CREAR`, `EDITAR` y `CAMBIAR_ESTADO` sobre `productos`.
- **`existeActivo(id)`** queda expuesto en el servicio para HU-12, HU-17, HU-19 y HU-21.

## Contrato resumido

**POST /api/v1/productos → 201** (con cabecera `Location: /api/v1/productos/2`):

```json
// petición
{ "nombre": "Pan francés", "descripcion": "Corteza crujiente, 80 g",
  "precio": 1200.00, "idUnidad": 1 }
```

```json
// respuesta (captura real del MySQL local)
{ "idProducto": 2, "nombre": "Pan francés",
  "descripcion": "Corteza crujiente, 80 g", "precio": 1200.00,
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "activo": true, "tieneReceta": false, "stockDisponible": 0.00 }
```

**PATCH /api/v1/productos/2/estado → 200** con stock (captura real):

```json
{ "idProducto": 2, "nombre": "Pan francés",
  "descripcion": "Corteza crujiente, 80 g", "precio": 1200.00,
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "activo": false, "tieneReceta": false, "stockDisponible": 24.00,
  "advertencia": "El producto conserva 24 kg de stock disponible; desactivarlo no lo retira del inventario (R-03)." }
```

`advertencia` **solo aparece cuando hay algo que avisar** (desactivación con stock > 0); en el
resto de respuestas la clave no viaja. `descripcion` sí viaja siempre, con `null` cuando no la
hay (CP-04), porque la inclusión global de Jackson es `non_null` y el DTO la fuerza con
`@JsonInclude(ALWAYS)` en ese componente.

**GET /api/v1/productos → 200** — `PageResponse` con `content`, `page`, `size`,
`totalElements`, `totalPages`, `first` y `last`.

## Evidencia (verificación backend)

- `mvn clean test`: **452 tests, 0 fallos** (365 previos + 87 de esta HU: 18 de servicio,
  18 de controlador, 35 de seguridad parametrizada, 8 + 8 de DTOs).
- Verificación en caliente contra MySQL 8.4 local (puerto 8085):
  - `GET /productos` → 200 con la fila real `tieneReceta: true` para el producto que ya tenía
    receta de la verificación de HU-08 y `descripcion: null` presente.
  - Filtro `?nombre=Pan` → 200; `?sort=precio,desc` → 200; `?sort=estado` → 400
    `CAMPO_ORDEN_INVALIDO`.
  - `POST` válido → 201 + `Location`; precio `1200.555` → `1200.56`; precio `0` → 201;
    unidad `9999` → 409 `UNIDAD_INEXISTENTE`; nombre 101 y descripción 151 → 400.
  - `PUT` → 200 con el precio nuevo; `GET /9999` → 404; `DELETE` → 405.
  - Insertado un lote de 24 unidades en `lotes` + `inventario_producto_terminado`:
    `GET /productos/2` → `stockDisponible: 24.00`; `PATCH` desactivar → 200 con `advertencia`
    y el stock intacto; reactivar → 200 sin `advertencia`.
  - Matriz de roles completa (4 roles leen, COMPRAS 403; los 4 no-ADMIN 403 en POST/PUT/PATCH);
    SEC-01 y SEC-02 en caliente → 401.
  - SQL: `CHECK ck_productos_precio` rechaza `-100` (error 3819); `estado` con
    `DEFAULT '1'`; `productos` sin receta listados con `LEFT JOIN`; ninguna fila de inventario
    por producto creada por el alta.
  - Bitácora: `CREAR×3`, `EDITAR×1`, `CAMBIAR_ESTADO×2` con `tablaAfectada = productos`.

## Desviaciones vs especificación

- **`PRODUCTO_CON_STOCK` no se emite como código de error.** La especificación lo lista en §5
  como «advertencia no bloqueante»: un 409 es un conflicto y aquí la desactivación sí procede.
  Se resuelve con **200 + campo `advertencia`** en `ProductoResponse` (decisión acordada con el
  usuario). El frontend muestra el aviso sin revertir el estado.
- **Redondeo del precio en el servicio** (CP-10) en lugar de `@Digits(fraction = 2)`: el
  contrato de HU-11 espera redondeo, no rechazo, y así no depende del motor.
- **`UnidadRefResponse` movido de `materiaprima.dto` a `common.dto`** para compartirlo entre
  HU-08 y HU-11 sin duplicarlo.

## Pendientes / deuda

- **CP-16** (el precio de un pedido ya registrado no cambia al subir el del catálogo) no se
  puede ejecutar todavía: `detalle_pedido` se escribe en HU-17. La regla ya está garantizada
  por diseño —HU-11 no escribe en `detalle_pedido`— y la prueba de punta a punta queda como
  criterio de cierre de HU-17.
- **CP-21** («crear la receta en HU-12 y volver a consultar») se verificó con la receta que ya
  existía en la base de desarrollo; el caso completo se cierra cuando HU-12 pueda crear recetas
  por API.
- La unidad de venta se tomó del catálogo de HU-07; en la verificación en caliente se usó
  `idUnidad = 1` (Kilogramo) por comodidad, no porque el dominio exija esa unidad.
