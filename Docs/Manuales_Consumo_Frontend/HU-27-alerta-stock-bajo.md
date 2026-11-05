# HU-27 — Alerta de stock bajo · Manual de consumo para el frontend

Contrato práctico para listar las materias primas en alerta, pintar el indicador global del menú
y mostrar la sugerencia de compra agrupada por proveedor. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-27-alerta-stock-bajo.md`](../Hechos/HU-27-alerta-stock-bajo.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** listado para `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA`; resumen para cualquier
  rol autenticado; sugerencia de compra solo para `ADMIN` y `COMPRAS`.
- **Dependencias:** el inventario y el stock mínimo de materia prima son de HU-14/HU-15; la compra
  que se prellena con la sugerencia es de HU-13. Esta historia es de **solo lectura**.

> **Estado:** backend verificado con QA ciego (CP-01…CP-26 y SEC-01…SEC-05, con las excepciones de
> la ficha: solo CP-21, que espera a HU-24). El consumo desde Angular sigue pendiente. Los ids, nombres, costos y proveedores
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

| Endpoint | ADMIN | PRODUCCION | COMPRAS | CONSULTA | VENTAS |
|---|---|---|---|---|---|
| `GET /inventario/alertas` | sí | sí | sí | sí | 403 |
| `GET /inventario/alertas/resumen` | sí | sí | sí | sí | sí |
| `GET /inventario/alertas/sugerencia-compra` | sí | 403 | sí | 403 | 403 |

Ocultar una acción en la pantalla no sustituye al `403`, que el backend aplica igual. El menú de
`VENTAS` puede mostrar el indicador global (`/resumen`) pero no la pantalla de alertas.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/inventario/alertas` | ADMIN / PRODUCCION / COMPRAS / CONSULTA | 200, 400, 401, 403 |
| GET | `/inventario/alertas/resumen` | Cualquier rol autenticado | 200, 401 |
| GET | `/inventario/alertas/sugerencia-compra` | ADMIN / COMPRAS | 200, 401, 403 |

Los tres son `GET` sin cuerpo; cualquier otro método responde `405`. **No hay caché**: cada
llamada refleja el inventario actual.

### 2.1. Listar las alertas

```http
GET /api/v1/inventario/alertas?incluirInactivas=false
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `incluirInactivas` | booleano | `false` | `true` añade las materias primas inactivas. Un valor no booleano → `400 PARAMETRO_INVALIDO` |

```json
// 200 OK
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

- **Qué entra:** las materias primas con `cantidadDisponible < stockMinimo` (estricto: con disponible igual al mínimo no hay alerta) y las agotadas.
- **`nivel`:** `AGOTADO` cuando la cantidad es cero y `BAJO` cuando es mayor que cero pero no
  supera el mínimo. Mostrar `AGOTADO` en rojo y `BAJO` en ámbar.
- **Orden:** `AGOTADO` primero y, dentro de cada nivel, mayor `faltante` primero. No hace falta
  reordenar en el cliente.
- **`faltante`** = `stockMinimo − cantidadDisponible`; **`costoReposicion`** = `faltante ×
  costoUnitario`; `costoReposicionTotal` es la suma de los ítems.
- **`resumen`** cuenta los mismos ítems devueltos: `total = agotadas + bajas`.
- **`ultimaActualizacion` es opcional.** Se omite si la materia prima no tiene fila de inventario;
  no esperar la clave ni tratarla como `null`.
- Sin alertas: `items` llega como arreglo vacío, `resumen` en ceros y `costoReposicionTotal` en
  `0`.

### 2.2. Resumen para el indicador global

```http
GET /api/v1/inventario/alertas/resumen
```

```json
// 200 OK
{ "total": 2, "agotadas": 1, "bajas": 1 }
```

- Disponible para **cualquier rol autenticado**, incluido `VENTAS`.
- Cuenta **solo materias primas activas** y no admite parámetros.
- Es liviano: pensado para consultarse al entrar a la aplicación y de forma periódica.

### 2.3. Sugerencia de compra

```http
GET /api/v1/inventario/alertas/sugerencia-compra
```

```json
// 200 OK
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

- **Agrupada por proveedor.** El proveedor es el de la **última compra `RECIBIDA`** de cada
  materia prima.
- **Sin proveedor:** las materias primas sin compras recibidas van en un grupo **sin la clave
  `proveedor`** (el JSON omite los nulos). Mostrarlo como "Sin proveedor asignado" y pedir al
  usuario que elija uno.
- **`cantidadSugerida`** = `faltante × 1,2`, redondeado hacia arriba a 2 decimales (18,60 sale de
  15,50 × 1,2). **`costoEstimado`** = `cantidadSugerida × costoUnitario`; **`totalEstimado`** es
  la suma del grupo.
- **No incluye las de faltante cero** (mínimo cero y agotadas): salen en el listado pero no
  tienen nada que comprar.
- Sin materias primas por reponer, la respuesta es el arreglo vacío `[]`.
- **Solo `ADMIN` y `COMPRAS`.** Para los demás roles, no mostrar el botón.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-11-05T14:05:41.123", "status": 400,
  "error": "Bad Request", "code": "PARAMETRO_INVALIDO",
  "message": "El parámetro 'incluirInactivas' tiene un valor inválido.",
  "path": "/api/v1/inventario/alertas", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `PARAMETRO_INVALIDO` | 400 | `incluirInactivas` con un valor no booleano | Corregir la petición; enviar solo `true` o `false` |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (VENTAS en el listado; cualquiera distinto de ADMIN/COMPRAS en la sugerencia) | Ocultar la acción |

La especificación original nombraba `RANGO_INVALIDO` para el parámetro inválido; el backend
responde `PARAMETRO_INVALIDO`. Tratar ese código en el cliente.

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. Los métodos distintos de
`GET` responden `405`. El `Content-Type` de las respuestas es `application/json` sin
`charset=UTF-8`: si el cliente necesita el charset explícito, lo aporta en la petición y
decodifica como UTF-8.

---

## 4. Indicador global y prellenado de compra

**Indicador global.** Al iniciar sesión, y luego cada cierto tiempo, llamar a `/resumen` y pintar
una insignia en el menú de inventario:

| Condición | Insignia |
|---|---|
| `agotadas > 0` | Rojo con `total` |
| `agotadas = 0` y `bajas > 0` | Ámbar con `total` |
| `total = 0` | Sin insignia |

Al pulsar la insignia, `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA` van a la pantalla de alertas
(`GET /alertas`); `VENTAS` solo ve el contador, porque el listado le responde `403`.

**Prellenado de compra (HU-13).** Desde la sugerencia, el usuario `ADMIN` o `COMPRAS` elige un
grupo y el frontend abre el alta de compra de HU-13 con esos datos:

1. Tomar `proveedor.idProveedor` del grupo como proveedor de la compra. Si el grupo no trae
   `proveedor`, dejar el selector vacío.
2. Por cada ítem, agregar una línea con la materia prima y `cantidadSugerida` como cantidad.
3. Dejar todo editable: la sugerencia es un punto de partida, no una orden.
4. La compra se registra con `POST /api/v1/compras` (HU-13); esta historia **no crea compras ni
   mueve stock**.

Nota: la sugerencia devuelve el nombre de la materia prima (`materiaPrima`), no su id. Para
resolver el id al prellenar, cruzar por nombre con el listado de `/alertas` (que sí trae
`idMateriaPrima`) o con el catálogo de materias primas.

---

## 5. Pantalla

- **Acciones según el rol.** El botón "Sugerencia de compra" solo para `ADMIN` y `COMPRAS`. La
  pantalla de alertas, para `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA`.
- **Tabla de alertas:** columnas nombre, unidad, disponible, mínimo, faltante, nivel y costo de
  reposición; pie con `costoReposicionTotal`. Mostrar la fecha de `ultimaActualizacion` solo si
  viene.
- **Interruptor "Incluir inactivas"** enviando `incluirInactivas=true|false`; apagado por defecto.
- **Cifras con dos decimales** y costos con formato de moneda local.
- **Estados de la pantalla:**

| Estado | Qué se muestra |
|---|---|
| Cargando | Esqueleto de tabla + `MatProgressBar` |
| Sin alertas | "Todas las materias primas están por encima de su mínimo" |
| Con alertas | Tabla ordenada como llega, `AGOTADO` en rojo y `BAJO` en ámbar |
| Sugerencia vacía | "No hay materias primas por reponer" |
| Error de red / 5xx | Mensaje + "Reintentar" |
| 401 | Redirigir al login |
| 403 | Ocultar la acción y mostrar "No tienes permiso para esta operación" |

Flujo mínimo en Angular: cargar `/resumen` al iniciar sesión, cargar `/alertas` al entrar a la
pantalla, cargar `/sugerencia-compra` solo al abrir el panel de compra, y volver a llamar tras
registrar una compra o un ajuste de inventario (no hay caché que invalidar).

---

## 6. Qué NO está en el contrato

- **No hay escrituras.** Solo `GET`; no se descarta una alerta ni se cambia el mínimo aquí (el
  stock mínimo es de HU-15).
- **No hay paginación ni filtros por nivel.** El listado llega completo y ordenado; filtrar por
  `nivel` se hace en el cliente.
- **No hay creación de compra.** La sugerencia solo propone; la compra es de HU-13.
- **No hay notificaciones por correo ni push.** La alerta se consulta bajo demanda.
- **No hay historial de alertas.** `generadoEn` es el instante de la consulta.

---

## 7. Limitaciones conocidas

- **Usuario inactivo con token vigente.** Recibe `403 USUARIO_INACTIVO` (el filtro JWT comprueba
  el estado en cada petición); un token vencido o inválido recibe `401 NO_AUTENTICADO`.
- **La sugerencia devuelve el nombre, no el id, de la materia prima.** Resolver el id para el
  prellenado según la sección 4.
- **Proveedor basado en la última compra recibida.** Si esa compra no es la que se quiere repetir,
  el usuario cambia el proveedor al prellenar.
- **Costos de la especificación §4.** Los ejemplos de costo de la especificación eran
  inconsistentes (15,5 × 18500 = 286750, no 344100, que corresponde a 18,60 × 18500); el backend
  usa los cálculos correctos.
- **Backend verificado con QA ciego.** Cualquier cambio de contrato actualiza este manual en el
  mismo commit.
