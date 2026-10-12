# HU-04 — Bitácora de auditoría · Manual de consumo

> **Estado: entregado.** Dos endpoints de consulta, ambos **solo para `ADMIN`** y **solo
> `GET`**: `GET /api/v1/bitacora` (con filtros y paginación) y
> `GET /api/v1/bitacora/acciones` (catálogo de acciones). La bitácora **no se escribe por
> API**: la llenan los servicios anotados con `@Auditable` y el login. No existen `POST`,
> `PUT` ni `DELETE`.

## Autenticación

Igual que el resto de la API: `Authorization: Bearer {{token}}` con el token de
`POST /api/v1/auth/login`. **Los dos endpoints exigen rol `ADMIN`**; cualquier otro rol
recibe `403 SIN_PERMISO`. En Postman, la carpeta `00 - Setup` de
`ProductionFood-HU04.postman_collection.json` guarda el token.

## Qué se audita (y qué no)

| Acción | Cuándo | `tabla_afectada` |
|---|---|---|
| `CREAR` | Alta de una entidad | `usuarios` |
| `EDITAR` | Modificación | `usuarios` |
| `CAMBIAR_ESTADO` | Activar/desactivar | `usuarios` |
| `LOGIN` | Inicio de sesión correcto | `usuarios` |
| `LOGIN_FALLIDO` | Intento fallido (correo inexistente, contraseña mala o cuenta inactiva) | `usuarios` |
| `SEED` | Semilla de la base (script de migración) | `usuarios` |

Las **consultas (`GET`) no se auditan**: generarían volumen sin trazabilidad de cambios. El
registro ocurre **después** de que la operación termina con éxito, nunca antes.

`usuario` del registro indica **quién** actuó:

- **Persona**: objeto `{ "idUsuario": N, "nombre": "..." }`.
- **Sistema** (`id_usuario = NULL`): login fallido con correo desconocido o contraseña mal,
  o el script de semilla. En JSON el campo **se omite** (ver «Casos límite»).

## Endpoints

| Método | Ruta | Requiere | Códigos |
|---|---|---|---|
| GET | `/api/v1/bitacora` | rol `ADMIN` | 200, 400, 401, 403 |
| GET | `/api/v1/bitacora/acciones` | rol `ADMIN` | 200, 401, 403 |

### GET /api/v1/bitacora — consultar con filtros y paginación

```http
GET /api/v1/bitacora?tabla=usuarios&accion=CREAR&fechaInicio=2026-09-01&fechaFin=2026-09-30&page=0&size=20&sort=fecha,desc
Authorization: Bearer {{token}}
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `idUsuario` | int | — | Filtra por autor. **No sirve para el «Sistema»** (sus filas tienen `NULL`); para eso, filtrar por `accion=LOGIN_FALLIDO`. |
| `tabla` | string | — | Coincidencia exacta con `tabla_afectada`. |
| `accion` | string | — | Coincidencia exacta (ver catálogo abajo). |
| `fechaInicio` | `YYYY-MM-DD` | — | **Inclusivo desde las 00:00**. |
| `fechaFin` | `YYYY-MM-DD` | — | **Inclusivo hasta el final del día**. |
| `page` | int | `0` | `>= 0`. |
| `size` | int | `20` | `1..100`; por encima de 100 → `400`. |
| `sort` | string | `fecha,desc` | Lista blanca: `fecha`, `id`, `accion`, `tabla`, con `,asc` o `,desc`. |

Respuesta `200 OK` (ejecución real; se pidió `size=2&sort=fecha,desc`):

```json
{
  "content": [
    {
      "idBitacora": 23,
      "usuario": { "idUsuario": 1, "nombre": "Administrador Inicial" },
      "accion": "LOGIN",
      "tablaAfectada": "usuarios",
      "detalle": "Inicio de sesión: admin@productionfood.local",
      "fecha": "2026-10-12T17:42:29"
    },
    {
      "idBitacora": 1,
      "accion": "SEED",
      "tablaAfectada": "usuarios",
      "detalle": "Creación del administrador inicial por script de semilla V3",
      "fecha": "2026-10-12T17:48:47"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 23,
  "totalPages": 12,
  "first": true,
  "last": false
}
```

Puntos del contrato que el frontend debe respetar:

- `fecha` es un `LocalDateTime` **sin zona** (`2026-10-12T17:42:29`): la hora ya está en
  `America/Bogota`, la del servidor. No hay que convertirla.
- La fila `SEED` **no trae `usuario`**: es una acción del sistema.
- El envelope de colección es el común de la API (`content`, `page`, `size`,
  `totalElements`, `totalPages`, `first`, `last`).

### GET /api/v1/bitacora/acciones — catálogo de acciones

Devuelve, ordenado alfabéticamente, **solo las acciones que existen** en ese momento. Sirve
para poblar el `<select>` de filtro sin que el frontend tenga que conocerlas de antemano.

Respuesta `200 OK`:

```json
["CAMBIAR_ESTADO","CREAR","LOGIN","LOGIN_FALLIDO","SEED"]
```

El catálogo **crece** con el uso: si todavía no hubo ninguna devolución, no aparecerá
`APROBAR_DEVOLUCION` (HU-18). El frontend no debe asumir una lista fija.

## Errores

| Código HTTP | `code` | Cuándo | Qué hace el frontend |
|---|---|---|---|
| 400 | `RANGO_FECHAS_INVALIDO` | `fechaInicio` posterior a `fechaFin` | Marcar el rango; no reintentar |
| 400 | `CAMPO_ORDEN_INVALIDO` | `sort` fuera de la lista blanca | Usar `fecha,desc` |
| 400 | `PARAMETRO_INVALIDO` | `page` negativo, `size` fuera de `1..100`, o un tipo mal formado (`fechaInicio=ayer`, `idUsuario=abc`) | Ajustar el parámetro |
| 401 | `NO_AUTENTICADO` | Sin token, o token inválido/expirado | Volver al login |
| 403 | `SIN_PERMISO` | Sesión válida con rol distinto de `ADMIN` | Bloquear la vista |
| 405 | `METODO_NO_PERMITIDO` | `POST`/`PUT`/`DELETE` sobre `/api/v1/bitacora` | No debería ocurrir: la pantalla no tiene botones de acción |
| 500 | `ERROR_INTERNO` | Error no controlado | Reintento genérico + log |

Ejemplos reales:

```json
{"status":400,"error":"Bad Request","code":"RANGO_FECHAS_INVALIDO",
 "message":"La fecha de inicio no puede ser posterior a la fecha de fin.",
 "path":"/api/v1/bitacora","fieldErrors":[]}
```

```json
{"status":403,"error":"Forbidden","code":"SIN_PERMISO",
 "message":"No tiene permisos para realizar esta acción.",
 "path":"/api/v1/bitacora","fieldErrors":[]}
```

Formato completo del cuerpo de error: `../../../PFDocs/00-base/04-CONTRATO-API.md` §5.

> `PFDocs` es un repositorio git **separado** del backend. La ruta relativa de arriba solo
> resuelve si ambos repos están clonados como hermanos. En GitHub, busca el documento en el
> repositorio **PFDocs**: `00-base/04-CONTRATO-API.md` (§5 · Formato de error).

## Casos límite

- **Una acción del sistema NO trae `usuario`; el campo se omite, no viene `null`.** La API
  aplica `default-property-inclusion: non_null` de forma global, así que los campos nulos
  **desaparecen** de la respuesta. El frontend debe tratar **`usuario` ausente** como
  «Sistema» (icono `settings`), no comprobar `usuario === null`. Es la diferencia entre
  `'usuario' in fila` y `fila.usuario === null`: la segunda siempre es `false`.
- **El rango de fechas es inclusivo en ambos extremos.** `fechaFin=2026-09-18` incluye todo
  lo ocurrido **ese día**, no solo la medianoche.
- **Filtrar por «Sistema» no se hace con `idUsuario=0`.** Un `idUsuario` inexistente (o 0)
  devuelve página vacía; las acciones del sistema se buscan por `accion=LOGIN_FALLIDO` (hoy
  es la única sin usuario). `idUsuario` no acepta «null» como valor de filtro.
- **La paginación es por `page`/`size`, no por `offset`.** El orden por defecto es
  `fecha,desc`: lo más reciente primero.
- **El catálogo de `/acciones` cambia** con el uso (ver arriba).

## Limitaciones conocidas

- **La bitácora puede tener huecos.** Si el `INSERT` de auditoría falla, la operación de
  negocio **sigue adelante** y el fallo solo queda en el log del servidor (regla R-03): es
  preferible un hueco a que un fallo de auditoría impida registrar una venta.
- **Puede registrar un cambio que no ocurrió.** La auditoría se confirma en su propia
  transacción al volver del método; si la transacción de negocio hace *rollback* al cerrar
  (p. ej. una clave foránea que falla al final), queda una fila de un cambio revertido. La
  trazabilidad del **inventario** no depende de la bitácora sino del kardex, que sí es
  transaccional (HU-13/HU-22).
- **Nunca se auditan contraseñas, hashes ni tokens** (R-05): el `detalle` de una creación de
  usuario lleva el correo y el rol, jamás la credencial.
- **En este alcance solo está anotado `UsuarioService`.** Los módulos de historias
  posteriores (compras, pedidos, devoluciones…) se auditan al escribirse, añadiendo
  `@Auditable` a sus servicios.
