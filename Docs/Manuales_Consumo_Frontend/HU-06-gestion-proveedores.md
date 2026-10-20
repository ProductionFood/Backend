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

## Errores

Cuerpo común (`../../../PFDocs/00-base/04-CONTRATO-API.md` §5):

```json
{"timestamp":"2026-10-19T10:15:30.123","status":400,"error":"Bad Request",
 "code":"VALIDACION_FALLIDA","message":"Los datos enviados no son válidos.",
 "path":"/api/v1/proveedores",
 "fieldErrors":[{"field":"nombre","message":"El nombre es obligatorio"}]}
```

| HTTP | `code` | Cuándo | Qué hace el frontend |
|---|---|---|---|
| 400 | `VALIDACION_FALLIDA` | Falla una regla del cuerpo (ver mensajes abajo) o el JSON es ilegible o con un tipo erróneo (entonces `fieldErrors` viene vacío) | Mostrar cada mensaje junto a su campo |
| 400 | `PARAMETRO_INVALIDO` | `page` negativo, `size` fuera de `1..100`, `activo` no booleano o `id` de ruta no numérico | Corregir el parámetro |
| 400 | `CAMPO_ORDEN_INVALIDO` | `sort` fuera de `nombre`/`id` | Usar `nombre,asc` |
| 401 | `NO_AUTENTICADO` | Sin token, o token inválido o expirado | Volver al login |
| 403 | `SIN_PERMISO` | Rol sin permiso para la operación | Bloquear la acción |
| 403 | `USUARIO_INACTIVO` | Token válido de un usuario dado de baja | Cerrar sesión e informar |
| 404 | `RECURSO_NO_ENCONTRADO` | `id` inexistente | Volver al listado |

Mensajes de `fieldErrors` (`field` → `message`):

| `field` | Mensajes |
|---|---|
| `nombre` | `El nombre es obligatorio` · `El nombre no puede superar 100 caracteres` |
| `contacto` | `El contacto no puede superar 100 caracteres` |
| `telefono` | `El teléfono no puede superar 20 caracteres` |
| `correo` | `Ingrese un correo válido` · `El correo no puede superar 100 caracteres` |
| `activo` (PATCH) | `El estado es obligatorio` |

## Reglas que afectan a la interfaz

- **El correo es opcional y no es único**: dos proveedores pueden compartirlo. Envía `null`,
  no `""`; el backend igualmente normaliza `""` o solo espacios a `null` (y recorta espacios).
- **PUT reemplaza todo**: precarga el formulario con los datos actuales y envía siempre los
  cuatro campos. Omitir uno equivale a borrarlo.
- **El estado no se envía** al crear ni en el PUT; solo cambia con el PATCH.
- **Confirmar antes de desactivar** con el texto: «No se podrán registrar nuevas compras a
  este proveedor.»
- **Compras (HU-13, aún no implementada):** rechazará comprar a un proveedor inactivo con
  `409 PROVEEDOR_INACTIVO`. Hoy ese código no existe en este módulo.
- Un proveedor inactivo sigue apareciendo en el listado: filtra con `activo=true` para
  selectores de compra.

## Estados de pantalla

- **Cargando:** indicador visible y controles deshabilitados mientras dura la petición.
- **Vacío:** mensaje explicativo con acción sugerida (limpiar filtros o «Nuevo proveedor»).
- **Error:** mensaje en español con opción de reintentar; los errores de campo, junto al campo.

## Checklist de integración

- [ ] Token Bearer en cada llamada; `401` lleva al login.
- [ ] Acciones de escritura visibles solo para `ADMIN` y `COMPRAS`.
- [ ] Modelo `Proveedor` con `null` (no omitidos) en contacto, teléfono y correo.
- [ ] Paginación con `size` entre 1 y 100 y orden `nombre|id`.
- [ ] PUT con los cuatro campos; sin `activo` en alta ni PUT.
- [ ] Confirmación antes de desactivar; botón de envío deshabilitado en vuelo.
- [ ] `fieldErrors` mostrados junto a cada campo.
