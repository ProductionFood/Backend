# HU-15 — Historial de compras · Backend verificado

| | |
|---|---|
| Fecha de implementación | 2026-10-29 |
| Commits / PR | PRs #101–#105 a `HU-15/historial-compras`; este cambio añade la documentación (PR5) |
| Colección Postman | `Docs/Postman/ProductionFood-HU15.postman_collection.json` (`Preparación`, `Listado con filtros (CP-01…CP-19)`, `Resumen`, `Por-proveedor`, `Seguridad (SEC-01…SEC-05)`) |
| Estado QA | **QA ciego verificado 2026-10-29 (20/21 CP + SEC completo; DEF-H15-001 reparado en el momento) · frontend pendiente** |

> Esta ficha documenta el backend de la historia. La verificación en caliente se ejecutó el
> 2026-10-29 contra la API real en `:8085` y MySQL 8.4 en modo ciego (solo la especificación,
> los casos de QA y el contrato transversal): 20 de los 21 CP pasaron al primer pase —CP-21
> quedó reparada en el momento con el fix del `405` (DEF-H15-001)— y la batería
> SEC-01…SEC-05 sobre los 3 endpoints (15 combinaciones) pasó completa. El consumo desde el
> frontend sigue pendiente. Los ejemplos salen de la especificación, de los DTO reales y de la
> ejecución de QA; los ids dependen de la base. El contrato de consumo está en el manual
> [`../Manuales_Consumo_Frontend/HU-15-historial-compras.md`](../Manuales_Consumo_Frontend/HU-15-historial-compras.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/compras` | ADMIN, COMPRAS, PRODUCCION, CONSULTA | 200/400/401/403/404 | Historial paginado con filtros `AND` (proveedor, rango, estado), `sort` de lista blanca e `incluirDetalle`; el filtro `idProveedor` inexistente responde `404 PROVEEDOR_INEXISTENTE` |
| GET | `/api/v1/compras/resumen` | ADMIN, COMPRAS | 200/400/401/403 | Resumen del período por estado con `gastoEjecutado` y `gastoComprometido` |
| GET | `/api/v1/compras/por-proveedor` | ADMIN, COMPRAS | 200/400/401/403 | Gasto ejecutado agrupado por proveedor, ordenado por total y con `porcentaje` |
| POST, PUT, PATCH, DELETE | `/api/v1/compras/resumen`, `/api/v1/compras/por-proveedor` | — | 405 | Paths reservados: `escriturasNoPermitidas` responde `405 METODO_NO_PERMITIDO` (R-01) |

**Roles.** Lectura del listado para `ADMIN`, `COMPRAS`, `PRODUCCION` y `CONSULTA`. El resumen
y el gasto por proveedor los reserva la matriz a `ADMIN` y `COMPRAS`: `PRODUCCION`, `CONSULTA`
y `VENTAS` reciben `403 SIN_PERMISO` en esos dos. La pantalla de tablero se muestra solo a los
roles que el backend admite; ocultar la pestaña no sustituye al `403`.

Piezas internas:

| Pieza | Rol |
|---|---|
| `ResumenComprasResponse` | DTO del resumen: `Periodo` (con ambos campos en `null` y sin serializar si no se enviaron fechas), el bloque `TotalesEstado` de cada estado (`recibidas`, `pendientes`, `canceladas`) y los dos gastos derivados |
| `GastoProveedorResponse` | Fila del ranking: `proveedor`, `cantidadCompras`, `total` y `porcentaje` (escala 2 con `HALF_UP`) |
| `ResumenEstadoFila` / `ResumenEstadoFilaRowMapper` | Fila de `resumenPorPeriodo`: cantidad y total agrupados por estado en una sola consulta |
| `GastoProveedorFila` / `GastoProveedorFilaRowMapper` | Fila de `gastoPorProveedor`: solo compras `RECIBIDA`, ordenadas por total descendente desde la base |
| `CompraDetalleFila` / `CompraDetalleFilaRowMapper` | Fila plana cabecera + línea del `LEFT JOIN` de `buscarConDetalle`, con la bandera `conDetalle()` para distinguir «compra sin líneas» (responde `detalle: []`) de «detalle no pedido» |
| `CompraRepository.buscarConDetalle` | Una sola consulta con `LEFT JOIN` a `detalle_compra`: la página de cabeceras se pagina **antes** de unir el detalle y la agrupación es en memoria con `LinkedHashMap` que preserva el orden de primera aparición de cada compra — nunca una consulta por compra |
| `ProveedorInexistenteFiltroException` | `404 PROVEEDOR_INEXISTENTE` del filtro `idProveedor` (solo existencia, no actividad): distinto del mismo `code` de las altas de HU-13, que ahí es `409` porque es un conflicto de negocio |
| `CompraController.escriturasNoPermitidas` | `405 METODO_NO_PERMITIDO` para `POST`/`PUT`/`PATCH`/`DELETE` sobre `/resumen` y `/por-proveedor`, sin `@PreAuthorize` porque no toca datos |
| Whitelist `ORDEN` (`CompraService`) | `fecha → c.fecha`, `estado → c.estado`, `total → total_compra` (alias del `SELECT`, no una columna de `compras`) y `id → c.id_compra`; por defecto `fecha,desc`. `PageRequest.of` rechaza cualquier campo fuera de la lista |
| `TAMANO_MAXIMO_CON_DETALLE = 20` | Tope de página cuando el listado se pide con detalle (CP-12): el recorte va antes de `PageRequest.of` y la respuesta refleja el tamaño recortado, sin responder `400` |

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| R-01 | **Solo lectura.** Esta historia no tiene escritura: el estado de una compra se cambia en HU-13 y las líneas en HU-14. Los paths de agregado están reservados y cualquier método de escritura sobre `/resumen` o `/por-proveedor` responde `405 METODO_NO_PERMITIDO` en lugar de caer en el `PUT /compras/{id}` de HU-13 |
| R-02 | **Filtros combinados con `AND`.** Proveedor, rango de fechas y estado se aplican a la vez; sin ninguno, el listado es el historial completo paginado. El cliente no filtra sobre una página de 20 |
| R-03 | **Rango inclusivo en los dos extremos.** `compras.fecha` es `DATE` (sin hora), así que la comparación es `BETWEEN`: `fechaInicio` y `fechaFin` se incluyen completos y nada se pierde por la medianoche. Un rango invertido se rechaza con `400 RANGO_FECHAS_INVALIDO` antes de tocar la base, igual en los tres endpoints |
| R-04 | **Canceladas fuera del gasto, pendientes aparte.** `gastoEjecutado` es solo `recibidas.total` y `gastoComprometido` solo `pendientes.total`; las `CANCELADA` se informan en su bloque con cantidad y total —para explicar el período completo— pero no financian ninguno de los dos. `/por-proveedor` cuenta únicamente compras `RECIBIDA` |
| R-05 | **El total siempre sale del detalle.** `totalCompra`, los `total` del resumen y los del ranking son `SUM(cantidad × precio_unitario)` recalculado en cada consulta (`COALESCE(…, 0)` para compras sin líneas): nunca una columna guardada ni una suma hecha en el cliente. `cantidadItems` del resumen y del ranking usa `COUNT(DISTINCT c.id_compra)` para no contar una compra varias veces por el `JOIN` |
| R-06 | **Sin N+1.** `incluirDetalle=true` resuelve cabecera y líneas en una única consulta con `LEFT JOIN` y agrupa en memoria; la página, el detalle y `totalElements` se leen dentro de la misma `@Transactional(readOnly = true)`, de modo que en `REPEATABLE READ` las tres consultas ven el mismo snapshot y una compra insertada durante la lectura no hace mentir al total. CP-18 midió la página con detalle en ~6–7 ms con 200 compras sembradas y 4 hilos escribiendo en paralelo, contra el umbral de 2000 ms de la prueba |

## Modelo de datos

**Sin migración Flyway nueva.** La historia solo lee el esquema existente: V1 ya crea
`compras` y `detalle_compra`, y V2 añade el índice `idx_compras_fecha (fecha, estado)`, que es
el que cubre los filtros de fecha y estado del historial — `EXPLAIN` verificado con
`type=range` y `Using index`.

`totalCompra` y `cantidadItems` **no son columnas de `compras`**: el listado los calcula con
dos subconsultas correlacionadas sobre `detalle_compra` (`SUM(cantidad × precio_unitario)` y
`COUNT(*)` de las líneas de esa compra) y con `COALESCE` para las compras sin líneas. El alias
`total_compra` del `SELECT` es la única forma en que `sort=total` ordena: no existe una columna
de total que desactualice.

## Contrato resumido

**GET /api/v1/compras?idProveedor=3&fechaInicio=2026-09-01&fechaFin=2026-09-18&estado=RECIBIDA → 200**
(`PageResponse<CompraResponse>`):

```json
{
  "content": [
    { "idCompra": 11, "proveedor": { "idProveedor": 3, "nombre": "Harinas del Caribe" },
      "fecha": "2026-09-15", "estado": "RECIBIDA",
      "cantidadItems": 4, "totalCompra": 1240000.00, "editable": false }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

Con `incluirDetalle=true` cada compra trae además `"detalle": [...]` con la misma forma de línea
de HU-14 (`subtotal`, `costoReferencia`, `diferenciaCosto`); sin el parámetro el campo **no se
serializa** y con él la página se recorta a 20 (CP-12). Una página fuera de rango responde
`200` con `content: []`, no un `404`.

**GET /api/v1/compras/resumen?fechaInicio=2026-09-01&fechaFin=2026-09-30 → 200**:

```json
{
  "periodo": { "desde": "2026-09-01", "hasta": "2026-09-30" },
  "recibidas":  { "cantidad": 8, "total": 4850000.00 },
  "pendientes": { "cantidad": 2, "total":  620000.00 },
  "canceladas": { "cantidad": 1, "total":   85000.00 },
  "gastoEjecutado":  4850000.00,
  "gastoComprometido": 620000.00
}
```

Período sin compras → `200` con todo en cero (cantidades `0`, totales `0.00`), no un `404`.
Sin fechas, `periodo` viene vacío porque los campos en `null` no se serializan.

**GET /api/v1/compras/por-proveedor?fechaInicio=2026-09-01&fechaFin=2026-09-30 → 200**
(array, sin paginación):

```json
[
  { "proveedor": "Harinas del Caribe S.A.S.", "cantidadCompras": 4,
    "total": 2340000.00, "porcentaje": 48.25 },
  { "proveedor": "Distribuidora Lácteos", "cantidadCompras": 3,
    "total": 1810000.00, "porcentaje": 37.32 }
]
```

Ordenado por `total` descendente (CP-15); los porcentajes suman 100,00 con residuo de
redondeo de ±0,01 (CP-16). Sin recibidas en el rango → `200` con `[]`.

**Errores:** `RANGO_FECHAS_INVALIDO` (400, en los tres endpoints); `PARAMETRO_INVALIDO` (400,
`estado` no canónico, `size` fuera de rango o fecha mal formada) y `CAMPO_ORDEN_INVALIDO` (400,
campo de `sort` fuera de la whitelist); `PROVEEDOR_INEXISTENTE` (404 **en el filtro** —el
mismo `code` en el alta de HU-13 es `409`, porque ahí es un conflicto de negocio—);
`METODO_NO_PERMITIDO` (405, escritura sobre `/resumen` y `/por-proveedor`); `NO_AUTENTICADO`
(401); `SIN_PERMISO` y `USUARIO_INACTIVO` (403).

## Evidencia (pruebas backend)

- **QA ciego del 2026-10-29:** **20/21 CP** al primer pase —CP-21 reparada en el momento— y
  **SEC-01…SEC-05 en los 3 endpoints = PASA** (15 combinaciones), sin defectos críticos ni
  altos abiertos.
- **CP-18 (rendimiento y carrera):** la página con detalle respondió en **~6–7 ms** con 200
  compras sembradas y 4 hilos escribiendo en paralelo, muy por debajo del umbral de 2000 ms
  que fija la prueba, tanto sin carrera como durante ella.
- **Suite completa:** `mvn -q test` con **exit code 0** y **1111 tests, 0 fallos, 0 errores y
  0 ignorados** (informes de Surefire, verificado el 2026-10-29 en la rama de la historia).
- El estado de QA va en la fila `Estado QA` de la cabecera, no aquí.

## Desviaciones vs especificación

- **Cifras mayores que 2^53 pierden centavos al parsearse en JavaScript.** La API devuelve el
  decimal exacto de `BigDecimal`; la pérdida ocurre en el parser del navegador (más allá de
  ~9 × 10^15) y el contrato §6 lo admite. Mismo hallazgo que D3 de HU-14, cerrado allí como
  falso positivo del backend.
- **`size` con `incluirDetalle=true` se recorta a 20 en vez de responder `400`** (CP-12): es
  un *clamp* de rendimiento y la cabecera de la respuesta refleja el tamaño recortado. Sin
  detalle, la cota sigue siendo la habitual de 100.
- **CP-21 se corrigió tras el QA:** el `PUT` sobre `/compras/resumen` caía en el
  `PUT /compras/{id}` de HU-13 y respondía `400 PARAMETRO_INVALIDO` en lugar de `405`;
  tras el fix (DEF-H15-001) el caso pasó.

## Defectos y cierre

| ID | Hallazgo | Estado |
|---|---|---|
| DEF-H15-001 | Un `PUT` sobre `/compras/resumen` o `/compras/por-proveedor` se resolvía contra `PUT /compras/{id}` de HU-13 («resumen» no es un id) y respondía `400 PARAMETRO_INVALIDO` en lugar de `405` | **Corregido en PR #105** con `escriturasNoPermitidas`, que lanza `HttpRequestMethodNotSupportedException`; CP-21 re-testeada en el momento |
| DEF-H15-002 | El script de verificación SQL de CP-13 en `tarea-qa.md` contaba con `COUNT(*)` sobre un `LEFT JOIN`, que duplica la fila de la compra por cada línea y contaría una `RECIBIDA` con dos líneas como dos compras | **Corregido en PFDocs PR #5** (`COUNT(DISTINCT c.id_compra)`); la API ya usaba `COUNT(DISTINCT)` en sus consultas de agregado |

Ninguno quedó abierto como crítico o alto: DEF-H15-001 se reparó durante la verificación y
DEF-H15-002 era del script de QA, no del backend.

**PRs:** #101 (resumen por período), #102 (gasto por proveedor), #103 (`incluirDetalle` con
`JOIN` único y tope de `size`), #104 (404 del filtro y orden por `total`) y #105 (fix del
`405`), todos mergeados en `HU-15/historial-compras`; esta documentación va como PR5 sobre
`HU-15/pr5-docs`.

## Pendientes / deuda

- **Frontend:** no existe pantalla de historial de compras; el manual de consumo es el contrato
  para Angular, que debe pintar el tablero (resumen + ranking) y la tabla con filtros `AND`,
  y cargar el detalle al expandir la fila.
- **Deuda transversal D2 de HU-14:** un `Accept` no soportado con token válido puede responder
  `401` engañoso; sigue abierta y se corrige en la configuración global de la API, no aquí.
- **Datos de QA en la base:** la colección crea usuarios, proveedores y compras de prueba en
  el entorno local; no son datos de negocio ni viajan con el repositorio.
