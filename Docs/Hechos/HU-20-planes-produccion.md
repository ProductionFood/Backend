# HU-20 — Planes de producción · Backend verificado

| | |
|---|---|
| Historia | HU-20 — Creación de plan de producción (cabecera, estados y edición) |
| Módulo | Producción |
| Endpoints | `GET /api/v1/planes-produccion`, `GET /api/v1/planes-produccion/{id}`, `POST /api/v1/planes-produccion`, `PUT /api/v1/planes-produccion/{id}`, `PATCH /api/v1/planes-produccion/{id}/estado` |
| Colección Postman | `Docs/Postman/ProductionFood-HU20.postman_collection.json` (`Preparación`, `Planes (CP-01…CP-23)`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Fecha de implementación | 2026-11-05 |
| Estado QA | **QA ciego verificado 2026-11-05 · 23/23 CP, 25/25 SEC, matriz de roles 25/25 y SQL 4/4 · frontend pendiente** |

> Esta ficha documenta la cabecera de un plan de producción: cuándo se planifica, cuándo se
> produce, quién es el responsable y en qué estado está. Los productos del plan son de HU-21;
> el consumo de materia prima y su efecto sobre el inventario son de HU-22 y HU-23. El contrato
> práctico para el frontend está en el manual
> [`../Manuales_Consumo_Frontend/HU-20-planes-produccion.md`](../Manuales_Consumo_Frontend/HU-20-planes-produccion.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/planes-produccion` | ADMIN, PRODUCCION, CONSULTA | 200/400/401/403 | Listado paginado con filtros `estado`, `idUsuario`, `fechaInicio` y `fechaFin`; responsable embebido y número de líneas |
| GET | `/api/v1/planes-produccion/{id}` | ADMIN, PRODUCCION, CONSULTA | 200/400/401/403/404 | Detalle del plan con su responsable, su estado y `editable` |
| POST | `/api/v1/planes-produccion` | ADMIN, PRODUCCION | 201/400/401/403/409 | Registra la cabecera en estado `PLANIFICADO`; `Location: /api/v1/planes-produccion/{id}` |
| PUT | `/api/v1/planes-produccion/{id}` | ADMIN, PRODUCCION | 200/400/401/403/404/409 | Edita fechas, observaciones y responsable solo si el plan sigue `PLANIFICADO` |
| PATCH | `/api/v1/planes-produccion/{id}/estado` | ADMIN, PRODUCCION | 200/400/401/403/404/409 | Cambia el estado según la máquina de R-01 y exige líneas al iniciar |

**No existe `DELETE`.** Responde `405 METODO_NO_PERMITIDO`. Un plan referencia a su
responsable y sus líneas de detalle son el compromiso de producción que gestiona HU-21; el
cierre correcto es `CANCELADO` o `COMPLETADO`, no el borrado.

**Roles.** Lectura —listado y detalle— para `ADMIN`, `PRODUCCION` y `CONSULTA`; escritura —alta,
edición y transición de estado— solo para `ADMIN` y `PRODUCCION`. `VENTAS` y `COMPRAS` no
participan en la planificación de la producción y reciben `403 SIN_PERMISO` en los cinco
endpoints.

Piezas internas:

| Pieza | Rol |
|---|---|
| `EstadoPlan` | Enum `PLANIFICADO`, `EN_PROCESO`, `COMPLETADO` y `CANCELADO`; la máquina de transiciones vive solo aquí, con `transicionesValidas()` y `puedeTransicionarA()` |
| `PlanProduccion` | POJO de la cabecera con las proyecciones de solo lectura `nombreResponsable` y `cantidadProductos` |
| `PlanProduccionRowMapper` | Traduce la fila del `JOIN` con `usuarios` y la subconsulta de líneas; lee por nombre de columna |
| `PlanProduccionRepository` | JDBC de `buscar`, `contar`, `buscarPorId`, `insertar`, `actualizarSiEstado`, `cambiarEstado` y `contarDetalles`; lista blanca `ORDEN` y `ORDER BY ... p.id_plan DESC` como desempate |
| `PlanProduccionService` | Alta, listado, detalle, edición, transición, `esEditable` y resolución del responsable; `@Transactional` y `@Auditable` en las escrituras |
| `PlanProduccionController` | Los cinco endpoints con `@PreAuthorize` y Swagger; no hay `DELETE` |
| `CrearPlanRequest` / `ActualizarPlanRequest` | Fechas obligatorias, observaciones de hasta 255 caracteres e `idUsuario` opcional; **no aceptan `estado`** en la creación |
| `CambiarEstadoPlanRequest` | Solo `estado`; el servicio lo convierte de forma defensiva para responder con el código de negocio |
| `PlanResponse` | `idPlan`, fechas, `estado` como texto, `observaciones`, `responsable` embebido, `cantidadProductos` y `editable` |

**Sin migración Flyway nueva.** V1 crea `planes_produccion` y `detalle_plan_produccion` con sus
PK, llaves foráneas e índices; V2 añade el `CHECK ck_planes_estado`, la normalización de los
estados y el índice `idx_planes_fecha (fecha_produccion, estado)`. Esta historia solo escribe
código sobre el esquema existente.

## Reglas de negocio implementadas

- **R-01 · máquina de estados.** `PLANIFICADO → EN_PROCESO` o `PLANIFICADO → CANCELADO`;
  `EN_PROCESO → COMPLETADO` o `EN_PROCESO → CANCELADO`; `COMPLETADO` y `CANCELADO` son
  terminales. Un salto prohibido o una salida de un estado terminal responde
  `409 TRANSICION_INVALIDA` con el estado actual y los destinos permitidos en el mensaje. Una
  auto-transición tampoco está permitida.
- **R-02 · no se inicia un plan sin productos.** Solo al pasar a `EN_PROCESO` se cuenta
  `detalle_plan_produccion` con `contarDetalles`; si es cero, responde
  `409 PLAN_SIN_PRODUCTOS`. La comprobación va en el cambio de estado, no en el alta: un plan
  recién creado no tiene líneas y eso es normal.
- **R-03 · la producción no precede a la planificación.** Se valida antes de persistir y antes
  de editar; `fechaProduccion` anterior a `fechaPlanificacion` responde `400 FECHAS_INVALIDAS`.
  Ambas fechas pueden coincidir y una producción futura es válida.
- **R-04 · el responsable sale del token por defecto.** Sin `idUsuario`, o con un rol distinto
  de `ADMIN`, el plan queda a nombre del usuario autenticado. Solo un `ADMIN` que envía
  `idUsuario` puede apuntarlo a otro usuario, y ese usuario debe existir o responde
  `409 USUARIO_INEXISTENTE`. En la edición, sin `idUsuario` **se conserva el responsable actual
  del plan**, no el usuario que edita: guardar una edición no cambia de dueño el plan.
- **R-05 · la cabecera solo se edita en `PLANIFICADO`.** Cualquier otro estado responde
  `409 PLAN_NO_EDITABLE`. El `UPDATE` va condicionado a `estado = 'PLANIFICADO'`: si otra
  transacción cambió el plan entre la lectura y la escritura, afecta cero filas y también
  responde `PLAN_NO_EDITABLE`, en lugar de pisar un plan que dejó de ser editable.
- **R-06 · cancelar no revierte consumos.** Ninguna operación del servicio mueve inventario ni
  escribe en el kardex: el consumo lo registran HU-22 y HU-23. `PlanProduccionService` no
  depende de ningún repositorio de inventario, y el `UPDATE` de estado solo toca la columna
  `estado`.
- **R-07 · el plan no genera lotes.** Completar un plan no crea lotes de producto terminado.
  Producir y registrar el lote (HU-19) siguen siendo acciones separadas; nada genera el lote
  automáticamente.

## Decisiones clave

| Tema | Decisión implementada |
|---|---|
| Responsable del token | `resolverResponsable(idUsuarioSolicitado, idUsuarioPorDefecto)` devuelve el solicitado solo cuando quien llama es `ADMIN` y lo envió; en el alta el valor por defecto es el usuario autenticado y en la edición es el responsable actual del plan |
| Carrera al editar | `actualizarSiEstado(plan, PLANIFICADO)` incluye el estado en el `WHERE`; cero filas afectadas se traduce a `409 PLAN_NO_EDITABLE` |
| Carrera al transicionar | `cambiarEstado(id, estadoEsperado, estadoNuevo)` incluye el estado leído en el `WHERE`; cero filas afectadas se traduce a `409 TRANSICION_INVALIDA` |
| `esEditable` | Método del servicio que devuelve `true` solo si el plan existe y está `PLANIFICADO`. Se expone como `editable` en `PlanResponse` y es la consulta que HU-21 hará antes de tocar el detalle |
| Estado inicial | El DTO de alta no tiene `estado` y el servicio fija `PLANIFICADO`; un `estado` enviado en el cuerpo se ignora |
| Literal de estado no canónico | `parsearEstado` recorre el enum y responde `400 VALIDACION_FALLIDA` con la lista de admitidos, sin propagar una excepción de deserialización ni un 500 |
| Listado | `JOIN usuarios` para el nombre del responsable y subconsulta correlacionada para `cantidadProductos` en el mismo `SELECT`; `contar` usa los mismos filtros sin el `JOIN` |
| Orden | Lista blanca `fechaProduccion`, `fechaPlanificacion`, `estado` e `id`; por defecto `fechaProduccion,desc`; desempate fijo `p.id_plan DESC` |
| Inventario | Ninguna transición toca inventario ni kardex; el servicio no tiene dependencias de inventario y la prueba lo verifica |
| Auditoría | `@Auditable` en `CREAR`, `EDITAR` y `CAMBIAR_ESTADO` sobre `planes_produccion`, con HU-04 |

## Modelo de datos

**Tabla `planes_produccion`** (V1 la crea; V2 añade el `CHECK` y el índice)

| Columna | Tipo | Notas |
|---|---|---|
| `id_plan` | `int unsigned` PK AI | |
| `fecha_planificacion` | `date` NOT NULL | CA-01 |
| `fecha_produccion` | `date` NOT NULL | CA-01; R-03 |
| `estado` | `varchar(30)` NOT NULL | CA-02; `ck_planes_estado` limita a los cuatro valores canónicos |
| `observaciones` | `varchar(255)` NULL | CA-03 |
| `id_usuario` | `int unsigned` NOT NULL FK | CA-04; FK a `usuarios`, por eso el `INNER JOIN` del listado nunca pierde filas |

**Tabla `detalle_plan_produccion`** (V1 la crea; esta historia solo la cuenta)

| Columna | Tipo | Notas |
|---|---|---|
| `id_detalle_plan` | `int unsigned` PK AI | |
| `id_plan` | `int unsigned` NOT NULL FK | FK a `planes_produccion` |
| `id_producto` | `int unsigned` NOT NULL FK | Producto planificado |
| `cantidad_planificada` | `decimal(10,2)` NOT NULL | Compromiso de producción |
| `cantidad_producida` | `decimal(10,2)` NOT NULL DEFAULT 0.00 | Avance real |

`cantidadProductos` es el `COUNT(*)` de estas líneas para el plan. Un plan recién creado
responde `0`; las líneas las agrega HU-21 y el GET de detalle **no** las devuelve todavía.

## Contrato resumido

**POST /api/v1/planes-produccion → 201** (con `Location: /api/v1/planes-produccion/{id}`):

```json
// petición
{ "fechaPlanificacion": "2026-11-05", "fechaProduccion": "2026-11-12",
  "observaciones": "Producción para el pedido #47 y reposición de estante" }
```

```json
// respuesta
{ "idPlan": 12,
  "fechaPlanificacion": "2026-11-05", "fechaProduccion": "2026-11-12",
  "estado": "PLANIFICADO",
  "observaciones": "Producción para el pedido #47 y reposición de estante",
  "responsable": { "idUsuario": 2, "nombre": "Ismael Batalla" },
  "cantidadProductos": 0, "editable": true }
```

**GET /api/v1/planes-produccion → 200** — `PageResponse` con `content`, `page`, `size`,
`totalElements`, `totalPages`, `first` y `last`; cada elemento de `content` tiene la forma del
objeto anterior. Por defecto: `page=0`, `size=20`, `sort=fechaProduccion,desc`.

**GET /api/v1/planes-produccion/{id} → 200** — mismo objeto. Un id inexistente responde
`404 RECURSO_NO_ENCONTRADO` y uno no numérico, `400 PARAMETRO_INVALIDO`.

**PUT /api/v1/planes-produccion/{id} → 200** — acepta el mismo cuerpo que el alta; el `estado`
no se modifica por esta ruta. Un plan que no está `PLANIFICADO` responde
`409 PLAN_NO_EDITABLE`.

**PATCH /api/v1/planes-produccion/{id}/estado → 200**:

```json
// petición
{ "estado": "EN_PROCESO" }

// respuesta
{ "idPlan": 12, "fechaPlanificacion": "2026-11-05",
  "fechaProduccion": "2026-11-12", "estado": "EN_PROCESO",
  "observaciones": "Producción para el pedido #47 y reposición de estante",
  "responsable": { "idUsuario": 2, "nombre": "Ismael Batalla" },
  "cantidadProductos": 2, "editable": false }
```

**Errores:** `USUARIO_INEXISTENTE`, `TRANSICION_INVALIDA`, `PLAN_SIN_PRODUCTOS` y
`PLAN_NO_EDITABLE` (409); `FECHAS_INVALIDAS`, `VALIDACION_FALLIDA`, `PARAMETRO_INVALIDO` y
`CAMPO_ORDEN_INVALIDO` (400); `RECURSO_NO_ENCONTRADO` (404); `SIN_PERMISO` y `USUARIO_INACTIVO`
(403); `NO_AUTENTICADO` (401); `METODO_NO_PERMITIDO` (405).

## Archivos creados

```
src/main/java/co/edu/corposucre/productionfood/produccion/
├── EstadoPlan.java
├── PlanProduccion.java
├── PlanProduccionRowMapper.java
├── PlanProduccionRepository.java
├── PlanProduccionService.java
├── PlanProduccionController.java
└── dto/
    ├── CrearPlanRequest.java
    ├── ActualizarPlanRequest.java
    ├── CambiarEstadoPlanRequest.java
    └── PlanResponse.java

src/test/java/co/edu/corposucre/productionfood/produccion/
├── EstadoPlanTest.java
├── PlanProduccionRepositoryTest.java
├── PlanProduccionServiceTest.java
├── PlanProduccionControllerTest.java
└── PlanProduccionSeguridadTest.java
```

## Evidencia (pruebas backend)

| Clase | Casos | Cobertura |
|---|---|---|
| `EstadoPlanTest` | 6 | Máquina de estados: transiciones válidas y terminales, auto-transición y conjuntos inmutables |
| `PlanProduccionRepositoryTest` | 12 | Integración JDBC contra MySQL: `insertar` y `buscarPorId`, `JOIN` del responsable, observaciones nulas, filtros y conteo, paginación estable, lista blanca de orden, `actualizarSiEstado`, `cambiarEstado` y `contarDetalles` |
| `PlanProduccionServiceTest` | 24 | R-01 a R-06: fechas, responsable por rol, edición conservando dueño, carreras del `UPDATE`, `PLAN_SIN_PRODUCTOS`, transiciones prohibidas, literal no canónico, `esEditable` y ausencia de dependencias de inventario |
| `PlanProduccionControllerTest` | 23 | Contrato HTTP: 201 + `Location`, `VALIDACION_FALLIDA` por campo, 400/404/409, filtros y orden, `405` sin `DELETE` y matriz de roles en el controlador |
| `PlanProduccionSeguridadTest` | 45 | SEC-01…SEC-05 parametrizados en los cinco endpoints (20 + 13 + 12 positivos de rol) |
| **Total de la HU** | **110** | |

## Smoke y QA ciego (2026-11-05)

- **Smoke con `curl` y MySQL:** los cinco endpoints respondieron; el alta devolvió `201` con
  `Location`, el listado paginó con el orden por defecto y el detalle devolvió el responsable y
  `cantidadProductos` reales. La base confirmó las filas en `planes_produccion` con el estado
  canónico y el responsable esperado.
- **QA ciego:** CP-01…CP-23 = **23/23 PASA**; SEC-01…SEC-05 en los cinco endpoints =
  **25/25 PASA**; matriz de roles = **25/25 PASA**; verificaciones SQL = **4/4 PASA**.
- **Defectos:** 0 críticos, altos, medios ni bajos. Todos los endpoints respondieron por debajo
  de 20 ms.
- **SQL verificado:** el responsable coincide con el usuario del token en CP-10/CP-11 y con el
  designado por el ADMIN en CP-12; `detalle_plan_produccion` no tiene líneas antes de CP-14;
  cancelar un plan en proceso deja `inventario` y `movimientos_inventario_mp` sin cambios
  (CP-19); `SHOW CREATE TABLE planes_produccion` confirma `ck_planes_estado`.
- La colección cubre los casos HTTP; los chequeos de inventario de CP-19 se ejecutan con las
  consultas SQL anotadas en la misma colección y en `PFDocs/HU-20/tarea-qa.md`.

## Desviaciones vs especificación

- **Sin `DELETE`.** La especificación no lo pide y el cierre es por estado; `DELETE` responde
  `405 METODO_NO_PERMITIDO`.
- **`PUT` no modifica el estado.** El estado tiene su propio `PATCH` con la máquina de
  transiciones; mezclarlo en la edición permitiría saltos sin validar R-01.
- **`editable` se publica en la respuesta.** No está en el JSON de la especificación, pero se
  deriva de R-05 y ahorra al consumidor reimplementar la regla; el valor coincide con
  `esEditable(idPlan)`.
- **La edición conserva el responsable si no viene `idUsuario`.** La especificación solo fija la
  regla del alta; se aplicó el mismo criterio en la edición para que guardar no cambie de dueño
  el plan. Un `ADMIN` que envía `idUsuario` sí puede reasignarlo.
- **El cuerpo de alta ignora `estado`.** No se rechaza con un error: el DTO no lo declara y
  Jackson descarta las propiedades desconocidas. El plan nace `PLANIFICADO` en cualquier caso.
- **Designación de responsable inexistente responde 409, no 400.** Es un conflicto con un
  recurso que no existe, coherente con `PRODUCTO_INEXISTENTE` y el resto de la API.

## Pendientes / deuda

- **HU-21 · productos del plan.** `GET /planes-produccion/{id}` devuelve `cantidadProductos`,
  pero no las líneas de `detalle_plan_produccion`. El detalle completo y el alta de productos
  son de HU-21.
- **HU-22 y HU-23 · consumo e inventario.** Ninguna transición mueve stock. El consumo real y su
  descuento de inventario se registran en esas historias.
- **R-07 · sin generación automática de lotes.** Completar un plan no crea lotes de producto
  terminado; el registro sigue siendo manual en HU-19.
- **Cancelar no revierte consumos (R-06).** La materia prima ya consumida no vuelve al
  inventario; corregir un consumo mal registrado requiere un ajuste explícito.
- **Frontend pendiente.** No existe pantalla de planes; el manual de consumo es el contrato para
  Angular.
- **Datos de QA en la base.** Quedaron los planes y usuarios del sufijo de la ejecución de QA.
  Son datos de prueba locales; no forman parte del negocio ni viajan con el repositorio.
