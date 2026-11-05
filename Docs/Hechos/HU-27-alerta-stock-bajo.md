# HU-27 — Alerta de stock bajo · Backend verificado

| | |
|---|---|
| Historia | HU-27 — Alerta de stock bajo de materias primas |
| Módulo | Inventario |
| Endpoints | `GET /api/v1/inventario/alertas`, `GET /api/v1/inventario/alertas/resumen`, `GET /api/v1/inventario/alertas/sugerencia-compra` |
| Colección Postman | `Docs/Postman/ProductionFood-HU27.postman_collection.json` (`Preparación`, `Alertas (CP-01…CP-26)`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Pull requests | #156 repositorio y servicio · #157 sugerencia de compra · #158 controlador · #159 corrección D-02 |
| Estado QA | **QA ciego verificado · CP-01…CP-26 y SEC-01…SEC-05 pasan, salvo CP-20/21/22 (no ejecutables o parciales en base compartida) · frontend pendiente** |

> Esta ficha documenta las alertas de stock bajo: qué materias primas están en o bajo su mínimo,
> cuánto falta, cuánto costaría reponerlo y una sugerencia de compra agrupada por proveedor. El
> inventario de materia prima y su mínimo son de HU-14/HU-15; la compra se registra en HU-13. Esta
> historia solo lee: no escribe ni mueve stock. El contrato práctico para el frontend está en el
> manual
> [`../Manuales_Consumo_Frontend/HU-27-alerta-stock-bajo.md`](../Manuales_Consumo_Frontend/HU-27-alerta-stock-bajo.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/inventario/alertas?incluirInactivas=false` | ADMIN, PRODUCCION, COMPRAS, CONSULTA | 200/400/401/403 | Materias primas con `cantidadDisponible <= stockMinimo`, con faltante, nivel y costo de reposición; `generadoEn`, `resumen` y `costoReposicionTotal` |
| GET | `/api/v1/inventario/alertas/resumen` | Cualquier rol autenticado | 200/401 | Contadores `{total, agotadas, bajas}` para el indicador global; solo materias primas activas |
| GET | `/api/v1/inventario/alertas/sugerencia-compra` | ADMIN, COMPRAS | 200/401/403 | Arreglo de grupos por proveedor con cantidad sugerida y costo estimado |

**Roles.** El listado es para `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA`; `VENTAS` recibe
`403 SIN_PERMISO`. El resumen es el único que ve cualquier rol autenticado, incluido `VENTAS`,
porque solo expone contadores. La sugerencia de compra es de `ADMIN` y `COMPRAS`; los demás
reciben `403 SIN_PERMISO`.

**Solo lectura y sin caché.** Los tres recursos solo admiten `GET`; cualquier otro método
responde `405`. Cada llamada consulta la base: un cambio de inventario se refleja de inmediato.

Piezas internas:

| Pieza | Rol |
|---|---|
| `AlertaRepository` | JDBC sobre la vista `v_inventario_materia_prima` y la última compra recibida (`compras`, `detalle_compra`, `proveedores`); devuelve `AlertaStockFila` (vía `AlertaStockRowMapper`) y `SugerenciaFila` |
| `AlertaService` | Calcula faltante, nivel, costo de reposición, orden y resumen; arma la sugerencia de compra agrupada por proveedor |
| `AlertaController` | Los tres endpoints con `@PreAuthorize` y Swagger; solo despacha al servicio |
| DTO de respuesta | `ListadoAlertasResponse`, `AlertaStockResponse`, `ResumenAlertasResponse` y `SugerenciaCompraResponse` |

**Sin migración Flyway nueva.** Se reutilizan las tablas existentes de inventario, materias
primas y compras. Esta historia solo escribe código sobre el esquema vigente.

## Reglas de negocio implementadas

- **R-01 · una materia prima está en alerta cuando `cantidadDisponible <= stockMinimo`.** Si no
  tiene fila de inventario, se trata como cantidad cero. Con mínimo cero solo entra la que está
  en cero.
- **R-02 · nivel `AGOTADO` cuando la cantidad es cero y `BAJO` cuando es mayor que cero pero no
  supera el mínimo.** El resumen cuenta ambos niveles por separado y `total = agotadas + bajas`.
- **R-03 · orden estable.** `AGOTADO` primero y, dentro de cada nivel, mayor `faltante` primero.
- **R-04 · cálculo del faltante y del costo de reposición.** `faltante = stockMinimo −
  cantidadDisponible` y `costoReposicion = faltante × costoUnitario`; `costoReposicionTotal` es la
  suma de los ítems.
- **R-05 · cantidad sugerida con margen.** `cantidadSugerida = faltante × 1,2` redondeado hacia
  arriba a 2 decimales; `costoEstimado = cantidadSugerida × costoUnitario`; `totalEstimado` suma
  los ítems del grupo.
- **R-06 · el proveedor sugerido es el de la última compra `RECIBIDA`.** Las materias primas sin
  compras recibidas van en un grupo **sin la clave `proveedor`** (el JSON omite los nulos). Las
  de faltante cero (mínimo cero y agotadas) se excluyen de la sugerencia, aunque sí salen en
  `/alertas`.
- **R-07 · por defecto solo materias primas activas.** `incluirInactivas=true` añade las
  inactivas al listado; el resumen siempre cuenta solo las activas.

## Decisiones clave

| Tema | Decisión implementada |
|---|---|
| Parámetro inválido | `incluirInactivas` no booleano responde `400 PARAMETRO_INVALIDO` (código transversal). La especificación decía `RANGO_INVALIDO`; se mantiene el transversal por coherencia con el resto de la API |
| Resumen abierto | El resumen es visible para cualquier rol autenticado para que el indicador global del menú funcione sin exponer costos |
| Sugerencia restringida | Incluye proveedor y costos, por eso solo `ADMIN` y `COMPRAS` |
| Sin caché | No hay caché ni tabla de alertas: se calcula en cada llamada desde el inventario actual |
| Solo `GET` | Los demás métodos responden `405`; la historia no crea compras ni modifica stock |
| Dato ausente | `ultimaActualizacion` se omite si la materia prima no tiene fila de inventario |
| Redondeo | Cantidad sugerida hacia arriba a 2 decimales; los costos se redondean a 2 decimales |
| Prellenado de compra | El frontend toma un grupo y prellena el alta de compra de HU-13; el backend no lo hace |
| Ejemplos de la especificación | La §4 trae un ejemplo de costo inconsistente (Mantequilla: 15,5 × 18500 = 286750, no 344100, que corresponde a 18,60 × 18500). El JSON real usa los cálculos correctos |

## Modelo de datos

**Sin tablas nuevas.** La alerta se deriva de:

| Origen | Uso |
|---|---|
| Vista `v_inventario_materia_prima` | Nombre, unidad, cantidad disponible, stock mínimo, costo unitario, estado y última actualización |
| `compras` / `detalle_compra` | Última compra `RECIBIDA` de cada materia prima, para el proveedor sugerido |
| `proveedores` | Id y nombre del proveedor del grupo |

## Contrato resumido

**GET /api/v1/inventario/alertas?incluirInactivas=false → 200**:

```json
{ "generadoEn": "2026-11-05T14:02:11.482",
  "resumen": { "total": 2, "agotadas": 1, "bajas": 1 },
  "items": [
    { "idMateriaPrima": 14, "nombre": "Levadura", "unidad": "kg",
      "cantidadDisponible": 0.00, "stockMinimo": 5.00, "faltante": 5.00,
      "nivel": "AGOTADO", "costoUnitario": 12000.00, "costoReposicion": 60000.00 },
    { "idMateriaPrima": 7, "nombre": "Mantequilla", "unidad": "kg",
      "cantidadDisponible": 4.50, "stockMinimo": 20.00, "faltante": 15.50,
      "nivel": "BAJO", "costoUnitario": 18500.00, "costoReposicion": 286750.00,
      "ultimaActualizacion": "2026-11-04T16:20:00" }
  ],
  "costoReposicionTotal": 346750.00 }
```

**GET /api/v1/inventario/alertas/resumen → 200**:

```json
{ "total": 2, "agotadas": 1, "bajas": 1 }
```

**GET /api/v1/inventario/alertas/sugerencia-compra → 200**:

```json
[
  { "proveedor": { "idProveedor": 3, "nombre": "Lácteos del Sur" },
    "items": [
      { "materiaPrima": "Mantequilla", "faltante": 15.50,
        "cantidadSugerida": 18.60, "costoEstimado": 344100.00 }
    ],
    "totalEstimado": 344100.00 },
  { "items": [
      { "materiaPrima": "Levadura", "faltante": 5.00,
        "cantidadSugerida": 6.00, "costoEstimado": 72000.00 }
    ],
    "totalEstimado": 72000.00 }
]
```

La Mantequilla muestra el cálculo real: `faltante 15,50 × 1,2 = 18,60` y `18,60 × 18500 =
344100`. El costo de reposición del listado usa el faltante (`15,50 × 18500 = 286750`). El
segundo grupo no trae `proveedor` porque la Levadura no tiene compras recibidas.

**Errores:** `PARAMETRO_INVALIDO` (400); `NO_AUTENTICADO` (401); `SIN_PERMISO` (403).

## Archivos creados

```
src/main/java/co/edu/corposucre/productionfood/inventario/
├── AlertaController.java
├── AlertaService.java
├── AlertaRepository.java
├── AlertaStockFila.java
├── AlertaStockRowMapper.java
├── SugerenciaFila.java
└── dto/
    ├── ListadoAlertasResponse.java
    ├── AlertaStockResponse.java
    ├── ResumenAlertasResponse.java
    └── SugerenciaCompraResponse.java

src/test/java/co/edu/corposucre/productionfood/inventario/
├── AlertaRepositoryTest.java
├── AlertaServiceTest.java
├── AlertaControllerTest.java
└── AlertaSeguridadTest.java
```

## Evidencia (pruebas backend)

Las pruebas cubren el repositorio JDBC contra MySQL, el cálculo de faltante, niveles, orden y
resumen, la sugerencia de compra (factor 1,2, redondeo hacia arriba, proveedor de la última
compra recibida, grupo sin proveedor y exclusión de faltante cero), el contrato HTTP y la matriz
de roles y seguridad.

`mvn test` sobre la suite completa: **1759 tests, 0 fallos**.

## Smoke y QA ciego

- **QA ciego:** CP-01…CP-26 y SEC-01…SEC-05 pasan, salvo los casos descritos abajo.
- **CP-20, CP-21 y CP-22:** no ejecutables o parciales en una base compartida, porque exigen
  alterar el inventario o fijar datos controlados. La lógica está cubierta por pruebas
  automatizadas.
- **SEC-03 y SEC-05:** el QA los reportó parcial y fallido, pero se reprodujeron a mano contra
  la API y **pasan**: un token vencido responde `401 NO_AUTENTICADO` y un usuario desactivado con
  token vigente responde `403 USUARIO_INACTIVO` en los tres endpoints. El hallazgo D-01 del QA
  (401 en lugar de 403) fue un falso positivo y no hay defecto en el filtro JWT.
- **D-02:** defecto detectado en el QA y corregido en el PR #159.
- **Especificación §4:** el ejemplo de costo de la Mantequilla es inconsistente (ver
  Decisiones clave); la implementación usa los cálculos correctos.

## Desviaciones vs especificación

- **`PARAMETRO_INVALIDO` en lugar de `RANGO_INVALIDO`.** Un `incluirInactivas` no booleano
  responde `400 PARAMETRO_INVALIDO`, el código transversal de la API para parámetros mal
  formados.
- **Ejemplos de costo de la §4.** Se usan los cálculos correctos (`15,5 × 18500 = 286750` y
  `18,60 × 18500 = 344100`) en lugar de los valores inconsistentes del ejemplo.

## Pendientes / deuda

- **Frontend pendiente.** No existe pantalla de alertas ni indicador global; el manual de
  consumo es el contrato para Angular.
- **Prellenado de compra.** La sugerencia no crea la compra: el frontend prellena el alta de HU-13.
- **Sin notificaciones.** No hay correo ni aviso automático; la alerta se consulta bajo demanda.
- **Datos de QA en la base.** Quedaron usuarios y registros de prueba locales; no forman parte
  del negocio ni viajan con el repositorio.
