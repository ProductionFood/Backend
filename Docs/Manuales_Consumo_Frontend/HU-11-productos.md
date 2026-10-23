# HU-11 — Productos · Manual de consumo para el frontend

Contrato práctico del catálogo de productos terminados. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-11-productos.md`](../Hechos/HU-11-productos.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085` mientras el 8080 esté
  ocupado por pgadmin).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** lectura `ADMIN`, `VENTAS`, `PRODUCCION`, `CONSULTA`; escritura solo `ADMIN`.
  `COMPRAS` **no** lee el catálogo de productos.

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

El frontend debe guardar el token y enviarlo en cada llamada. Un `401 NO_AUTENTICADO`
significa token ausente, malformado o expirado: forzar el login de nuevo.

---

## 2. Endpoints

| Método | Ruta | Rol mínimo | Códigos |
|---|---|---|---|
| GET | `/productos` | VENTAS / PRODUCCION / CONSULTA / ADMIN | 200, 400, 401, 403 |
| GET | `/productos/{id}` | VENTAS / PRODUCCION / CONSULTA / ADMIN | 200, 400, 401, 403, 404 |
| POST | `/productos` | ADMIN | 201, 400, 401, 403, 409 |
| PUT | `/productos/{id}` | ADMIN | 200, 400, 401, 403, 404, 409 |
| PATCH | `/productos/{id}/estado` | ADMIN | 200, 400, 401, 403, 404 |

No hay `DELETE` (R-05): responde `405 METODO_NO_PERMITIDO`.

### 2.1. Listar productos

```http
GET /api/v1/productos?nombre=&activo=&page=0&size=20&sort=nombre,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `nombre` | texto | — | Prefijo, sin distinguir mayúsculas; los comodines SQL se escapan |
| `activo` | booleano | — | `true` / `false` |
| `page` | entero ≥ 0 | `0` | |
| `size` | entero 1…100 | `20` | |
| `sort` | `campo,dirección` | `nombre,asc` | Campos válidos: `nombre`, `precio`, `id`. Dirección `asc`/`desc` |

Respuesta real:

```json
{
  "content": [
    { "idProducto": 2, "nombre": "Pan francés",
      "descripcion": "Corteza crujiente, 80 g", "precio": 1200.00,
      "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
      "activo": true, "tieneReceta": false, "stockDisponible": 0.00 }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1,
  "first": true, "last": true
}
```

- `tieneReceta` es de **solo lectura**: `false` significa que el producto se puede vender si
  hay stock, pero **no planificar** (R-02). El frontend debe mostrarlo con el aviso y el enlace
  a HU-12.
- `stockDisponible` es de **solo lectura** y agrega los lotes vigentes (HU-19). Un producto
  recién creado siempre trae `0.00`.
- `unidad` es la unidad de **venta**; la de producción es la de la receta (R-04).

### 2.2. Obtener un producto

```http
GET /api/v1/productos/2
```

Mismo objeto que un elemento de `content`. `404 RECURSO_NO_ENCONTRADO` si el id no existe.

### 2.3. Crear producto

```http
POST /api/v1/productos
Content-Type: application/json

{ "nombre": "Pan francés",
  "descripcion": "Corteza crujiente, 80 g",
  "precio": 1200.00,
  "idUnidad": 1 }
```

`201 Created` + `Location: /api/v1/productos/{id}`:

```json
{ "idProducto": 2, "nombre": "Pan francés",
  "descripcion": "Corteza crujiente, 80 g", "precio": 1200.00,
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "activo": true, "tieneReceta": false, "stockDisponible": 0.00 }
```

- `descripcion` es opcional; si se omite, la respuesta la trae como `null` (la clave **sí**
  aparece, no la asumas ausente).
- `precio` acepta `0` (muestras/promociones, R-06) y **redondea a 2 decimales**: `1200.555`
  devuelve `1200.56`.
- El alta **no crea inventario**: el stock entra con los lotes (HU-19).

### 2.4. Editar producto

```http
PUT /api/v1/productos/2
Content-Type: application/json

{ "nombre": "Pan francés",
  "descripcion": "Receta mejorada",
  "precio": 1500.00,
  "idUnidad": 1 }
```

`200 OK` con el producto actualizado. Es un **reemplazo completo**: todos los campos
editables se sobrescriben. El `estado` no se cambia por aquí (usar el PATCH).

> **R-01:** subir el precio solo afecta a ventas futuras. Los pedidos ya registrados conservan
> su `precio_unitario` y no se recalculan.

### 2.5. Activar / desactivar

```http
PATCH /api/v1/productos/2/estado
Content-Type: application/json

{ "activo": false }
```

`200 OK`:

```json
{ "idProducto": 2, "nombre": "Pan francés",
  "descripcion": "Corteza crujiente, 80 g", "precio": 1200.00,
  "unidad": { "idUnidad": 1, "nombre": "Kilogramo", "abreviatura": "kg" },
  "activo": false, "tieneReceta": false, "stockDisponible": 24.00,
  "advertencia": "El producto conserva 24 kg de stock disponible; desactivarlo no lo retira del inventario (R-03)." }
```

- Desactivar **no retira el stock** (R-03): si había existencias, el `200` trae
  `advertencia`. El frontend debe mostrarla como aviso informativo, **sin revertir** el cambio
  (la operación ya se hizo).
- En cualquier otra respuesta la clave `advertencia` **no viaja**.
- La operación es idempotente.

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-10-23T11:21:25.038", "status": 409,
  "error": "Conflict", "code": "UNIDAD_INEXISTENTE",
  "message": "La unidad de medida indicada no existe.",
  "path": "/api/v1/productos", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `VALIDACION_FALLIDA` | 400 | Falta un campo o no cumple longitud/rango | Pintar `fieldErrors[]` junto a cada campo |
| `PARAMETRO_INVALIDO` | 400 | `page` negativo o `size` fuera de 1…100 | Corregir la petición |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` con un campo fuera de `{nombre, precio, id}` | Usar solo la lista blanca |
| `UNIDAD_INEXISTENTE` | 409 | `idUnidad` no existe | Recargar el catálogo de unidades (HU-07) |
| `RECURSO_NO_ENCONTRADO` | 404 | El id no existe | Volver al listado |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | Rol sin permiso (p. ej. COMPRAS o escritura no-ADMIN) | Ocultar la acción |
| `USUARIO_INACTIVO` | 403 | El usuario fue desactivado tras emitir el token | Cerrar sesión |
| `METODO_NO_PERMITIDO` | 405 | `DELETE /productos/{id}` | No hay borrado: desactivar |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto.

---

## 4. Casos límite que el frontend debe validar antes de llamar

| Campo | Regla | Mensaje de servidor |
|---|---|---|
| `nombre` | requerido, 1…100 | "El nombre es obligatorio" / "El nombre no puede superar 100 caracteres" |
| `descripcion` | opcional, 0…150 | "La descripción no puede superar 150 caracteres" |
| `precio` | requerido, 0…99 999 999,99, se redondea a 2 decimales | "El precio debe ser 0 o mayor" |
| `idUnidad` | requerido y existente | "La unidad de venta es obligatoria" |

- El contador de la descripción debe mostrarse sobre 150; el nombre sobre 100.
- El precio se captura como texto y se envía como número; `0` es válido.
- El selector de unidad es el catálogo de **unidades de venta** (HU-07); si está vacío, enlazar
  a HU-07 en lugar de permitir guardar.

---

## 5. Limitaciones conocidas

- **`tieneReceta` no cambia desde este módulo**: se crea la receta en HU-12. Mientras HU-12 no
  exista como API, el valor solo refleja lo que haya en la base.
- **`stockDisponible` será siempre `0.00` hasta HU-19**, que es la historia que crea los lotes
  de producto terminado.
- El precio de los pedidos ya registrados no cambia al editar el catálogo (R-01). La verificación
  de punta a punta es criterio de cierre de HU-17.
