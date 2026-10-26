# HU-12 — Gestión de recetas · Backend verificado

| | |
|---|---|
| Fecha de implementación | 2026-10-25 |
| Commits / PR | PRs #71–#78 a `HU-12/gestion-recetas`: #71 persistencia, #72 alta transaccional, #73 consulta/edición/estado, #74 controlador/seguridad, #75 llaves generadas en MySQL, #76 documentación, #77 validación de cantidades y carrera, #78 redondeo `HALF_UP` |
| Colección Postman | `Docs/Postman/ProductionFood-HU12.postman_collection.json` (`Preparación`, `Recetas (CP-01…CP-24)`, `Roles (matriz)`, `Seguridad (SEC-01…05)`) |
| Estado QA | **QA backend verificado en caliente 2026-10-25 (CP-01…CP-24 + SEC-01…05) · frontend pendiente** |

> Esta ficha documenta el backend de la historia. Además del *smoke* manual inicial, el
> 2026-10-25 se ejecutó la matriz de `tarea-qa.md` con agentes ciegos contra la API real (`:8085`)
> y MySQL 8.4. Los defectos encontrados en las dos primeras pasadas se corrigieron y la tercera
> pasada cerró **80/80 comprobaciones** sin defectos abiertos. El consumo desde el frontend sigue
> pendiente.

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/recetas` | ADMIN, PRODUCCION, CONSULTA | 200/400/401/403 | Listado paginado con filtros `nombre` (prefijo) y `activo`, orden de lista blanca, detalle completo y costos estimados de cada receta |
| GET | `/api/v1/recetas/{id}` | ADMIN, PRODUCCION, CONSULTA | 200/400/401/403/404 | Receta con producto, unidad de producción, detalle y costos; 404 si el id no existe |
| GET | `/api/v1/productos/{idProducto}/receta` | ADMIN, PRODUCCION, CONSULTA | 200/400/401/403/404 | Receta asociada al producto (CA-01); 404 si el producto no tiene receta |
| POST | `/api/v1/recetas` | ADMIN, PRODUCCION | 201/400/401/403/409 | Crea cabecera y detalle inicial en una transacción; `201` con `Location` |
| PUT | `/api/v1/recetas/{id}` | ADMIN, PRODUCCION | 200/400/401/403/404/409 | Reemplaza nombre, rendimiento y unidad de producción; no cambia producto ni estado |
| PATCH | `/api/v1/recetas/{id}/estado` | ADMIN, PRODUCCION | 200/400/401/403/404 | Activa o desactiva la receta de forma idempotente |
| POST | `/api/v1/recetas/{id}/detalles` | ADMIN, PRODUCCION | 201/400/401/403/404/409 | Agrega un ingrediente y devuelve la receta completa recalculada |
| PUT | `/api/v1/recetas/{id}/detalles/{idDetalle}` | ADMIN, PRODUCCION | 200/400/401/403/404 | Cambia la cantidad de una línea y recalcula costos |
| DELETE | `/api/v1/recetas/{id}/detalles/{idDetalle}` | ADMIN, PRODUCCION | 204/400/401/403/404 | Quita físicamente un ingrediente; `204` sin cuerpo |

**No existe `DELETE` de receta** (R-07): `DELETE /api/v1/recetas/{id}` responde
`405 METODO_NO_PERMITIDO`. La baja de una receta es el `PATCH` de estado; la cabecera no se
elimina nunca. La única eliminación física de la historia es la línea de detalle, porque un
ingrediente quitado no tiene valor histórico y `detalle_receta` no está referenciada por
ninguna otra tabla.

**Roles.** Lectura (`GET`): `ADMIN`, `PRODUCCION` y `CONSULTA`. Escritura (`POST`, `PUT`,
`PATCH`, `DELETE`): `ADMIN` y `PRODUCCION`. `VENTAS` y `COMPRAS` no participan en el módulo y
reciben `403 SIN_PERMISO`; `CONSULTA` puede leer pero no escribir (CP-23).

**Contrato de `Location`.** El alta de la receta responde
`Location: /api/v1/recetas/{idReceta}`. El alta de un ingrediente responde
`Location: /api/v1/recetas/{id}` —la receta modificada, no la línea— porque el cuerpo de la
respuesta es la receta completa recalculada. El detalle no tiene `Location` propia.

Piezas internas:

| Pieza | Rol |
|---|---|
| `Receta`, `DetalleReceta` | POJOs de persistencia (sin entidad JPA) para `recetas` y `detalle_receta` |
| `RecetaRepository` | JDBC: `buscarPorId`, `buscarPorProducto`, `existePorProducto`, `insertar`, `actualizar`, `cambiarEstado`, `listar` y `contar` |
| `DetalleRecetaRepository` | JDBC: `listarPorReceta`, `listarPorRecetas` (una sola consulta para toda la página), `buscarPorId`, `insertar`, `actualizarCantidad`, `eliminar` y `existeMateriaPrima` |
| `RecetaRowMapper`, `DetalleRecetaRowMapper` | Mapeo explícito de los alias del `JOIN` a producto, unidad y materia prima |
| `RecetaService` | Validaciones, transacción del alta, lista blanca de orden, cálculo de costos y `@Auditable` |
| `RecetaController` | Los nueve endpoints con `@PreAuthorize` y Swagger (`@Operation`/`@ApiResponse`) |
| DTOs | `CrearRecetaRequest`, `ActualizarRecetaRequest`, `AgregarDetalleRequest`, `ActualizarDetalleRequest`, `RecetaResponse`, `DetalleRecetaResponse`, `ProductoRefResponse` y `MateriaPrimaRefResponse` |

Sin migración Flyway propia: `recetas` y `detalle_receta` existen desde V1, V2 añadió
`uk_detalle_receta (id_receta, id_materia_prima)` y el `DEFAULT 1` de `recetas.estado`, y esta
historia solo consume ese esquema.

## Modelo y contrato

**Tabla `recetas`**: `id_receta` (PK), `id_producto` (FK y **UNIQUE**, CA-01),
`nombre` `varchar(100)`, `cantidad_producir` `decimal(10,2)`, `id_unidad` (FK) y `estado`
`tinyint(1)` con `DEFAULT 1`.

**Tabla `detalle_receta`**: `id_detalle_receta` (PK), `id_receta` (FK),
`id_materia_prima` (FK), `cantidad` `decimal(10,3)` y
`UNIQUE uk_detalle_receta (id_receta, id_materia_prima)`.

**`RecetaResponse`**:

| Campo | Tipo | Notas |
|---|---|---|
| `idReceta` | entero | |
| `producto` | objeto | `{ idProducto, nombre }`; no incluye precio ni stock |
| `nombre` | texto | Receta, no producto |
| `cantidadProducir` | decimal | **Rendimiento de una tanda completa**, escala 2 |
| `unidad` | objeto | `{ idUnidad, nombre, abreviatura }`: la unidad de **producción** (R-04), con tres claves |
| `activo` | booleano | `recetas.estado` |
| `completa` | booleano | `true` si y solo si hay al menos una línea de detalle (R-05) |
| `detalles[]` | arreglo | Líneas ordenadas por `id_detalle_receta`; vacío en una receta incompleta |
| `costoTotalEstimado` | decimal | Suma de los costos de las líneas, escala 2 |
| `costoPorUnidad` | decimal | `costoTotalEstimado / cantidadProducir`, escala 2, `HALF_UP` |

Cada elemento de `detalles[]` es un `DetalleRecetaResponse`:

| Campo | Tipo | Notas |
|---|---|---|
| `idDetalleReceta` | entero | |
| `materiaPrima` | objeto | `{ idMateriaPrima, nombre, unidad }`; aquí `unidad` es la **abreviatura** en texto (`"kg"`, `"L"`…), no un objeto |
| `cantidad` | decimal | Consumo de **una tanda**, escala 3 (R-06) |
| `costoEstimado` | decimal | `cantidad × materias_primas.costo_unitario`, escala 2 |

**Reglas de negocio implementadas.**

- **R-01 · sin ingredientes duplicados.** `uk_detalle_receta` es la garantía; el servicio
  anticipa el `409 MATERIA_PRIMA_DUPLICADA` tanto en la lista inicial como al agregar una
  línea, y traduce la `DuplicateKeyException` de una carrera al mismo código.
- **R-02 · `cantidadProducir` es rendimiento por tanda.** No es «por unidad». Una receta que
  rinde 30 panes con 5 kg de harina lleva `cantidadProducir = 30` y `cantidad = 5.000`. HU-22
  calculará `(planificado / 30) × 5`. Interpretarlo al revés no produce error: produce
  consumos treinta veces mayores tres historias después.
- **R-03 · el rendimiento no puede ser cero ni negativo.** El servicio responde
  `400 CANTIDAD_PRODUCIR_INVALIDA`; es el divisor de `costoPorUnidad` y del consumo teórico.
- **R-04 · la unidad de la receta es la de producción.** Se embebe como objeto porque el
  cliente necesita `idUnidad`, `nombre` y `abreviatura`; puede diferir de la unidad de venta
  del producto (HU-11 R-04).
- **R-05 · una receta sin ingredientes se permite.** Se crea con `detalles` vacío u omitido y
  queda con `completa: false` y costos en cero. No sirve para planificar, pero se completa
  después sin tener que crearla otra vez.
- **R-06 · el detalle lleva escala 3.** `cantidad` se guarda con `setScale(3, HALF_UP)`; el
  tercer decimal es significativo (0,005 kg de sal). `cantidadProducir` y los costos van con
  escala 2.
- **R-07 · se elimina la línea, no la receta.** Solo `DELETE /recetas/{id}/detalles/{idDetalle}`;
  la cabecera se desactiva. La baja de un ingrediente no toca el historial de producción
  porque `detalle_receta` no es referenciada por otras tablas.
- **Producto activo y sin receta previa (CA-01).** `PRODUCTO_INEXISTENTE` si no existe o está
  inactivo; `PRODUCTO_YA_TIENE_RECETA` si ya tiene una, esté activa o no.
- **Materia prima activa.** `MATERIA_PRIMA_INEXISTENTE` si no existe o está inactiva.
- **Costo de referencia.** `costoUnitario` sale de `materias_primas` (HU-08 R-03): es un costo
  de referencia, **no el costo real** de cada compra. Los costos de la respuesta se recalculan
  en cada lectura con el valor vigente.

## Decisiones de implementación

| Tema | Decisión |
|---|---|
| JDBC, no JPA | `RecetaRepository` y `DetalleRecetaRepository` usan `NamedParameterJdbcTemplate` y mappers propios. `recetas` ya se consultaba de forma nativa desde `ProductoRepository` y la historia escribe cabecera y detalle en una misma transacción; el mapeo explícito deja los alias del `JOIN` como única fuente de las proyecciones |
| Llave generada en MySQL | `GeneratedKeyHolder.getKeyAs(Integer.class)` falla con MySQL porque el conector devuelve `BigInteger` (`DataRetrievalFailureException`). Se toma `getKey()` como `Number` y se reduce con `intValue()` (PR #75). Aplica al alta de receta y al alta de línea |
| Orden cualificado con `r.` | La lista blanca traduce `nombre` a `r.nombre` y `cantidadProducir` a `r.cantidad_producir`. El `SELECT` hace `JOIN` a `productos` y `unidades_medida`, que **también tienen `nombre`**; sin el prefijo el motor rechaza el `ORDER BY` por ambigüedad. `id` se traduce a `r.id_receta` |
| Anti-N+1 en el listado | El detalle de todas las recetas de la página se trae con `listarPorRecetas(ids)` (un `IN`) y se agrupa en memoria por `idReceta`. Consultar línea a línea repetiría una ida a la base por cada fila de la página. El `COUNT` no repite los `JOIN` porque los filtros son solo de cabecera y los `JOIN` son `INNER` sobre FK `NOT NULL` |
| Línea ajena = 404 | `actualizarDetalle` y `eliminarDetalle` buscan la línea y exigen que `idReceta` coincida con el de la ruta. Una línea de otra receta se trata como inexistente (`404 RECURSO_NO_ENCONTRADO`), no como prohibida: el subrecurso anidado solo conoce las líneas de su receta |
| `Location` del detalle | `POST /recetas/{id}/detalles` devuelve `201` con `Location: /api/v1/recetas/{id}`: el recurso creado es la línea, pero la representación devuelta es la receta completa recalculada, y es la URL que el cliente puede volver a pedir tal cual |
| Estado explícito en el `INSERT` | El alta envía `estado = true` de forma explícita. El `DEFAULT 1` de V2 es la red de seguridad para inserciones externas, no el mecanismo de la aplicación |
| Duplicados de la lista inicial | Se validan **antes** de insertar la cabecera: un `409` en el tercer ingrediente no deja receta a medias (CP-13). En `agregarDetalle` la comprobación previa da el `409` legible y el `UNIQUE` cubre la carrera (CP-14) |
| Transacción del alta | `crear` es `@Transactional`: cabecera y todas las líneas se confirman juntas o no se confirma nada |
| Costos con `BigDecimal` | Se suman las líneas con escala 2 y se divide entre `cantidadProducir` con escala 2 y `RoundingMode.HALF_UP`. Nunca `double` |
| Orden del detalle | `ORDER BY id_detalle_receta` fija el orden de inserción. Sin él, dos lecturas de la misma receta podrían devolver los ingredientes en distinto orden |
| Filtro por nombre | Prefijo sin distinguir mayúsculas con `FiltroTexto.patronPrefijo`, que escapa los comodines SQL. El `ESCAPE` del `LIKE` va con cuatro barras en el literal Java para que llegue correcto al driver |
| Cantidades del detalle | Se normalizan a escala 3 con `HALF_UP` y se rechazan con `400 VALIDACION_FALLIDA` si el valor redondeado queda en `0.000` o no cabe en `decimal(10,3)` (`>= 10 000 000`). El DTO solo exige `@Positive`; la regla vive en el servicio (PR #78) |
| Auditoría | `@Auditable` en `CREAR` y `EDITAR` sobre `recetas`, `CAMBIAR_ESTADO` sobre `recetas` y `ELIMINAR` sobre `detalle_receta` |

## Pruebas

| Clase | Casos | Cobertura |
|---|---|---|
| `RecetaServiceTest` | 45 | Alta con y sin detalle, validaciones de producto/unidad/materia prima, duplicados en lista y por carrera, redondeo `HALF_UP` y límites de `decimal(10,3)`, costos de CP-20/CP-21, listado con una sola consulta de detalle, lista blanca de orden, consulta, `porProducto`, edición, estado idempotente y borrado de línea ajena |
| `RecetaControllerTest` | 27 | Los nueve endpoints, `201` con `Location`, camelCase, 400 por paginación/orden/validación, 404 y los códigos de negocio en el borde HTTP |
| `RecetaSeguridadTest` | 81 | SEC-01…SEC-05 y la matriz de acceso: 9 endpoints × 4 casos de autenticación/autorización (36) + SEC-04 con 24 combinaciones de rol denegado + 21 combinaciones permitidas (CP-23/CP-24) |
| **Total de la historia** | **153** | |

La suite completa quedó en **736 tests verdes** (`mvn test`) al cerrar el PR #78. El desglose
de la historia es 45 + 27 + 81 = 153; el resto son las historias anteriores.

> Las pruebas de `RecetaSeguridadTest` levantan el contexto web real (controlador, cadena de
> seguridad, filtro JWT y manejadores de error) y solo simulan el servicio y el repositorio de
> usuarios, así que la autorización se prueba de verdad sin necesidad de MySQL.

## Evidencia del smoke en caliente

Ejecutado el **2026-10-25** contra la API real en `:8085` y MySQL 8.4, con el administrador del
seed. No fue la matriz formal de QA: fue una pasada manual por los caminos principales.

Secuencia verificada:

1. `POST /recetas` con el producto 2 (`Pan francés`) → `201` y `Location`.
2. `GET /recetas/3` → receta con su detalle completo y costos.
3. `POST /recetas/3/detalles` → `201` y `Location: /api/v1/recetas/3`.
4. `PUT /recetas/3/detalles/{id}` → `200` con el costo recalculado.
5. `DELETE /recetas/3/detalles/{id}` → `204` sin cuerpo.
6. `PATCH /recetas/3/estado` → `200` idempotente.
7. `PUT /recetas/3` → nombre final `Receta smoke HU-12 editada`.
8. `POST /recetas/3/detalles` repitiendo la materia prima 4 → `409 MATERIA_PRIMA_DUPLICADA`.
9. `GET /productos/2/receta` → `200` con la misma receta.
10. Consulta directa a `bitacora` en MySQL → las escrituras quedaron registradas.

**Estado real que dejó el smoke en la base** (reconstruido de `recetas`, `detalle_receta` y
`materias_primas`; los costos son los que devuelve el contrato con el `costo_unitario`
vigente):

```json
{
  "idReceta": 3,
  "producto": { "idProducto": 2, "nombre": "Pan francés" },
  "nombre": "Receta smoke HU-12 editada",
  "cantidadProducir": 31.00,
  "unidad": { "idUnidad": 5, "nombre": "Unidad", "abreviatura": "und" },
  "activo": true,
  "completa": true,
  "detalles": [
    { "idDetalleReceta": 2,
      "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
      "cantidad": 5.000, "costoEstimado": 16753.75 },
    { "idDetalleReceta": 3,
      "materiaPrima": { "idMateriaPrima": 2, "nombre": "Levadura", "unidad": "L" },
      "cantidad": 0.050, "costoEstimado": 9.03 }
  ],
  "costoTotalEstimado": 16762.78,
  "costoPorUnidad": 540.73
}
```

- `5.000 × 3350.75 = 16753.75` y `0.050 × 180.55 = 9.03`; el total `16762.78` dividido entre
  `31.00` da `540.73` con `HALF_UP`.
- La receta 3 usa la unidad 5 (`Unidad`, `und`) como unidad de **producción**, distinta de la
  unidad de venta del producto 2 (`Kilogramo`, `kg`): es R-04 funcionando con datos reales.
- La línea `idDetalleReceta = 4` se agregó, se editó y se eliminó durante el smoke; ya no
  existe. Las líneas 2 y 3 son las que quedaron.

**Bitácora del smoke** (`tabla_afectada` y acción):

| Acción | Tabla | Detalle |
|---|---|---|
| `CREAR` | `recetas` | `Receta creada: Receta smoke HU-12` |
| `EDITAR` | `recetas` | `Receta 3 con detalle agregado` |
| `EDITAR` | `recetas` | `Ingrediente 4 de la receta 3 editado` |
| `ELIMINAR` | `detalle_receta` | `Ingrediente 4 eliminado de la receta 3` |
| `CAMBIAR_ESTADO` | `recetas` | `Receta 3 estado true` |
| `EDITAR` | `recetas` | `Receta editada: Receta smoke HU-12 editada` |

## Verificación en caliente formal (QA backend verificado)

Ejecutada el **2026-10-25** con agentes ciegos contra la API real (`:8085`) y MySQL 8.4, sin leer
la implementación y con fixtures propios. Se hicieron tres pasadas:

1. **Primera:** CP-01…CP-24 y SEC-01…SEC-05. Resultado 158/160. Defectos: 500 al enviar
   `cantidad: 0.0004` (redondeaba a `0.000`), 409 genérico por cantidad fuera de
   `decimal(10,3)` y 409 genérico en la carrera de dos altas del mismo producto.
2. **Segunda:** tras corregir esos tres defectos, 158/160. Quedó abierto que cualquier cantidad
   con más de tres decimales se rechazaba en vez de redondearse con `HALF_UP` (`0.0005` debía
   guardarse como `0.001`).
3. **Tercera (focalizada):** 80/80. Matriz de 11 cantidades × 3 endpoints, 16 CP de regresión,
   16 comprobaciones de seguridad, contrato, auditoría y tiempos.

Resultado final:

- **CP-01…CP-24: PASAN**, incluidos CP-02, CP-12, CP-13, CP-14 y CP-22 (atomicidad por tres
  vías: tercer detalle con MP inexistente, tercer detalle con cantidad inválida y carrera de dos
  altas; en las tres, 0 huérfanos y sin `CREAR` en bitácora).
- **SEC-01…SEC-05 en los nueve endpoints: PASAN**, con tokens reales fabricados desde el
  `JWT_SECRET` del entorno. La matriz de roles también pasa (CONSULTA lee y no escribe;
  VENTAS/COMPRAS reciben 403).
- **Cantidades:** `0`/negativas/`0.0001`/`0.0004` → 400 `VALIDACION_FALLIDA` sin insertar;
  `0.0005` y `0.0009` → `0.001`; `1.2345` → `1.235`; `9999999.999` → se guarda;
  `10000000` y `99999999.999` → 400. Verificado en base en los tres endpoints de escritura.
- **Contrato:** paginación, filtros, orden, `%` escapado, `GET /productos/{id}/receta` 200/404,
  `DELETE /recetas/{id}` 405, detalle de otra receta 404 y MP inactiva 409.
- **Auditoría:** `CREAR`, `EDITAR`, `CAMBIAR_ESTADO` y `ELIMINAR` presentes; las escrituras
  fallidas no generan `CREAR`.
- **Rendimiento:** máximo medido 43 ms en la matriz completa; todos los endpoints muy por debajo
  de 2 s.
- **Defectos abiertos: ninguno.**

## Códigos de error

| `code` | HTTP | Cuándo | Qué debe hacer el cliente |
|---|---|---|---|
| `PRODUCTO_YA_TIENE_RECETA` | 409 | El producto ya tiene una receta (CA-01) | Abrir la receta existente con `GET /productos/{id}/receta` |
| `MATERIA_PRIMA_DUPLICADA` | 409 | La materia prima ya está en la receta o se repite en `detalles[]` | Quitarla del formulario; el selector debe excluir las ya usadas |
| `PRODUCTO_INEXISTENTE` | 409 | `idProducto` no existe o está inactivo | Recargar el catálogo de productos (HU-11) |
| `MATERIA_PRIMA_INEXISTENTE` | 409 | `idMateriaPrima` no existe o está inactiva | Recargar el catálogo de materias primas (HU-08) |
| `UNIDAD_INEXISTENTE` | 409 | `idUnidad` no existe | Recargar el catálogo de unidades (HU-07) |
| `CANTIDAD_PRODUCIR_INVALIDA` | 400 | `cantidadProducir ≤ 0` | Corregir el rendimiento de la tanda (R-02/R-03) |
| `VALIDACION_FALLIDA` | 400 | Falta un campo obligatorio, `nombre` vacío o demasiado largo, o `cantidad ≤ 0` en el detalle | Pintar `fieldErrors[]` junto a cada campo |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100 o id no numérico | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` fuera de `{nombre, cantidadProducir, id}` | Usar solo la lista blanca |
| `RECURSO_NO_ENCONTRADO` | 404 | Receta, línea de detalle o receta de producto inexistente (incluye línea de otra receta) | Volver al listado o refrescar la receta |
| `METODO_NO_PERMITIDO` | 405 | `DELETE /api/v1/recetas/{id}` | La baja es el `PATCH` de estado |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (VENTAS/COMPRAS en todo el módulo; CONSULTA en escritura) | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado después de emitir el token | Cerrar sesión |

Forma del error (mensajes reales del servicio; `timestamp` y `path` ilustrativos):

```json
{ "timestamp": "2026-10-25T18:53:58.120", "status": 409, "error": "Conflict",
  "code": "PRODUCTO_YA_TIENE_RECETA",
  "message": "El producto ya tiene una receta asignada.",
  "path": "/api/v1/recetas", "fieldErrors": [] }
```

```json
{ "timestamp": "2026-10-25T18:53:58.120", "status": 400, "error": "Bad Request",
  "code": "CANTIDAD_PRODUCIR_INVALIDA",
  "message": "La cantidad a producir debe ser un número mayor que cero.",
  "path": "/api/v1/recetas", "fieldErrors": [] }
```

```json
{ "timestamp": "2026-10-25T18:53:58.120", "status": 400, "error": "Bad Request",
  "code": "VALIDACION_FALLIDA",
  "message": "Los datos enviados no son válidos.",
  "path": "/api/v1/recetas",
  "fieldErrors": [ { "field": "nombre", "message": "El nombre es obligatorio" } ] }
```

```json
{ "timestamp": "2026-10-25T18:53:58.120", "status": 404, "error": "Not Found",
  "code": "RECURSO_NO_ENCONTRADO",
  "message": "No se encontró la receta con id 9999.",
  "path": "/api/v1/recetas/9999", "fieldErrors": [] }
```

**Petición de edición usada en el smoke** (`PUT /api/v1/recetas/3`; el nombre, el rendimiento
y la unidad son los que quedaron en la base):

```json
{ "nombre": "Receta smoke HU-12 editada",
  "cantidadProducir": 31.00,
  "idUnidad": 5 }
```

**Alta de referencia** (ejemplo de contrato, no del smoke; `cantidadProducir = 30` es el caso
de CP-20/CP-21):

```json
{ "idProducto": 3,
  "nombre": "Masa de pan francés",
  "cantidadProducir": 30.00,
  "idUnidad": 5,
  "detalles": [
    { "idMateriaPrima": 1, "cantidad": 5.000 },
    { "idMateriaPrima": 2, "cantidad": 0.050 }
  ] }
```

Con `costo_unitario` de 3200.00 y 180.00, esa alta responde `costoTotalEstimado: 16009.00` y
`costoPorUnidad: 533.63`. **Los costos cambian con `materias_primas.costo_unitario`**: los del
smoke (3350.75 y 180.55) dan 16762.78 y 540.73 para los mismos ingredientes.

## Desviaciones vs especificación

- **`cantidadProducir ≤ 0` responde `CANTIDAD_PRODUCIR_INVALIDA`, no `VALIDACION_FALLIDA`.**
  El DTO no lleva `@Positive` a propósito: la especificación (§5) define el código de negocio
  y la tarea de QA lo espera en CP-04/CP-05. La validación vive en el servicio.
- **`UNIDAD_INEXISTENTE` (409) no está en §5 de la especificación.** El controlador lo declara
  y el servicio lo emite al crear y al editar, con el mismo código que HU-11. Es un código
  transversal ya establecido en el proyecto.
- **El `404` del alta es una red de seguridad.** `POST /recetas` declara `404` por si la receta
  no puede releerse después del `INSERT`; en el flujo normal no ocurre. No es un caso de
  negocio.
- **`Location` del alta de detalle apunta a la receta, no a la línea.** La especificación no
  fija la cabecera; se eligió la URL de la representación devuelta (la receta completa).
- **Los detalles no tienen endpoint propio de lectura.** Se obtienen dentro de la receta; no
  hay `GET /recetas/{id}/detalles` ni `GET /.../detalles/{id}`.
- **`sort` no admite `activo`.** La lista blanca es `nombre`, `cantidadProducir` e `id`; el
  estado se filtra con `activo`, no se ordena.
- **La especificación de diseño menciona `GET /productos?sinReceta=true`.** Ese filtro no
  existe en HU-11: su listado solo acepta `nombre` y `activo`, pero devuelve `tieneReceta`.
  El frontend debe filtrar por `tieneReceta` en el cliente o consultar la receta del producto
  con `GET /productos/{id}/receta`.

## Limitaciones conocidas

- **No hay `DELETE` de receta.** `DELETE /api/v1/recetas/{id}` responde `405`; la baja es
  `PATCH /estado`. Desactivar no borra el detalle y la receta sigue consultable.
- **No se puede cambiar el producto de una receta.** CA-01 ata la receta a su producto; para
  moverla hay que desactivar la actual y crear otra.
- **La unidad del detalle no se edita.** `materiaPrima` de una línea es inmutable: para cambiar
  de ingrediente se elimina la línea y se agrega otra.
- **Los costos son de referencia.** Salen de `materias_primas.costo_unitario` (HU-08 R-03) y se
  recalculan en cada respuesta. No reflejan el costo real de cada compra; con el mismo
  ingrediente y otra compra, el costo cambia.
- **No hay `@Schema` en los DTO.** Swagger documenta operaciones y códigos con
  `@Operation`/`@ApiResponse`, pero los esquemas de request/response no llevan descripciones ni
  ejemplos por campo. El contrato práctico es este documento y el manual del frontend.
- **El listado devuelve el detalle completo de cada receta.** Es cómodo para el cliente, pero
  una página de 20 recetas con muchos ingredientes trae todas las líneas. Si el rendimiento se
  vuelve un problema, se pedirá como historia nueva (no se cambia el contrato aquí).
- **El seed V3 no crea recetas.** Solo siembra roles, unidades, el administrador y una fila de
  bitácora. Las recetas que hay en la base de desarrollo vienen de verificaciones anteriores:
  la receta 1 (`Receta QA HU-08`, producto 1) y la receta 3 del smoke (`Receta smoke HU-12
  editada`, producto 2). **No son datos de negocio ni fixtures de QA**: un entorno limpio
  arranca sin recetas.
- **Los datos del smoke son de desarrollo.** La receta 3, sus líneas 2 y 3 y las filas de
  bitácora citadas existen en la base local usada el 2026-10-25; no viajan con el repositorio.
- **Frontend pendiente.** El backend está verificado en caliente (CP-01…CP-24 y SEC-01…05),
  pero no existe pantalla de recetas; el manual de consumo es el contrato para Angular.
