# HU-26 — Eficiencia de producción · Backend verificado

| | |
|---|---|
| Historia | HU-26 — Eficiencia de producción (planificado vs. producido, teórico vs. real) |
| Módulo | Reportes (`reporte`) |
| Endpoints | `GET /api/v1/reportes/eficiencia`, `GET /api/v1/reportes/eficiencia/{idPlan}`, `GET /api/v1/reportes/eficiencia/materias-primas` |
| Fecha de implementación | 2026-11-05 |
| Colección Postman | `Docs/Postman/ProductionFood-HU26.postman_collection.json` (`Preparación`, `Reporte por período`, `Desviación por materia prima`, `Detalle de un plan`, `Errores`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Pull requests | #178 repositorio · #179 servicio · #180 controlador y batería de seguridad · #181 corrección de precisión del `rendimientoAjustado` (D-1) · #182 documentación · PR final #183 · ajustes tras el QA: #184 (zona horaria, A-9), #185 (mensajes 401, A-8), #186 (consumo fuera de receta, A-2); también implementados el umbral configurable (A-5) y solo planes completados en promedios (A-6) · este PR de documentación (sin número) |
| Estado QA | **QA ciego verificado · CP-01…CP-25 y SEC-01…SEC-05 pasan (SEC-03 cubierto por prueba automática, ver más abajo) · D-1 corregido · ajustes A-1…A-9 resueltos o decididos (umbral de `tendencia` pendiente de confirmar con negocio) · frontend pendiente** |

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
| Promedios sin nulos | `rendimientoPromedio` y `rendimientoAjustadoPromedio` son la media de los valores **no nulos a nivel de consumo** (92, 100, `null` → 96, nunca 64). Sin `orElse(0)`; `null` si no hay valores. `registrosSinRendimiento` cuenta los consumos excluidos (real 0 o consumo fuera de receta, A-2) |
| Consumo fuera de receta (A-2) | Con teórica 0 o inexistente, `eficiencia`, `rendimientoAjustado` y `desviacion` viajan `null`; el consumo queda fuera de los promedios y cuenta en `registrosSinRendimiento` |
| Rendimiento por plan | Media de los valores no nulos de sus consumos; `null` si ninguno |
| Solo `COMPLETADO` en promedios (A-6) | Los planes `CANCELADO` y `EN_PROCESO` con consumo aparecen en `planes[]` con su estado y se cuentan en `planesCancelados` y `planesEnProceso` (campo aditivo de `resumen`), pero no entran en ningún promedio ni en el acumulado por materia prima. `planesAnalizados` son solo los `COMPLETADO` con consumo |
| Planes sin consumo | Un plan sin ningún consumo con `cantidad_real` no aparece en `planes[]` (R-05) |
| `desviacionMateriaPrima` | Media **sin ponderar** de las desviaciones acumuladas por materia prima no nulas: no suma cantidades de unidades distintas (ver A-1) |
| `sinReceta` | Productos de planes del período (excluye `CANCELADO`, con o sin consumo, A-4) sin fila activa en `recetas`; `{producto, planes, nota}`. Lista vacía si no hay (R-07) |
| `tendencia` | Umbral configurable `productionfood.eficiencia.umbral-tendencia` (`5.00` por defecto, A-5): desviación `> umbral` → `CONSUMO_EXCESIVO`; `< -umbral` → `CONSUMO_REDUCIDO`; entre `-umbral` y `umbral` inclusive → `NORMAL`; desviación `null` → `SIN_BASE`. La especificación no daba los umbrales; el valor definitivo está pendiente de confirmar con negocio |
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
  "resumen": { "planesAnalizados": 1, "planesCancelados": 0, "planesEnProceso": 0,
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
`NO_AUTENTICADO` (401, con mensaje distinto según la causa, A-8); `SIN_PERMISO` (403); `USUARIO_INACTIVO` (403); `METODO_NO_PERMITIDO` (405).

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

- **92 tests** en las clases `Eficiencia*Test` en la entrega original; los ajustes añadieron pruebas (ver «Ajustes posteriores al QA»).
- `mvn test` sobre la suite completa en la entrega original: **2204 tests, 0 fallos, 1 omitido**. El omitido es el caso
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

### Ajustes posteriores al QA (A-1…A-9)

El QA señaló nueve puntos que la especificación no fija. Cada uno quedó **Resuelto** (cambio de
código) o como **Decisión** (se documenta y se mantiene). No eran defectos.

| ID | Tema | Estado | Qué quedó |
|---|---|---|---|
| A-1 | `resumen.desviacionMateriaPrima` | Decisión | Se mantiene como **media sin ponderar** de las desviaciones por insumo; no suma cantidades de unidades distintas. No se quita para no romper el contrato. El frontend usa `/eficiencia/materias-primas` para el detalle y la tarjeta dice «media de las desviaciones por insumo», no «se gastó X % más» |
| A-2 | Consumo fuera de receta | Resuelto (PR #186) | Con teórica 0, `eficiencia`, `rendimientoAjustado` y `desviacion` son `null`; queda fuera de los promedios y cuenta en `registrosSinRendimiento` |
| A-3 | Cancelados en `/materias-primas` | Decisión | El acumulado excluye los cancelados (y los `EN_PROCESO` por A-6); consistente con R-05 |
| A-4 | `sinReceta` y R-05 | Decisión | Se mantiene sobre todos los planes no cancelados del período, con o sin consumo: el hueco es más útil justo cuando aún no hay consumos |
| A-5 | `tendencia` | Resuelto (PR #186) | Umbral configurable `productionfood.eficiencia.umbral-tendencia` (`5.00` por defecto). El valor definitivo sigue pendiente de confirmar con negocio |
| A-6 | Planes `EN_PROCESO` | Resuelto (PR #186) | Solo `COMPLETADO` entra en promedios, `planesAnalizados` y `/materias-primas`. `EN_PROCESO` y `CANCELADO` se listan en `planes[]` con su estado; campo aditivo `resumen.planesEnProceso` |
| A-7 | Agregaciones | Decisión | Se documenta: el cumplimiento del plan es la media simple de sus productos; `rendimientoPromedio` es la media por consumo, no por plan |
| A-8 | Mensaje de los `401` | Resuelto (PR #185) | Mensaje distinto por causa (sin token, token inválido, token expirado) con el mismo `code` `NO_AUTENTICADO`; los textos están en `security/MensajesSeguridad.java` |
| A-9 | Hora de registro (HU-22) | Resuelto (PR #184) | `FlywayZonaHorariaConfig` renueva el pool tras migrar, de modo que las conexiones quedan en -05:00. Limitaciones: la fila SEED de V3 y las filas ya guardadas en UTC no se corrigen |

Cada PR reporta la suite completa sobre su propia base: #184 **2205 tests**, #185 **2210**,
#186 **2211**. No se suman entre sí.

## Desviaciones vs especificación

- **Campos añadidos.** `consumos[]` incluye `unidad` y `rendimientoAjustado` por consumo (el
  ejemplo de la especificación solo traía el del plan).
- **`desviacionMateriaPrima`.** El ejemplo de la especificación (5.80 con harina +7.00 y
  levadura -0.28) no permite deducir la fórmula; ver A-1.
- **`tendencia`.** Se añaden `CONSUMO_REDUCIDO` y `SIN_BASE` a los dos valores del ejemplo y el
  umbral es configurable; ver A-5.
- **`planes[]` incluye cancelados y en proceso con consumo**, marcados por su estado y fuera de los
  promedios (A-6); `resumen` añade `planesEnProceso`.

## Pendientes / deuda

- **Frontend pendiente.** No existe la pantalla del reporte; el manual de consumo es el contrato
  para Angular.
- **Umbral de `tendencia`** (A-5): confirmar con negocio el valor definitivo de
  `productionfood.eficiencia.umbral-tendencia`.
- **Datos históricos en UTC** (A-9): la fila SEED de V3 y las filas de HU-22 guardadas antes del
  PR #184 no se corrigen.
- **Datos de QA en la base.** Quedaron usuarios y registros de prueba locales en el esquema
  aislado; no forman parte del negocio ni viajan con el repositorio.
