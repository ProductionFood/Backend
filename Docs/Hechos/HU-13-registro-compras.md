# HU-13 — Registro de compras · Backend verificado

| | |
|---|---|
| Fecha de implementación | 2026-10-26 |
| Commits / PR | PRs #88–#93 a `HU-13/registro-compras`; este cambio añade la documentación |
| Colección Postman | `Docs/Postman/ProductionFood-HU13.postman_collection.json` (`Preparación`, `Compras (CP-01…CP-22)`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Estado QA | **QA backend verificado en caliente 2026-10-26 (22 CP + 25 SEC) · frontend pendiente** |

> Esta ficha documenta el backend de la historia. La verificación en caliente se ejecutó el
> 2026-10-26 contra la API real en `:8085` y MySQL 8.4 en modo ciego (solo la especificación,
> los casos de QA y el contrato transversal): los 22 casos funcionales y los 25 de seguridad
> (5 casos × 5 endpoints) pasaron, más la matriz completa de roles, las 9 combinaciones de la
> máquina de estados y la conciliación de inventario/kardex con 0 descuadres. El consumo desde
> el frontend sigue pendiente. Los ejemplos salen de la especificación, de los DTO reales y de
> la ejecución de QA; los totales, los saldos y los ids dependen de la base.

## Qué se construyó

| Método | Ruta | Roles | Códigos | Descripción |
|---|---|---|---|---|
| GET | `/api/v1/compras` | ADMIN, COMPRAS, PRODUCCION, CONSULTA | 200/400/401/403 | Listado paginado con filtros `idProveedor`, `estado`, `fechaInicio` y `fechaFin`, paginación `page`/`size` y orden de lista blanca (`fecha`, `estado`, `id`) |
| GET | `/api/v1/compras/{id}` | ADMIN, COMPRAS, PRODUCCION, CONSULTA | 200/400/401/403/404 | Cabecera con proveedor embebido, totales derivados y `editable` |
| POST | `/api/v1/compras` | ADMIN, COMPRAS | 201/400/401/403/409 | Registra la compra —siempre `PENDIENTE`— con `Location: /api/v1/compras/{id}` |
| PUT | `/api/v1/compras/{id}` | ADMIN, COMPRAS | 200/400/401/403/404/409 | Edita proveedor y fecha mientras la compra siga `PENDIENTE` |
| PATCH | `/api/v1/compras/{id}/estado` | ADMIN, COMPRAS | 200/400/401/403/404/409 | Transición de estado: **recibir**, que suma el stock (R-01), o cancelar |

**No existe `DELETE`**: responde `405 METODO_NO_PERMITIDO` tanto en la colección como en el
recurso. Las compras referencian al proveedor y sus líneas alimentan el kardex de materias
primas; borrarlas rompería el historial de compras y la trazabilidad del inventario.

**Roles.** Lectura de los cinco endpoints para `ADMIN`, `COMPRAS`, `PRODUCCION` y `CONSULTA`.
Escritura (alta, edición de cabecera y transiciones) solo para `ADMIN` y `COMPRAS`. `VENTAS`
no participa en el módulo y recibe `403 SIN_PERMISO` en los cinco endpoints.

Piezas internas:

| Pieza | Rol |
|---|---|
| `CompraService` | Alta, edición, listado con filtros, `obtener`, `recibir`, `cancelar` y `@Auditable` en cada operación |
| `CompraController` | Los cinco endpoints con `@PreAuthorize` y Swagger; el PATCH despacha por destino |
| `CompraRepository` | JDBC explícito: `insertar`, `actualizar`, `cambiarEstado`, `buscar`/`contar` con el `WHERE` compartido, `buscarPorId`, `contarDetalles` y `listarDetalles` |
| `CompraRowMapper` / `Compra` | POJO sobre `compras`; `nombreProveedor` llega de un `JOIN`, `totalCompra` y `cantidadItems` de dos subconsultas |
| `EstadoCompra` | Enum espejo del `CHECK ck_compras_estado` con la máquina de transiciones inmutable (R-02) |
| `LocalDateObligatoriaDeserializer` | Distingue `fecha` omitida (hoy) de `fecha: null` explícita (400) |
| `PageRequest` / `PageResponse` | Paginación transversal: `page >= 0`, `size` 1…100 y `sort` validado contra lista blanca y dirección |
| `InventarioService` | `registrarEntrada(..., ENTRADA_COMPRA, "detalle_compra", idDetalle)` dentro de la recepción |
| DTOs | `CrearCompraRequest`, `ActualizarCompraRequest`, `CambiarEstadoCompraRequest`, `CompraResponse`, `RecepcionResponse`, `MovimientoGeneradoResponse`, `ProveedorRefResponse` |

**Sin migración Flyway nueva.** V1 ya crea `compras` y `detalle_compra`; V2 añade
`ck_compras_estado`, el índice `idx_compras_fecha` y la tabla `movimientos_inventario_mp` con
`tabla_origen`/`id_origen`. Esta historia solo escribe código sobre el esquema existente.

## Decisiones clave

| Regla | Decisión implementada |
|---|---|
| CA-03 / R-02 | Una compra **solo nace `PENDIENTE`**. El `POST` con `estado` ausente responde `400 VALIDACION_FALLIDA` (`fieldErrors[0].field = "estado"`) y un estado canónico distinto de `PENDIENTE` también: crear una `RECIBIDA` directamente saltaría la recepción y esa compra no sumaría stock (R-01) |
| CA-02 / R-07 | `fecha` omitida → se registra **hoy**; `fecha: null` explícita → `400 VALIDACION_FALLIDA` en el campo `fecha`; fecha posterior a hoy → `400 FECHA_FUTURA`. Las fechas pasadas se admiten |
| R-06 | El proveedor se valida **solo al crear**: debe existir (`409 PROVEEDOR_INEXISTENTE`) y estar activo (`409 PROVEEDOR_INACTIVO`, que la tabla de errores marca «(solo al crear)»). En el `PUT` solo se comprueba existencia, porque una compra ya registrada no se bloquea por desactivar a su proveedor |
| HU-04 R-06 | El controlador **despacha el PATCH por destino** (`RECIBIDA → recibir`, `CANCELADA → cancelar`, resto → `transicionInvalida`) porque la acción de bitácora sale de la anotación `@Auditable` del método interceptado: la recepción audita `RECEPCION_COMPRA` y la cancelación `CAMBIAR_ESTADO` |
| CA-04 | `totalCompra` y `cantidadItems` son **subconsultas sobre `detalle_compra`**, no columnas de `compras`; una compra recién registrada responde `0.00` y `0` |
| HU-14 CA-05 | El detalle de líneas **no está en HU-13**: lo sirve HU-14 con `GET /api/v1/compras/{id}/detalles`. Por eso `GET /{id}` devuelve la cabecera con sus totales |
| Contrato §4 | `sort` valida **campo y dirección**: admite `campo` o `campo,asc|desc`; un campo fuera de la lista blanca, una dirección vacía o con más de dos partes responde `400 CAMPO_ORDEN_INVALIDO` |

## Reglas de negocio implementadas

- **R-01 · la recepción es la entrada principal de stock.** En **una sola transacción**: se
  comprueba que la compra tenga líneas (`409 COMPRA_SIN_DETALLE`) y, por cada línea de
  `detalle_compra`, se llama a
  `InventarioService.registrarEntrada(idMateriaPrima, cantidad, ENTRADA_COMPRA, "detalle_compra", idDetalle)`
  con la observación `Compra #id`; después se escribe `compras.estado = 'RECIBIDA'`. La
  respuesta `RecepcionResponse` devuelve `movimientosGenerados` con `materiaPrima`, `cantidad`,
  `saldoAnterior` y `saldoPosterior` por línea. **Si falla una línea no entra ninguna**
  (rollback entero).
- **R-02 · máquina de estados.** `PENDIENTE → {RECIBIDA, CANCELADA}`; `RECIBIDA` y `CANCELADA`
  son terminales. Cualquier otra transición —incluida la auto-transición— responde
  `409 TRANSICION_INVALIDA` con el estado actual, el destino y los estados permitidos
  (`«Desde RECIBIDA no hay transiciones disponibles»`).
- **R-03 · sin detalle no se recibe.** `compraRepository.contarDetalles(id) == 0` se comprueba
  **antes** de tocar el inventario y responde `409 COMPRA_SIN_DETALLE`.
- **R-04 · el detalle solo cambia estando `PENDIENTE`.** El `PUT` responde
  `409 COMPRA_NO_EDITABLE` en cualquier otro estado; el campo `editable` de la respuesta nace
  de la misma máquina de estados.
- **R-05 · cancelar no devuelve stock.** Cancelar solo es posible desde `PENDIENTE`; una
  compra `RECIBIDA` no puede cancelarse (`409 TRANSICION_INVALIDA`) y la cancelación no
  escribe en el inventario.
- **R-06 · proveedor desactivado después.** Si el proveedor se desactiva tras el alta, la
  recepción procede con normalidad: la mercancía ya fue pedida.
- **R-07 · fecha pasada sí, futura no.** Se registran compras atrasadas (CP-06 → `201`);
  una fecha posterior a hoy responde `400 FECHA_FUTURA`.
- **CA-01 · el proveedor debe existir.** Se comprueba con `ProveedorRepository.findById` antes
  de mirar fecha y estado, para que un error de referencia no se enmascare tras uno de formato.
- **CA-02/CA-03 · fecha y estado.** `@NotNull` en `idProveedor` y `estado`; `fecha` usa el
  deserializador propio que distingue omisión de `null` explícito. Un literal no canónico
  (`"ENVIADA"`, `"pendiente"`) falla en la deserialización y responde `400 VALIDACION_FALLIDA`
  con `fieldErrors`.
- **Listado.** `page >= 0`, `size` entre 1 y 100. Filtros opcionales `idProveedor`, `estado`,
  `fechaInicio` y `fechaFin`; un rango invertido responde `400 RANGO_FECHAS_INVALIDO`. Lista
  blanca de orden: `fecha`, `estado`, `id` (`id` se traduce a `c.id_compra`); por defecto
  `fecha,desc`. La paginación no tiene cota superior de página: una página fuera de rango
  devuelve `content: []` con `200`.
- **Auditoría.** `@Auditable` con acciones `CREAR` (alta), `EDITAR` (PUT), `RECEPCION_COMPRA`
  (recepción) y `CAMBIAR_ESTADO` (cancelación) sobre la tabla `compras`.

## Modelo de datos

**Tabla `compras`** (V1 la crea; V2 añade el `CHECK` y el índice)

| Columna | Tipo | Notas |
|---|---|---|
| `id_compra` | `int unsigned` PK AI | |
| `id_proveedor` | `int unsigned` NOT NULL FK → `proveedores` | CA-01 |
| `fecha` | `date` NOT NULL | CA-02, R-07; `idx_compras_fecha (fecha, estado)` |
| `estado` | `varchar(30)` NOT NULL | CA-03; `CHECK ck_compras_estado IN ('PENDIENTE','RECIBIDA','CANCELADA')` |

`totalCompra` y `cantidadItems` **no son columnas**: la consulta de listado y la de detalle
los calcula con dos subconsultas sobre `detalle_compra` (`SUM(cantidad * precio_unitario)` y
`COUNT(*)`, o `0.00`/`0` cuando no hay líneas). El detalle de la compra vive en
`detalle_compra` (`id_compra`, `id_materia_prima`, `cantidad decimal(10,2)`,
`precio_unitario decimal(10,2)`) y lo gestiona HU-14.

La recepción escribe en `inventario` y en `movimientos_inventario_mp` con
`tipo_movimiento = 'ENTRADA_COMPRA'`, `tabla_origen = 'detalle_compra'`,
`id_origen = id_detalle_compra`, los dos saldos y `observacion = 'Compra #<id>'`;
`id_usuario` queda en `NULL`. Ese movimiento es el que HU-10 muestra en el kardex de materia
prima.

## Contrato resumido

**POST /api/v1/compras → 201** (con `Location: /api/v1/compras/28`):

```json
// petición
{ "idProveedor": 19, "fecha": "2026-10-26", "estado": "PENDIENTE" }
```

```json
// respuesta real (la compra 28 de la base de QA; con fecha omitida la respuesta es igual,
// con la fecha de hoy)
{ "idCompra": 28,
  "proveedor": { "idProveedor": 19, "nombre": "QA-H09 Proveedor 1792780001" },
  "fecha": "2026-10-26", "estado": "PENDIENTE",
  "totalCompra": 0.00, "cantidadItems": 0, "editable": true }
```

**GET /api/v1/compras → 200** — `PageResponse` con `content`, `page`, `size`,
`totalElements`, `totalPages`, `first` y `last`; cada elemento de `content` tiene la forma del
objeto anterior. Por defecto: `page=0`, `size=20`, `sort=fecha,desc`.

```json
{ "content": [
    { "idCompra": 30, "proveedor": { "idProveedor": 19,
        "nombre": "QA-H09 Proveedor 1792780001" },
      "fecha": "2026-10-26", "estado": "RECIBIDA",
      "totalCompra": 250.00, "cantidadItems": 1, "editable": false },
    { "idCompra": 27, "proveedor": { "idProveedor": 19,
        "nombre": "QA-H09 Proveedor 1792780001" },
      "fecha": "2026-10-26", "estado": "PENDIENTE",
      "totalCompra": 0.00, "cantidadItems": 0, "editable": true }
  ],
  "page": 0, "size": 2, "totalElements": 30, "totalPages": 15,
  "first": true, "last": false }
```

**GET /api/v1/compras/{id} → 200** — la cabecera sola, con los totales derivados. Un id
inexistente o no numérico responde `404 RECURSO_NO_ENCONTRADO` y `400 PARAMETRO_INVALIDO`
respectivamente. **No trae las líneas** (HU-14).

**PATCH /api/v1/compras/{id}/estado con `{"estado": "RECIBIDA"}" → 200**
(`RecepcionResponse`):

```json
{ "idCompra": 30, "estado": "RECIBIDA", "totalCompra": 250.00,
  "movimientosGenerados": [
    { "materiaPrima": "QA-H10-1792970345-CP02", "cantidad": 25.00,
      "saldoAnterior": 50.00, "saldoPosterior": 75.00 }
  ] }
```

**PATCH con `{"estado": "CANCELADA"}" → 200** devuelve `CompraResponse` (misma forma que
`GET /{id}`), por ejemplo la compra 12 con `estado: "CANCELADA"` y `editable: false`.

**PUT /api/v1/compras/{id} → 200** — devuelve la compra releída de la base, con sus totales
derivados ya actualizados.

**Errores:** `PROVEEDOR_INEXISTENTE`, `PROVEEDOR_INACTIVO`, `TRANSICION_INVALIDA`,
`COMPRA_NO_EDITABLE` y `COMPRA_SIN_DETALLE` (409); `FECHA_FUTURA`, `RANGO_FECHAS_INVALIDO`,
`CAMPO_ORDEN_INVALIDO`, `PARAMETRO_INVALIDO` y `VALIDACION_FALLIDA` (400);
`RECURSO_NO_ENCONTRADO` (404); `SIN_PERMISO` y `USUARIO_INACTIVO` (403); `NO_AUTENTICADO`
(401); `METODO_NO_PERMITIDO` (405).

## Evidencia (pruebas backend)

| Clase | Casos | Cobertura |
|---|---|---|
| `CompraServiceTest` | 37 | Alta con el orden proveedor→fecha→estado, R-01/R-03/R-04/R-06/R-07, recepción transaccional, cancelación, máquina de estados, filtros, rango de fechas, orden y totales |
| `CompraControllerTest` | 25 | 201 + `Location`, 400/404/409 de cada endpoint, despacho del PATCH, filtros y orden del listado, id no numérico |
| `CompraSeguridadTest` | 45 | SEC-01…SEC-05 parametrizados en los 5 endpoints y matriz de roles con tokens reales |
| `EstadoCompraTest` | 5 | Transiciones permitidas, terminales y auto-transición |
| DTOs (`Crear`, `Actualizar`, `CambiarEstado`) | 10 | Obligatoriedad de `estado` y `fecha`, omisión de `fecha` frente a `null` explícito, `estado` no canónico |
| `PageRequestTest` (+4) | 14 | Dirección de `sort` vacía, no `asc`/`desc` y con más de dos partes |
| **Total de la HU** | **126** | |

La suite completa quedó en **955 tests verdes, 0 fallos y 0 errores** (`mvn test`,
`BUILD SUCCESS`, verificado el 2026-10-26). La rama `main` previa cerraba en **829 tests**,
así que la HU-13 suma **126**: 122 de sus siete clases de prueba y los 4 casos ampliados en
`PageRequestTest` con los arreglos de `sort`.

- **Verificación en caliente del 2026-10-26** contra la API real (`:8085`) y MySQL 8.4, en
  modo ciego: **CP-01…CP-22 = 22/22** y **SEC-01…SEC-05 en los 5 endpoints = 25/25**, sin
  defectos críticos ni altos abiertos.
- **Cobertura adicional:** matriz de 25 combinaciones de rol × endpoint (ADMIN y COMPRAS
  escriben, PRODUCCION y CONSULTA leen, VENTAS recibe 403 en todo), las 9 combinaciones de la
  máquina de estados 3×3 y conciliación de inventario/kardex **con 0 descuadres** y continuidad
  de saldos.
- **CP-09/CP-10** se verificaron en base: cada línea de la recepción generó su fila en
  `inventario` y su movimiento `ENTRADA_COMPRA` con `tabla_origen = 'detalle_compra'` y
  `id_origen` igual al `id_detalle_compra`; los saldos anterior y posterior de la respuesta
  coinciden con el kardex.
- **CP-16** se forzó con una tercera línea imposible (materia prima sin fila en `inventario`
  y desbordamiento de `decimal(10,2)`): la transacción revirtió entera y **ninguna línea
  entró**, sin movimientos ni fila de bitácora de recepción.
- **CP-15** se comprobó además por SQL: el número de movimientos con
  `tabla_origen = 'detalle_compra'` de la compra recibida sigue siendo el de sus líneas, nunca
  el doble.
- **Re-test focalizado tras los arreglos D3, D4 y D5:** los tres casos pasan (estado obligatorio
  con `fieldErrors[0].field = "estado"`, `fecha` omitida que nace hoy y `fecha: null` que se
  rechaza, direcciones de `sort` inválidas con `400 CAMPO_ORDEN_INVALIDO`) **sin defectos
  nuevos**.
- El estado de QA va en la fila `Estado QA` de la cabecera, no aquí.

## Desviaciones vs especificación

- **`GET /{id}` no devuelve el detalle de líneas.** La especificación §2 habla de «obtener
  compra con su detalle», pero el detalle lo implementa HU-14 (CA-05) con
  `GET /api/v1/compras/{id}/detalles`; HU-13 devuelve la cabecera con los totales derivados.
  Es el defecto D1, descartado por estar fuera de alcance.
- **`PROVEEDOR_INACTIVO` es solo del alta.** CA-01 dice validar el proveedor, pero la tabla de
  errores de la especificación matiza «(solo al crear)» y R-06 prohíbe bloquear una compra ya
  registrada: en el `PUT` solo se exige existencia. Es el defecto D2, descartado como
  comportamiento documentado.
- **`fecha` no lleva `@NotNull` en el DTO.** CA-02 la declara obligatoria «por defecto la fecha
  actual»; si llevara `@NotNull`, omitir el campo respondería `400` y no crearía la compra con
  hoy (CP-04). La obligatoriedad se resuelve con un deserializador propio que solo castiga el
  `null` explícito.
- **`estado` sí lleva `@NotNull` y además debe ser `PENDIENTE`.** CA-03 lo declara obligatorio
  y el diseño fija «al crear, el estado es siempre PENDIENTE»: un estado canónico distinto
  responde `400 VALIDACION_FALLIDA`, no un `201`.
- **`sort` valida también la dirección.** El contrato §4 admite `campo` o `campo,asc|desc`;
  una dirección vacía, desconocida o con más de dos partes responde `400 CAMPO_ORDEN_INVALIDO`
  en lugar de ignorarse.
- **No hay `DELETE`.** La especificación no lo pide y el kardex referencia las líneas de la
  compra; responde `405 METODO_NO_PERMITIDO`.
- **El `PUT` ignora el campo `estado` si viene en el cuerpo.** No es editable por ahí: el
  estado solo cambia por `PATCH`, que aplica la máquina de transiciones.
- **Observaciones del QA (no son defectos):** los movimientos que genera la recepción quedan
  con `id_usuario = NULL`; una recepción que revierte por un fallo de integridad responde
  `CONFLICTO_INTEGRIDAD`, un código que no está en el catálogo §5 del contrato transversal; y
  se admite recibir con materias primas desactivadas, algo que la especificación no restringe.

## Defectos y cierre

| ID | Severidad | Hallazgo | Estado |
|---|---|---|---|
| D1 | Alto | `GET /api/v1/compras/{id}` no devuelve el detalle de líneas pese a la especificación §2 | **Descartado, fuera de alcance:** lo sirve HU-14 (CA-05) y la tarea backend de HU-13 no lo incluye |
| D2 | Medio | El `PUT` admite un proveedor inactivo que el `POST` rechaza con `409` | **Descartado, comportamiento documentado:** R-06 y la anotación «(solo al crear)» de la tabla de errores |
| D3 | Medio | El `POST` sin campo `estado` creaba la compra en `PENDIENTE` con `201` | **Corregido en PR #92** (`400 VALIDACION_FALLIDA`, `fieldErrors[0].field = "estado"`) |
| D4 | Bajo | El `POST` con `fecha: null` explícito creaba la compra con la fecha de hoy | **Corregido en PR #92** (`400 VALIDACION_FALLIDA` en el campo `fecha`; omitir el campo sigue creando con hoy) |
| D5 | Bajo | La dirección de `sort` no se validaba (`sort=fecha,xyz` respondía `200`) | **Corregido en PR #92** y **bordes por PR #93** (`sort=fecha,` y `sort=fecha,asc,extra` → `400 CAMPO_ORDEN_INVALIDO`) |
| D6 | Bajo | Rutas con `;` en el path (`/compras/2;drop` y `%3B`) responden `401` con token válido | **Deuda transversal abierta:** afecta a todos los endpoints del proyecto, no es de HU-13 |
| D7 | Bajo | El mensaje del `401` es siempre «Su sesión ha expirado…», también sin token o con token malformado | **Deuda transversal abierta:** mismo alcance que D6 |
| D8 | Bajo | `/v3/api-docs` publica `components.securitySchemes` vacío (la autenticación no queda documentada en Swagger) | **Deuda transversal abierta:** mismo alcance que D6 |

Ninguno de los ocho defectos quedó abierto como crítico o alto: D1 y D2 se cierran como
fuera de alcance y comportamiento documentado, D3–D5 están corregidos y probados, y D6–D8 se
trasladan como deuda del proyecto.

**PRs:** #88 (persistencia), #89 (servicio de alta), #90 (recepción y listado), #91
(controlador, contrato y seguridad), #92 (arreglos D3/D4/D5) y #93 (bordes del `sort`),
todos mergeados en `HU-13/registro-compras`.

## Pendientes / deuda

- **Frontend:** no existe pantalla de compras ni de detalle de compras; el manual de consumo es
  el contrato para Angular. La recepción y el listado se prueban hoy solo desde la API.
- **HU-14 · detalle de la compra:** es la siguiente historia. Sin ella no se pueden agregar,
  editar ni listar líneas por API, y por eso las compras de recepción se preparan con SQL en la
  colección (CP-09, CP-10, CP-16, CP-19 y CP-22).
- **Deuda transversal D6/D7/D8:** rutas con `;`, mensaje genérico del `401` y
  `securitySchemes` vacío en Swagger; se corrigen en la configuración global de la API, no en
  esta historia.
- **Datos de QA en la base:** quedaron las compras 6–30, los proveedores 19–21 (el 20 y el 21
  desactivados), materias primas `QA-H13-*` y un usuario inactivo. Son datos de prueba
  locales; no son datos de negocio ni viajan con el repositorio.
