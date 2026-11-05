# HU-22 — Consumo de materia prima · Backend verificado

| | |
|---|---|
| Historia | HU-22 — Registro del consumo real de materia prima por producto del plan |
| Módulo | Producción |
| Endpoints | `GET /api/v1/planes-produccion/{idPlan}/consumos`, `GET /api/v1/detalles-plan/{idDetallePlan}/consumo-teorico`, `POST /api/v1/detalles-plan/{idDetallePlan}/consumos`, `PUT /api/v1/consumos/{id}` |
| Colección Postman | `Docs/Postman/ProductionFood-HU22.postman_collection.json` (`Preparación`, `Teórico y listado`, `Consumos (CP-01…CP-24)`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Fecha de implementación | 2026-11-05 |
| Estado QA | **QA ciego verificado 2026-11-05 · 24/24 CP, 20/20 SEC, matriz de roles 20/20 y SQL 3/3 · frontend pendiente** |

> Esta ficha documenta el consumo real de materia prima de una línea del plan: cuánto exige la
> receta, cuánto se usó realmente, la eficiencia, la desviación y el efecto en inventario. El
> consumo teórico estimado y las líneas del plan son de HU-21; los estados del plan son de HU-20;
> la receta es de HU-12. **Registrar un consumo descuenta inventario en la misma transacción**
> (HU-23), por eso el registro y su movimiento de kardex no se separan. HU-26 mostrará la
> eficiencia y la desviación en los reportes. El contrato práctico para el frontend está en el
> manual [`../Manuales_Consumo_Frontend/HU-22-consumo-materia-prima.md`](../Manuales_Consumo_Frontend/HU-22-consumo-materia-prima.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/planes-produccion/{idPlan}/consumos` | ADMIN, PRODUCCION, CONSULTA | 200/400/401/403/404 | Listado paginado de los consumos del plan; `page` (def. 0), `size` (def. 20, 1…100) y `sort` (def. `producto,asc`) |
| GET | `/api/v1/detalles-plan/{idDetallePlan}/consumo-teorico` | ADMIN, PRODUCCION | 200/401/403/404 | Consumo teórico calculado por el servidor con la receta, con rendimiento, factor, ingredientes y disponible |
| POST | `/api/v1/detalles-plan/{idDetallePlan}/consumos` | ADMIN, PRODUCCION | 201/400/401/403/404/409 | Registra la cantidad real de una materia prima y descuenta inventario; `Location: /api/v1/consumos/{idConsumo}` |
| PUT | `/api/v1/consumos/{id}` | ADMIN, PRODUCCION | 200/400/401/403/404/409 | Corrige la cantidad real y ajusta inventario solo por la diferencia |

**El consumo teórico es de solo lectura.** La consulta calcula lo que exige la receta para la
cantidad planificada, pero no registra nada ni descuenta stock. El registro real es el `POST` y
la corrección es el `PUT`; ambos escriben en `consumo_materia_prima` y en el kardex dentro de una
sola transacción.

**Roles.** Lectura del listado para `ADMIN`, `PRODUCCION` y `CONSULTA`. El consumo teórico, el
registro y la corrección quedan para `ADMIN` y `PRODUCCION`; `CONSULTA` puede ver el listado pero
no consultar el teórico ni escribir. `VENTAS` y `COMPRAS` no participan del consumo de materia
prima y reciben `403 SIN_PERMISO` en los cuatro endpoints.

Piezas internas:

| Pieza | Rol |
|---|---|
| `ConsumoMateriaPrima` | POJO de la fila: `idConsumo`, `idDetallePlan`, `idPlan`, producto, materia prima, unidad, cantidades teórica y real, fecha y estado del plan |
| `ConsumoRowMapper` | Traduce el `JOIN` con `detalle_plan_produccion`, `productos`, `planes_produccion`, `materias_primas` y `unidades_medida`; lee por nombre de columna |
| `ConsumoRepository` | JDBC de `listarPorPlan`, `contarPorPlan`, `listarPorDetallePlan`, `buscarPorId`, `buscarPorIdParaActualizar` (`FOR UPDATE`), `insertar`, `actualizarReal` y `existeConsumo`; lista blanca `ORDEN` para el ordenamiento |
| `CalculadoraConsumoService` | Reutilizada de HU-21; calcula la teórica con `(cantidadPlanificada / receta.cantidadProducir) × detalleReceta.cantidad`, escala 3 `HALF_UP`, una sola vez al final |
| `CalculadoraEficienciaService` | Cálculo puro de eficiencia y desviación; protege la división por cero y redondea a escala 2 `HALF_UP` |
| `InventarioService` | Reutilizado de HU-09/HU-23; descuenta con `SALIDA_PRODUCCION` y devuelve con `AJUSTE_POSITIVO` dentro de la transacción del servicio |
| `ConsumoService` | Teórico, listado, registro y corrección; valida línea, plan, materia prima, duplicado, cantidades y stock; `@Transactional` y `@Auditable` en las escrituras |
| `ConsumoController` | Los cuatro endpoints con `@PreAuthorize` y Swagger; solo despacha al servicio |
| `DetallePlanInexistenteException` | 404 con código propio `DETALLE_PLAN_INEXISTENTE`, distinto del transversal `RECURSO_NO_ENCONTRADO` |
| `RegistrarConsumoRequest` / `ActualizarConsumoRequest` | Cuerpos de registro y corrección; solo exigen presencia y **no admiten `cantidadTeorica`** |
| `ConsumoResponse` / `MateriaPrimaRefResponse` / `InventarioConsumoResponse` | Respuesta del consumo registrado, materia prima embebida y saldos antes/después |
| `ConsumoTeoricoLineaResponse` / `IngredienteTeoricoResponse` | Respuesta del teórico por línea: producto, cantidad planificada, rendimiento, factor e ingredientes con `porTanda`, `teorico` y `disponible` |

**Sin migración Flyway nueva.** V2 ya dejó `cantidad_real` como `NOT NULL` (§C-08), añadió
`uk_consumo_mp (id_detalle_plan, id_materia_prima)` (§C-13) y los `CHECK ck_cmp_teorica
(cantidad_teorica >= 0)` y `ck_cmp_real (cantidad_real >= 0)` (§C-14). La historia solo escribe
código sobre el esquema existente.

## Reglas de negocio implementadas

- **R-01 · la cantidad teórica la calcula el servidor.** El `POST` solo recibe `idMateriaPrima` y
  `cantidadReal`; una `cantidadTeorica` inyectada en el cuerpo se ignora y la respuesta usa la de
  la receta. Si el cliente pudiera enviarla, podría fabricar la eficiencia que quisiera y el
  reporte de HU-26 dejaría de medir. `RegistrarConsumoRequest` no declara ese campo.
- **R-02 · el teórico se calcula contra la cantidad planificada, no contra lo producido.** La
  fórmula es la de HU-12/HU-21 sobre `detalle_plan_produccion.cantidad_planificada`. La decisión
  mide la desviación frente al objetivo; HU-26 debe mostrar además el rendimiento por unidad
  producida para que la cifra sea interpretable.
- **R-03 · la eficiencia protege la división por cero.** `eficiencia = (teórica / real) × 100`.
  Si `cantidadReal` es cero, la respuesta es `null`, no `0`, `Infinity` ni un error. El frontend
  debe mostrar `—`. El cero es un consumo válido: significa que no se usó ese ingrediente.
- **R-04 · la desviación es la métrica con signo.** `desviacionPorcentaje = ((real − teórica) /
  teórica) × 100`. Positiva si se gastó de más, negativa si de menos y `null` cuando la teórica es
  cero. La eficiencia de CA-06 es contraintuitiva —consumir menos de lo previsto da más de
  100 %—, por eso se expone también la desviación.
- **R-05 · registrar descuenta inventario en la misma transacción.** El `INSERT` del consumo y la
  salida de inventario con `SALIDA_PRODUCCION` viven en la misma transacción; si el stock no
  alcanza, se revierte también el consumo. El kardex usa `tabla_origen =
  'consumo_materia_prima'` e `id_origen = idConsumo`.
- **R-06 · un ingrediente no se registra dos veces por línea.** El servicio comprueba
  `existeConsumo(idDetallePlan, idMateriaPrima)` y responde `409 CONSUMO_DUPLICADO`; el
  `UNIQUE uk_consumo_mp` cubre la carrera entre dos peticiones simultáneas. Para corregir se usa
  el `PUT`, no un segundo `POST`.
- **R-07 · corregir ajusta inventario solo por la diferencia.** De 0.300 a 0.500 se descuentan
  0.200 más con `SALIDA_PRODUCCION`; de 0.500 a 0.100 se devuelven 0.400 con `AJUSTE_POSITIVO`.
  No se revierte todo y se vuelve a descontar: el kardex refleja hechos, no operaciones
  contables intermedias. Un movimiento por diferencia y ninguno si la diferencia redondea a
  `0.00`.
- **R-08 · se permite materia prima fuera de la receta.** Un ingrediente imprevisto se acepta
  con `cantidadTeorica: 0.000` y `fueraDeReceta: true`. Con real positivo la eficiencia es
  `0.00` y la desviación `null`; el campo se **deriva de que la teórica sea cero**, no de una
  columna ni de una comparación aparte. Es información valiosa: la receta no refleja el proceso
  real.
- **R-09 · precisión decimal uniforme.** La teórica y la real se guardan con escala 3, la del
  `decimal(10,3)`. Al mover inventario se redondea a 2 decimales con `HALF_UP`, la escala de
  `inventario.cantidad_disponible`; los porcentajes también usan escala 2 `HALF_UP`. Un criterio
  distinto acumula gramos y descuadra el inventario sin que ningún movimiento individual parezca
  incorrecto.

## Decisiones clave

| Tema | Decisión implementada |
|---|---|
| Listado | **Paginado** con `PageResponse`, `page` desde 0, `size` 1…100 y orden por lista blanca: `producto`, `materiaPrima`, `cantidadTeorica`, `cantidadReal` y `fechaRegistro`; por defecto `producto,asc` |
| DTO del teórico | Se creó `ConsumoTeoricoLineaResponse` en vez de reutilizar `ConsumoTeoricoResponse` de HU-21: el teórico de HU-22 agrega `recetaRinde`, `factor` e ingredientes con `porTanda` y `disponible` |
| Fuera de receta | `fueraDeReceta` se deriva de `cantidadTeorica == 0`; no se guarda como columna ni se decide por una lista aparte |
| Inventario en la respuesta | Se omite cuando no hubo movimiento; un consumo real de cero no descuenta y la respuesta no incluye `inventario` |
| Corrección | El `PUT` exige que el plan del consumo esté `EN_PROCESO`; un plan terminado cerró su ciclo y uno sin iniciar todavía no consume. Devuelve `409 PLAN_NO_EN_PROCESO` |
| Fila de inventario ausente | `InventarioService` responde `404 RECURSO_NO_ENCONTRADO` si la materia prima no tiene fila; el consumo lo traduce a `409 STOCK_INSUFICIENTE`, igual que un saldo insuficiente |
| Tipos de movimiento | `SALIDA_PRODUCCION` al registrar y al corregir al alza; `AJUSTE_POSITIVO` al corregir a la baja. Cada corrección deja un solo movimiento por la diferencia |
| Orden de validaciones | Línea → plan en proceso → cantidad → materia prima activa → duplicado → teórica → `INSERT` → inventario. El `STOCK_INSUFICIENTE` revierte el consumo insertado |
| Concurrencia | El `PUT` bloquea el consumo con `SELECT ... FOR UPDATE`; dos correcciones simultáneas no leen la misma cantidad anterior |
| Cálculo del teórico | La cantidad planificada se lee de la línea; la receta y sus ingredientes se consultan en el servicio y la fórmula se delega en `CalculadoraConsumoService`, sin duplicarla |
| Auditoría | `@Auditable` en `REGISTRAR_CONSUMO` y `CORREGIR_CONSUMO` sobre `consumo_materia_prima`; las consultas no generan bitácora |

## Modelo de datos

**Tabla `consumo_materia_prima`** (V1 la crea; V2 añade `NOT NULL`, `UNIQUE` y `CHECK`)

| Columna | Tipo | Notas |
|---|---|---|
| `id_consumo` | `int unsigned` PK AI | |
| `id_detalle_plan` | `int unsigned` NOT NULL FK | CA-01; FK a `detalle_plan_produccion` |
| `id_materia_prima` | `int unsigned` NOT NULL FK | CA-02; FK a `materias_primas` |
| `cantidad_teorica` | `decimal(10,3)` NOT NULL | CA-03; calculada por el servidor; `ck_cmp_teorica` exige `>= 0` |
| `cantidad_real` | `decimal(10,3)` NOT NULL | CA-04; `NOT NULL` desde V2 §C-08; `ck_cmp_real` exige `>= 0` |
| `fecha_registro` | `datetime` NOT NULL DEFAULT `CURRENT_TIMESTAMP` | CA-05; la completa la base |

`uk_consumo_mp (id_detalle_plan, id_materia_prima)` impide el doble consumo incluso por SQL
directo. La fecha de registro no se envía: la base la asigna y la respuesta la devuelve.

## Contrato resumido

**GET /api/v1/detalles-plan/20/consumo-teorico → 200** (producto 1, plan 141, línea 20):

```json
{ "idDetallePlan": 20,
  "producto": "Pan de prueba",
  "cantidadPlanificada": 1.00,
  "recetaRinde": 10.00,
  "factor": 0.100,
  "ingredientes": [
    { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg",
      "porTanda": 2.500, "teorico": 0.250, "disponible": 33.33 }
  ] }
```

`disponible` depende de la base. El ejemplo usa el valor del smoke. Un producto sin receta
devuelve `ingredientes: []`, `recetaRinde: null` y `factor: null`.

**POST /api/v1/detalles-plan/20/consumos → 201** (con `Location: /api/v1/consumos/97`):

```json
// petición
{ "idMateriaPrima": 1, "cantidadReal": 0.300 }
```

```json
// respuesta
{ "idConsumo": 97,
  "idDetallePlan": 20,
  "producto": "Pan de prueba",
  "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
  "cantidadTeorica": 0.250,
  "cantidadReal": 0.300,
  "eficiencia": 83.33,
  "desviacionPorcentaje": 20.00,
  "fueraDeReceta": false,
  "fechaRegistro": "2026-11-05T13:51:08",
  "inventario": { "anterior": 33.33, "posterior": 33.03 } }
```

`eficiencia = (0.250 / 0.300) × 100 = 83.33` · `desviacion = ((0.300 − 0.250) / 0.250) × 100 =
20.00`. El inventario baja 0.30, con `SALIDA_PRODUCCION`.

**PUT /api/v1/consumos/97 → 200**, corrección al alza:

```json
// petición
{ "cantidadReal": 0.500 }

// respuesta
{ "idConsumo": 97, "idDetallePlan": 20, "producto": "Pan de prueba",
  "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
  "cantidadTeorica": 0.250, "cantidadReal": 0.500,
  "eficiencia": 50.00, "desviacionPorcentaje": 100.00,
  "fueraDeReceta": false, "fechaRegistro": "2026-11-05T13:51:08",
  "inventario": { "anterior": 33.03, "posterior": 32.83 } }
```

Corrección a la baja, `PUT` con `{ "cantidadReal": 0.100 }`: responde `200` con
`eficiencia: 250.00`, `desviacionPorcentaje: -60.00` e `inventario: { "anterior": 32.83,
"posterior": 33.23 }`. El kardex de la corrección deja `SALIDA_PRODUCCION 0.200` y
`AJUSTE_POSITIVO 0.400`, un movimiento por diferencia.

**POST con `cantidadReal: 0` (línea 18, materia prima 1) → 201**:

```json
{ "idConsumo": 98, "idDetallePlan": 18, "producto": "Pan de prueba",
  "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
  "cantidadTeorica": 0.250, "cantidadReal": 0.000,
  "eficiencia": null, "desviacionPorcentaje": -100.00,
  "fueraDeReceta": false, "fechaRegistro": "2026-11-05T13:51:20" }
```

No aparece `inventario`: no hubo movimiento. `eficiencia: null` es el caso protegido de R-03.

**POST con materia prima fuera de receta (materia prima 4, `0.500`) → 201**:

```json
{ "idConsumo": 101, "idDetallePlan": 19, "producto": "Pan de prueba",
  "materiaPrima": { "idMateriaPrima": 4, "nombre": "Mantequilla", "unidad": "kg" },
  "cantidadTeorica": 0.000, "cantidadReal": 0.500,
  "eficiencia": 0.00, "desviacionPorcentaje": null,
  "fueraDeReceta": true, "fechaRegistro": "2026-11-05T13:51:32",
  "inventario": { "anterior": 2.00, "posterior": 1.50 } }
```

**POST con `cantidadTeorica: 999` inyectada → 201**: la respuesta usa la teórica real de la
receta, `0.250`; el campo no forma parte del DTO (CP-02).

**GET /api/v1/planes-produccion/141/consumos?page=0&size=20&sort=materiaPrima,asc → 200**:

```json
{ "content": [
    { "idConsumo": 97, "idDetallePlan": 20, "producto": "Pan de prueba",
      "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
      "cantidadTeorica": 0.250, "cantidadReal": 0.300,
      "eficiencia": 83.33, "desviacionPorcentaje": 20.00,
      "fueraDeReceta": false, "fechaRegistro": "2026-11-05T13:51:08" }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true }
```

El listado no incluye `inventario`: el efecto en stock se ve al registrar o corregir, no en la
consulta.

**Errores:** `CANTIDAD_INVALIDA`, `VALIDACION_FALLIDA`, `PARAMETRO_INVALIDO` y
`CAMPO_ORDEN_INVALIDO` (400); `NO_AUTENTICADO` (401); `SIN_PERMISO` y `USUARIO_INACTIVO` (403);
`DETALLE_PLAN_INEXISTENTE`, `PLAN_INEXISTENTE` y `RECURSO_NO_ENCONTRADO` (404);
`CONSUMO_DUPLICADO`, `PLAN_NO_EN_PROCESO`, `MATERIA_PRIMA_INEXISTENTE` y `STOCK_INSUFICIENTE`
(409). El mensaje de `CANTIDAD_INVALIDA` es «La cantidad real no puede ser negativa.».

## Archivos creados

```
src/main/java/co/edu/corposucre/productionfood/produccion/
├── CalculadoraEficienciaService.java
├── ConsumoMateriaPrima.java
├── ConsumoRowMapper.java
├── ConsumoRepository.java
├── ConsumoService.java
├── ConsumoController.java
├── DetallePlanInexistenteException.java
└── dto/
    ├── RegistrarConsumoRequest.java
    ├── ActualizarConsumoRequest.java
    ├── ConsumoResponse.java
    ├── ConsumoTeoricoLineaResponse.java
    ├── IngredienteTeoricoResponse.java
    ├── MateriaPrimaRefResponse.java
    └── InventarioConsumoResponse.java

src/test/java/co/edu/corposucre/productionfood/produccion/
├── CalculadoraEficienciaServiceTest.java
├── ConsumoServiceTest.java
├── ConsumoRegistrarServiceTest.java
├── ConsumoActualizarServiceTest.java
├── ConsumoListarServiceTest.java
├── ConsumoRepositoryTest.java
├── ConsumoControllerTest.java
└── ConsumoSeguridadTest.java
```

La implementación reutiliza `CalculadoraConsumoService` de HU-21 y `InventarioService` de
HU-09/HU-23; no crea tablas ni migraciones.

## Evidencia (pruebas backend)

| Clase | Casos | Cobertura |
|---|---|---|
| `CalculadoraEficienciaServiceTest` | 9 | Eficiencia normal, real cero, teórica cero, ambos cero, eficiencia mayor que 100, desviación positiva y negativa, teórico nulo y redondeo `HALF_UP` |
| `ConsumoServiceTest` | 5 | Teórico con receta, producto sin receta, línea inexistente, receta sin ingredientes o rendimiento no positivo y consulta sin escrituras |
| `ConsumoRegistrarServiceTest` | 13 | Registro con descuento, redondeo de 3 a 2, real cero, `0.001`, fuera de receta, plan no en proceso, línea inexistente, materia prima inactiva, duplicado, cantidad negativa, stock insuficiente y violación del `UNIQUE` |
| `ConsumoActualizarServiceTest` | 13 | Corrección al alza y a la baja por diferencia, mismo valor, diferencia que redondea a cero, cantidad negativa, cero a cero, devolución total, consumo inexistente, plan planificado o completado, stock insuficiente y bloqueo `FOR UPDATE` |
| `ConsumoListarServiceTest` | 6 | Página con eficiencia y desviación, fuera de receta, plan inexistente, plan sin consumos, lista blanca de orden y campo inválido |
| `ConsumoRepositoryTest` | 10 | Integración JDBC contra MySQL: inserción con escala 3 y fecha automática, `NOT NULL` de `cantidad_real`, `UNIQUE`, listado y conteo, orden por los cinco campos, búsqueda por línea, búsqueda por id, `FOR UPDATE`, `existeConsumo` y `actualizarReal` |
| `ConsumoControllerTest` | 24 | Contrato HTTP de los cuatro endpoints: 200, 201 con `Location`, paginación, orden, 400, 404, 409, teórico sin receta, `cantidadTeorica` inyectada ignorada y real cero |
| `ConsumoSeguridadTest` | 36 | SEC-01…SEC-05 en los cuatro endpoints (27 negativos de seguridad, con las 11 combinaciones de rol denegado de SEC-04, y 9 positivos de rol) |
| **Total de la HU** | **116** | |

`mvn test` sobre la suite completa: **1815 tests, 0 fallos, 0 errores, 0 skipped**.

## Smoke y QA ciego (2026-11-05)

- **Smoke propio:** plan 141 con la línea 20; teórico `0.250`; registro de `0.300` con
  eficiencia `83.33` y desviación `20.00`; corrección a `0.500` y a `0.100` con ajuste por
  diferencia; consumo real cero y consumo fuera de receta. Todos respondieron el contrato
  documentado.
- **QA ciego:** CP-01…CP-24 = **24/24 PASA**; SEC-01…SEC-05 en los cuatro endpoints =
  **20/20 PASA**; matriz de roles = **20/20 PASA**; verificaciones SQL = **3/3 PASA**.
- **Defectos:** cero críticos, altos, medios ni bajos.
- **SQL verificado:** CP-05 confirma `cantidad_real decimal(10,3) NOT NULL`, `uk_consumo_mp` y
  los `CHECK` de V2; CP-17 confirma que el `INSERT` directo que repite
  `(id_detalle_plan, id_materia_prima)` falla con error **1062**; la conciliación de inventario
  deja **0 filas**. El kardex de la corrección deja `SALIDA_PRODUCCION 0.200` y
  `AJUSTE_POSITIVO 0.400`, un movimiento por diferencia.
- La colección cubre los casos HTTP; los chequeos de integridad de CP-05 y CP-17 se ejecutan con
  el SQL anotado en la misma colección y en `PFDocs/HU-22/tarea-qa.md`.

## Desviaciones vs especificación

- **`GET` del listado paginado.** La especificación nombra el endpoint pero no fija paginación;
  se aplica la regla transversal del contrato de API (`page`, `size`, `sort` con lista blanca),
  igual que el resto de listados del proyecto.
- **`ConsumoTeoricoLineaResponse` en lugar de `ConsumoTeoricoResponse`.** El DTO de HU-21
  representa el consumo teórico dentro de una línea del plan; el de HU-22 necesita además
  `recetaRinde`, `factor` y el `disponible` de cada ingrediente. Se prefirió un DTO propio antes
  que sobrecargar el de HU-21.
- **`fueraDeReceta` derivado de la teórica cero.** No hay columna ni marca persistida; la
  respuesta lo calcula como `cantidadTeorica == 0`. Es consistente con R-08 y evita un dato
  duplicado que podría desincronizarse.
- **`inventario` omitido cuando no hay movimiento.** La inclusión global de Jackson es
  `non_null`; un consumo real de cero no descuenta stock y la respuesta no incluye el campo. La
  interfaz debe tratar la ausencia como «sin movimiento», no como un error.
- **El `PUT` solo con plan `EN_PROCESO`.** La especificación no lo decía de forma explícita; se
  añadió porque corregir un consumo de un plan terminado reescribiría el histórico y uno sin
  iniciar no tiene consumo real que corregir.
- **Fila de inventario ausente traducida a `STOCK_INSUFICIENTE`.** `InventarioService` responde
  404 cuando la materia prima no tiene fila; para el consumo eso es un stock insuficiente y se
  responde 409 con el código de negocio.
- **Tipos de movimiento concretos.** El registro y la corrección al alza usan
  `SALIDA_PRODUCCION`; la corrección a la baja usa `AJUSTE_POSITIVO`. La especificación habla de
  «movimiento de kardex» sin fijar los tipos; se eligieron los que ya usa el módulo de
  inventario.

## Pendientes / deuda

- **Frontend pendiente.** No existe pantalla de consumo de materia prima; el manual de consumo
  es el contrato para Angular. La tabla debe mostrar `—` cuando la eficiencia sea `null` y marcar
  los consumos fuera de receta.
- **HU-26 debe mostrar eficiencia y desviación.** La eficiencia sola es contraintuitiva (R-04);
  el reporte debe exponer también la desviación y, por R-02, distinguir la comparación contra lo
  planificado del rendimiento por unidad producida.
- **El listado no muestra inventario.** El efecto en stock solo viene en la respuesta del
  registro y de la corrección; si el reporte necesita el kardex, se consulta por HU-10/HU-23.
- **Consumo real contra lo producido.** La historia calcula el teórico contra lo planificado
  (R-02). La lectura de eficiencia por unidad efectivamente producida queda para HU-26.
- **Datos de QA en la base.** Quedaron planes, líneas y consumos del sufijo de la ejecución de
  QA. Son datos de prueba locales; no forman parte del negocio ni viajan con el repositorio.
