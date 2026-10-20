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
