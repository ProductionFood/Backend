# HU-22 — Consumo de materia prima · Manual de consumo para el frontend

Contrato práctico para consultar el consumo teórico, registrar el consumo real, corregirlo y
listar los consumos de un plan. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-22-consumo-materia-prima.md`](../Hechos/HU-22-consumo-materia-prima.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** el listado lo leen `ADMIN`, `PRODUCCION` y `CONSULTA`; el consumo teórico, el
  registro y la corrección quedan para `ADMIN` y `PRODUCCION`. `VENTAS` y `COMPRAS` no acceden.
- **Dependencias:** el plan y sus estados son de HU-20; la línea y el consumo teórico estimado
  son de HU-21; la receta es de HU-12; el inventario de materia prima es de HU-09/HU-23. Registrar
  un consumo **descuenta stock** en la misma transacción, no es un apunte informativo.

> **Estado:** backend verificado con QA ciego el 2026-11-05 (24/24 CP, 20/20 SEC y matriz de
> roles 20/20). El consumo desde Angular sigue pendiente. Los ids, nombres, recetas y saldos de
> los ejemplos dependen de la base; se documentan los del smoke.

---

## 1. Autenticación

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "correo": "admin@productionfood.local", "password": "Admin123*" }
```

```json
{ "token": "eyJhbGciOiJIUzM4NCJ9...",
  "tipo": "Bearer",
  "expiraEn": 3600,
  "usuario": { "idUsuario": 1, "nombre": "Administrador Inicial",
               "correo": "admin@productionfood.local", "rol": "ADMIN" } }
```

El frontend guarda el token y lo envía en cada llamada. Un `401 NO_AUTENTICADO` significa token
ausente, malformado o expirado: forzar el login de nuevo.

| Endpoint | ADMIN | PRODUCCION | CONSULTA | VENTAS | COMPRAS |
|---|---|---|---|---|---|
| `GET /planes-produccion/{idPlan}/consumos` | sí | sí | sí | 403 | 403 |
| `GET /detalles-plan/{idDetallePlan}/consumo-teorico` | sí | sí | 403 | 403 | 403 |
| `POST /detalles-plan/{idDetallePlan}/consumos` | sí | sí | 403 | 403 | 403 |
| `PUT /consumos/{id}` | sí | sí | 403 | 403 | 403 |

Ocultar una acción en la pantalla no sustituye al `403`, que el backend aplica igual. `CONSULTA`
puede ver el listado de consumos, pero no el teórico ni las escrituras.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/planes-produccion/{idPlan}/consumos` | ADMIN / PRODUCCION / CONSULTA | 200, 400, 401, 403, 404 |
| GET | `/detalles-plan/{idDetallePlan}/consumo-teorico` | ADMIN / PRODUCCION | 200, 401, 403, 404 |
| POST | `/detalles-plan/{idDetallePlan}/consumos` | ADMIN / PRODUCCION | 201, 400, 401, 403, 404, 409 |
| PUT | `/consumos/{id}` | ADMIN / PRODUCCION | 200, 400, 401, 403, 404, 409 |

El detalle es un subrecurso del plan: `{idPlan}` debe existir y `{idDetallePlan}` debe existir.
Si el plan no existe, el listado responde `404 PLAN_INEXISTENTE`; si la línea no existe, el
teórico y el registro responden `404 DETALLE_PLAN_INEXISTENTE`; si el consumo no existe, la
corrección responde `404 RECURSO_NO_ENCONTRADO`.

### 2.1. Listar los consumos del plan

```http
GET /api/v1/planes-produccion/141/consumos?page=0&size=20&sort=materiaPrima,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `page` | entero ≥ 0 | `0` | Base 0: la primera página es `0` |
| `size` | entero 1…100 | `20` | Un valor fuera de rango → `400 PARAMETRO_INVALIDO` |
| `sort` | `campo,dirección` | `producto,asc` | Campos válidos: `producto`, `materiaPrima`, `cantidadTeorica`, `cantidadReal`, `fechaRegistro`. Otro campo → `400 CAMPO_ORDEN_INVALIDO` |

```json
// 200 OK
{ "content": [
    { "idConsumo": 97,
      "idDetallePlan": 20,
      "producto": "Pan de prueba",
      "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
      "cantidadTeorica": 0.250,
      "cantidadReal": 0.300,
      "eficiencia": 83.33,
      "desviacionPorcentaje": 20.00,
      "fueraDeReceta": false,
      "fechaRegistro": "2026-11-05T13:51:08" }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true }
```

- Cada fila trae el producto y la **abreviatura** de la unidad de la materia prima para pintar la
  tabla sin una segunda llamada.
- `cantidadTeorica` y `cantidadReal` viajan con tres decimales; `eficiencia` y
  `desviacionPorcentaje` con dos.
- `eficiencia` puede ser `null` y `desviacionPorcentaje` puede ser `null`: son casos válidos
  (ver §5).
- **El listado no incluye `inventario`.** El efecto en stock solo llega en la respuesta del
  registro y de la corrección.
- El listado no exige un estado concreto: un plan `COMPLETADO` o `CANCELADO` se puede consultar.
- Un plan sin consumos devuelve `content: []` con `totalElements: 0`; no es un error.

### 2.2. Consultar el consumo teórico de una línea

```http
GET /api/v1/detalles-plan/20/consumo-teorico
```

```json
// 200 OK
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

- `recetaRinde` es el rendimiento de **una tanda completa** de la receta, no de una unidad.
- `factor` es la cantidad de tandas que cubren lo planificado:
  `cantidadPlanificada / recetaRinde`, con tres decimales.
- `porTanda` es lo que consume una tanda; `teorico` es esa cantidad escalada a lo planificado en
  la línea. `disponible` es el stock actual de la materia prima.
- El **teórico lo calcula el servidor** con la receta y la cantidad planificada. El cliente no
  envía ninguna cantidad.
- Un producto sin receta devuelve `ingredientes: []`, `recetaRinde: null` y `factor: null`; no es
  un error. La interfaz debe avisar que no hay consumo teórico y permitir registrar los
  ingredientes manualmente.
- `disponible` depende de la base; en el ejemplo es el valor del smoke.

### 2.3. Registrar el consumo real

```http
POST /api/v1/detalles-plan/20/consumos
Content-Type: application/json

{ "idMateriaPrima": 1, "cantidadReal": 0.300 }
```

`201 Created` + `Location: /api/v1/consumos/97`:

```json
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

**Reglas del registro:**

- **Solo con el plan `EN_PROCESO`.** En `PLANIFICADO`, `COMPLETADO` o `CANCELADO` responde
  `409 PLAN_NO_EN_PROCESO`.
- **La línea debe existir.** Si no, `404 DETALLE_PLAN_INEXISTENTE`.
- **`idMateriaPrima` debe existir y estar activa.** Si no, `409 MATERIA_PRIMA_INEXISTENTE`.
- **`cantidadReal` es obligatoria y no puede ser negativa.** El cero es válido; un negativo
  responde `400 CANTIDAD_INVALIDA`.
- **La materia prima no puede repetirse en la misma línea.** Un segundo `POST` con la misma
  materia prima responde `409 CONSUMO_DUPLICADO`. Para cambiar la cantidad se corrige el consumo
  existente con el `PUT`.
- **La cantidad teórica no se envía.** Si el JSON incluye `cantidadTeorica`, se ignora y la
  respuesta usa la de la receta (R-01, CP-02).
- **Registrar descuenta inventario.** La respuesta trae `inventario` con los saldos anterior y
  posterior. Si el stock no alcanza, responde `409 STOCK_INSUFICIENTE` y no deja el consumo
  registrado.
- **Un consumo real de cero es válido.** No mueve inventario y la respuesta **omite**
  `inventario`; la eficiencia viaja `null` y la desviación `-100.00` (ver §5).

**Consumo real cero (ejemplo real de la línea 18):**

```json
// petición
{ "idMateriaPrima": 1, "cantidadReal": 0 }

// 201 Created
{ "idConsumo": 98,
  "idDetallePlan": 18,
  "producto": "Pan de prueba",
  "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
  "cantidadTeorica": 0.250,
  "cantidadReal": 0.000,
  "eficiencia": null,
  "desviacionPorcentaje": -100.00,
  "fueraDeReceta": false,
  "fechaRegistro": "2026-11-05T13:51:20" }
```

**Materia prima fuera de receta (ejemplo real, materia prima 4):**

```json
// petición
{ "idMateriaPrima": 4, "cantidadReal": 0.500 }

// 201 Created
{ "idConsumo": 101,
  "idDetallePlan": 19,
  "producto": "Pan de prueba",
  "materiaPrima": { "idMateriaPrima": 4, "nombre": "Mantequilla", "unidad": "kg" },
  "cantidadTeorica": 0.000,
  "cantidadReal": 0.500,
  "eficiencia": 0.00,
  "desviacionPorcentaje": null,
  "fueraDeReceta": true,
  "fechaRegistro": "2026-11-05T13:51:32",
  "inventario": { "anterior": 2.00, "posterior": 1.50 } }
```

### 2.4. Corregir la cantidad real

```http
PUT /api/v1/consumos/97
Content-Type: application/json

{ "cantidadReal": 0.500 }
```

`200 OK`:

```json
{ "idConsumo": 97,
  "idDetallePlan": 20,
  "producto": "Pan de prueba",
  "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
  "cantidadTeorica": 0.250,
  "cantidadReal": 0.500,
  "eficiencia": 50.00,
  "desviacionPorcentaje": 100.00,
  "fueraDeReceta": false,
  "fechaRegistro": "2026-11-05T13:51:08",
  "inventario": { "anterior": 33.03, "posterior": 32.83 } }
```

Corrección a la baja con `{ "cantidadReal": 0.100 }`:

```json
// 200 OK (fragmento)
{ "cantidadTeorica": 0.250, "cantidadReal": 0.100,
  "eficiencia": 250.00, "desviacionPorcentaje": -60.00,
  "fueraDeReceta": false,
  "inventario": { "anterior": 32.83, "posterior": 33.23 } }
```

- **Solo se corrige con el plan del consumo en `EN_PROCESO`.** Otro estado responde
  `409 PLAN_NO_EN_PROCESO`.
- **El ajuste es por diferencia**, no revertir todo y volver a descontar. Al alza se descuenta el
  excedente con `SALIDA_PRODUCCION`; a la baja se devuelve la diferencia con `AJUSTE_POSITIVO`.
- **La cantidad teórica guardada no se recalcula.** Se conserva la que quedó al registrar.
- **`cantidadReal` puede ser cero.** Es válido; si la diferencia redondea a `0.00` no hay
  movimiento y la respuesta omite `inventario`.
- **Si el consumo no existe**, `404 RECURSO_NO_ENCONTRADO`.
- **Si el ajuste al alza agota el stock**, `409 STOCK_INSUFICIENTE` y no se actualiza el consumo.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-11-05T13:54:12.123", "status": 400,
  "error": "Bad Request", "code": "CANTIDAD_INVALIDA",
  "message": "La cantidad real no puede ser negativa.",
  "path": "/api/v1/detalles-plan/20/consumos", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Falta `idMateriaPrima` o `cantidadReal`, o el cuerpo no es JSON | Pintar `fieldErrors[]` junto a cada campo |
| `CANTIDAD_INVALIDA` | 400 | `cantidadReal` negativa | Corregir el campo; el cero sí es válido |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100 o id de ruta no numérico | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo o una dirección fuera de la lista blanca | Usar solo los cinco campos permitidos, con `asc`/`desc` |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |
| `DETALLE_PLAN_INEXISTENTE` | 404 | La línea del plan no existe | Refrescar el detalle del plan (HU-21) |
| `PLAN_INEXISTENTE` | 404 | El plan del listado no existe | Volver al listado de planes (HU-20) |
| `RECURSO_NO_ENCONTRADO` | 404 | El consumo a corregir no existe | Refrescar el listado de consumos |
| `CONSUMO_DUPLICADO` | 409 | Esa materia prima ya tiene consumo en la línea | No ofrecer un segundo alta; llevar a la corrección del consumo existente |
| `PLAN_NO_EN_PROCESO` | 409 | Registro o corrección con un plan que no está `EN_PROCESO` | Refrescar el estado y pasar a modo lectura |
| `MATERIA_PRIMA_INEXISTENTE` | 409 | La materia prima no existe o está inactiva | Recargar el selector de materias primas |
| `STOCK_INSUFICIENTE` | 409 | No hay inventario suficiente o la materia prima no tiene fila de inventario | Mostrar el `disponible` y no reintentar hasta reponer stock |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. El `Content-Type` de las
respuestas es `application/json` sin `charset=UTF-8`: si el cliente necesita el charset
explícito, lo aporta en la petición y decodifica como UTF-8.

---

## 4. Casos límite que el frontend debe manejar

| Caso | Qué llega | Qué hacer |
|---|---|---|
| **Eficiencia nula** | `eficiencia: null` con `cantidadReal: 0.000` | Mostrar `—` con un tooltip («No se puede calcular: no se registró consumo»). **Nunca** `NaN`, `Infinity` ni `0` |
| **Desviación nula** | `desviacionPorcentaje: null` con `cantidadTeorica: 0.000` | Mostrar `—`; no hay base para medir la desviación |
| **Fuera de receta** | `fueraDeReceta: true` y `cantidadTeorica: 0.000` | Marcar la fila como consumo fuera de receta; con real positivo la eficiencia es `0.00` |
| **Cantidad real cero** | `cantidadReal: 0.000`, sin `inventario` | Es un registro válido: no se usó ese ingrediente. No bloquear el envío |
| **Inventario ausente** | La respuesta no trae la clave `inventario` | Interpretar «sin movimiento»; no mostrar saldos inventados ni `undefined` |
| **Redondeo** | El consumo guarda 3 decimales; el inventario, 2 | Enviar hasta 3 decimales; **solo** el inventario se redondea a 2 con `HALF_UP`. No redondear la cantidad real antes de enviarla |
| **Plan no `EN_PROCESO`** | `409 PLAN_NO_EN_PROCESO` en `POST`/`PUT` | Poner la pantalla en modo lectura y refrescar el estado del plan |
| **Stock insuficiente** | `409 STOCK_INSUFICIENTE` | Mostrar el `disponible` del teórico junto al ingrediente y deshabilitar el registro hasta reponer |
| **Producto sin receta** | `ingredientes: []`, `recetaRinde: null`, `factor: null` | Avisar que no hay consumo teórico y permitir registrar materias primas manualmente |
| **Materia prima repetida** | `409 CONSUMO_DUPLICADO` | No permitir un segundo alta; abrir la corrección del consumo existente |
| **Diferencia que redondea a cero** | `PUT` con una diferencia menor que `0.005` | La respuesta puede omitir `inventario`: no hubo movimiento real |

**`cantidadTeorica` no se envía nunca.** No es un campo del formulario ni del cuerpo del `POST`:
la calcula el servidor con la receta (R-01). Si el cliente la incluye, el servidor la ignora y la
respuesta usa el valor de la receta; no debe usarse para calcular la eficiencia en el cliente.

---

## 5. Pantalla y estados

| Estado | Qué se muestra |
|---|---|
| Cargando teóricos | `MatProgressBar` en el diálogo y la tabla deshabilitada |
| Tabla sin consumos | «Este plan todavía no tiene consumos» + botón para registrar (si está `EN_PROCESO`) |
| Producto sin receta | «Sin receta: no hay consumo teórico. Registre los ingredientes manualmente.» |
| Eficiencia nula | `—` con tooltip; nunca `NaN` ni `Infinity` |
| Consumo fuera de receta | Chip ámbar y el teórico `0.000` |
| Stock insuficiente | Rojo junto al ingrediente, con el `disponible`; botón deshabilitado |
| Plan no `EN_PROCESO` | Tabla en modo lectura, sin acciones de registro ni corrección |
| Registrando o corrigiendo | Botón con spinner y deshabilitado mientras la petición está en vuelo |
| Error de red / 5xx | Mensaje + «Reintentar» conservando la página y el formulario |
| 401 | Redirigir al login |
| 403 | Ocultar la acción y mostrar «No tienes permiso para esta operación» |

Flujo mínimo en Angular: al abrir el diálogo, consultar el teórico de la línea, prellenar cada
`cantidadReal` con el `teorico` y mostrar el `disponible`; al confirmar, enviar un `POST` por cada
ingrediente pendiente y refrescar el listado con un solo `GET` al final. Tras un `201`, usar el
`idConsumo` del `Location` o de la respuesta para futuras correcciones. Tras un `PUT`, actualizar
la fila con la respuesta, que ya trae la eficiencia, la desviación y el ajuste de inventario.

---

## 6. Qué NO está en el contrato

- **No hay `GET` de un consumo individual.** El consumo se obtiene del listado paginado por plan;
  las respuestas de escritura sí traen el consumo completo.
- **No hay endpoint para eliminar un consumo.** El borrado de una línea del plan se bloquea
  cuando tiene consumos (HU-21 R-08); un consumo se corrige, no se elimina.
- **No hay endpoint de kardex en esta historia.** El movimiento se consulta con HU-10/HU-23.
- **No se puede cambiar la materia prima de un consumo.** Se corrige la cantidad de la misma
  materia prima; para otra materia prima se registra un consumo nuevo si no está repetida.
- **No hay contadores agregados** (eficiencia media del plan, total consumido): se calculan en el
  cliente o quedan para HU-26.
- **No hay historial de correcciones.** La bitácora de HU-04 registra las operaciones, pero no se
  expone como historial por consumo.

---

## 7. Limitaciones conocidas

- **El consumo real desde el frontend sigue pendiente.** El backend está verificado; este manual
  es el contrato para Angular.
- **La eficiencia sola es contraintuitiva.** Consumir menos de lo teórico da más de 100 %; por
  eso el contrato expone también `desviacionPorcentaje`, que es la cifra legible. Mostrar ambas.
- **El teórico se calcula contra lo planificado, no contra lo producido.** Una eficiencia baja
  puede significar que se produjo menos, no que se desperdició materia prima; HU-26 debe mostrar
  ambas lecturas.
- **Registrar descuenta inventario.** No es un apunte: si el stock no alcanza, el consumo no se
  registra. La interfaz debe advertirlo antes de confirmar.
- **El listado no trae `inventario`.** El efecto en stock solo se ve al registrar o corregir.
- **Backend verificado con QA ciego el 2026-11-05.** Cualquier cambio de contrato actualiza este
  manual en el mismo commit.
