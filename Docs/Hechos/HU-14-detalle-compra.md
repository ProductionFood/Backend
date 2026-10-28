# HU-14 — Detalle de compra · Backend verificado

| | |
|---|---|
| Fecha de implementación | 2026-10-28 |
| Commits / PR | PRs #95–#98 a `HU-14/detalle-compra`; este cambio añade la documentación (PR6) |
| Colección Postman | `Docs/Postman/ProductionFood-HU14.postman_collection.json` (`Preparación`, `Detalle (CP-01…CP-22)`, `Seguridad (SEC-01…SEC-05)`) |
| Estado QA | **QA ciego verificado 2026-10-28 (22 CP + 20 SEC en los 4 endpoints) · frontend pendiente** |

> Esta ficha documenta el backend de la historia. La verificación en caliente se ejecutó el
> 2026-10-28 contra la API real en `:8085` y MySQL 8.4 en modo ciego (solo la especificación,
> los casos de QA y el contrato transversal): los 22 casos funcionales y la batería SEC-01…05
> sobre los 4 endpoints (20 combinaciones) pasaron, con verificación en vivo del redondeo de
> CP-13, del costo de referencia de CP-20 y de la restricción `uk_detalle_compra` de CP-19.
> El consumo desde el frontend sigue pendiente. Los ejemplos salen de la especificación, de
> los DTO reales y de la ejecución de QA; los ids dependen de la base.

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/compras/{idCompra}/detalles` | ADMIN, COMPRAS, PRODUCCION, CONSULTA | 200/401/403/404 | Listado con estado, bandera `editable`, líneas, total e ítems |
| POST | `/api/v1/compras/{idCompra}/detalles` | ADMIN, COMPRAS | 201/400/401/403/404/409 | Agrega un ítem —materia prima, cantidad y precio— con `Location: /api/v1/compras/{id}/detalles/{idDetalle}` |
| PUT | `/api/v1/compras/{idCompra}/detalles/{idDetalle}` | ADMIN, COMPRAS | 200/400/401/403/404/409 | Editar cantidad y precio de una línea mientras la compra siga `PENDIENTE` |
| DELETE | `/api/v1/compras/{idCompra}/detalles/{idDetalle}` | ADMIN, COMPRAS | 204/400/401/403/404/409 | Quitar un ítem; responde `204` sin cuerpo |

**Roles.** Lectura de los cuatro endpoints para `ADMIN`, `COMPRAS`, `PRODUCCION` y `CONSULTA`.
Escritura (alta, edición y borrado) solo para `ADMIN` y `COMPRAS`. A diferencia de HU-13,
`VENTAS` no participa del subrecurso **ni siquiera en lectura**: recibe `403 SIN_PERMISO` en
los cuatro endpoints, incluido el `GET`.

Piezas internas:

| Pieza | Rol |
|---|---|
| `DetalleCompraService` | Las cuatro operaciones con el orden de validaciones de la spec §5, `@Auditable` en agregar/editar/eliminar y el total recalculado en cada respuesta |
| `DetalleCompraController` | Subrecurso anidado con `@PreAuthorize`, Swagger y `Location` en el alta |
| `DetalleCompraRepository` | JDBC explícito: `listarPorCompra`, `insertar`, `actualizar`, `eliminar`, `obtenerPorId`, `existeMateriaPrimaEnCompra` (R-02), `calcularTotal` (`SUM` en la base) y los costos de referencia en una sola consulta |
| `DetalleCompra` / `DetalleCompraRowMapper` | POJO sobre `detalle_compra` con `JOIN` a `materias_primas` y `unidades_medida`; `subtotal()` calcula con `HALF_UP` (R-03) |
| `CompraInexistenteException` | `404 COMPRA_INEXISTENTE`, distinto del `404` genérico: distingue que el que falta es el documento padre |
| `DetalleCompraListaResponse` / `DetalleCompraResponse` | Cabecera del listado (`estado`, `editable`, `totalCompra`, `cantidadItems`) y línea con `subtotal`, `totalCompra`, `costoReferencia` y `diferenciaCosto` (R-04) |
| `AgregarDetalleCompraRequest` / `ActualizarDetalleCompraRequest` | Alta con `idMateriaPrima` + cantidad + precio; edición solo con cantidad y precio, **sin** materia prima (R-02) |

**Sin migración Flyway nueva.** V1 ya crea `detalle_compra` y V2 añade
`UNIQUE KEY uk_detalle_compra (id_compra, id_materia_prima)` junto con los `CHECK` de
`cantidad > 0` y `precio_unitario >= 0` (§C-13). Esta historia solo escribe código sobre el
esquema existente.

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| R-01 | La compra debe estar `PENDIENTE` en **los cuatro endpoints**, incluido el `DELETE`: `exigirPendiente` se invoca antes de tocar la línea en alta, edición y borrado. El `GET` es de lectura y responde `200` con `editable: false` sobre compras `RECIBIDA`/`CANCELADA` (ver desviaciones) |
| R-02 | Duplicados con **doble barrera**: comprobación previa `existeMateriaPrimaEnCompra` → `409 MATERIA_PRIMA_DUPLICADA` con mensaje que sugiere editar la línea existente, y captura de `DuplicateKeyException` para la carrera que supera la comprobación y revienta `uk_detalle_compra`. El `PUT` no acepta materia prima: no se reemplaza, se elimina y se agrega otra |
| R-03 | `BigDecimal` en todo el cálculo. El subtotal de la línea es `cantidad × precio` con `setScale(2, HALF_UP)` y el total **sale del `SUM(cantidad * precio_unitario)` de la base**, redondeado a escala 2 con `HALF_UP` en cada respuesta; nunca se acumula en memoria ni se suma en el cliente. CP-13 verificado en vivo: `33.33 × 3333.33 → 111099.89` |
| R-04 | `costoReferencia` es `materias_primas.costo_unitario` vigente y `diferenciaCosto` es `precioUnitario − costoReferencia` a escala 2: **solo lectura**. Registrar o editar una línea nunca escribe el costo de la materia prima (CP-20 verificado: comprar a precio distinto no lo actualiza). Sin costo de referencia, ambos campos van en `null` y no se serializan |
| R-05 | `precioUnitario` puede ser `0` (`@PositiveOrZero`): hay muestras y bonificaciones. `cantidad` **no** lleva `@Positive` en el DTO a propósito: si lo llevara, un cero se rechazaría con `VALIDACION_FALLIDA` y el contrato exige `CANTIDAD_INVALIDA` del servicio. El servicio normaliza la cantidad a escala 2 antes de validar, de modo que `0.001` también responde `400 CANTIDAD_INVALIDA` en lugar de reventar el `CHECK` |
| R-06 | El borrado está permitido mientras la compra siga `PENDIENTE`: una línea no tiene valor histórico propio antes de ejecutar el documento. Una vez `RECIBIDA`, nada del detalle se toca |
| R-07 | `cantidad` y `precio_unitario` viajan a escala `decimal(10,2)` (a diferencia de `detalle_receta`, con 3 decimales en HU-12). El POJO fija la escala en el constructor y el servicio la re-valida |
| CA-04 | `totalCompra` se devuelve en la respuesta del `POST`, del `PUT` y del listado: evita que el cliente pida el total aparte y garantiza que la cifra mostrada sea la del servidor |
| Auditoría | `@Auditable` en agregar (`EDITAR` sobre `compras`), editar (`EDITAR`) y eliminar (`ELIMINAR` sobre `detalle_compra`), con HU-04 |

## Modelo de datos

**Tabla `detalle_compra`** (la crea V1; V2 añade el `UNIQUE` y los dos `CHECK`)

| Columna | Tipo | Notas |
|---|---|---|
| `id_detalle_compra` | `int unsigned` PK AI | |
| `id_compra` | `int unsigned` NOT NULL FK → `compras` | CA-01 |
| `id_materia_prima` | `int unsigned` NOT NULL FK → `materias_primas` | CA-02; `UNIQUE (id_compra, id_materia_prima)` |
| `cantidad` | `decimal(10,2)` NOT NULL | `CHECK > 0`; R-05, R-07 |
| `precio_unitario` | `decimal(10,2)` NOT NULL | `CHECK >= 0`; R-05, R-07 |

`totalCompra` y `cantidadItems` **no son columnas de `detalle_compra` ni de `compras`**: los
calcula el backend en cada respuesta con `SUM(cantidad * precio_unitario)` y `COUNT(*)` sobre
las líneas de la compra.

## Contrato resumido

**POST /api/v1/compras/12/detalles → 201** (con `Location: /api/v1/compras/12/detalles/28`):

```json
// petición
{ "idMateriaPrima": 1, "cantidad": 100.00, "precioUnitario": 3200.00 }
```

```json
// respuesta (forma del DTO; los valores dependen de la base)
{ "idDetalleCompra": 28,
  "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo", "unidad": "kg" },
  "cantidad": 100.00, "precioUnitario": 3200.00, "subtotal": 320000.00,
  "totalCompra": 320000.00,
  "costoReferencia": 3200.00, "diferenciaCosto": 0.00 }
```

**GET /api/v1/compras/12/detalles → 200** (`DetalleCompraListaResponse`):

```json
{ "idCompra": 12, "estado": "PENDIENTE", "editable": true,
  "detalles": [
    { "idDetalleCompra": 28,
      "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo", "unidad": "kg" },
      "cantidad": 100.00, "precioUnitario": 3200.00, "subtotal": 320000.00,
      "costoReferencia": 3200.00, "diferenciaCosto": 0.00 },
    { "idDetalleCompra": 29,
      "materiaPrima": { "idMateriaPrima": 2, "nombre": "Azúcar", "unidad": "kg" },
      "cantidad": 50.00, "precioUnitario": 3200.00, "subtotal": 160000.00,
      "costoReferencia": 3000.00, "diferenciaCosto": 200.00 }
  ],
  "totalCompra": 480000.00, "cantidadItems": 2 }
```

En el listado cada línea va **sin** `totalCompra`: el total agregado viaja una sola vez en la
cabecera y el campo `null` ni siquiera se serializa. Una compra vacía responde
`"detalles": []`, `"totalCompra": 0.00` y `"cantidadItems": 0`.

**PUT /api/v1/compras/12/detalles/28 → 200** — mismo objeto que el `POST`, con el subtotal y el
total ya recalculados. El cuerpo solo admite `cantidad` y `precioUnitario`; si trae
`idMateriaPrima` se ignora, porque la materia prima de la línea no se reemplaza (R-02).

**DELETE /api/v1/compras/12/detalles/28 → 204** — sin cuerpo.

**Errores:** `COMPRA_INEXISTENTE` (404); `COMPRA_NO_EDITABLE`, `MATERIA_PRIMA_INEXISTENTE` y
`MATERIA_PRIMA_DUPLICADA` (409); `CANTIDAD_INVALIDA`, `VALIDACION_FALLIDA` y
`PARAMETRO_INVALIDO` (400, el último para ids de ruta no numéricos); `RECURSO_NO_ENCONTRADO`
(404, línea inexistente o de otra compra); `SIN_PERMISO` y `USUARIO_INACTIVO` (403);
`NO_AUTENTICADO` (401); `TIPO_CONTENIDO_NO_SOPORTADO` (415, fix D1).

## Evidencia (pruebas backend)

| Clase | Casos | Cobertura |
|---|---|---|
| `DetalleCompraServiceTest` | 25 | Orden de validaciones de la spec §5, R-01 en los cuatro endpoints, R-02 con y sin carrera, R-03 con `HALF_UP`, R-04 sin escritura del costo, R-05, R-06 y totales |
| `DetalleCompraControllerTest` | 13 | `201` + `Location`, `200`/`204`/`400`/`404`/`409` de cada endpoint e ids no numéricos |
| `DetalleCompraSeguridadTest` | 36 (9 métodos) | SEC-01…SEC-05 parametrizados sobre los 4 endpoints (26 casos) y matriz de roles con tokens reales (10) |
| `DetalleCompraRepositoryTest` | 9 | Inserción, listado con `JOIN`, total en la base, decimales exactos y unicidad |
| `DetalleCompraTest` | 6 | POJO: `subtotal()` con `HALF_UP`, escala 2 del constructor y nulos |
| **Total de la HU** | **89** | |

La suite completa quedó en **1046 tests verdes, 0 fallos y 0 errores** (`mvn test`,
`BUILD SUCCESS`, verificado el 2026-10-28 en la rama de la historia).

- **Verificación en caliente del 2026-10-28** contra la API real (`:8085`) y MySQL 8.4, en modo
  ciego: **CP-01…CP-22 = 22/22** y **SEC-01…SEC-05 en los 4 endpoints = 20/20**, sin defectos
  críticos ni altos abiertos (veredicto APTO).
- **CP-13 verificado en vivo:** `33.33 × 3333.33` responde `subtotal: 111099.89`; el total
  coincidía con el `SUM` de la base (evaluado con `ROUND(..., 2)`, sin centavos fantasma).
- **CP-20 verificado:** comprar a un precio distinto del costo de referencia **no** actualiza
  `materias_primas.costo_unitario`; el comparativo viaja en `costoReferencia`/`diferenciaCosto`.
- **CP-19 verificado por SQL:** el segundo `INSERT` repitiendo `(id_compra, id_materia_prima)`
  falla con el error 1062 de `uk_detalle_compra`; los `CHECK` de cantidad y precio también
  están activos.
- El estado de QA va en la fila `Estado QA` de la cabecera, no aquí.

## Desviaciones vs especificación

- **El `GET` sobre una compra `RECIBIDA` o `CANCELADA` responde `200` con `editable: false`, no
  `409`.** R-01 dice que «los cuatro endpoints» verifican el estado; en la práctica los tres de
  escritura responden `409 COMPRA_NO_EDITABLE` y el de lectura devuelve la foto del documento,
  que es lo que la UI necesita para pintar el detalle histórico. Es la ambigüedad detectada en
  QA y se documenta como comportamiento correcto.
- **`diferenciaCosto` puede ser negativa o cero.** La spec §4 solo la ejemplifica en `0.00`; el
  contrato la define como la diferencia con signo (negativa = esta compra salió más barata).
- **`CANTIDAD_INVALIDA` la emite el servicio, no la validación de forma.** Los DTO no llevan
  `@Positive` en `cantidad` a propósito (R-05): un cero debe responder el código de negocio, no
  `VALIDACION_FALLIDA`.
- **La carrera de dos `DELETE` concurrentes de la misma línea puede responder `[204, 204]`**,
  mientras el secuencial responde `204` y después `404`. No hay corrupción de datos: el segundo
  borrado no encuentra la fila. Queda como nota de contrato, no como defecto.
- **Jackson acepta de forma laxa algunas variantes** (cantidades como texto, notación científica,
  `8.5` como id de materia prima que se trunca a `8`). Es el comportamiento transversal del
  mapper, no específico de HU-14.

## Defectos y cierre

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| D1 | Medio | Un `Content-Type` no soportado respondía `500 ERROR_INTERNO` en lugar de un 4xx | **Corregido en PR #98** (`415 TIPO_CONTENIDO_NO_SOPORTADO`) y re-testeado en vivo |
| D2 | Medio | Un `Accept` no soportado con token válido respondía `401 NO_AUTENTICADO` engañoso («sesión expirada») con `path /error` | **Deuda transversal abierta:** mismo alcance que D6/D7 de HU-13; se corrige en la configuración global |
| D3 | Medio | `totalCompra` perdía precisión en totales mayores que 2^53 (Δ 0,34 frente al `SUM` de SQL) | **Falso positivo:** la API devuelve el decimal exacto de `BigDecimal`; la pérdida ocurría al parsear la respuesta del harness como número de coma flotante (más allá de 2^53), no en el backend |
| D4 | Medio | `costoReferencia` y `diferenciaCosto`, documentados en la spec §4, no estaban en la API (R-04 no exponible) | **Corregido en PR #98** (comparativo de costo en las respuestas de línea y del listado) y re-testeado en vivo |
| D5 | Bajo | Un decimal fuera de rango responde `409 CONFLICTO_INTEGRIDAD` en lugar de `400` | **Deuda abierta:** el código no está en el catálogo §5 del contrato transversal; comportamiento de la capa de persistencia |

Ninguno de los cinco defectos quedó abierto como crítico o alto: D1 y D4 están corregidos y
re-testeados, D3 se cierra como falso positivo y D2/D5 se trasladan como deuda transversal.

**PRs:** #95 (persistencia y DTOs), #96 (servicio), #97 (controlador, contrato y seguridad) y
#98 (arreglos D1/D4), todos mergeados en `HU-14/detalle-compra`; esta documentación va como PR6
sobre `HU-14/pr6-docs`.

## Pendientes / deuda

- **Frontend:** no existe pantalla de detalle de compra; el manual de consumo es el contrato
  para Angular, que debe pintar el comparativo de costo (↑/↓/=) y ocultar la edición fuera de
  `PENDIENTE`.
- **Deuda transversal D2/D5:** el `401` engañoso con `Accept` no soportado y el
  `CONFLICTO_INTEGRIDAD` sin catálogo; se corrigen en la configuración global de la API, no en
  esta historia.
- **Carrera de doble `DELETE`:** se puede cerrar con un estado canónico si el equipo lo decide;
  hoy responde `[204, 204]` sin corromper datos.
- **Datos de QA en la base:** quedaron materias primas y compras de prueba locales (proveedor
  19, MP 1/2/4); no son datos de negocio ni viajan con el repositorio.
