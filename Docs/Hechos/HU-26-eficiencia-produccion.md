# HU-26 — Eficiencia de producción · Backend verificado

| | |
|---|---|
| Historia | HU-26 — Eficiencia de producción (planificado vs. producido, teórico vs. real) |
| Módulo | Reportes (`reporte`) |
| Endpoints | `GET /api/v1/reportes/eficiencia`, `GET /api/v1/reportes/eficiencia/{idPlan}`, `GET /api/v1/reportes/eficiencia/materias-primas` |
| Fecha de implementación | 2026-11-05 |
| Colección Postman | `Docs/Postman/ProductionFood-HU26.postman_collection.json` (`Preparación`, `Reporte por período`, `Desviación por materia prima`, `Detalle de un plan`, `Errores`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Pull requests | #178 repositorio · #179 servicio · #180 controlador y batería de seguridad · #181 corrección de precisión del `rendimientoAjustado` (D-1) · PR docs: pendiente · PR final: pendiente |
| Estado QA | **QA ciego verificado · CP-01…CP-25 y SEC-01…SEC-05 pasan (SEC-03 cubierto por prueba automática, ver más abajo) · D-1 corregido · puntos ambiguos A-1…A-9 pendientes de decisión · frontend pendiente** |

> Esta ficha documenta el reporte de eficiencia de producción: por período, por plan y por materia
> prima. La historia solo lee: no escribe, no audita y no mueve stock. Mide **tres cosas
> distintas** (cumplimiento del plan, rendimiento de materia prima y rendimiento ajustado) y nunca
> las mezcla en un solo porcentaje. El contrato práctico para el frontend está en el manual
> [`../Manuales_Consumo_Frontend/HU-26-eficiencia-produccion.md`](../Manuales_Consumo_Frontend/HU-26-eficiencia-produccion.md).

## Criterios de aceptación → implementación

| CA | Enunciado | Cómo quedó |
|---|---|---|
| CA-01 | Cada plan con sus productos: `cantidad_planificada` vs. `cantidad_producida` | `planes[].productos[]` con `planificada`, `producida` y `cumplimiento`, desde `detalle_plan_produccion` |
| CA-02 | Consumo teórico vs. real por materia prima | `planes[].consumos[]` (por registro) y `/eficiencia/materias-primas` (acumulado), desde `consumo_materia_prima` |
| CA-03 | Porcentaje de eficiencia general | `resumen` con `cumplimientoPromedio`, `rendimientoPromedio` y `rendimientoAjustadoPromedio` por separado, con protección contra división por cero y `registrosSinRendimiento` |
| CA-04 | Filtrar por rango de fechas de producción | `?fechaInicio=&fechaFin=` (inclusivos, opcionales) sobre `planes_produccion.fecha_produccion` con `idx_planes_fecha` |

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/reportes/eficiencia?fechaInicio=&fechaFin=` | ADMIN, PRODUCCION | 200/400/401/403 | Reporte del período: `periodo`, `resumen`, `planes[]` (con `productos[]` y `consumos[]`) y `sinReceta[]` |
| GET | `/api/v1/reportes/eficiencia/{idPlan}` | ADMIN, PRODUCCION | 200/400/401/403/404 | Detalle de un plan: la misma forma de un elemento de `planes[]` |
| GET | `/api/v1/reportes/eficiencia/materias-primas?fechaInicio=&fechaFin=` | ADMIN, PRODUCCION, COMPRAS | 200/400/401/403 | Desviación acumulada por materia prima, con `tendencia`; lista sin paginación |

**Roles.** El reporte y el detalle son de `ADMIN` y `PRODUCCION`; el acumulado por materia prima
también lo ve `COMPRAS` (le sirve para detectar recetas mal calibradas o desperdicio). `VENTAS` y
`CONSULTA` reciben `403 SIN_PERMISO` en los tres; `COMPRAS` lo recibe en los dos primeros.

**Solo lectura y sin caché.** Los tres recursos solo admiten `GET`; `POST`, `PUT`, `PATCH` y
`DELETE` responden `405 METODO_NO_PERMITIDO`. Cada llamada consulta la base.

Piezas internas:

| Pieza | Rol |
|---|---|
| `EficienciaRepository` | JDBC de solo lectura: planes con consumo, productos por plan, consumos por plan, acumulado por materia prima y productos sin receta |
| `EficienciaService` | Valida el rango de fechas, calcula cumplimiento, rendimiento y ajustado, promedia ignorando `null`, clasifica la tendencia; `@Transactional(readOnly = true)` |
| `EficienciaController` | Los tres endpoints con `@PreAuthorize` por método y Swagger con ejemplos; solo despacha al servicio |
| `PlanSinConsumosException` | `404 PLAN_SIN_CONSUMOS` (el plan existe pero no tiene consumos registrados) |
| DTO de respuesta | `EficienciaResponse`, `PeriodoResponse`, `ResumenEficienciaResponse`, `EficienciaPlanResponse` (con `Producto` y `Consumo`), `DesviacionMateriaPrimaResponse`, `SinRecetaResponse` |
| `CalculadoraEficienciaService` (HU-22) | **Reutilizada**: la desviación y la eficiencia salen de ella; no se duplican sus fórmulas |

**Sin migración Flyway nueva.** Se consultan `planes_produccion`, `detalle_plan_produccion`,
`consumo_materia_prima`, `productos`, `materias_primas`, `unidades_medida` y `recetas`, con el
índice `idx_planes_fecha (fecha_produccion, estado)` de V2.

## Decisiones de cálculo

| Tema | Decisión implementada |
|---|---|
| Tres métricas | **Cumplimiento** = `producida / planificada × 100` (por producto; el del plan es la media de sus productos). **Rendimiento** = `teórica / real × 100` (calculadora de HU-22). **Rendimiento ajustado** = `(teórica × producida / planificada) / real × 100`. Se entregan por separado y nunca se promedian entre sí (R-01, R-02) |
| Cumplimiento | Nuevo en esta historia; la planificada es siempre > 0 (`CHECK` en V2). Da `111.11` si se produjo de más y `0.00` si se produjo 0 |
| Rendimiento ajustado | Reutiliza `eficiencia(teórica × producida / planificada, real)` de la calculadora. El teórico ajustado se calcula con **escala 10** (D-1): con escala 4, cantidades pequeñas como 0.003 kg distorsionaban el cociente |
| Desviación | `(real − teórica) / teórica × 100` de la calculadora, `null` si la teórica es 0 |
| División por cero | `rendimiento` y `rendimientoAjustado` llegan `null` si `real = 0`; `desviacion` llega `null` si `teórica = 0`. Sin error; el cliente muestra «—» |
| Promedios sin nulos | `rendimientoPromedio` y `rendimientoAjustadoPromedio` son la media de los valores **no nulos a nivel de consumo** (92, 100, `null` → 96, nunca 64). Sin `orElse(0)`; `null` si no hay valores. `registrosSinRendimiento` cuenta los consumos excluidos |
| Rendimiento por plan | Media de los valores no nulos de sus consumos; `null` si ninguno |
| Cancelados fuera | Los planes `CANCELADO` con consumo aparecen en `planes[]` con su estado y se cuentan en `planesCancelados`, pero no entran en ningún promedio ni en el acumulado por materia prima. `planesAnalizados` son los no cancelados con consumo |
| Planes sin consumo | Un plan sin ningún consumo con `cantidad_real` no aparece en `planes[]` (R-05) |
| `desviacionMateriaPrima` | Media **sin ponderar** de las desviaciones acumuladas por materia prima no nulas (ver A-1) |
| `sinReceta` | Productos de planes del período (excluye `CANCELADO`) sin fila activa en `recetas`; `{producto, planes, nota}`. Lista vacía si no hay (R-07) |
| `tendencia` | Constante nombrada `UMBRAL_TENDENCIA = 5.00`: desviación `> 5.00` → `CONSUMO_EXCESIVO`; `< -5.00` → `CONSUMO_REDUCIDO`; entre `-5.00` y `5.00` inclusive → `NORMAL`; desviación `null` → `SIN_BASE`. Los umbrales los fijó la implementación (la especificación no los daba) |
| Orden de materias primas | Por `|desviacion|` descendente (`null` al final) y luego por nombre |
| Rango de fechas | Ambas fechas opcionales e inclusivas. `periodo.desde` y `periodo.hasta` viajan `null` si no se enviaron. `fechaInicio > fechaFin` → `400 RANGO_FECHAS_INVALIDO`; fecha mal formada → `400 PARAMETRO_INVALIDO` |
| Escalas | Porcentajes con 2 decimales (`HALF_UP`); `teorica` y `real` con 3; `planificada` y `producida` con 2. Los números salen como JSON sin ceros finales (`66.67`, `0.0`, `96.0`) |
| Sin zona horaria de Java | `fecha_produccion` es un `DATE`; no se usa `LocalDate.now()` |

## Modelo de datos

**Sin tablas nuevas.** El reporte se deriva de:

| Origen | Uso |
|---|---|
| `planes_produccion` | Planes del período, estado, responsable (índice `idx_planes_fecha`) |
| `detalle_plan_produccion` | `cantidad_planificada` y `cantidad_producida` por producto |
| `consumo_materia_prima` | `cantidad_teorica` y `cantidad_real` por consumo |
| `materias_primas` / `unidades_medida` | Nombre y abreviatura de la unidad |
| `productos` / `recetas` | Nombre del producto y detección de `sinReceta` (receta activa) |

## Contrato resumido

**GET /api/v1/reportes/eficiencia?fechaInicio=2026-09-01&fechaFin=2026-09-30 → 200** (recortado a
un plan):

```json
{ "periodo": { "desde": "2026-09-01", "hasta": "2026-09-30" },
  "resumen": { "planesAnalizados": 1, "planesCancelados": 0,
    "cumplimientoPromedio": 66.67, "rendimientoPromedio": 92.59,
    "rendimientoAjustadoPromedio": 61.73, "registrosSinRendimiento": 1,
    "desviacionMateriaPrima": -46.0 },
  "planes": [
    { "idPlan": 1, "fechaProduccion": "2026-09-18", "estado": "COMPLETADO",
      "responsable": "Ismael Batalla",
      "cumplimiento": 66.67, "rendimiento": 92.59, "rendimientoAjustado": 61.73,
      "productos": [
        { "producto": "Pan francés", "planificada": 90.00, "producida": 60.00,
          "cumplimiento": 66.67 } ],
      "consumos": [
        { "materiaPrima": "Harina de trigo", "unidad": "kg", "teorica": 15.000, "real": 16.200,
          "desviacion": 8.00, "eficiencia": 92.59, "rendimientoAjustado": 61.73 },
        { "materiaPrima": "Sal", "unidad": "kg", "teorica": 0.300, "real": 0.000,
          "desviacion": -100.00, "eficiencia": null, "rendimientoAjustado": null } ] } ],
  "sinReceta": [
    { "producto": "Pan integral", "planes": 2,
      "nota": "Sin receta: no se puede calcular consumo teórico" } ] }
```

**GET /api/v1/reportes/eficiencia/materias-primas?fechaInicio=…&fechaFin=… → 200:**

```json
[ { "materiaPrima": "Harina de trigo", "unidad": "kg",
    "teoricaAcumulada": 180.000, "realAcumulada": 192.600,
    "desviacion": 7.00, "planes": 12, "tendencia": "CONSUMO_EXCESIVO" },
  { "materiaPrima": "Levadura", "unidad": "kg",
    "teoricaAcumulada": 1.800, "realAcumulada": 1.795,
    "desviacion": -0.28, "planes": 12, "tendencia": "NORMAL" } ]
```

**GET /api/v1/reportes/eficiencia/{idPlan} → 200:** un elemento de `planes[]` como el de arriba
(sin envoltorio).

**Errores:** `RANGO_FECHAS_INVALIDO` (400: `fechaInicio` posterior a `fechaFin`);
`PARAMETRO_INVALIDO` (400: fecha mal formada o `idPlan` no numérico o fuera de rango);
`PLAN_INEXISTENTE` (404); `PLAN_SIN_CONSUMOS` (404: el plan existe sin consumos registrados);
`NO_AUTENTICADO` (401); `SIN_PERMISO` (403); `USUARIO_INACTIVO` (403); `METODO_NO_PERMITIDO` (405).

## Archivos creados

```
src/main/java/co/edu/corposucre/productionfood/reporte/
├── EficienciaController.java
├── EficienciaService.java
├── EficienciaRepository.java
├── PlanSinConsumosException.java
├── PlanEficienciaFila.java
├── ProductoPlanFila.java
├── ConsumoEficienciaFila.java
├── MateriaPrimaAcumuladaFila.java
├── SinRecetaFila.java
└── dto/
    ├── EficienciaResponse.java
    ├── PeriodoResponse.java
    ├── ResumenEficienciaResponse.java
    ├── EficienciaPlanResponse.java
    ├── DesviacionMateriaPrimaResponse.java
    └── SinRecetaResponse.java

src/test/java/co/edu/corposucre/productionfood/reporte/
├── EficienciaRepositoryTest.java
├── EficienciaServiceTest.java
├── EficienciaControllerTest.java
└── EficienciaSeguridadTest.java
```

## Evidencia (pruebas backend)

Las pruebas cubren el repositorio JDBC contra MySQL, las reglas del servicio (cumplimiento,
rendimiento, ajustado, promedios sin nulos, división por cero, cancelados, tendencia y rango de
fechas), el contrato HTTP y la matriz de roles y seguridad.

- **92 tests** en las clases `Eficiencia*Test`.
- `mvn test` sobre la suite completa: **2203 tests, 0 fallos, 1 omitido**. El omitido es el caso
  que necesita una fila con `cantidad_real` `NULL`, que no se puede sembrar por la restricción
  `NOT NULL` de la tabla (V2, C-08); el código la excluye de todos modos.
- **SEC-03 (token expirado)** queda cubierto por `EficienciaSeguridadTest`, que prueba que un
  token expirado responde `401` en los tres endpoints.

## Smoke y QA ciego

QA ciego contra la API real en `:8086` y MySQL (esquema aislado), solo por la especificación, el
Swagger y SQL de solo lectura. Los datos se crearon por la API (unidad, materias primas, compras,
productos con receta, planes, consumos y producido) y cada cifra se comparó con SQL independiente;
además se verificaron 20 planes aleatorios (60 consumos) con aritmética decimal externa.

- **CP-01…CP-25: PASS.** Cumplimiento `66.67`, `100.00`, `111.11` y `0.0`; desviación `8.0` y
  eficiencia `92.59`; consumo en cero con `eficiencia null` sin error; consumo fuera de receta con
  `desviacion null`; promedio 92, 100, `null` → `96.0` con `registrosSinRendimiento 1`;
  `rendimiento 150.0` frente a `rendimientoAjustado 100.0` en el mismo plan; filtro de fechas
  inclusivo; rango invertido `400 RANGO_FECHAS_INVALIDO`; planes sin consumo fuera; cancelados
  contados aparte y fuera de los promedios; acumulado por materia prima y `tendencia`;
  `sinReceta`; rango vacío con promedios `null`; detalle de plan; `404 PLAN_SIN_CONSUMOS` y
  `404 PLAN_INEXISTENTE`.
- **CP-23 (rendimiento):** con 100 planes × 5 consumos (500 registros) `/eficiencia` responde en
  **15-24 ms** (115 KB); sin filtro, con más de 230 planes, 17 ms; `/materias-primas` 5 ms y el
  detalle 4 ms. `EXPLAIN` usa `idx_planes_fecha` (`type range`, 100 filas). El límite es 2 s.
- **SEC-01…SEC-05: PASS.** Sin token y token malformado (`abc.def.ghi`, `xyz`, firma alterada y
  `alg:none`) dan `401 NO_AUTENTICADO` en los tres; `VENTAS` y `CONSULTA` reciben `403 SIN_PERMISO`
  en los tres y `COMPRAS` en dos; un usuario desactivado con token vigente recibe
  `403 USUARIO_INACTIVO`. **SEC-03:** el QA no pudo emitir un token realmente expirado (no tenía el
  secreto y la vigencia es de 1 h); lo verificó con firma alterada y `alg:none`, y el caso del
  token expirado lo cubre `EficienciaSeguridadTest`.
- **Matriz de roles:** `ADMIN` y `PRODUCCION` 200 en los tres; `COMPRAS` 200 solo en
  `/materias-primas`; `VENTAS` y `CONSULTA` 403 en los tres.
- **Concurrencia:** más de 3600 peticiones simultáneas (10 a 150 hilos, 6 variantes de URL) con
  **0 respuestas 5xx** y cuerpo idéntico al de referencia; 60 escrituras y 300 lecturas a la vez
  sin agotar el pool.
- **Bordes:** fechas mal formadas (`abc`, `2026-13-01`, `2026-02-30`, con hora, año 99999…) y
  `idPlan` no numérico o fuera de rango (`abc`, `1.5`, `2147483648`) → `400 PARAMETRO_INVALIDO`;
  `idPlan` 0, -1 y 999 → `404 PLAN_INEXISTENTE`; intentos de inyección SQL → `400` con las tablas
  intactas; solo `fechaInicio` o solo `fechaFin` → `200`; `POST`/`PUT`/`PATCH`/`DELETE` → `405`.

### Defectos del QA

| ID | Severidad | Hallazgo | Resolución |
|---|---|---|---|
| D-1 | BAJA | `rendimientoAjustado` perdía precisión con cantidades pequeñas: el teórico ajustado se redondeaba a 4 decimales antes de dividir por el real (plan con sal 0.003 kg daba `110.0` en vez de `111.00`; Sal 0,3 y 0,3 daba `111.1` en vez de `111.11`) | Corregido en el PR #181: el teórico ajustado usa **escala 10**. Con cantidades grandes ya coincidía al centésimo. No afectaba a `eficiencia`, `desviacion` ni `cumplimiento` |

### Puntos ambiguos del QA (pendientes de decisión)

La especificación no fija estos comportamientos. Se documentan como decisiones vigentes o
pendientes de confirmar con negocio; no son defectos.

| ID | Tema | Comportamiento actual | Pendiente |
|---|---|---|---|
| A-1 | `resumen.desviacionMateriaPrima` | Media **sin ponderar** de las desviaciones acumuladas por materia prima. Plan 1 solo: harina +8 y sal -100 dan `-46.0`, mientras que la desviación agregada por cantidades sería +5.88. Un insumo de consumo mínimo con un registro en cero domina el indicador y puede invertir su signo | Decidir si pasa a media ponderada por cantidad teórica o a una media sobre registros con `real > 0` |
| A-2 | Consumo fuera de receta | `teorica 0` y `desviacion null`, pero `eficiencia 0.00` (consecuencia de HU-22), y ese 0 **entra** en el promedio de rendimiento: el plan con un consumo fuera de receta mostró `66.67` con los otros dos consumos al 100 % | Decidir si debe ser `null` o quedar fuera del promedio |
| A-3 | Cancelados en `/materias-primas` | Se excluyen del acumulado (Oct: Harina `planes 1`, teórica 15). Un SQL sin filtro de estado sumaría 3 planes y daría 45 | Confirmar: la especificación solo dice que los cancelados se marcan aparte en el reporte general |
| A-4 | `sinReceta` y R-05 | Lista productos de planes que no entran en `planes[]` (un plan `PLANIFICADO` o `COMPLETADO` sin consumos sí cuenta; uno `CANCELADO` sin consumos no). Es coherente con hacer visible el hueco, pero se aparta de «solo planes con consumo» | Confirmar el criterio |
| A-5 | `tendencia` | Umbrales ±5 puntos porcentuales y los valores `CONSUMO_REDUCIDO` y `SIN_BASE` los definió la implementación | Confirmar con negocio |
| A-6 | Planes `EN_PROCESO` con consumo | Entran en `planesAnalizados` y en los promedios (R-05 los incluye); su cumplimiento parcial sesga el promedio | Decidir si se separan |
| A-7 | Agregaciones | `cumplimiento` del plan es la media simple de sus productos (incluidos los sin receta); `cumplimientoPromedio` es la media por plan; `rendimientoPromedio` es la media por registro de consumo, no por plan | Documentado; la especificación no lo dice |
| A-8 | Mensaje de los `401` | Todo `401` dice «Su sesión ha expirado», también sin token | Transversal; fuera de HU-26 |
| A-9 | Hora de registro (HU-22) | `fechaRegistro` de consumos y `fechaActualizacion` de inventario salen en **UTC** mientras el pool y el `timestamp` de error usan -05:00. No afecta a este reporte (usa solo `fecha_produccion`, un `DATE`), pero cualquier regla basada en esa fecha-hora cambiaría de día después de las 19:00 | **Reportado aparte, no es de HU-26**: lo atiende la historia de HU-22 |

## Desviaciones vs especificación

- **Campos añadidos.** `consumos[]` incluye `unidad` y `rendimientoAjustado` por consumo (el
  ejemplo de la especificación solo traía el del plan).
- **`desviacionMateriaPrima`.** El ejemplo de la especificación (5.80 con harina +7.00 y
  levadura -0.28) no permite deducir la fórmula; ver A-1.
- **`tendencia`.** Se añaden `CONSUMO_REDUCIDO` y `SIN_BASE` a los dos valores del ejemplo; ver A-5.
- **`planes[]` incluye cancelados con consumo**, marcados por su estado y fuera de los promedios.

## Pendientes / deuda

- **Frontend pendiente.** No existe la pantalla del reporte; el manual de consumo es el contrato
  para Angular.
- **Decisiones de negocio abiertas:** A-1 (indicador `desviacionMateriaPrima`), A-2 (consumo fuera
  de receta en el promedio), A-3, A-4, A-5 y A-6.
- **A-9** (hora en UTC de los registros de HU-22) queda como deuda de HU-22, no de esta historia.
- **PR docs y PR final** sin numerar todavía.
- **Datos de QA en la base.** Quedaron usuarios y registros de prueba locales en el esquema
  aislado; no forman parte del negocio ni viajan con el repositorio.
