# HU-06 — Gestión de proveedores · Manual de consumo

> **Estado: entregado.** Cinco endpoints bajo `/api/v1/proveedores`: listado paginado,
> detalle, alta, edición y cambio de estado. **No existe `DELETE`**: la baja de un proveedor
> es el `PATCH /{id}/estado` (CA-05), porque las compras futuras (HU-15) lo referencian.

## Autenticación y roles

Igual que el resto de la API: `Authorization: Bearer {{token}}` con el token de
`POST /api/v1/auth/login` (HU-03). Base URL: `/api/v1`.

| Endpoint | ADMIN | COMPRAS | CONSULTA | Otros roles |
|---|---|---|---|---|
| `GET /proveedores`, `GET /proveedores/{id}` | sí | sí | sí | 403 |
| `POST /proveedores`, `PUT /proveedores/{id}`, `PATCH /proveedores/{id}/estado` | sí | sí | 403 | 403 |

Qué debe ocultar la UI (`*siRol`): el botón «Nuevo proveedor», la acción «Editar» y el
interruptor de estado se muestran solo a `ADMIN` y `COMPRAS`; `CONSULTA` ve el listado y el
detalle en solo lectura. Ocultar no sustituye al backend: este responde `403 SIN_PERMISO`.

## Modelo TypeScript

```ts
interface Proveedor {
  idProveedor: number;
  nombre: string;
  contacto: string | null;
  telefono: string | null;
  correo: string | null;
  activo: boolean;
}

// Mismo cuerpo para POST y PUT.
interface ProveedorRequest {
  nombre: string;           // obligatorio, máx. 100
  contacto: string | null;  // opcional, máx. 100
  telefono: string | null;  // opcional, máx. 20
  correo: string | null;    // opcional, formato de correo, máx. 100
}

interface CambiarEstadoRequest { activo: boolean; }

interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
```

`contacto`, `telefono` y `correo` **siempre llegan en el JSON**, con valor `null` si el
proveedor no los tiene (a diferencia de otros módulos, donde los nulos se omiten).

## Endpoints

| Método | Ruta | Requiere | Códigos |
|---|---|---|---|
| GET | `/api/v1/proveedores` | ADMIN, COMPRAS, CONSULTA | 200, 400, 401, 403 |
| GET | `/api/v1/proveedores/{id}` | ADMIN, COMPRAS, CONSULTA | 200, 400, 401, 403, 404 |
| POST | `/api/v1/proveedores` | ADMIN, COMPRAS | 201, 400, 401, 403 |
| PUT | `/api/v1/proveedores/{id}` | ADMIN, COMPRAS | 200, 400, 401, 403, 404 |
| PATCH | `/api/v1/proveedores/{id}/estado` | ADMIN, COMPRAS | 200, 400, 401, 403, 404 |

### GET /api/v1/proveedores — listado

| Parámetro | Valores | Por defecto | Error |
|---|---|---|---|
| `nombre` | Prefijo, sin distinguir mayúsculas; `%` y `_` se tratan como texto literal | — | — |
| `activo` | `true`, `false` u omitido (todos) | omitido | 400 `PARAMETRO_INVALIDO` si no es booleano |
| `page` | `>= 0` | `0` | 400 `PARAMETRO_INVALIDO` |
| `size` | `1..100` (no se recorta) | `20` | 400 `PARAMETRO_INVALIDO` |
| `sort` | `nombre` o `id`, con `,asc` o `,desc` | `nombre,asc` | 400 `CAMPO_ORDEN_INVALIDO` |

```http
GET /api/v1/proveedores?nombre=agro&activo=true&page=0&size=20&sort=nombre,asc
Authorization: Bearer {{token}}
```

```json
{
  "content": [
    {
      "idProveedor": 3,
      "nombre": "Agroinsumos del Caribe",
      "contacto": "Marta Pérez",
      "telefono": "3001234567",
      "correo": "ventas@agrocaribe.example",
      "activo": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

Sin coincidencias devuelve `200` con `content: []` y `totalElements: 0`, no un error.

### GET /api/v1/proveedores/{id} — detalle

Respuesta `200 OK`: un `Proveedor` como el del listado. Un `id` que no existe devuelve
`404 RECURSO_NO_ENCONTRADO` y un `id` no numérico (`/proveedores/abc`) devuelve
`400 PARAMETRO_INVALIDO`.

### POST /api/v1/proveedores — alta

Solo `nombre` es obligatorio. El proveedor se crea **siempre activo**: no envíes `activo`.

```http
POST /api/v1/proveedores
Authorization: Bearer {{token}}
Content-Type: application/json

{ "nombre": "Agroinsumos del Caribe", "contacto": "Marta Pérez",
  "telefono": "3001234567", "correo": null }
```

Respuesta `201 Created` con cabecera `Location: /api/v1/proveedores/3`:

```json
{ "idProveedor": 3, "nombre": "Agroinsumos del Caribe", "contacto": "Marta Pérez",
  "telefono": "3001234567", "correo": null, "activo": true }
```

### PUT /api/v1/proveedores/{id} — edición

**Reemplaza todo**: nombre, contacto, teléfono y correo se sobrescriben siempre. Envía
**los cuatro campos** con el valor actual de lo que no cambia; un campo `null` (o ausente)
**borra** el dato guardado. El estado no se modifica aquí.

```http
PUT /api/v1/proveedores/3
Content-Type: application/json

{ "nombre": "Agroinsumos del Caribe S.A.S.", "contacto": "Marta Pérez",
  "telefono": "3001234567", "correo": "ventas@agrocaribe.example" }
```

Respuesta `200 OK`: el `Proveedor` actualizado. `404 RECURSO_NO_ENCONTRADO` si el id no
existe.

### PATCH /api/v1/proveedores/{id}/estado — activar o desactivar

```http
PATCH /api/v1/proveedores/3/estado
Content-Type: application/json

{ "activo": false }
```

Respuesta `200 OK`: el `Proveedor` con `"activo": false`. El cambio es idempotente: repetir
el mismo valor no falla. Desactivar **no cancela** las compras ya registradas, ni siquiera
las pendientes. `activo` es obligatorio: sin él, `400 VALIDACION_FALLIDA` con
`fieldErrors` sobre `activo`.
