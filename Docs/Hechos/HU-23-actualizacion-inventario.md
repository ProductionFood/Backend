# HU-23 — Actualización automática de inventario por producción · Backend verificado

| | |
|---|---|
| Historia | HU-23 — Actualización automática del inventario al registrar consumo de materia prima |
| Módulo | Producción / Inventario |
| Endpoints | **Ninguno propio (R-01).** Comportamiento transaccional de `POST /api/v1/detalles-plan/{id}/consumos` y `PUT /api/v1/consumos/{id}` (HU-22) más `GET /api/v1/inventario/alertas` (HU-27, reutilizado) |
| Fecha de implementación | 2026-11-05 |
| Colección Postman | `Docs/Postman/ProductionFood-HU23.postman_collection.json` (`Preparación`, `Casos (CP-01…CP-23)`, `Roles (matriz)`, `Seguridad (SEC-01…SEC-05)`) |
| Pull requests | #173 `alertas[]` en POST y PUT · #174 pruebas de integración CA-01…CA-04 · #175 corrección de auditoría y pool (D-01) · PR docs · PR final #177 |
| Estado QA | **QA ciego verificado · CP-01…CP-23 y SEC-01…SEC-05 sobre POST consumos, PUT consumos y GET alertas · CP-03, CP-11, CP-12 y CP-20 parciales o no aplicables al contrato (ver QA) · frontend pendiente** |

> Esta ficha documenta las garantías de consistencia del inventario al registrar o corregir un
> consumo de materia prima: el descuento, el kardex, la fecha de actualización, la alerta de stock
> bajo y la atomicidad. No hay recursos nuevos: es el comportamiento de HU-22 (consumos) y la
> lectura de HU-27 (alertas). El contrato práctico para el frontend está en el manual
> [`../Manuales_Consumo_Frontend/HU-23-actualizacion-inventario.md`](../Manuales_Consumo_Frontend/HU-23-actualizacion-inventario.md).

## Qué se construyó

| Método | Ruta | Roles | Códigos | Relación con HU-23 |
|---|---|---|---|---|
| POST | `/api/v1/detalles-plan/{id}/consumos` | ADMIN, PRODUCCION | 201/400/401/403/404/409 | Disparador: descuenta inventario, escribe kardex y ahora devuelve `alertas[]` |
| PUT | `/api/v1/consumos/{id}` | ADMIN, PRODUCCION | 200/400/401/403/404/409 | Ajusta por diferencia y devuelve `alertas[]` |
| GET | `/api/v1/inventario/alertas` | ADMIN, PRODUCCION, COMPRAS, CONSULTA | 200/400/401/403 | Reutilizado de HU-27 para el indicador global; no lleva `alertas[]` |

**HU-23 no tiene endpoints propios (R-01).** Se implementó junto con HU-22 y la parte que faltaba
(CA-03) se añadió después en el PR #173.

### Criterios de aceptación

| CA | Enunciado | Cobertura |
|---|---|---|
| CA-01 | Se descuenta `cantidad_real` del inventario | Ya lo cubría HU-22: `InventarioService.registrarSalida` en la misma transacción |
| CA-02 | Se actualiza `fecha_actualizacion` | Ya lo cubría HU-22: `ON UPDATE CURRENT_TIMESTAMP` de V2, la base la mantiene sola |
| CA-03 | Alerta si el stock queda bajo el mínimo | **Faltaba y se añadió en el PR #173**: `alertas[]` en las respuestas del POST y del PUT |
| CA-04 | Operación transaccional | Ya lo cubría HU-22: `@Transactional` sobre el servicio completo |

### `alertas[]` (PR #173)

- Va en las respuestas del `POST` (201) y del `PUT` (200) de consumos. El `GET` listado no lo lleva.
- Reutiliza la vista `v_inventario_materia_prima` de HU-27: es el mismo objeto de
  `/inventario/alertas`, con el nivel `BAJO` o `AGOTADO`.
- Lista vacía si no hay alerta, también cuando el saldo queda **igual** al mínimo (no está por
  debajo).
- Es informativa: nunca bloquea el registro (R-04).

## Reglas de negocio implementadas

- **R-02 · todo o nada.** El bloqueo de la fila, la validación de stock, el `UPDATE` del saldo, el
  movimiento `SALIDA_PRODUCCION` y el `INSERT` del consumo viven en una transacción. Un fallo en
  cualquier paso deja inventario, kardex y consumo sin cambios.
- **R-03 · bloqueo de fila.** Dos consumos simultáneos de la misma materia prima se serializan: con
  stock para uno, un `201` y un `409`, y el saldo nunca queda negativo.
- **R-04 · alerta informativa.** Se calcula tras el descuento y no impide la operación.
- **R-05 · stock insuficiente.** `409 STOCK_INSUFICIENTE` y no se registra nada.
- **R-06 · fecha automática.** El servicio no asigna `fecha_actualizacion`.
- **R-07 · toda salida pasa por `InventarioService`.** Kardex y saldo no se desincronizan.
- **R-08 · redondeo.** El descuento redondea con `HALF_UP` a 2 decimales por cada consumo.

## Decisiones clave

| Tema | Decisión implementada |
|---|---|
| Alcance | Sin endpoints nuevos; el POST admite **un ingrediente por petición** (contrato de HU-22). El lote de varios ingredientes del ejemplo de la especificación no se implementó: cambiaría el contrato publicado y no es un CA |
| Atomicidad multi-ingrediente | Cada POST es atómico. Si el llamador agrupa varias llamadas en una transacción, la atomicidad multi-ingrediente queda garantizada (así se prueba en la prueba de integración) |
| `alertas[]` | Devuelve el objeto completo de HU-27 (incluye `unidad`, `costoUnitario`, `costoReposicion`, `ultimaActualizacion`), superconjunto del de la especificación |
| Materia prima sin fila de inventario | Responde `409 STOCK_INSUFICIENTE` (contrato ya publicado en HU-22), no el `500 INVENTARIO_NO_INICIALIZADO` de la especificación. Se mantiene el contrato publicado |
| Roles de `GET /inventario/alertas` | Lo ve también `CONSULTA`; `VENTAS` recibe `403` |
| Auditoría | Corre tras el commit de la transacción de negocio (D-01) |

## Modelo de datos

**Sin tablas ni migraciones nuevas.** Coordina:

| Origen | Operación |
|---|---|
| `consumo_materia_prima` | `INSERT` / `UPDATE` de la cantidad real (HU-22) |
| `inventario` | `UPDATE cantidad_disponible`; `fecha_actualizacion` por `ON UPDATE CURRENT_TIMESTAMP` (V2) |
| `movimientos_inventario_mp` | `INSERT` `SALIDA_PRODUCCION` (alta y alza) o `AJUSTE_POSITIVO` (baja) |
| Vista `v_inventario_materia_prima` | Lectura para `alertas[]` |

## Contrato resumido

**POST `/api/v1/detalles-plan/{id}/consumos` → 201** (fragmento con alerta):

```json
{ "idConsumo": 120, "idDetallePlan": 20,
  "materiaPrima": { "idMateriaPrima": 5, "nombre": "Harina de trigo", "unidad": "kg" },
  "cantidadTeorica": 0.250, "cantidadReal": 16.200,
  "inventario": { "anterior": 62.50, "posterior": 46.30 },
  "alertas": [
    { "idMateriaPrima": 5, "nombre": "Harina de trigo", "unidad": "kg",
      "cantidadDisponible": 46.30, "stockMinimo": 50.00, "faltante": 3.70,
      "nivel": "BAJO", "costoUnitario": 4200.00, "costoReposicion": 15540.00,
      "ultimaActualizacion": "2026-11-05T15:20:11" } ] }
```

Sin alerta, `alertas` llega como `[]`. Un consumo real de cero no mueve inventario: la respuesta
omite `inventario` y trae `alertas` según el saldo actual.

**Errores:** los de HU-22 (`CANTIDAD_INVALIDA`, `PLAN_NO_EN_PROCESO`, `CONSUMO_DUPLICADO`,
`MATERIA_PRIMA_INEXISTENTE`, `STOCK_INSUFICIENTE`, …) y los transversales `NO_AUTENTICADO`,
`SIN_PERMISO`, `USUARIO_INACTIVO`, `ERROR_INTERNO` (500).

## Archivos creados o modificados

La implementación vive en los PR #173 a #175 sobre las clases de consumo de producción, el
servicio de alertas, el aspecto de auditoría y sus pruebas. Esta documentación añade:

```
Docs/Hechos/HU-23-actualizacion-inventario.md
Docs/Manuales_Consumo_Frontend/HU-23-actualizacion-inventario.md
Docs/Postman/ProductionFood-HU23.postman_collection.json
```

## Evidencia (pruebas backend)

El PR #174 añade 18 pruebas de integración contra MySQL real que cubren CA-01…CA-04: descuento y
kardex, fecha de actualización, alertas (bajo, agotado, igual al mínimo), rollback total ante
fallos, rollback de varios ingredientes agrupados en una transacción del llamador, concurrencia
(10 iteraciones) y conciliación kardex contra saldo.

## Smoke y QA ciego

QA ciego en un esquema aislado, con la cadena completa de preparación, contra la API en `:8086`,
sobre `POST` consumos, `PUT` consumos y `GET` alertas: CP-01…CP-23 y SEC-01…SEC-05.

- **OK:** CP-01, 02, 04…10, 13…19, 21, 22 y 23 (diez POST secuenciales en 0,12 s).
- **Parciales o no aplicables:**
  - CP-03 y CP-11: el contrato admite un ingrediente por POST. El rollback de varios ingredientes
    se prueba en la prueba de integración agrupando llamadas en una transacción del llamador; con
    un ingrediente sin stock el resultado es `409` sin efectos.
  - CP-12: se probó inyectando triggers de fallo en el consumo y en el kardex: `500 ERROR_INTERNO`
    y no cambia nada, también en el PUT.
  - CP-20: sin fila de inventario responde `409 STOCK_INSUFICIENTE` (contrato de HU-22), no el
    `500 INVENTARIO_NO_INICIALIZADO` de la especificación. Se mantiene el contrato publicado.
- **SEC-01…SEC-05:** OK en los tres endpoints. SEC-03 es parcial: se probó un token con `exp`
  pasado y con firma inválida (`401`); no distingue caducado de inválido.
- **Roles:** `VENTAS`, `CONSULTA` y `COMPRAS` reciben `403` en POST y PUT. En `GET /inventario/alertas`
  `VENTAS` recibe `403` y `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA` reciben `200`.

### Defectos del QA

| ID | Severidad | Hallazgo | Resolución |
|---|---|---|---|
| D-01 | ALTA | Con 10 o más escrituras concurrentes sobre endpoints `@Auditable`, todas esperaban 30 s (Hikari total=10, activas=10) y algunas daban `500` o un `401` enmascarado (redirección a `/error`). La auditoría con `REQUIRES_NEW` corría dentro de la transacción de negocio y pedía una segunda conexión | Corregido en el PR #175: `@Order` del `AuditoriaAspect` justo fuera del advisor transaccional, de modo que la auditoría corre tras el commit. Re-test: 72 PUT concurrentes (3 rondas de 24), todos `200`, máximo 0,36 s |
| H-2 | MEDIA | El doble envío concurrente daba `500`/`401` en lugar de `409` | Era consecuencia de D-01; resuelto con el mismo PR |
| H-3 | INFO | Durante la tormenta de D-01 se vieron `fecha` del kardex y `fecha_actualizacion` en UTC (+5 h) y una conexión con sesión `+00:00` | No se reprodujo tras reiniciar ni tras el fix: 10 de 10 conexiones en -05:00 y fechas coherentes incluso con 72 escrituras concurrentes. Probable efecto del agotamiento del pool; vigilar |

**Efecto colateral del fix de D-01.** Ya no queda fila de bitácora de un cambio que hizo rollback.
La ventana inversa es commit correcto con fallo de la auditoría, que se registra en el log.

### Dudas de contrato (no son defectos)

- El `409 STOCK_INSUFICIENTE` no trae `detalle` estructurado: las cifras van en el `message` (en el
  PUT, «requerido» es la diferencia).
- **Redondeo por consumo (R-08).** `HALF_UP` a 2 decimales: diez consumos de `0.005` bajan `0.10` en
  inventario y kardex aunque `cantidad_real` sume `0.050`. Hay sesgo al alza con consumos muy
  pequeños; es coherente con la especificación.
- El kardex de `SALIDA_PRODUCCION` no guarda `id_usuario`.
- `alertas[]` devuelve el objeto completo de HU-27, superconjunto del de la especificación.
- `GET /inventario/alertas` también lo ve `CONSULTA`.

## Desviaciones vs especificación

- **Un ingrediente por petición.** El ejemplo con `consumos[]` y `consumosRegistrados` no se
  implementó (ver Decisiones clave).
- **`alertas[]` con más campos** que el ejemplo de la especificación.
- **Sin fila de inventario** responde `409 STOCK_INSUFICIENTE`, no `500 INVENTARIO_NO_INICIALIZADO`.
- **`409` sin `detalle` estructurado.**
- **Conciliación.** No hay endpoint de conciliación en perfil dev (paso 9 de la tarea de backend);
  la conciliación es la consulta SQL de la tarea de QA §3.

## Pendientes / deuda

- **Frontend pendiente:** alerta tras el consumo, indicador global y diálogo de stock insuficiente.
- **Endpoint de conciliación en perfil dev:** no implementado.
- **Decidir `500` contra `409` en CP-20** (hoy se mantiene `409`).
- **`detalle` estructurado del `409`** si el frontend lo necesita.
- **CP-23 (rendimiento)** solo se midió en secuencial y concurrente tras el fix de D-01.
- **Zona horaria (H-3):** observación no reproducida; vigilar.
- **PR final a `main`:** #177 (sin mergear).
