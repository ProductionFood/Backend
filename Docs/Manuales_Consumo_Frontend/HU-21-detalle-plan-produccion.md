# HU-21 — Productos del plan de producción · Manual de consumo para el frontend

Contrato práctico para listar las líneas de un plan, agregar productos, editar lo planificado,
registrar lo producido y quitar una línea. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-21-detalle-plan-produccion.md`](../Hechos/HU-21-detalle-plan-produccion.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura (listado) para `ADMIN`, `PRODUCCION` y `CONSULTA`; escritura (alta, edición
  de lo planificado, registro de producción y borrado) solo para `ADMIN` y `PRODUCCION`.
  `VENTAS` y `COMPRAS` no participan en la planificación de la producción.
- **Dependencias:** el plan y sus estados son de HU-20; el consumo real de materia prima es de
  HU-22 y el stock de producto terminado de HU-19/HU-23. Esta historia solo gestiona las líneas.

> **Estado:** backend verificado con QA ciego el 2026-11-05 (23/23 CP, 25/25 SEC y matriz de
> roles 25/25). El consumo desde Angular sigue pendiente. Los ids, nombres, recetas y productos
> dependen de la base.

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
| `GET /planes-produccion/{idPlan}/detalles` | sí | sí | sí | 403 | 403 |
| `POST /planes-produccion/{idPlan}/detalles` | sí | sí | 403 | 403 | 403 |
| `PUT /planes-produccion/{idPlan}/detalles/{idDetalle}` | sí | sí | 403 | 403 | 403 |
| `PATCH /planes-produccion/{idPlan}/detalles/{idDetalle}/producido` | sí | sí | 403 | 403 | 403 |
| `DELETE /planes-produccion/{idPlan}/detalles/{idDetalle}` | sí | sí | 403 | 403 | 403 |

Ocultar una acción en la pantalla no sustituye al `403`, que el backend aplica igual. `CONSULTA`
solo lee; `VENTAS` y `COMPRAS` no acceden al módulo.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/planes-produccion/{idPlan}/detalles` | ADMIN / PRODUCCION / CONSULTA | 200, 400, 401, 403, 404 |
| POST | `/planes-produccion/{idPlan}/detalles` | ADMIN / PRODUCCION | 201, 400, 401, 403, 404, 409 |
| PUT | `/planes-produccion/{idPlan}/detalles/{idDetalle}` | ADMIN / PRODUCCION | 200, 400, 401, 403, 404, 409 |
| PATCH | `/planes-produccion/{idPlan}/detalles/{idDetalle}/producido` | ADMIN / PRODUCCION | 200, 400, 401, 403, 404, 409 |
| DELETE | `/planes-produccion/{idPlan}/detalles/{idDetalle}` | ADMIN / PRODUCCION | 204, 400, 401, 403, 404, 409 |

El detalle es un subrecurso del plan: `{idPlan}` debe existir y `{idDetalle}` debe pertenecer a
ese plan. Si el plan no existe, cualquier operación responde `404 PLAN_INEXISTENTE`; si la línea
no existe o es de otro plan, `404 RECURSO_NO_ENCONTRADO`.

### 2.1. Listar los productos del plan

```http
GET /api/v1/planes-produccion/9/detalles?page=0&size=20&sort=producto,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `page` | entero ≥ 0 | `0` | Base 0: la primera página es `0` |
| `size` | entero 1…100 | `20` | Un valor fuera de rango → `400 PARAMETRO_INVALIDO` |
| `sort` | `campo,dirección` | `producto,asc` | Campos válidos: `producto`, `cantidadPlanificada`, `avance`. Dirección `asc`/`desc`. Otro campo → `400 CAMPO_ORDEN_INVALIDO` |

```json
// 200 OK
{ "content": [
    { "idDetallePlan": 112,
      "producto": { "idProducto": 2, "nombre": "Pan francés", "unidad": "kg" },
      "cantidadPlanificada": 90.00, "cantidadProducida": 0.00, "avance": 0.00,
      "tieneReceta": true,
      "consumoTeoricoEstimado": [
        { "materiaPrima": "Harina de trigo premium", "cantidad": 14.516, "unidad": "kg" },
        { "materiaPrima": "Levadura", "cantidad": 0.145, "unidad": "L" }
      ] }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true }
```

- `producto` se embebe con el nombre y la **abreviatura** de la unidad para pintar la fila sin
  una segunda llamada.
- `cantidadPlanificada` y `cantidadProducida` viajan con dos decimales.
- `avance` es un porcentaje con dos decimales y **puede superar 100.00**; no es un error (R-04).
- `tieneReceta = false` significa que el producto se puede planificar pero no tiene consumo
  teórico; en ese caso `consumoTeoricoEstimado` llega como **lista vacía**, nunca `null` (R-06).
- El listado no exige un estado concreto: un plan `COMPLETADO` o `CANCELADO` se puede consultar.

### 2.2. Agregar un producto

```http
POST /api/v1/planes-produccion/9/detalles
Content-Type: application/json

{ "idProducto": 2, "cantidadPlanificada": 90.00 }
```

`201 Created` + `Location: /api/v1/planes-produccion/9/detalles/112`:

```json
{ "idDetallePlan": 112,
  "producto": { "idProducto": 2, "nombre": "Pan francés", "unidad": "kg" },
  "cantidadPlanificada": 90.00, "cantidadProducida": 0.00, "avance": 0.00,
  "tieneReceta": true,
  "consumoTeoricoEstimado": [
    { "materiaPrima": "Harina de trigo premium", "cantidad": 14.516, "unidad": "kg" },
    { "materiaPrima": "Levadura", "cantidad": 0.145, "unidad": "L" }
  ] }
```

**Reglas del alta:**

- **Solo con el plan `PLANIFICADO`.** En `EN_PROCESO`, `COMPLETADO` o `CANCELADO` responde
  `409 PLAN_NO_EDITABLE`: el plan ya iniciado comprometió sus líneas.
- **`idProducto` es obligatorio y debe existir y estar activo.** Si no, `409
  PRODUCTO_INEXISTENTE`.
- **El producto no puede repetirse en el plan.** Si ya tiene una línea, `409
  PRODUCTO_DUPLICADO`; para producir más se edita la cantidad de la línea existente (R-05).
- **`cantidadPlanificada` es obligatoria y mayor que cero.** Cero o negativo responde
  `400 CANTIDAD_INVALIDA`. Un valor como `0.001` se redondea a `0.00` y también es inválido.
- **La línea nace con `cantidadProducida = 0.00` y `avance = 0.00`.** La base aplica su
  `DEFAULT`; no se envía la cantidad producida en el alta.
- **`consumoTeoricoEstimado` es informativo.** Se calcula con la receta del producto; no
  descuenta inventario ni sustituye al consumo real de HU-22.
- **No toca inventario ni lotes** (R-07).

### 2.3. Editar la cantidad planificada

```http
PUT /api/v1/planes-produccion/9/detalles/112
Content-Type: application/json

{ "cantidadPlanificada": 95.00 }
```

`200 OK` con la línea releída de la base y el consumo teórico recalculado sobre la nueva
cantidad.

- **Solo se edita mientras el plan está `PLANIFICADO`.** Cualquier otro estado responde
  `409 PLAN_NO_EDITABLE`; editar el objetivo después de producir haría que la eficiencia de
  HU-26 siempre saliera perfecta (R-02).
- **La cantidad es obligatoria y mayor que cero.** Si no, `400 CANTIDAD_INVALIDA`.
- **La línea debe pertenecer al plan de la ruta.** Si no existe o es de otro plan, `404
  RECURSO_NO_ENCONTRADO`.
- **No se puede cambiar el producto de una línea.** Para otro producto se quita la línea y se
  agrega la nueva.
- La cantidad producida no se modifica aquí; su endpoint es el `PATCH .../producido`.

### 2.4. Registrar la cantidad producida

```http
PATCH /api/v1/planes-produccion/9/detalles/112/producido
Content-Type: application/json

{ "cantidadProducida": 60.00 }
```

`200 OK`:

```json
{ "idDetallePlan": 112,
  "producto": { "idProducto": 2, "nombre": "Pan francés", "unidad": "kg" },
  "cantidadPlanificada": 90.00, "cantidadProducida": 60.00, "avance": 66.67,
  "tieneReceta": true,
  "consumoTeoricoEstimado": [ /* ... */ ] }
```

Con `{ "cantidadProducida": 100.00 }` sobre la misma línea de 90, `avance` responde `111.11`.
**Producir de más es válido** (R-04): la interfaz lo señala, no lo impide. `{ "cantidadProducida":
0 }` también es válido y deja `avance: 0.00` (CP-11).

- **Solo con el plan `EN_PROCESO`.** Un plan `PLANIFICADO` responde `409 PLAN_NO_EN_PROCESO`
  —todavía no se ha empezado— y uno `COMPLETADO` o `CANCELADO`, el mismo código —el plan ya
  cerró— (R-03).
- **La cantidad no puede ser negativa.** Si lo es, `400 CANTIDAD_INVALIDA`. El cero sí se
  acepta.
- **No hay tope superior.** El avance puede superar el 100 %.
- **No crea el lote ni suma stock (R-07).** Actualizar `cantidad_producida` es solo un registro
  de avance; el lote de producto terminado se registra aparte en HU-19. Avisar en la interfaz
  que registrar producción **no** deja el producto a la venta.
- **La línea debe pertenecer al plan de la ruta**; si no, `404 RECURSO_NO_ENCONTRADO`.

### 2.5. Quitar un producto del plan

```http
DELETE /api/v1/planes-produccion/9/detalles/112
```

`204 No Content` sin cuerpo.

- **Solo se permite con el plan `PLANIFICADO`.** En `EN_PROCESO`, `COMPLETADO` o `CANCELADO`
  responde `409 PLAN_NO_EDITABLE`: quitar una línea de un plan ya iniciado altera el compromiso
  y la eficiencia histórica.
- **La línea no puede tener consumos registrados.** Si HU-22 ya registró consumo contra ella,
  responde `409 DETALLE_CON_CONSUMOS` y no borra (R-08). Primero hay que resolver el consumo.
- **La línea debe pertenecer al plan de la ruta**; si no existe o es de otro plan, `404
  RECURSO_NO_ENCONTRADO`.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-11-05T11:20:32.123", "status": 409,
  "error": "Conflict", "code": "PLAN_NO_EDITABLE",
  "message": "El plan de producción con id 9 no es editable: solo los planes PLANIFICADO admiten cambios en su detalle.",
  "path": "/api/v1/planes-produccion/9/detalles", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Falta `idProducto` o una cantidad, o el cuerpo no es JSON | Pintar `fieldErrors[]` junto a cada campo |
| `CANTIDAD_INVALIDA` | 400 | Cantidad planificada ≤ 0 (o que redondea a cero) o cantidad producida negativa | Corregir el campo; usar el mínimo `0.01` para planificar |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100 o id de ruta no numérico | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo o una dirección fuera de la lista blanca | Usar solo `producto`, `cantidadPlanificada` o `avance`, con `asc`/`desc` |
| `PLAN_INEXISTENTE` | 404 | El plan de la ruta no existe | Volver al listado de planes (HU-20) |
| `RECURSO_NO_ENCONTRADO` | 404 | La línea no existe o pertenece a otro plan | Refrescar el detalle del plan |
| `PLAN_NO_EDITABLE` | 409 | Alta, edición o borrado con un plan que no está `PLANIFICADO` | Refrescar el plan y mostrar solo las acciones válidas |
| `PLAN_NO_EN_PROCESO` | 409 | Registro de producción con un plan que no está `EN_PROCESO` | Refrescar el estado; para producir primero hay que iniciar el plan |
| `PRODUCTO_INEXISTENTE` | 409 | El producto no existe o está inactivo | Recargar el selector de productos |
| `PRODUCTO_DUPLICADO` | 409 | El producto ya tiene una línea en el plan | Llevar a la línea existente para editar su cantidad (R-05) |
| `DETALLE_CON_CONSUMOS` | 409 | La línea tiene consumos de materia prima registrados | No ofrecer borrado; primero resolver el consumo (HU-22) |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. El `Content-Type` de las
respuestas es `application/json` sin `charset=UTF-8`: si el cliente necesita el charset
explícito, lo aporta en la petición y decodifica como UTF-8.

---

## 4. Flujo planificado → producido

```
PLANIFICADO                         EN_PROCESO                        COMPLETADO
POST   agregar línea                PATCH .../producido               (sin escrituras)
PUT    editar cantidad planificada  registrar avance                  solo lectura
DELETE quitar línea                 (sin editar lo planificado)
```

1. **Planificar:** con el plan `PLANIFICADO`, `POST` agrega productos y `PUT` ajusta la cantidad
   planificada. `DELETE` quita líneas mientras no tengan consumos.
2. **Iniciar:** el cambio a `EN_PROCESO` es de HU-20 y exige al menos una línea. Desde ese
   momento, `PUT` y `DELETE` responden `409 PLAN_NO_EDITABLE` y solo se puede registrar avance
   con `PATCH .../producido`.
3. **Producir:** cada `PATCH` reemplaza `cantidadProducida` —no la suma— y recalcula `avance`.
   El cero es válido y no hay tope superior.
4. **Completar o cancelar:** el plan pasa a un estado terminal con HU-20. El detalle sigue
   consultable, pero ya no admite escrituras.

**Ninguna de estas operaciones mueve stock.** El consumo real y el inventario se afectan en
HU-22 y HU-23; el lote de producto terminado se registra en HU-19.

---

## 5. Pantalla y formulario

| Campo | Regla | Mensaje / código |
|---|---|---|
| `producto` | Requerido, activo y no repetido en el plan | `PRODUCTO_INEXISTENTE` / `PRODUCTO_DUPLICADO` |
| `cantidadPlanificada` | Requerida, mayor que 0, hasta 2 decimales | "La cantidad planificada debe ser mayor que cero" / `CANTIDAD_INVALIDA` |
| `cantidadProducida` | Requerida en el registro de avance, ≥ 0, sin tope | "La cantidad producida no puede ser negativa" / `CANTIDAD_INVALIDA` |

- **Acciones según el estado del plan.** Con `PLANIFICADO` se muestra "Agregar producto", "Editar
  cantidad" y "Quitar"; con `EN_PROCESO` se muestra solo "Registrar producción"; con
  `COMPLETADO` o `CANCELADO` todo queda en solo lectura.
- **El aviso de R-07 es obligatorio en la UI de producción:** registrar avance **no** crea el
  lote ni deja stock vendible; hay que registrar el lote en HU-19. Un tooltip o texto junto al
  botón "Registrar producción" evita que el usuario crea que el producto ya está en inventario.
- **Aviso de producto sin receta.** Antes de guardar, si el producto no tiene receta se advierte
  que se puede planificar pero **no tendrá consumo teórico** y quedará fuera del análisis de
  eficiencia (R-06). No se oculta el hueco.
- **Avance mayor que 100 % no es un error.** Mostrarlo como advertencia visual (por ejemplo, en
  ámbar) y no bloquear el registro; producir de más es un dato real (R-04).
- **El avance se refresca con la respuesta del `PATCH`.** No hace falta una segunda llamada: la
  respuesta trae la línea completa, incluido `consumoTeoricoEstimado`.
- **El botón "Quitar" se oculta o deshabilita fuera de `PLANIFICADO`.** Un `409
  DETALLE_CON_CONSUMOS` se muestra junto a la fila con la instrucción de resolver el consumo
  primero.
- **La tabla se ordena por `producto,asc` por defecto**, con `size=20`. El selector de orden solo
  ofrece las tres columnas de la lista blanca; no enviar un campo libre.
- **Estados de la pantalla:**

| Estado | Qué se muestra |
|---|---|
| Cargando | Esqueleto de tabla + `MatProgressBar` |
| Plan sin líneas | "Este plan no tiene productos" + botón para agregar (si está `PLANIFICADO`) |
| Plan en proceso | Tabla con solo la acción de registrar producción |
| Plan cerrado | Tabla en solo lectura |
| Error de red / 5xx | Mensaje + "Reintentar" conservando la página |
| 401 | Redirigir al login |
| 403 | Ocultar la acción y mostrar "No tienes permiso para esta operación" |

Flujo mínimo en Angular: cargar el detalle al entrar con `page=0&size=20&sort=producto,asc`,
cargar el catálogo de productos solo al abrir el alta, deshabilitar el botón de envío mientras la
petición está en vuelo y, tras un `201`, insertar o recargar la fila. Tras un `DELETE` con `204`,
quitar la fila de la tabla sin esperar cuerpo.

---

## 6. Qué NO está en el contrato

- **No hay `GET` de una sola línea.** El detalle se obtiene del listado paginado por plan. La
  respuesta de las escrituras sí trae la línea completa.
- **No se puede cambiar el producto de una línea.** Se quita y se agrega otra.
- **No hay endpoint de consumo real ni de inventario.** El consumo teórico es informativo; el
  consumo real es de HU-22 y el stock de HU-19/HU-23.
- **No hay endpoint para crear el lote de producto terminado.** Es de HU-19 y es una acción
  separada de registrar producción (R-07).
- **No hay contadores agregados** (avance total del plan, productos pendientes): se calculan en
  el cliente con las líneas del listado.
- **No hay historial de cambios de cantidad.** La bitácora de HU-04 registra las operaciones,
  pero no se expone como historial por línea.

---

## 7. Limitaciones conocidas

- **`DELETE` solo funciona con el plan `PLANIFICADO`.** No es un defecto: un plan iniciado ya
  comprometió sus líneas. La única salida es cancelar el plan (HU-20), no borrar sus productos.
- **Registrar producción no crea lote ni stock (R-07).** `cantidadProducida` es un avance
  informativo; el producto no aparece como stock vendible hasta que se registre el lote en
  HU-19. Es el hueco más visible del flujo de producción.
- **El consumo teórico no descuenta inventario.** Solo estima la materia prima necesaria; el
  consumo real y su descuento son de HU-22/HU-23.
- **`DETALLE_CON_CONSUMOS` dependerá de HU-22.** Mientras no exista el registro de consumo, el
  caso se reproduce insertando una fila de `consumo_materia_prima` por SQL.
- **Un producto sin receta no tiene consumo teórico.** `consumoTeoricoEstimado` llega vacío y
  `tieneReceta` en `false`; no es un error (R-06).
- **Backend verificado con QA ciego el 2026-11-05.** Cualquier cambio de contrato actualiza este
  manual en el mismo commit.
