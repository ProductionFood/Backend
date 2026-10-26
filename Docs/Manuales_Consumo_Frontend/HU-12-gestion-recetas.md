# HU-12 — Gestión de recetas · Manual de consumo para el frontend

Contrato práctico de las recetas y sus ingredientes. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-12-gestion-recetas.md`](../Hechos/HU-12-gestion-recetas.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085` mientras el 8080 esté
  ocupado por pgadmin).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura `ADMIN`, `PRODUCCION`, `CONSULTA`; escritura `ADMIN`, `PRODUCCION`.
  `VENTAS` y `COMPRAS` **no** participan en este módulo.

> **Estado:** backend implementado 2026-10-25 · verificación en caliente formal y frontend pendientes.
> Los valores de los ejemplos salen de las filas que dejó el *smoke* manual en MySQL, no de una
> captura HTTP; las cantidades y los costos cambian con la base y con
> `materias_primas.costo_unitario`.

---

## 1. Autenticación

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "correo": "admin@productionfood.local", "password": "Admin123*" }
```

```json
{ "token": "eyJhbGciOiJIUzM4NCJ9...",
  "usuario": { "idUsuario": 1, "nombre": "Administrador Inicial", "rol": "ADMIN" } }
```

El frontend debe guardar el token y enviarlo en cada llamada. Un `401 NO_AUTENTICADO` significa
token ausente, malformado o expirado: forzar el login de nuevo.

Matriz de roles del módulo:

| Endpoint | ADMIN | PRODUCCION | CONSULTA | VENTAS | COMPRAS |
|---|---|---|---|---|---|
| `GET /recetas` | sí | sí | sí | 403 | 403 |
| `GET /recetas/{id}` | sí | sí | sí | 403 | 403 |
| `GET /productos/{idProducto}/receta` | sí | sí | sí | 403 | 403 |
| `POST /recetas` | sí | sí | 403 | 403 | 403 |
| `PUT /recetas/{id}` | sí | sí | 403 | 403 | 403 |
| `PATCH /recetas/{id}/estado` | sí | sí | 403 | 403 | 403 |
| `POST /recetas/{id}/detalles` | sí | sí | 403 | 403 | 403 |
| `PUT /recetas/{id}/detalles/{idDetalle}` | sí | sí | 403 | 403 | 403 |
| `DELETE /recetas/{id}/detalles/{idDetalle}` | sí | sí | 403 | 403 | 403 |

`CONSULTA` es un rol de solo lectura: puede ver recetas pero no crear, editar, cambiar estado ni
tocar ingredientes. La pantalla debe ocultar los botones de escritura para ese rol.

---

## 2. Endpoints

| Método | Ruta | Rol mínimo | Códigos |
|---|---|---|---|
| GET | `/recetas` | CONSULTA / PRODUCCION / ADMIN | 200, 400, 401, 403 |
| GET | `/recetas/{id}` | CONSULTA / PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |
| GET | `/productos/{idProducto}/receta` | CONSULTA / PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |
| POST | `/recetas` | PRODUCCION / ADMIN | 201, 400, 401, 403, 409 |
| PUT | `/recetas/{id}` | PRODUCCION / ADMIN | 200, 400, 401, 403, 404, 409 |
| PATCH | `/recetas/{id}/estado` | PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |
| POST | `/recetas/{id}/detalles` | PRODUCCION / ADMIN | 201, 400, 401, 403, 404, 409 |
| PUT | `/recetas/{id}/detalles/{idDetalle}` | PRODUCCION / ADMIN | 200, 400, 401, 403, 404 |
| DELETE | `/recetas/{id}/detalles/{idDetalle}` | PRODUCCION / ADMIN | 204, 400, 401, 403, 404 |

No existe `DELETE /recetas/{id}`: responde `405 METODO_NO_PERMITIDO`. La baja es el `PATCH` de
estado.

### 2.1. Listar recetas

```http
GET /api/v1/recetas?nombre=&activo=&page=0&size=20&sort=nombre,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `nombre` | texto | — | Prefijo, sin distinguir mayúsculas; los comodines SQL se escapan |
| `activo` | booleano | — | `true` / `false`; omitirlo no filtra |
| `page` | entero ≥ 0 | `0` | Base 0: la primera página es `0` |
| `size` | entero 1…100 | `20` | |
| `sort` | `campo,dirección` | `nombre,asc` | Campos válidos: `nombre`, `cantidadProducir`, `id`. Dirección `asc`/`desc` |

Respuesta `200 OK` (receta real del smoke; la base tenía además la receta 1):

```json
{
  "content": [
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
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

- El listado trae el **detalle completo** de cada receta, no solo la cabecera. Para una tabla
  resumen basta con `nombre`, `producto.nombre`, `cantidadProducir`, `unidad.abreviatura`,
  `activo` y `costoPorUnidad`.
- `unidad` es un **objeto de tres claves** (`idUnidad`, `nombre`, `abreviatura`). En cambio,
  `detalles[].materiaPrima.unidad` es un **texto** con la abreviatura (`"kg"`, `"L"`): no son
  intercambiables en las plantillas de Angular.
- `cantidadProducir` y los costos vienen con **dos decimales**; `cantidad` del detalle con
  **tres**.
- `completa: false` indica una receta sin ingredientes. Se puede mostrar, pero no planificar.
- El orden del detalle es el de inserción (`idDetalleReceta` ascendente).

### 2.2. Obtener una receta

```http
GET /api/v1/recetas/3
```

Devuelve el mismo objeto de un elemento de `content`, sin la envoltura de página. Responde
`404 RECURSO_NO_ENCONTRADO` si la receta no existe.

### 2.3. Obtener la receta de un producto

```http
GET /api/v1/productos/2/receta
```

Misma estructura que el detalle anterior. Cada producto tiene como máximo una receta (CA-01),
así que la ruta no pide `idReceta`. Responde `404` si el producto no tiene receta —también si
el producto no existe—; ese `404` no es un error de la petición, solo significa que el producto
no es planificable.

Es la llamada natural desde el catálogo de productos: `GET /productos` ya trae `tieneReceta`,
pero no el `idReceta`. Si `tieneReceta` es `true`, esta ruta resuelve la receta en una sola
llamada.

### 2.4. Crear una receta

```http
POST /api/v1/recetas
Content-Type: application/json

{ "idProducto": 3,
  "nombre": "Masa de pan francés",
  "cantidadProducir": 30.00,
  "idUnidad": 5,
  "detalles": [
    { "idMateriaPrima": 1, "cantidad": 5.000 },
    { "idMateriaPrima": 2, "cantidad": 0.050 }
  ] }
```

`201 Created` + `Location: /api/v1/recetas/{idReceta}`, con la receta completa:

```json
{ "idReceta": 4,
  "producto": { "idProducto": 3, "nombre": "Croissant" },
  "nombre": "Masa de pan francés",
  "cantidadProducir": 30.00,
  "unidad": { "idUnidad": 5, "nombre": "Unidad", "abreviatura": "und" },
  "activo": true,
  "completa": true,
  "detalles": [
    { "idDetalleReceta": 5,
      "materiaPrima": { "idMateriaPrima": 1, "nombre": "Harina de trigo premium", "unidad": "kg" },
      "cantidad": 5.000, "costoEstimado": 16000.00 },
    { "idDetalleReceta": 6,
      "materiaPrima": { "idMateriaPrima": 2, "nombre": "Levadura", "unidad": "L" },
      "cantidad": 0.050, "costoEstimado": 9.00 }
  ],
  "costoTotalEstimado": 16009.00,
  "costoPorUnidad": 533.63 }
```

- `idReceta` y `Location` son la fuente del identificador; no los calcules.
- `detalles` puede omitirse o venir vacío: la receta se crea con `completa: false` (R-05).
- La operación es **transaccional**: si un ingrediente falla, no queda cabecera ni líneas.
- El producto debe existir, estar activo y **no tener ya receta**; la materia prima debe
  existir, estar activa y no repetirse.
- `cantidadProducir` es el rendimiento de **una tanda completa** (R-02), no de una unidad.

### 2.5. Editar la cabecera

```http
PUT /api/v1/recetas/3
Content-Type: application/json

{ "nombre": "Receta smoke HU-12 editada",
  "cantidadProducir": 31.00,
  "idUnidad": 5 }
```

`200 OK` con la receta completa y los costos recalculados. Es un **reemplazo completo** de los
tres campos editables.

- **No cambia el producto**: CA-01 ata la receta a su producto y `ActualizarRecetaRequest` no
  incluye `idProducto`.
- **No cambia el estado**: usar `PATCH /estado`.
- **No toca los ingredientes**: se editan por el subrecurso de detalles.
- `idUnidad` se revalida siempre: si no existe, `409 UNIDAD_INEXISTENTE`.

### 2.6. Activar / desactivar

```http
PATCH /api/v1/recetas/3/estado
Content-Type: application/json

{ "activo": false }
```

`200 OK` con la receta completa y `activo` aplicado. Es idempotente: enviar el estado que ya
tiene responde `200` y no cambia nada.

- Desactivar **no borra el detalle** ni impide consultarlo; solo saca la receta de la
  planificación.
- La cabecera nunca se elimina (R-07).
- Un cuerpo sin `activo` responde `400 VALIDACION_FALLIDA`.

### 2.7. Agregar un ingrediente

```http
POST /api/v1/recetas/3/detalles
Content-Type: application/json

{ "idMateriaPrima": 4, "cantidad": 0.250 }
```

`201 Created` + `Location: /api/v1/recetas/3` (la **receta** modificada, no la línea), con la
receta completa y los costos recalculados:

```json
{ "idReceta": 3,
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
      "cantidad": 0.050, "costoEstimado": 9.03 },
    { "idDetalleReceta": 7,
      "materiaPrima": { "idMateriaPrima": 4, "nombre": "Mantequilla", "unidad": "kg" },
      "cantidad": 0.250, "costoEstimado": 4625.00 }
  ],
  "costoTotalEstimado": 21387.78,
  "costoPorUnidad": 689.93 }
```

- `201` aunque no se devuelva la línea: el recurso creado es la línea y la representación es la
  receta completa. Si el cliente necesita el `idDetalleReceta` para editar o borrar, lo toma
  del último elemento de `detalles` o busca por `materiaPrima.idMateriaPrima`.
- `409 MATERIA_PRIMA_DUPLICADA` si la materia prima ya está en la receta. El selector de
  ingredientes debe excluir las ya presentes.
- `404 RECURSO_NO_ENCONTRADO` si la receta no existe.

### 2.8. Editar la cantidad de un ingrediente

```http
PUT /api/v1/recetas/3/detalles/7
Content-Type: application/json

{ "cantidad": 0.300 }
```

`200 OK` con la receta completa y el costo recalculado. Solo cambia `cantidad`; **la materia
prima no se reemplaza** (el `UNIQUE` de la receta prohíbe repetir un ingrediente): para cambiar
de ingrediente se elimina la línea y se agrega otra.

- La línea debe pertenecer a la receta de la ruta. Una línea de otra receta responde
  `404` aunque exista.
- `cantidad ≤ 0` responde `400 VALIDACION_FALLIDA`.

### 2.9. Quitar un ingrediente

```http
DELETE /api/v1/recetas/3/detalles/7
```

`204 No Content`, sin cuerpo. El detalle se elimina físicamente (R-07). La línea debe
pertenecer a la receta de la ruta; si no, `404`.

Después del `204` la receta puede quedar con `completa: false` si era el último ingrediente.
La pantalla debe refrescar la receta (`GET /recetas/{id}`) para actualizar el detalle y los
costos; el `DELETE` no devuelve el estado nuevo.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-25T18:53:58.120", "status": 409, "error": "Conflict",
  "code": "UNIDAD_INEXISTENTE",
  "message": "La unidad de medida indicada no existe.",
  "path": "/api/v1/recetas", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `PRODUCTO_YA_TIENE_RECETA` | 409 | El producto elegido ya tiene receta | Abrir la existente con `GET /productos/{id}/receta` |
| `MATERIA_PRIMA_DUPLICADA` | 409 | La materia prima ya está en la receta o se repite en la lista inicial | Quitar la línea; el selector debe excluir las usadas |
| `PRODUCTO_INEXISTENTE` | 409 | El producto no existe o está inactivo | Recargar el catálogo (HU-11) |
| `MATERIA_PRIMA_INEXISTENTE` | 409 | La materia prima no existe o está inactiva | Recargar el catálogo (HU-08) |
| `UNIDAD_INEXISTENTE` | 409 | La unidad no existe | Recargar unidades (HU-07) |
| `CANTIDAD_PRODUCIR_INVALIDA` | 400 | `cantidadProducir ≤ 0` | Corregir el rendimiento de la tanda |
| `VALIDACION_FALLIDA` | 400 | Campo faltante o inválido; `cantidad ≤ 0` en el detalle | Pintar `fieldErrors[]` |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo, `size` fuera de 1…100 o id no numérico | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` fuera de `{nombre, cantidadProducir, id}` | Usar solo la lista blanca |
| `RECURSO_NO_ENCONTRADO` | 404 | Receta o línea inexistente; el producto no tiene receta | Volver al listado o refrescar |
| `METODO_NO_PERMITIDO` | 405 | `DELETE /recetas/{id}` | No ofrecer borrado de receta: desactivar |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (VENTAS/COMPRAS; CONSULTA en escritura) | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | Usuario desactivado después de emitir el token | Cerrar sesión |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. Los `field` posibles en esta
historia:

| Campo | Regla | Mensaje de servidor |
|---|---|---|
| `nombre` | requerido, 1…100 | `El nombre es obligatorio` / `El nombre no puede superar 100 caracteres` |
| `idProducto` | requerido | `El producto es obligatorio` |
| `cantidadProducir` | requerido (el > 0 lo valida el servicio) | `La cantidad a producir es obligatoria` |
| `idUnidad` | requerido | `La unidad de producción es obligatoria` |
| `idMateriaPrima` | requerido | `La materia prima es obligatoria` |
| `cantidad` | requerido y > 0 | `La cantidad es obligatoria` / `La cantidad debe ser mayor que cero` |
| `activo` | requerido en el PATCH | `El estado es obligatorio` |

`cantidadProducir` es el caso especial: si falta, es `VALIDACION_FALLIDA` con `fieldErrors`; si
viene con `0` o negativo, es `400 CANTIDAD_PRODUCIR_INVALIDA` **sin** `fieldErrors`. La pantalla
debe manejar los dos caminos.

---

## 4. Flujo recomendado en Angular

### 4.1. Crear una receta

1. Cargar productos con `GET /productos?size=100` (HU-11) y filtrar en el cliente por
   `tieneReceta === false`. **No existe `?sinReceta=true`**: el backend no expone ese filtro.
2. Cargar unidades con `GET /unidades-medida?size=100` (HU-07) para el selector de unidad de
   producción.
3. Cargar materias primas activas con `GET /materias-primas?activo=true&size=100` (HU-08) y
   excluir las que ya estén en la lista local (CA-05).
4. Enviar **una sola** `POST /recetas` con la cabecera y todos los ingredientes. No encadenar
   un alta de cabecera y varios `POST /detalles`: el alta única es transaccional y deja la
   receta completa o nada.
5. Tomar `idReceta` de la respuesta y navegar al editor.

### 4.2. Editar

1. `GET /recetas/{id}` para hidratar el formulario.
2. `PUT /recetas/{id}` solo para nombre, `cantidadProducir` y `idUnidad`.
3. Cada ingrediente se edita con `PUT /recetas/{id}/detalles/{idDetalle}` (solo `cantidad`).
4. Cada alta o baja de ingrediente refresca la receta con la respuesta del `POST` (que ya viene
   completa) o con un `GET` después del `DELETE`.
5. `PATCH /recetas/{id}/estado` para activar o desactivar. No hay borrado de receta.

### 4.3. Advertencias de contrato

- **R-02:** etiquetar `cantidadProducir` como «Cantidad que rinde una tanda completa», no
  «por unidad». Mostrar una columna derivada «por unidad» (`cantidad / cantidadProducir`) ayuda
  a detectar el error de captura. Un `1` en una receta de 5 kg de harina produce 5 kg por pan:
  es visible de inmediato en esa columna.
- **R-05:** si `completa` es `false`, mostrar el aviso «Esta receta no tiene ingredientes. No se
  podrá usar para planificar producción», no solo un mensaje de campo vacío.
- **Costos:** son estimados con el costo de referencia de HU-08 y se recalculan en cada
  lectura. No los uses como costo contable; si `costo_unitario` cambia, cambian todos los
  valores de la receta.
- **Escala:** el input de cantidad debe permitir `step="0.001"`; el de rendimiento, dos
  decimales.

---

## 5. Casos límite que el frontend debe conocer

| Situación | Comportamiento |
|---|---|
| `detalles` omitido o `[]` en el alta | `201` con `completa: false` y costos en cero |
| Misma materia prima dos veces en `detalles[]` | `409 MATERIA_PRIMA_DUPLICADA`; **no se crea la receta** |
| Materia prima ya presente al agregarla | `409 MATERIA_PRIMA_DUPLICADA` |
| Línea de otra receta en `PUT`/`DELETE` | `404 RECURSO_NO_ENCONTRADO`, no `403` |
| Producto sin receta en `/productos/{id}/receta` | `404`; no es un error de la petición |
| Receta desactivada | Se sigue leyendo con `activo: false`; el detalle no se borra |
| `cantidad: 0.005` | Se guarda y devuelve `0.005` (R-06), no `0.01` |
| `cantidad` con más de 3 decimales | Se redondea a escala 3 con `HALF_UP` (`0.0005` → `0.001`) antes de guardar |
| `cantidad` que redondea a `0.000` | `400 VALIDACION_FALLIDA`: no se puede guardar una cantidad efectiva de cero |
| `cantidad` ≥ `10 000 000` | `400 VALIDACION_FALLIDA`: no cabe en `decimal(10,3)` |
| `cantidadProducir: 0` | `400 CANTIDAD_PRODUCIR_INVALIDA` (sin `fieldErrors`) |
| `sort=activo` | `400 CAMPO_ORDEN_INVALIDO`; el estado se filtra con `activo` |
| `DELETE /recetas/{id}` | `405 METODO_NO_PERMITIDO` |
| Última línea eliminada | La receta queda con `completa: false`; refrescar con `GET /recetas/{id}` |

---

## 6. Qué NO está en el contrato

- **No hay `DELETE` de receta.** Solo se desactiva. No diseñar un botón de borrado para la
  cabecera.
- **No se puede cambiar el producto de una receta.** `PUT /recetas/{id}` no acepta
  `idProducto`. Para mover una receta a otro producto hay que desactivar la actual y crear otra.
- **No se puede cambiar la materia prima de una línea.** Se elimina y se agrega.
- **No hay endpoints de ingredientes aislados.** No existe `GET /recetas/{id}/detalles` ni
  `GET /recetas/{id}/detalles/{idDetalle}`: el detalle siempre viaja dentro de la receta.
- **No hay filtro por producto en el listado.** Para una receta concreta de un producto se usa
  `GET /productos/{idProducto}/receta`.
- **No hay `@Schema` en los DTO.** Swagger muestra operaciones y códigos, pero no ejemplos de
  campo por campo. Este manual es la referencia.
- **No hay endpoint de costos.** `costoEstimado`, `costoTotalEstimado` y `costoPorUnidad` son
  campos calculados de la respuesta; no se envían ni se editan.
- **No hay paginación del detalle.** Los ingredientes de una receta llegan todos, ordenados por
  inserción.

---

## 7. Limitaciones conocidas

- **Backend implementado el 2026-10-25; la verificación en caliente formal y el frontend están
  pendientes.** El smoke manual cubrió alta, consulta, edición, estado, borrado de ingrediente,
  duplicado y `porProducto`, pero no la matriz completa de CP-01…CP-24 ni SEC-01…SEC-05 en
  caliente.
- **No hay pantalla de recetas todavía.** Este manual es el contrato para construirla.
- **El seed V3 no crea recetas.** Un entorno limpio arranca sin recetas; los ejemplos con
  `idReceta = 3` vienen de la base de desarrollo usada en el smoke y no existen en otra base.
- **Los costos dependen de `materias_primas.costo_unitario`.** No son históricos ni contables:
  una edición del costo unitario en HU-08 cambia los valores de todas las recetas.
- **La lista puede ser pesada.** Cada elemento trae su detalle completo; con páginas grandes o
  recetas de muchos ingredientes conviene pedir solo las columnas de resumen que la tabla
  necesita y no reutilizar la respuesta completa para el editor.
