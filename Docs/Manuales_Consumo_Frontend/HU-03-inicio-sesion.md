# HU-03 — Inicio de sesión · Manual de consumo

> **Estado: entregado.** Los dos endpoints de autenticación: `POST /api/v1/auth/login`
> (público) y `GET /api/v1/auth/perfil` (cualquier usuario autenticado). Este manual
> **reemplaza** la sección de autenticación de
> [HU-01 — Registro de usuarios](HU-01-registro-usuarios.md), que quedó desalineada tras
> el cambio de contrato de HU-03.

## Autenticación

Todo request autenticado lleva el token que devuelve el login:

```http
Authorization: Bearer {{token}}
```

El token vence **1 hora** después de emitirlo (`app.jwt.expiration-ms: 3600000`) y
**no hay refresh token**: cuando caduca, el usuario vuelve a iniciar sesión. El frontend
debe guardar la hora de expiración que devuelve el login (`expiraEn`, en segundos) y
cerrar la sesión o redirigir al formulario antes de que el token quede inservible, para
que el usuario no reciba un error a mitad de una acción.

En Postman, la carpeta `00 - Setup` de
`ProductionFood-HU03.postman_collection.json` guarda el token en la variable `token`.

## Endpoints

| Método | Ruta | Requiere | Códigos |
|---|---|---|---|
| POST | `/api/v1/auth/login` | público | 200, 400, 401, 403 |
| GET | `/api/v1/auth/perfil` | cualquier sesión | 200, 401 |

### POST /api/v1/auth/login — iniciar sesión

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "correo": "admin@productionfood.local", "password": "Admin123*" }
```

El cuerpo tiene exactamente dos campos: `correo` y `password`. Ambos son obligatorios y
el correo debe tener formato válido; si falta alguno → `400 VALIDACION_FALLIDA` con
`fieldErrors`.

Respuesta `200 OK` (CP-01, ejecución real sobre el backend local):

```json
{
  "token": "eyJhbGciOiJIUzM4NCJ9…[truncado]",
  "tipo": "Bearer",
  "expiraEn": 3600,
  "usuario": {
    "idUsuario": 1,
    "nombre": "Administrador Inicial",
    "correo": "admin@productionfood.local",
    "rol": "ADMIN"
  }
}
```

Puntos del contrato que el frontend debe respetar:

- `tipo` es siempre `"Bearer"`: es el esquema con el que hay que enviarlo en la cabecera
  `Authorization`.
- `expiraEn` va en **segundos** (3600 = 1 hora). No es una fecha: es una duración desde
  el momento de la respuesta.
- `usuario.rol` es texto plano (`"ADMIN"`), no un objeto de rol. Es el mismo dato que
  devuelve `GET /auth/perfil` y no trae `idRol` ni `descripcion`.
- El hash de la contraseña no aparece en ningún nivel de la respuesta (CP-14).

#### Credenciales incorrectas → 401

Se obtiene el mismo código y el mismo mensaje tanto si el correo no existe como si la
contraseña es errónea:

```json
{
  "status": 401,
  "error": "Unauthorized",
  "code": "CREDENCIALES_INVALIDAS",
  "message": "Correo o contraseña incorrectos.",
  "path": "/api/v1/auth/login",
  "fieldErrors": []
}
```

El frontend **no puede** distinguir los dos casos, y es deliberado (R-01): si pudiera,
cualquiera podría enumerar los correos registrados probando direcciones y descartando
unos contra otros antes de forzar contraseñas. La UI debe mostrar un único mensaje
neutro, sin sugerir «el correo no existe».

#### Usuario desactivado → 403

Credenciales correctas sobre una cuenta con `activo = false`:

```json
{
  "status": 403,
  "error": "Forbidden",
  "code": "USUARIO_INACTIVO",
  "message": "Su usuario está desactivado. Contacte al administrador.",
  "path": "/api/v1/auth/login",
  "fieldErrors": []
}
```

El `403` solo se emite **después** de validar la contraseña (R-02). Un usuario
desactivado que se equivoca en la contraseña recibe `401`, no `403`: si fuera al revés,
el `403` confirmaría que el correo existe sin que el atacante conozca la credencial. El
frontend no necesita conocer esta regla, pero sí saber que `403 USUARIO_INACTIVO` en el
login significa «credenciales correctas, cuenta dada de baja» y no «permisos
insuficientes».

### GET /api/v1/auth/perfil — datos del usuario autenticado

```http
GET /api/v1/auth/perfil
Authorization: Bearer {{token}}
```

No exige rol: cualquier usuario autenticado ve su propio perfil, y solo el suyo. Los
datos salen del token ya validado, no de una consulta adicional.

Respuesta `200 OK` (CP-16):

```json
{
  "idUsuario": 1,
  "nombre": "Administrador Inicial",
  "correo": "admin@productionfood.local",
  "rol": "ADMIN"
}
```

Es exactamente el mismo objeto que aparece en `usuario` dentro de la respuesta del login.
Útil para rehidratar la sesión al recargar la página sin volver a pedir el token al
usuario.

## Errores

| Código HTTP | `code` | Cuándo | Qué hace el frontend |
|---|---|---|---|
| 400 | `VALIDACION_FALLIDA` | `correo` vacío, sin formato válido, o `password` vacío | Marcar campos con `fieldErrors`; no reintentar |
| 401 | `CREDENCIALES_INVALIDAS` | Correo inexistente **o** contraseña incorrecta | Mostrar el mensaje neutro; no distinguir los casos |
| 401 | `NO_AUTENTICADO` | Petición a `/perfil` sin token, con token malformado o expirado | Borrar la sesión local y volver al login |
| 403 | `USUARIO_INACTIVO` | Credenciales correctas, cuenta desactivada | Mensaje «usuario desactivado»; no reintentar |
| 403 | `SIN_PERMISO` | Token válido sin el rol que exige el recurso | Bloquear la vista |
| 500 | `ERROR_INTERNO` | Error no controlado | Reintento genérico + log |

`401 NO_AUTENTICADO` es el único 401 que **no** significa «credenciales incorrectas». Si el
frontend lo trata como tal, el usuario vería «correo o contraseña incorrectos» cuando el
problema es que su sesión caducó. La distinción se hace por el campo `code`.

Formato completo del cuerpo de error: `../../../PFDocs/00-base/04-CONTRATO-API.md` §5.

> `PFDocs` es un repositorio git **separado** del backend. Esa ruta relativa solo resuelve
> si ambos repos están clonados como hermanos bajo un mismo directorio padre (por ejemplo
> `ElectivaII/Backend` y `ElectivaII/PFDocs`). Si estás leyendo este manual en GitHub,
> donde no existe la carpeta hermana, busca el documento en el repositorio **PFDocs**:
> `00-base/04-CONTRATO-API.md` (§5 · Formato de error).

## Casos límite

- **La expiración se anuncia antes de que ocurra.** `expiraEn` permite avisar con
  antelación. Cuando el token expira sin aviso previo, cualquier endpoint protegido
  responde `401 NO_AUTENTICADO`, no `403`: para el servidor la petición es anónima.
- **Un usuario desactivado con token vigente recibe `403 USUARIO_INACTIVO` de
  inmediato**, sin esperar a que el JWT caduque: el backend comprueba el estado en cada
  petición (HU-03 R-05). Ocurre tanto en `/perfil` como en el resto de recursos.
- **El correo se normaliza** a minúsculas y sin espacios antes de comparar, así que
  `Admin@...` y `admin@...` son la misma cuenta. No hace falta normalizar en el cliente.
- **Cambiar de contraseña no existe todavía**: un usuario que la olvide necesita que un
  ADMIN se la cambie. No hay endpoint de recuperación en este alcance.

## Limitaciones conocidas

- **La bitácora no registra los intentos fallidos de login.** Un `401 CREDENCIALES_INVALIDAS`
  no deja rastro. Se difiere a HU-04, que es la historia que construye el servicio de
  bitácora; ver `../Hechos/HU-03-inicio-sesion.md`.
- **No hay bloqueo por número de intentos.** BCrypt hace lento cada intento (~100 ms),
  lo que limita el ritmo, pero no impide probar contraseñas indefinidamente.
- **No hay logout server-side ni revocación**: cerrar sesión en el frontend es borrar el
  token local. Un token robado sigue siendo válido hasta que expira, salvo que el
  administrador desactive la cuenta.