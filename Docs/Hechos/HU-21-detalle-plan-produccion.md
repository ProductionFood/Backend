# HU-21 — Productos del plan de producción · Backend verificado

| | |
|---|---|
| Historia | HU-21 — Productos en plan de producción (cantidad planificada vs. producida) |
| Módulo | Producción |
| Endpoints | `GET /api/v1/planes-produccion/{idPlan}/detalles`, `POST /api/v1/planes-produccion/{idPlan}/detalles`, `PUT /api/v1/planes-produccion/{idPlan}/detalles/{idDetalle}`, `PATCH /api/v1/planes-produccion/{idPlan}/detalles/{idDetalle}/producido`, `DELETE /api/v1/planes-produccion/{idPlan}/detalles/{idDetalle}` |
| Colección Postman | `Docs/Postman/ProductionFood-HU21.postman_collection.json` (`Preparación`, `Detalles (CP-01…CP-23)`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Fecha de implementación | 2026-11-05 |
| Estado QA | **QA ciego verificado 2026-11-05 · 23/23 CP, 25/25 SEC, matriz de roles 25/25 y SQL 4/4 · frontend pendiente** |

> Esta ficha documenta las líneas de producto de un plan de producción: qué se planifica, cuánto
> se ha producido, el avance y el consumo teórico estimado de materia prima. La cabecera y sus
> estados son de HU-20; el consumo real de materia prima y su descuento de inventario son de
> HU-22 y HU-23. El contrato práctico para el frontend está en el manual
> [`../Manuales_Consumo_Frontend/HU-21-detalle-plan-produccion.md`](../Manuales_Consumo_Frontend/HU-21-detalle-plan-produccion.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/planes-produccion/{idPlan}/detalles` | ADMIN, PRODUCCION, CONSULTA | 200/400/401/403/404 | Listado paginado de líneas con producto embebido, cantidades, avance y consumo teórico estimado; `page` (def. 0), `size` (def. 20, 1…100) y `sort` (def. `producto,asc`) |
| POST | `/api/v1/planes-produccion/{idPlan}/detalles` | ADMIN, PRODUCCION | 201/400/401/403/404/409 | Agrega un producto a un plan `PLANIFICADO`; `Location: /api/v1/planes-produccion/{idPlan}/detalles/{idDetalle}` |
| PUT | `/api/v1/planes-produccion/{idPlan}/detalles/{idDetalle}` | ADMIN, PRODUCCION | 200/400/401/403/404/409 | Edita `cantidadPlanificada` solo mientras el plan está `PLANIFICADO` |
| PATCH | `/api/v1/planes-produccion/{idPlan}/detalles/{idDetalle}/producido` | ADMIN, PRODUCCION | 200/400/401/403/404/409 | Registra `cantidadProducida` solo con el plan `EN_PROCESO`; cero válido y sin tope superior |
| DELETE | `/api/v1/planes-produccion/{idPlan}/detalles/{idDetalle}` | ADMIN, PRODUCCION | 204/400/401/403/404/409 | Quita una línea de un plan `PLANIFICADO`; se bloquea si la línea ya tiene consumos |

**Dos escrituras para dos momentos.** `PUT` planifica —solo con el plan `PLANIFICADO`— y `PATCH`
produce —solo con el plan `EN_PROCESO`—. Un único `PUT` que aceptara ambos campos permitiría
reescribir el objetivo después de producir y la comparación planificado-vs-producido de HU-26
dejaría de medir nada (R-01).

**Roles.** Lectura —listado— para `ADMIN`, `PRODUCCION` y `CONSULTA`; escritura —alta, edición,
registro de producción y borrado— solo para `ADMIN` y `PRODUCCION`. `VENTAS` y `COMPRAS` no
participan en la planificación de la producción y reciben `403 SIN_PERMISO` en los cinco
endpoints.

Piezas internas:

| Pieza | Rol |
|---|---|
| `DetallePlanProduccion` | POJO de la línea: `idDetallePlan`, `idPlan`, `idProducto`, nombre y unidad del producto, cantidades planificada y producida, y `tieneReceta` |
| `DetallePlanRowMapper` | Traduce la fila del `JOIN` con `productos` y `unidades_medida`; lee por nombre de columna |
| `DetallePlanRepository` | JDBC de `listarPorPlan`, `contarPorPlan`, `buscarPorId`, `buscarPorIdParaActualizar` (`FOR UPDATE`), `insertar`, `actualizarPlanificada`, `actualizarProducida`, `existeProducto`, `tieneConsumos` y `eliminar`; lista blanca `ORDEN` para el ordenamiento |
| `CalculadoraConsumoService` | Cálculo puro, sin repositorios: `(cantidadPlanificada / receta.cantidadProducir) × detalleReceta.cantidad`, escala 3 `HALF_UP`, una sola vez al final; reutilizable por HU-22 |
| `ConsumoTeorico` | Record de dominio `materiaPrima`, `cantidad`, `unidad` |
| `DetallePlanService` | Listado, alta, edición de lo planificado, registro de producción y borrado; valida estados, pertenencia de la línea, cantidades, producto y duplicados; `@Transactional` y `@Auditable` en las escrituras |
| `DetallePlanController` | Los cinco endpoints con `@PreAuthorize` y Swagger; solo despacha al servicio |
| `PlanInexistenteException` | 404 con código propio `PLAN_INEXISTENTE`, distinto del transversal `RECURSO_NO_ENCONTRADO` |
| `AgregarDetallePlanRequest` / `ActualizarPlanificadaRequest` / `RegistrarProducidaRequest` | Cuerpos de alta, edición y producción; solo exigen presencia, para que las cantidades inválidas lleguen al servicio como `CANTIDAD_INVALIDA` |
| `DetallePlanResponse` / `ProductoRefResponse` / `ConsumoTeoricoResponse` | Respuesta de una línea, producto embebido y consumo teórico por materia prima |

**Sin migración Flyway nueva.** V1 crea `detalle_plan_produccion` con su PK, llaves foráneas e
índices; V2 añade `uk_detalle_plan (id_plan, id_producto)` y los `CHECK ck_dpp_planificada
(cantidad_planificada > 0)` y `ck_dpp_producida (cantidad_producida >= 0)`. Esta historia solo
escribe código sobre el esquema existente.

## Reglas de negocio implementadas

- **R-01 · planificar y producir son operaciones distintas.** `PUT` cambia la cantidad
  planificada y `PATCH .../producido` registra el avance; ninguno acepta el campo del otro.
- **R-02 · `cantidad_planificada` solo se edita con el plan `PLANIFICADO`.** Cualquier otro
  estado responde `409 PLAN_NO_EDITABLE`. El alta y el borrado aplican la misma condición: un
  plan en proceso ya comprometió sus líneas y uno terminal cerró su ciclo.
- **R-03 · `cantidad_producida` solo se registra con el plan `EN_PROCESO`.** Antes no se ha
  empezado y después el plan ya cerró; ambos casos responden `409 PLAN_NO_EN_PROCESO`.
- **R-04 · se puede producir más de lo planificado.** No hay tope superior: `avance` puede
  superar `100.00` —100 sobre 90 responde `111.11`— y sigue siendo un dato real que HU-26 debe
  mostrar. La interfaz lo señala, no lo impide.
- **R-05 · un producto no se repite en el mismo plan.** El alta comprueba
  `existeProducto(idPlan, idProducto)` antes de insertar y responde
  `409 PRODUCTO_DUPLICADO`; la base lo respalda con `uk_detalle_plan` (error 1062 por SQL
  directo). Para producir más del mismo producto se edita la cantidad, no se agrega otra línea.
- **R-06 · un producto sin receta se puede planificar, pero no tiene consumo teórico.** Se
  responde `tieneReceta: false` y `consumoTeoricoEstimado: []`, nunca `null`; el producto queda
  fuera del análisis de eficiencia de HU-26.
- **R-07 · registrar producción no crea el lote ni suma stock.** El `PATCH` solo actualiza
  `cantidad_producida`: `DetallePlanService` no depende de inventario, lotes ni kardex, y no se
  escribe en `inventario_producto_terminado` ni en `movimientos_inventario_pt`. Producir y
  registrar el lote (HU-19) siguen siendo acciones separadas.
- **R-08 · quitar una línea con consumos registrados.** El borrado comprueba
  `tieneConsumos(idDetalle)` y responde `409 DETALLE_CON_CONSUMOS`; la línea se bloquea con
  `SELECT ... FOR UPDATE` para que no aparezca un consumo entre la comprobación y el `DELETE`.
  **Decisión añadida:** el borrado solo se permite con el plan `PLANIFICADO`; en cualquier otro
  estado responde `409 PLAN_NO_EDITABLE`, porque quitar una línea de un plan ya iniciado
  alteraría el compromiso y la eficiencia histórica.

## Decisiones clave

| Tema | Decisión implementada |
|---|---|
| Separación planificar/producir | `PUT` y `PATCH .../producido` con estados distintos; el DTO de cada uno declara un solo campo |
| Borrado | Solo con plan `PLANIFICADO` y sin consumos; `FOR UPDATE` antes de comprobar consumos y borrar |
| Avance | `cantidadProducida × 100 / cantidadPlanificada`, escala 2 `HALF_UP`: 60/90 = `66.67`, 100/90 = `111.11` |
| Consumo teórico | Escala 3 `HALF_UP` calculada una sola vez; el servicio es puro y lo reutilizará HU-22 |
| Línea de otro plan | La pertenencia se comprueba en el servicio; una línea ajena responde `404 RECURSO_NO_ENCONTRADO`, no se edita ni se borra |
| Cantidades | Se normalizan a escala 2 antes de comparar; `0.001` queda en cero y responde `CANTIDAD_INVALIDA`, no un error de integridad del `CHECK` |
| Validación de forma | Los DTO no llevan `@Positive`/`@PositiveOrZero`: un cero o un negativo llega al servicio y responde `CANTIDAD_INVALIDA` (400), no `VALIDACION_FALLIDA` |
| Producto | Debe existir y estar activo; si no, `409 PRODUCTO_INEXISTENTE` |
| Listado | Carga de recetas e ingredientes en lote (dos consultas por página, no una por línea) para evitar el N+1 |
| Orden | Lista blanca `producto`, `cantidadPlanificada` y `avance`; por defecto `producto,asc`; otro campo o dirección responde `400 CAMPO_ORDEN_INVALIDO` |
| Inventario | Ninguna operación toca inventario, lotes ni kardex; el servicio no tiene dependencias de esas áreas |
| Auditoría | `@Auditable` en `CREAR`, `EDITAR` y `ELIMINAR` sobre `detalle_plan_produccion`, con HU-04 |

## Modelo de datos

**Tabla `detalle_plan_produccion`** (V1 la crea; V2 añade el `UNIQUE` y los dos `CHECK`)

| Columna | Tipo | Notas |
|---|---|---|
| `id_detalle_plan` | `int unsigned` PK AI | |
| `id_plan` | `int unsigned` NOT NULL FK | CA-01; FK a `planes_produccion` |
| `id_producto` | `int unsigned` NOT NULL FK | CA-02; FK a `productos` |
| `cantidad_planificada` | `decimal(10,2)` NOT NULL | CA-03; `ck_dpp_planificada` exige `> 0` |
| `cantidad_producida` | `decimal(10,2)` NOT NULL DEFAULT 0.00 | CA-04; `ck_dpp_producida` exige `>= 0` |

`uk_detalle_plan (id_plan, id_producto)` impide el duplicado incluso por SQL directo. El alta no
envía `cantidad_producida`: la columna la inicializa con su `DEFAULT 0.00`, y la respuesta
releída de la base confirma el cero.

## Contrato resumido

**POST /api/v1/planes-produccion/9/detalles → 201** (con
`Location: /api/v1/planes-produccion/9/detalles/112`):

```json
// petición
{ "idProducto": 2, "cantidadPlanificada": 90.00 }
```

```json
// respuesta
{ "idDetallePlan": 112,
  "producto": { "idProducto": 2, "nombre": "Pan francés", "unidad": "kg" },
  "cantidadPlanificada": 90.00, "cantidadProducida": 0.00, "avance": 0.00,
  "tieneReceta": true,
  "consumoTeoricoEstimado": [
    { "materiaPrima": "Harina de trigo premium", "cantidad": 14.516, "unidad": "kg" },
    { "materiaPrima": "Levadura", "cantidad": 0.145, "unidad": "L" }
  ] }
```

**GET /api/v1/planes-produccion/9/detalles → 200** — `PageResponse` con `content`, `page`,
`size`, `totalElements`, `totalPages`, `first` y `last`; por defecto `page=0`, `size=20`,
`sort=producto,asc`. Cada elemento de `content` tiene la forma anterior. El
`consumoTeoricoEstimado` de un producto con receta se calcula con la fórmula de HU-12 R-02:
`(90 / 30) × 5 kg = 15.000 kg`.

**PUT /api/v1/planes-produccion/9/detalles/112 → 200**:

```json
// petición
{ "cantidadPlanificada": 95.00 }

// respuesta
{ "idDetallePlan": 112,
  "producto": { "idProducto": 2, "nombre": "Pan francés", "unidad": "kg" },
  "cantidadPlanificada": 95.00, "cantidadProducida": 0.00, "avance": 0.00,
  "tieneReceta": true, "consumoTeoricoEstimado": [ /* recalculado sobre 95 */ ] }
```

**PATCH /api/v1/planes-produccion/9/detalles/112/producido → 200**:

```json
// petición
{ "cantidadProducida": 60.00 }

// respuesta
{ "idDetallePlan": 112,
  "producto": { "idProducto": 2, "nombre": "Pan francés", "unidad": "kg" },
  "cantidadPlanificada": 90.00, "cantidadProducida": 60.00, "avance": 66.67,
  "tieneReceta": true, "consumoTeoricoEstimado": [ /* ... */ ] }
```

Con `{ "cantidadProducida": 100.00 }` sobre la misma línea de 90, `avance` responde `111.11`
(R-04). Con `{ "cantidadProducida": 0 }` responde `200` y `avance: 0.00`: cero es un registro
válido.

**Producto sin receta → 201/200:**

```json
{ "idDetallePlan": 114,
  "producto": { "idProducto": 17, "nombre": "QA-H12B-1792974032-P1", "unidad": "und" },
  "cantidadPlanificada": 10.00, "cantidadProducida": 0.00, "avance": 0.00,
  "tieneReceta": false, "consumoTeoricoEstimado": [] }
```

**DELETE /api/v1/planes-produccion/9/detalles/112 → 204** sin cuerpo. Con consumos registrados
responde `409 DETALLE_CON_CONSUMOS`; con el plan fuera de `PLANIFICADO`, `409 PLAN_NO_EDITABLE`.

**Errores:** `PLAN_NO_EDITABLE`, `PLAN_NO_EN_PROCESO`, `PRODUCTO_INEXISTENTE`,
`PRODUCTO_DUPLICADO` y `DETALLE_CON_CONSUMOS` (409); `CANTIDAD_INVALIDA`,
`VALIDACION_FALLIDA`, `PARAMETRO_INVALIDO` y `CAMPO_ORDEN_INVALIDO` (400); `PLAN_INEXISTENTE` y
`RECURSO_NO_ENCONTRADO` (404); `SIN_PERMISO` y `USUARIO_INACTIVO` (403); `NO_AUTENTICADO` (401).

## Archivos creados

```
src/main/java/co/edu/corposucre/productionfood/
├── produccion/
│   ├── CalculadoraConsumoService.java
│   ├── ConsumoTeorico.java
│   ├── DetallePlanProduccion.java
│   ├── DetallePlanRowMapper.java
│   ├── DetallePlanRepository.java
│   ├── DetallePlanService.java
│   ├── DetallePlanController.java
│   ├── PlanInexistenteException.java
│   └── dto/
│       ├── AgregarDetallePlanRequest.java
│       ├── ActualizarPlanificadaRequest.java
│       ├── RegistrarProducidaRequest.java
│       ├── DetallePlanResponse.java
│       ├── ProductoRefResponse.java
│       └── ConsumoTeoricoResponse.java
└── receta/
    └── RecetaRepository.java        (añade buscarPorProductos para la carga en lote)

src/test/java/co/edu/corposucre/productionfood/
├── produccion/
│   ├── CalculadoraConsumoServiceTest.java
│   ├── DetallePlanRepositoryTest.java
│   ├── DetallePlanServiceTest.java
│   ├── DetallePlanControllerTest.java
│   └── DetallePlanSeguridadTest.java
└── receta/
    └── RecetaRepositoryBusquedaProductosTest.java
```

## Evidencia (pruebas backend)

| Clase | Casos | Cobertura |
|---|---|---|
| `CalculadoraConsumoServiceTest` | 7 | Fórmula de R-02/R-06, orden de ingredientes, escala 3 `HALF_UP`, sin receta, rendimiento cero y cantidad nula |
| `DetallePlanRepositoryTest` | 12 | Integración JDBC contra MySQL: alta con `DEFAULT 0.00`, listado y conteo, orden de la lista blanca, búsqueda bloqueada con `FOR UPDATE`, actualización de cantidades, `existeProducto` y `tieneConsumos` |
| `DetallePlanServiceTest` | 30 | R-01 a R-08: estados, cantidades, duplicados, producto inexistente, línea de otro plan, avance 66.67/111.11, consumo teórico, borrado con y sin consumos, `PLAN_INEXISTENTE` y ausencia de dependencias de inventario |
| `DetallePlanControllerTest` | 19 | Contrato HTTP: 201 + `Location`, 200, 204, `CANTIDAD_INVALIDA` frente a `VALIDACION_FALLIDA`, 400/404/409, paginación, orden y matriz de roles |
| `DetallePlanSeguridadTest` | 45 | SEC-01…SEC-05 parametrizados en los cinco endpoints (34 negativos de seguridad + 11 positivos de rol) |
| `RecetaRepositoryBusquedaProductosTest` | 3 | `buscarPorProductos` en lote: devuelve solo las recetas pedidas con los datos del `JOIN`, una lista vacía o `null` devuelve lista vacía, y omite productos sin receta o no pedidos |
| **Total de la HU** | **116** | |

`mvn test` sobre la suite completa: **1699 tests, 0 fallos, 0 errores, 0 skipped**.

## Smoke y QA ciego (2026-11-05)

- **Smoke propio:** alta de un plan, alta de líneas con receta y sin receta, edición de lo
  planificado, registro de producción, borrado, y comprobación en vivo de `409`, `404`, `400` y
  del caso crítico SEC-04. Todos respondieron el contrato documentado.
- **QA ciego:** CP-01…CP-23 = **23/23 PASA**; SEC-01…SEC-05 en los cinco endpoints =
  **25/25 PASA**; matriz de roles = **25/25 PASA**; verificaciones SQL = **4/4 PASA**.
- **Defectos:** cero críticos, altos, medios ni bajos.
- **SQL verificado:** `cantidad_producida` nace en `0.00` por el `DEFAULT` (CP-02); el `INSERT`
  directo que repite `(id_plan, id_producto)` falla con error **1062** por `uk_detalle_plan`
  (CP-17); el consumo teórico coincide con la receta y con `ROUND(cantidad_planificada /
  cantidad_producir * detalle_receta.cantidad, 3)` (CP-18); registrar producción no deja filas
  en `movimientos_inventario_pt` con `tabla_origen = 'detalle_plan_produccion'` (CP-20, R-07).
- La colección cubre los casos HTTP; los chequeos de integridad de CP-17, CP-18, CP-20 y CP-22
  se ejecutan con el SQL anotado en la misma colección y en `PFDocs/HU-21/tarea-qa.md`.

## Desviaciones vs especificación

- **`DELETE` solo con el plan `PLANIFICADO`.** La especificación solo exigía R-08 (no borrar una
  línea con consumos). Se añadió la condición de estado para no reescribir el compromiso de un
  plan ya iniciado; fuera de `PLANIFICADO` responde `409 PLAN_NO_EDITABLE`.
- **Las escrituras devuelven la línea completa, con `consumoTeoricoEstimado`.** El ejemplo de la
  especificación para el `PATCH` muestra solo `idDetallePlan`, cantidades y `avance`; la
  implementación devuelve `DetallePlanResponse` completo para que el frontend refresque la fila
  sin una segunda llamada. El `PATCH` recalcula el consumo teórico si la cantidad planificada
  cambió.

## Pendientes / deuda

- **R-07 · registrar producción no crea lote ni stock.** Completar el avance no genera el lote de
  producto terminado ni suma a `inventario_producto_terminado`; el lote se registra aparte en
  HU-19. Es el hueco más visible del flujo: un plan puede completarse y el producto no aparecer
  como stock vendible.
- **HU-22 · consumo real de materia prima.** `consumoTeoricoEstimado` es informativo; el consumo
  real y su descuento de inventario son de HU-22. `CalculadoraConsumoService` queda listo para
  reutilizarse allí.
- **HU-23 · impacto en inventario.** Ninguna operación de esta historia mueve kardex ni saldos.
- **R-08 · borrado con consumos.** Solo se puede comprobar cuando HU-22 registre consumos; hasta
  entonces el caso se valida insertando una fila de `consumo_materia_prima` por SQL.
- **Frontend pendiente.** No existe pantalla de productos del plan; el manual de consumo es el
  contrato para Angular.
- **Datos de QA en la base.** Quedaron planes, líneas y productos del sufijo de la ejecución de
  QA. Son datos de prueba locales; no forman parte del negocio ni viajan con el repositorio.
