# HU-20 — Planes de producción · Manual de consumo para el frontend

Contrato práctico para listar planes, consultar su detalle, crear la cabecera, editarla y
cambiar el estado. Complementa `../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-20-planes-produccion.md`](../Hechos/HU-20-planes-produccion.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura (listado y detalle) para `ADMIN`, `PRODUCCION` y `CONSULTA`; escritura
  (alta, edición y cambio de estado) solo para `ADMIN` y `PRODUCCION`. `VENTAS` y `COMPRAS` no
  participan en la planificación de la producción.
- **Dependencias:** los productos del plan son de HU-21 y el consumo de materia prima de
  HU-22/HU-23. Esta historia solo gestiona la cabecera.

> **Estado:** backend verificado con QA ciego el 2026-11-05 (23/23 CP, 25/25 SEC y matriz de
> roles 25/25). El consumo desde Angular sigue pendiente. Los ids, nombres y fechas dependen de
> la base.

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
| `GET /planes-produccion` | sí | sí | sí | 403 | 403 |
| `GET /planes-produccion/{id}` | sí | sí | sí | 403 | 403 |
| `POST /planes-produccion` | sí | sí | 403 | 403 | 403 |
| `PUT /planes-produccion/{id}` | sí | sí | 403 | 403 | 403 |
| `PATCH /planes-produccion/{id}/estado` | sí | sí | 403 | 403 | 403 |

Ocultar una acción en la pantalla no sustituye al `403`, que el backend aplica igual. `CONSULTA`
solo lee; `VENTAS` y `COMPRAS` no acceden al módulo.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/planes-produccion` | ADMIN / PRODUCCION / CONSULTA | 200, 400, 401, 403 |
| GET | `/planes-produccion/{id}` | ADMIN / PRODUCCION / CONSULTA | 200, 400, 401, 403, 404 |
| POST | `/planes-produccion` | ADMIN / PRODUCCION | 201, 400, 401, 403, 409 |
| PUT | `/planes-produccion/{id}` | ADMIN / PRODUCCION | 200, 400, 401, 403, 404, 409 |
| PATCH | `/planes-produccion/{id}/estado` | ADMIN / PRODUCCION | 200, 400, 401, 403, 404, 409 |

No hay `DELETE`: responde `405 METODO_NO_PERMITIDO`. Un plan no se borra; se cancela o se
completa.

### 2.1. Listar planes

```http
GET /api/v1/planes-produccion?estado=PLANIFICADO&idUsuario=2&fechaInicio=2026-11-01&fechaFin=2026-11-30&page=0&size=20&sort=fechaProduccion,desc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `estado` | texto | — | `PLANIFICADO`, `EN_PROCESO`, `COMPLETADO` o `CANCELADO`. Un valor no canónico → `400 PARAMETRO_INVALIDO` |
| `idUsuario` | entero | — | Filtra por el responsable del plan |
| `fechaInicio` | fecha ISO | — | Primer día **incluido** del rango sobre `fechaProduccion` |
| `fechaFin` | fecha ISO | — | Último día **incluido** del rango sobre `fechaProduccion` |
| `page` | entero ≥ 0 | `0` | Base 0: la primera página es `0` |
| `size` | entero 1…100 | `20` | Un valor fuera de rango → `400 PARAMETRO_INVALIDO` |
| `sort` | `campo,dirección` | `fechaProduccion,desc` | Campos válidos: `fechaProduccion`, `fechaPlanificacion`, `estado`, `id`. Dirección `asc`/`desc`. Otro campo → `400 CAMPO_ORDEN_INVALIDO` |

Los filtros son opcionales y se combinan. El rango de fechas se aplica a la fecha de
**producción**, no a la de planificación.

```json
// 200 OK
{ "content": [
    { "idPlan": 12,
      "fechaPlanificacion": "2026-11-05", "fechaProduccion": "2026-11-12",
      "estado": "PLANIFICADO",
      "observaciones": "Producción para el pedido #47",
      "responsable": { "idUsuario": 2, "nombre": "Ismael Batalla" },
      "cantidadProductos": 0, "editable": true }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true }
```

- `responsable` se embebe para pintar la lista sin una segunda llamada por fila.
- `cantidadProductos` cuenta las líneas de `detalle_plan_produccion`. Un plan recién creado
  responde `0`; las líneas las agrega HU-21.
- `editable = true` solo mientras el plan está `PLANIFICADO`.
- `observaciones` siempre está presente en el JSON; sin observaciones llega `null`.

### 2.2. Obtener un plan

```http
GET /api/v1/planes-produccion/12
```

Devuelve el mismo objeto que un elemento de `content`. Un id inexistente responde
`404 RECURSO_NO_ENCONTRADO` y uno no numérico, `400 PARAMETRO_INVALIDO`. **No devuelve las
líneas de producto**: eso es HU-21.

### 2.3. Registrar un plan

```http
POST /api/v1/planes-produccion
Content-Type: application/json

{ "fechaPlanificacion": "2026-11-05",
  "fechaProduccion": "2026-11-12",
  "observaciones": "Producción para el pedido #47",
  "idUsuario": 2 }
```

`201 Created` + `Location: /api/v1/planes-produccion/12`:

```json
{ "idPlan": 12,
  "fechaPlanificacion": "2026-11-05", "fechaProduccion": "2026-11-12",
  "estado": "PLANIFICADO", "observaciones": "Producción para el pedido #47",
  "responsable": { "idUsuario": 2, "nombre": "Ismael Batalla" },
  "cantidadProductos": 0, "editable": true }
```

**Reglas del alta:**

- **El plan nace siempre `PLANIFICADO`.** El cuerpo no acepta `estado`; si se envía, se ignora.
- **Las dos fechas son obligatorias.** Omitir cualquiera responde `400 VALIDACION_FALLIDA` con
  el campo en `fieldErrors`.
- **La producción no puede ser anterior a la planificación.** Responde `400 FECHAS_INVALIDAS`.
  Fechas iguales son válidas y una producción futura es lo normal.
- **El responsable es el usuario del token por defecto.** Sin `idUsuario`, el plan queda a
  nombre de quien lo crea.
- **Solo `ADMIN` puede asignar el plan a otro usuario.** Un `PRODUCCION` que envíe `idUsuario`
  es ignorado y el plan queda a su nombre. Un `ADMIN` que designa un usuario inexistente
  recibe `409 USUARIO_INEXISTENTE`.
- **`observaciones` es opcional**, máximo 255 caracteres. Superarlo responde
  `400 VALIDACION_FALLIDA`.
- **No hay campo de estado en el formulario.** Los cambios de estado tienen su propio endpoint.

### 2.4. Editar la cabecera

```http
PUT /api/v1/planes-produccion/12
Content-Type: application/json

{ "fechaPlanificacion": "2026-11-06",
  "fechaProduccion": "2026-11-13",
  "observaciones": "Producción para el pedido #47 y reposición de estante",
  "idUsuario": 2 }
```

`200 OK` con el plan releído de la base. El cuerpo es el mismo del alta: **las dos fechas son
obligatorias** aunque solo se quiera cambiar las observaciones.

- **Solo se edita mientras el plan está `PLANIFICADO`.** Cualquier otro estado responde
  `409 PLAN_NO_EDITABLE`.
- **Sin `idUsuario`, el responsable actual se conserva**, aunque quien edite sea otro usuario.
  Un `ADMIN` que envía `idUsuario` sí puede reasignarlo; si el usuario no existe responde
  `409 USUARIO_INEXISTENTE`.
- **El estado no se modifica aquí.** Usar `PATCH /planes-produccion/{id}/estado`.
- La producción sigue sin poder ser anterior a la planificación (`400 FECHAS_INVALIDAS`).
- Un plan inexistente responde `404 RECURSO_NO_ENCONTRADO`.

### 2.5. Cambiar el estado

```http
PATCH /api/v1/planes-produccion/12/estado
Content-Type: application/json

{ "estado": "EN_PROCESO" }
```

`200 OK` con el plan releído de la base en su estado nuevo:

```json
{ "idPlan": 12, "fechaPlanificacion": "2026-11-05",
  "fechaProduccion": "2026-11-12", "estado": "EN_PROCESO",
  "observaciones": "Producción para el pedido #47",
  "responsable": { "idUsuario": 2, "nombre": "Ismael Batalla" },
  "cantidadProductos": 2, "editable": false }
```

| Estado actual | Destinos permitidos |
|---|---|
| `PLANIFICADO` | `EN_PROCESO`, `CANCELADO` |
| `EN_PROCESO` | `COMPLETADO`, `CANCELADO` |
| `COMPLETADO` | ninguno (terminal) |
| `CANCELADO` | ninguno (terminal) |

- **Para pasar a `EN_PROCESO` el plan debe tener al menos una línea de detalle.** Si no tiene,
  responde `409 PLAN_SIN_PRODUCTOS`. Las líneas las agrega HU-21.
- **Un salto prohibido o una salida de un estado terminal** responde
  `409 TRANSICION_INVALIDA`, con el estado actual y los permitidos en el mensaje.
- **Un literal no canónico** responde `400 VALIDACION_FALLIDA` con los valores admitidos.
- **El estado no se puede reactivar.** `COMPLETADO` y `CANCELADO` son definitivos.
- **Ningún cambio de estado mueve inventario.** El consumo y el stock los registran HU-22 y
  HU-23.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-11-05T09:15:42.123", "status": 409,
  "error": "Conflict", "code": "PLAN_NO_EDITABLE",
  "message": "El plan de producción con id 12 no es editable: solo los planes PLANIFICADO admiten cambios en su cabecera.",
  "path": "/api/v1/planes-produccion/12", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Falta una fecha, las observaciones superan 255 caracteres, el cuerpo no es JSON o el `estado` del `PATCH` no es canónico | Pintar `fieldErrors[]` junto a cada campo o mostrar los estados admitidos |
| `FECHAS_INVALIDAS` | 400 | `fechaProduccion` anterior a `fechaPlanificacion` | Corregir las fechas; iguales sí se permiten |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100, `estado` no canónico en el filtro, fecha mal formada o id de ruta no numérico | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo o una dirección fuera de la lista blanca | Usar solo `fechaProduccion`, `fechaPlanificacion`, `estado` o `id`, con `asc`/`desc` |
| `USUARIO_INEXISTENTE` | 409 | Un `ADMIN` designa un `idUsuario` que no existe | Recargar el selector de usuarios |
| `PLAN_NO_EDITABLE` | 409 | Se intenta editar un plan que no está `PLANIFICADO` (también si cambió de estado durante la edición) | Deshabilitar la edición y refrescar el plan |
| `PLAN_SIN_PRODUCTOS` | 409 | Se intenta iniciar un plan sin líneas de detalle | Llevar a la pantalla de productos del plan (HU-21) |
| `TRANSICION_INVALIDA` | 409 | Salto de estado prohibido, auto-transición o salida de un estado terminal | Refrescar el estado y mostrar solo las acciones válidas |
| `RECURSO_NO_ENCONTRADO` | 404 | El id del plan no existe | Volver al listado |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |
| `METODO_NO_PERMITIDO` | 405 | `DELETE` sobre un plan | No hay borrado |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. El `Content-Type` de las
respuestas es `application/json` sin `charset=UTF-8`: si el cliente necesita el charset
explícito, lo aporta en la petición y decodifica como UTF-8.

---

## 4. Flujos de estado

```
PLANIFICADO ──▶ EN_PROCESO ──▶ COMPLETADO
     │               │
     └───────────────┴───────▶ CANCELADO
```

1. **Alta:** `POST /planes-produccion` → nace `PLANIFICADO`, con `editable = true` y
   `cantidadProductos = 0`.
2. **Productos:** HU-21 agrega las líneas. Mientras no haya líneas, el botón "Iniciar" debe
   estar deshabilitado con el aviso de R-02.
3. **Iniciar:** `PATCH` con `{"estado":"EN_PROCESO"}`. Si el plan no tiene líneas,
   `409 PLAN_SIN_PRODUCTOS`. Al iniciar, `editable` pasa a `false`.
4. **Completar:** `PATCH` con `{"estado":"COMPLETADO"}`. Estado terminal: no admite más cambios.
5. **Cancelar:** `PATCH` con `{"estado":"CANCELADO"}` desde `PLANIFICADO` o `EN_PROCESO`.

**Ninguna de estas transiciones mueve stock.** El inventario se afecta en HU-22 y HU-23, no en
el cambio de estado.

---

## 5. Formulario y pantalla

| Campo | Regla | Mensaje / código |
|---|---|---|
| `fechaPlanificacion` | Requerida, ISO `yyyy-MM-dd` | "La fecha de planificación es obligatoria" |
| `fechaProduccion` | Requerida, ISO `yyyy-MM-dd`, **≥ planificación** | "La fecha de producción es obligatoria" / `FECHAS_INVALIDAS` |
| `responsable` | Requerido y automático; solo `ADMIN` puede cambiarlo | `USUARIO_INEXISTENTE` |
| `observaciones` | Opcional, máximo 255 | "Las observaciones no pueden superar 255 caracteres" |
| `estado` | **No hay campo en el formulario** | Se cambia por las acciones de estado |

- **Responsable de solo lectura para `PRODUCCION`.** El formulario muestra el usuario
  autenticado como responsable y no permite elegir otro; solo `ADMIN` ve un selector. Enviar un
  `idUsuario` ajeno con token de `PRODUCCION` no lo cambia: el backend lo ignora.
- **El `MatDatepicker` de producción lleva `min` = `fechaPlanificacion`**, así se evita el `400`
  antes de enviar.
- **El contador de observaciones se muestra sobre 255.**
- **La edición solo se ofrece con `editable = true`.** Un plan `EN_PROCESO`, `COMPLETADO` o
  `CANCELADO` se muestra en solo lectura.
- **El botón "Iniciar" se deshabilita si `cantidadProductos = 0`**, con el tooltip
  "Agregue productos antes de iniciar la producción" (R-02).
- **Aviso de cancelación (R-06).** El diálogo debe advertir que la materia prima ya consumida
  **no vuelve al inventario** y que, para corregir un consumo mal registrado, hace falta un
  ajuste de inventario. Cancelar significa "no se sigue produciendo", no "no pasó nada".
- **Los errores del servidor se muestran junto al campo** con `fieldErrors[].field`, no en un
  toast genérico.
- **Estados de la pantalla:**

| Estado | Qué se muestra |
|---|---|
| Cargando | Esqueleto de tabla + `MatProgressBar` |
| Vacío sin filtros | "No hay planes de producción" + botón para crear el primero |
| Vacío con filtros | "Ningún plan coincide con los filtros" + botón para limpiarlos |
| Plan sin productos | "Agregue productos antes de iniciar la producción"; "Iniciar" deshabilitado |
| Error de red / 5xx | Mensaje + "Reintentar" conservando filtros |
| 401 | Redirigir al login |
| 403 | Ocultar la acción y mostrar "No tienes permiso para esta operación" |

Flujo mínimo en Angular: listar al entrar con `sort=fechaProduccion,desc`, cargar el catálogo de
usuarios solo para el selector de `ADMIN`, navegar al detalle con `idPlan`, deshabilitar el botón
de envío mientras la petición está en vuelo y, tras un `201`, refrescar el listado. Un plan
recién creado no tiene productos: enlazar a HU-21 en lugar de ofrecer "Iniciar".

---

## 6. Qué NO está en el contrato

- **No existe `DELETE`.** Un plan se cancela o se completa; `DELETE` responde `405`.
- **El detalle no trae las líneas de producto.** Devuelve `cantidadProductos`, pero el listado
  de líneas y su alta son de HU-21.
- **No hay endpoint de consumo de materia prima.** El consumo y el descuento de inventario son
  de HU-22 y HU-23.
- **No hay contadores agregados** (planes por estado, avances): se calculan en el cliente con el
  listado y sus filtros.
- **No hay historial de cambios de estado.** La bitácora de HU-04 registra las operaciones, pero
  no se expone como un historial por plan.

---

## 7. Limitaciones conocidas

- **`cantidadProductos = 0` en todos los planes hasta que exista HU-21.** No es un error: la
  cabecera se crea sin líneas y esas líneas son de la historia siguiente.
- **Un plan sin productos no se puede iniciar.** `409 PLAN_SIN_PRODUCTOS` es el comportamiento
  correcto de R-02, no un defecto.
- **Cancelar no revierte consumos.** El material ya usado no vuelve al inventario; se corrige
  con un ajuste explícito (R-06).
- **Completar no genera lotes de producto terminado.** Producir y registrar el lote en HU-19
  siguen siendo acciones manuales (R-07).
- **Backend verificado con QA ciego el 2026-11-05.** Cualquier cambio de contrato actualiza este
  manual en el mismo commit.
