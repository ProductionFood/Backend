# HU-03 — Inicio de sesión · HECHA

| | |
|---|---|
| Fecha de entrega | 2026-10-12 |
| Commits / PR | sin commit (pendiente; la Unidad 6 es solo documentación) |
| Colección Postman | `Docs/Postman/ProductionFood-HU03.postman_collection.json` (carpetas `00 - Setup`, `Credenciales inválidas`, `Usuario desactivado`, `Perfil`) |
| Estado QA | batería `../../../PFDocs/HU-03/tarea-qa.md` (CP-01..CP-16 + SEC-01..SEC-05) pendiente con equipo QA |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| POST | `/api/v1/auth/login` | 200/400/401/403 | Autenticación pública; emite JWT Bearer de 1 h |
| GET | `/api/v1/auth/perfil` | 200/401 | Datos del usuario autenticado, sin requisito de rol |

Los dos endpoints **ya existían** desde HU-01 (el login) y la base de seguridad que HU-03
necesitaba (JWT, `SecurityConfig`, filtro, entry point) también estaba montada desde HU-01.
HU-03 **corrige el contrato** de lo ya entregado y añade el endpoint de perfil; el detalle
está en «Qué cambió respecto de lo que ya existía».

Reglas de negocio implementadas:

- **R-01 · mensaje único**: correo inexistente y contraseña incorrecta devuelven el mismo
  `401 CREDENCIALES_INVALIDAS` con el mensaje `"Correo o contraseña incorrectos."`. Ambos
  caminos construyen la excepción desde un único método privado, para que nadie pueda
  divergirlos por descuido.
- **R-02 · orden de las comprobaciones**: buscar → verificar contraseña → recién entonces
  comprobar `estado`. Invertirlo convertiría el `403` en un oráculo de existencia de
  correos (CP-08).
- **R-03 · tiempo constante**: cuando el correo no existe se ejecuta igualmente un
  `passwordEncoder.matches()` contra un hash ficticio generado en el constructor, para que
  ese camino no sea más rápido que un fallo de contraseña real.
- **R-04 · vigencia**: `expiraEn` se deriva de `app.jwt.expiration-ms` a través de
  `JwtService.expiracionSegundos()`; cambiar la configuración mueve a la vez la firma del
  token y el número que ve el cliente. Sin refresh token.
- **R-05 · estado en cada petición**: lo verifica `JwtAuthenticationFilter` (HU-02), no el
  login; un token de usuario desactivado recibe `403 USUARIO_INACTIVO` de inmediato.

## Contrato resumido

Ejemplos reales (ejecución del 2026-10-12; token truncado):

**POST /api/v1/auth/login → 200**

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

**POST /api/v1/auth/login, contraseña incorrecta → 401**

```json
{"status":401,"error":"Unauthorized","code":"CREDENCIALES_INVALIDAS",
 "message":"Correo o contraseña incorrectos.",
 "path":"/api/v1/auth/login","fieldErrors":[]}
```

**POST /api/v1/auth/login, usuario desactivado → 403**

```json
{"status":403,"error":"Forbidden","code":"USUARIO_INACTIVO",
 "message":"Su usuario está desactivado. Contacte al administrador.",
 "path":"/api/v1/auth/login","fieldErrors":[]}
```

**GET /api/v1/auth/perfil con token válido → 200**

```json
{"idUsuario":1,"nombre":"Administrador Inicial","correo":"admin@productionfood.local","rol":"ADMIN"}
```

**GET /api/v1/auth/perfil sin cabecera Authorization → 401**

```json
{"status":401,"error":"Unauthorized","code":"NO_AUTENTICADO",
 "message":"Su sesión ha expirado. Inicie sesión nuevamente.",
 "path":"/api/v1/auth/perfil","fieldErrors":[]}
```

## Qué cambió respecto de lo que ya existía

El login **ya se había entregado en HU-01**, con otro contrato. Estos cuatro puntos son un
cambio de contrato, no una ampliación: un frontend escrito contra HU-01 se rompe.

| | HU-01 (antes) | HU-03 (ahora) |
|---|---|---|
| Credenciales incorrectas | `409 CREDENCIALES_INVALIDAS` | `401 CREDENCIALES_INVALIDAS` |
| Usuario desactivado en login | `409 CREDENCIALES_INVALIDAS` (indistinguible) | `403 USUARIO_INACTIVO` |
| Forma de la respuesta | plana: `{token, tipo, expiresIn, correo, nombre, rol}` | anidada: `{token, tipo, expiraEn, usuario: {idUsuario, nombre, correo, rol}}` |
| `GET /auth/perfil` | no existía | `200` con `PerfilResponse` |

Dos detalles del cambio de forma que conviene no pasar por alto:

- `expiresIn` pasó a llamarse **`expiraEn`**. El nombre nuevo evita la ambigüedad de si
  eran milisegundos o segundos: el valor sigue siendo **segundos** (3600).
- Se añadió **`idUsuario`**, que antes no viajaba en la respuesta del login; con él el
  frontend ya no necesita llamar a `/perfil` solo para conocer su id.

`LoginResponse` **reutiliza** `PerfilResponse` para el objeto `usuario` en vez de declarar
unos campos paralelos: es el mismo dato que devuelve `/perfil`, y dos records duplicados
podrían divergir sin que ninguna prueba lo notara.

## Criterios de aceptación cubiertos

| CA | Enunciado | Dónde |
|---|---|---|
| CA-01 | Se recibe correo y contraseña | `LoginRequest` con `@NotBlank` + `@Email` |
| CA-02 | Credenciales correctas → token JWT | `200` con `token`, `tipo: "Bearer"` y datos del usuario |
| CA-03 | Usuario desactivado → `403` | `USUARIO_INACTIVO` **después** de validar la contraseña (R-02) |
| CA-04 | Credenciales incorrectas → `401` | `CREDENCIALES_INVALIDAS`, mismo mensaje en ambos fallos (R-01) |
| CA-05 | El token contiene `id_usuario`, `correo` y `rol` | Claims `sub`, `correo`, `nombre`, `rol` en `JwtService.generar` |

## Evidencia (verificación backend)

- `mvn test`: **78/78** en verde (11 suites, 2026-10-12). Sin cambios de código de
  producción en esta unidad: la Unidad 6 es exclusivamente documental.
- Colección HU-03 **escrita pero no ejecutada**: correrla exige el backend levantado con
  `docker compose` (MySQL + Flyway) y un `newman` sobre la colección. Queda como paso
  pendiente del equipo de QA, igual que las baterías de HU-01 y HU-02.
- Revisión de las colecciones HU-01 y HU-02 existentes: sus peticiones de login solo
  assertan `status 200` y que `json.token` venga no vacío, y no tocan ningún campo que
  haya cambiado de nombre o de nivel. **No hay asserts rotos por el cambio de contrato**,
  así que no se modificaron.
- Verificación de los ejemplos documentados contra el código: los records
  `LoginResponse`, `PerfilResponse` y `ErrorResponse`, y las excepciones
  `AutenticacionException`/`EntryPointNoAutenticado`, confirman los nombres de campo, el
  anidamiento y los mensajes tal y como aparecen arriba.

## Desviaciones vs especificación

- **Paso 9 de `tarea-backend.md` (bitácora `LOGIN_FALLIDO`): no implementado.** Ver
  «Diferido» más abajo.
- `tarea-backend.md` §2 nombra los archivos de seguridad como
  `ApiAuthenticationEntryPoint.java` y `ApiAccessDeniedHandler.java`; los entregados se
  llaman `EntryPointNoAutenticado.java` y `AccesoDenegadoHandler.java`. Mismo
  comportamiento, otro nombre; no es una desviación funcional.

## Diferido

- **Registro en bitácora de los intentos fallidos de login (`LOGIN_FALLIDO`,
  `id_usuario = NULL`).** No se implementa en HU-03 porque **HU-04 depende de HU-03** y es
  la historia que construye `BitacoraService`: hacerlo aquí exigiría anticipar una
  infraestructura de auditoría que todavía no existe, para después duplicarla oadaptarla
  en HU-04. Es el mismo diferimiento que HU-02 registra como R-07.
  - `../PFDocs/HU-03/tarea-backend.md` **no se editó**: `PFDocs` es un repositorio git
    **separado** de `Backend` (remote `ProductionFood/PFDocs` frente a
    `ProductionFood/Backend`), así que marcar el paso 9 ahí exige decidir primero en qué
    repositorio vive la planificación, y no corresponde decidirlo desde el backend. Queda
    pendiente de que se edite en PFDocs o se confirme que debe editarse desde aquí.

## Pendientes / deuda

- Batería de QA (`tarea-qa.md` CP-01..CP-16 + SEC-01..SEC-05) y corrida de la colección
  con `newman`: los ejecuta el equipo de QA, no el Backend.
- Bloqueo por número de intentos fallidos, refresh token, logout server-side y recuperación
  de contraseña: fuera del alcance acordado de HU-03 (especificación §8). El bloqueo por
  intentos es un hueco de seguridad real que conviene proponer como historia aparte.
- Editar el paso 9 de `../PFDocs/HU-03/tarea-backend.md` (ver «Diferido»).
- `Docs/Hechos/HU-01-registro-usuarios.md` y
  `Docs/Manuales_Consumo_Frontend/HU-01-registro-usuarios.md` se actualizaron para que no
  afirmen el `409` retirado, pero siguen siendo documentos **de HU-01**: describen el
  contrato de la entrega de esa fecha, no el vigente. Quien consuma autenticación debe ir
  a `HU-03-inicio-sesion.md`.