# HU-10 — Consulta de inventario de materia prima · Backend implementado

| | |
|---|---|
| Fecha de implementación | 2026-10-23 |
| Commits / PR | PRs #66–#68; este PR añade la documentación |
| Colección Postman | `Docs/Postman/ProductionFood-HU10.postman_collection.json` (`Preparación`, `Consultas (CP-01…CP-20)`, `Roles (matriz)`, `Seguridad (SEC-01…05)`) |
| Estado QA | **Backend implementado 2026-10-23 · verificación en caliente (MySQL) y frontend pendientes** |

> Esta ficha documenta el backend de la historia. La implementación está cerrada con pruebas
> automatizadas; la verificación en caliente contra la API real y MySQL, y el consumo desde el
> frontend, siguen **pendientes**. Los ejemplos de contrato salen de la especificación y de los
> DTO reales, no de una ejecución pasada.

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/inventario` | ADMIN, PRODUCCION, COMPRAS, CONSULTA | 200/400/401/403 | Listado paginado con filtros combinables `idMateriaPrima`, `soloStockBajo` e `incluirInactivas`, orden de lista blanca y la fila completa de la vista |
| GET | `/api/v1/inventario/{idMateriaPrima}` | ADMIN, PRODUCCION, COMPRAS, CONSULTA | 200/400/401/403/404 | Detalle de una materia prima contra la misma vista; 404 si no existe |
| GET | `/api/v1/inventario/{idMateriaPrima}/movimientos` | ADMIN, PRODUCCION, COMPRAS | 200/400/401/403/404 | Kardex paginado con rango de fechas inclusivo, saldos y documento de origen |

**No existe ningún endpoint de escritura** (R-01): no hay `POST`, `PUT`, `PATCH` ni `DELETE`.
El inventario se mueve desde HU-09 (lotes), HU-13 (recepción de compras) y HU-23 (consumo de
producción), siempre a través de `InventarioService`; un endpoint de escritura en este módulo
abriría una ruta que dejaría el saldo sin su movimiento de kardex. Un intento de escritura
responde `405 METODO_NO_PERMITIDO` (CP-17).

**Roles.** Lectura del listado y del detalle: `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA`. El
kardex queda fuera de `CONSULTA` porque expone el detalle operativo de quién movió el stock y con
qué documento. `VENTAS` no accede a ninguno de los tres endpoints.

Piezas internas:

| Pieza | Rol |
|---|---|
| `InventarioController` | Los tres `GET` con `@PreAuthorize` y Swagger; sin métodos de escritura |
| `InventarioService` | Validación de paginación/orden, normalización de filtros, regla 404 vs página vacía y delegación en los repositorios de lectura |
| `InventarioConsultaRepository` | JDBC de solo lectura sobre `v_inventario_materia_prima`; `WHERE` compartido por `buscar` y `contar` |
| `InventarioConsultaRowMapper` | Mapeo explícito de la vista a `InventarioResponse` |
| `KardexConsultaRepository` | JDBC de solo lectura sobre `movimientos_inventario_mp` con `LEFT JOIN usuarios`; orden fijo `id_movimiento DESC` |
| `MovimientoInventarioRowMapper` | Mapeo del kardex a `MovimientoResponse`, con `usuario = "Sistema"` cuando no hay autor |
| DTOs | `InventarioResponse`, `MovimientoResponse` y `OrigenResponse` |
| Vista `v_inventario_materia_prima` | Única fuente de `cantidad_disponible`, `estado_stock` y `faltante` (V2) |

Sin migración Flyway propia: la vista se creó en V2 y esta historia solo la consume.

## Decisiones clave

| Tema | Decisión implementada |
|---|---|
| R-01 · solo lectura | La vista y el kardex se consultan con `NamedParameterJdbcTemplate` y mappers propios. No se crean entidades JPA para proyecciones que nunca se escriben; el kardex separa lectura (`KardexConsultaRepository`) de escritura (`MovimientoInventarioRepository`) |
| R-02 · la vista es la fuente | Listado, detalle y filtros leen `v_inventario_materia_prima`; el servicio no reconstruye `estado_stock` ni `faltante`, así que los tres caminos muestran los mismos valores calculados |
| R-04 · inactivas visibles | `incluirInactivas` es un booleano con **default `true`**. Solo se excluyen con `incluirInactivas=false`; el parámetro ausente o nulo mantiene las inactivas |
| Kardex | Orden **fijo** por `id_movimiento DESC` (del más reciente al más antiguo). No acepta `sort`: el orden constante aprovecha el índice `idx_mov_mp_materia` |
| Autor desconocido | `id_usuario NULL` se traduce a `usuario: "Sistema"`, nunca a usuario 0 ni cadena vacía (CP-16). El `LEFT JOIN` es obligatorio también en el `COUNT` para no perder filas |
| Existencia en el kardex | Se valida contra la vista de inventario, no contra los movimientos: una MP recién creada existe y devuelve página vacía (CP-15); una MP inexistente devuelve 404 |
| Fechas del kardex | `fechaInicio`/`fechaFin` en `YYYY-MM-DD`, **ambos inclusivos**. La conversión a `Timestamp` se hace en Java (`hasta.plusDays(1)` con `<`) para no envolver `m.fecha` en funciones y conservar el índice |
| `RANGO_FECHAS_INVALIDO` | Código propio del contrato transversal (`04-CONTRATO-API.md` §4), distinto del genérico `PARAMETRO_INVALIDO` |
| Paginación | `page >= 0` y `size` entre 1 y 100. El `OFFSET` se calcula en `long`: `page` no tiene cota superior y `page * size` desbordaría un `int` |
| Orden del listado | Lista blanca resuelta en el servicio (`nombre`, `cantidadDisponible`, `faltante`, `fechaActualizacion`); la columna SQL resultante es la única que llega al repositorio |

## Reglas de negocio implementadas

- **R-01 · solo lectura.** Esta historia no modifica nada. El `InventarioController` no expone
  métodos de escritura y los tests comprueban que `POST`, `PUT` y `PATCH` responden `405`.
- **R-02 · se consulta la vista, no la tabla.** `v_inventario_materia_prima` resuelve con un
  `LEFT JOIN` a `inventario` y `COALESCE(..., 0)` los tres cálculos: cantidad disponible,
  `estado_stock` y `faltante`. Una MP sin fila de inventario aparece en cero, no desaparece.
- **R-03 · el kardex explica el saldo.** `GET /{id}/movimientos` devuelve qué entró o salió,
  cuándo, quién y por qué documento, con `saldoAnterior` y `saldoPosterior`. Es la única forma de
  reconstruir cómo se llegó a la `cantidadDisponible` actual.
- **R-04 · las inactivas con stock se muestran.** Una MP desactivada conserva sus existencias;
  ocultarla haría desaparecer stock real del sistema. Se devuelve con `activo: false` y solo se
  excluye con `incluirInactivas=false`.
- **R-05 · `estadoStock`.** `AGOTADO` si `disponible = 0`; `BAJO` si `0 < disponible <
  stockMinimo`; `OK` si `disponible >= stockMinimo`. Con `stockMinimo = 0` nunca se marca `BAJO`.
  `soloStockBajo=true` filtra `estado_stock IN ('BAJO','AGOTADO')`.
- **CA-03 · filtro por materia prima.** `idMateriaPrima` es opcional y combinable con los demás
  filtros. Un id inexistente devuelve una página vacía, no 404 (el 404 es del detalle).
- **Kardex con fechas.** `fechaInicio` y `fechaFin` son opcionales e inclusivos; si
  `fechaInicio > fechaFin` responde `400 RANGO_FECHAS_INVALIDO`.
- **Kardex de una MP sin movimientos.** Página vacía (`content: []`, `totalElements: 0`), sin
  error (CP-15).
- **Documento de origen.** `origen` se compone de `tabla_origen` e `id_origen`; se omite cuando
  ambos son nulos y, cuando solo falta uno, el campo ausente se omite del objeto (`non_null`).

## Modelo de datos

**Vista `v_inventario_materia_prima`** (V2 §C-07). Campos que expone el contrato:

| Campo JSON | Origen | Notas |
|---|---|---|
| `idMateriaPrima`, `nombre` | `materias_primas` | |
| `unidad` | `unidades_medida.abreviatura` | Abreviatura (`kg`, `g`, `L`…), no el nombre de la unidad |
| `cantidadDisponible` | `COALESCE(inventario.cantidad_disponible, 0)` | Dos decimales |
| `stockMinimo` | `materias_primas.stock_minimo` | |
| `faltante` | `GREATEST(stock_minimo - disponible, 0)` | Nunca negativo |
| `estadoStock` | `CASE` de la vista | `AGOTADO`, `BAJO` u `OK` (R-05) |
| `activo` | `materias_primas.estado` | `true` = activa; independiente del stock (R-04) |
| `fechaActualizacion` | `inventario.fecha_actualizacion` | `DEFAULT/ON UPDATE CURRENT_TIMESTAMP`; puede faltar de la respuesta solo si faltara la fila de inventario (la inclusión `non_null` de Jackson omite la clave) |

`costo_unitario` existe en la vista pero **no se expone** en este contrato.

**Campos ordenables** del listado: `nombre`, `cantidadDisponible`, `faltante` y
`fechaActualizacion`. Por defecto `nombre,asc`; dirección `asc`/`desc`. Cualquier otro campo
responde `400 CAMPO_ORDEN_INVALIDO`.

**Tabla `movimientos_inventario_mp`** (V2 §C-04): `id_movimiento`, `id_materia_prima`,
`tipo_movimiento` (`ENTRADA_COMPRA`, `ENTRADA_LOTE`, `SALIDA_PRODUCCION`, `AJUSTE_POSITIVO`,
`AJUSTE_NEGATIVO`, `BAJA_VENCIMIENTO`), `cantidad` `decimal(10,3)`, saldos `decimal(10,2)`,
`tabla_origen`/`id_origen`, `id_usuario` (nullable) y `fecha`.

## Contrato resumido

**GET /api/v1/inventario?soloStockBajo=true → 200**

```json
{
  "content": [
    { "idMateriaPrima": 7, "nombre": "Levadura", "unidad": "g",
      "cantidadDisponible": 0.00, "stockMinimo": 500.00, "faltante": 500.00,
      "estadoStock": "AGOTADO", "activo": true,
      "fechaActualizacion": "2026-09-17T16:40:02" },
    { "idMateriaPrima": 5, "nombre": "Harina de trigo", "unidad": "kg",
      "cantidadDisponible": 12.50, "stockMinimo": 50.00, "faltante": 37.50,
      "estadoStock": "BAJO", "activo": true,
      "fechaActualizacion": "2026-09-18T09:12:44" }
  ],
  "page": 0, "size": 20, "totalElements": 2, "totalPages": 1,
  "first": true, "last": true
}
```

**GET /api/v1/inventario/5 → 200** — el mismo objeto de un elemento de `content`, sin envoltura
de página. Un id inexistente responde `404 RECURSO_NO_ENCONTRADO`.

**GET /api/v1/inventario/5/movimientos?fechaInicio=2026-09-01&fechaFin=2026-09-30 → 200**

```json
{
  "content": [
    { "fecha": "2026-09-18T09:12:44", "tipo": "SALIDA_PRODUCCION",
      "cantidad": 100.000, "saldoAnterior": 112.50, "saldoPosterior": 12.50,
      "origen": { "tabla": "consumo_materia_prima", "id": 31 },
      "usuario": "Ismael Batalla" },
    { "fecha": "2026-09-12T08:05:11", "tipo": "ENTRADA_LOTE",
      "cantidad": 100.000, "saldoAnterior": 12.50, "saldoPosterior": 112.50,
      "origen": { "tabla": "lotes", "id": 14 },
      "usuario": "Carlos Cochero" }
  ],
  "page": 0, "size": 20, "totalElements": 2, "totalPages": 1,
  "first": true, "last": true
}
```

## Evidencia (pruebas backend)

Conteo de los tests de la rama, tomado de los fuentes:

| Clase | Casos | Cobertura |
|---|---|---|
| `InventarioConsultaServiceTest` | 9 | Listado con filtros y defaults, orden de lista blanca, paginación inválida, detalle y 404 |
| `KardexConsultaServiceTest` | 7 | Kardex paginado, fechas inclusivas, rango invertido, página vacía, MP inexistente y `"Sistema"` |
| `InventarioControllerTest` | 13 | Listado/detalle/kardex, camelCase, 400/404/405 y CP-17 en `POST`, `PUT` y `PATCH` |
| `InventarioSeguridadTest` | 28 | SEC-01…SEC-05 parametrizados en los 3 endpoints, CP-18 y CP-19 |
| **Total** | **57** | |

La suite completa quedó en **583 tests verdes** (`mvn test`) al cerrar el PR de la historia.

- **Verificación en caliente pendiente.** No se ha ejecutado todavía la matriz de
  `PFDocs/HU-10/tarea-qa.md` contra la API real y MySQL; por eso el estado de QA no es
  «verificado en caliente».
- Los casos de integridad (CP-14) y los bordes de stock (CP-07…CP-10) requieren fixtures: se
  preparan con lotes de HU-09 (recomendado) o con SQL directo sobre `inventario`, según se
  documenta en la colección de Postman.
- El estado de QA va en la fila `Estado QA`, no aquí.

## Desviaciones vs especificación

- **`incluirInactivas` no aparece nombrado en la especificación.** R-04 exige que las inactivas
  con stock se muestren y que «el filtro por estado permita excluirla», pero no fija el nombre del
  parámetro. Se implementó como `incluirInactivas` booleano con default `true`; solo
  `incluirInactivas=false` las excluye.
- **CP-17 responde `405 METODO_NO_PERMITIDO`, no 404.** La tarea de QA acepta `405` o `404`; el
  comportamiento real de Spring con la ruta mapeada y solo `GET` es `405`.
- **`fechaActualizacion` puede faltar de la respuesta.** La vista la obtiene con `LEFT JOIN`, así
  que sería nula únicamente si faltara la fila de `inventario`; con la inclusión global
  `non_null` de Jackson la clave se omite en ese caso. La regla HU-08 R-01 crea esa fila al dar
  de alta la MP, de modo que no debería ocurrir; se admite la ausencia como red de seguridad y
  no se inventa una fecha.
- **No se expone `costo_unitario`.** La vista lo trae, pero el contrato de consulta de inventario
  de HU-10 no lo incluye; publicarlo filtraría costo a roles que solo deben ver existencias.
- **El filtro `idMateriaPrima` no valida existencia.** Un id inexistente en el listado devuelve
  `200` con `content: []`; solo el detalle y el kardex responden `404`. La especificación no fija
  este comportamiento y el filtro se comporta como una restricción de conjunto.

## Pendientes / deuda

- **Verificación en caliente contra MySQL** de CP-01…CP-20 y SEC-01…SEC-05 en los tres endpoints,
  incluida la conciliación `inventario.cantidad_disponible` ↔ último `saldo_posterior` (CP-14).
- **Frontend:** no existe pantalla de inventario; el manual de consumo es el contrato para Angular.
- **Fixtures de QA:** CP-07/CP-08/CP-09/CP-10 necesitan un stock exacto y CP-16 un movimiento con
  `id_usuario NULL`. La colección deja las variables y las instrucciones de preparación.
- **CP-04 cruza con HU-09:** mover stock exige registrar un lote con la colección de esa historia
  (o un alta equivalente); `GET /inventario` no mueve nada por diseño.
- **CP-18/CP-19** están cubiertos por la matriz de roles y por la batería de seguridad.
