# HU-05 — Gestión de clientes · HECHA

| | |
|---|---|
| Fecha de entrega | 2026-10-20 |
| Commits / PR | PRs #31–#38 (cadena `HU-05/pr*` sobre la rama `HU-05/gestion-clientes`); ver «Entregado en» |
| Colección Postman | `Docs/Postman/ProductionFood-HU05.postman_collection.json` (`00 - Setup`, `Alta, edición y estado`, `Consulta`, `Errores`, `Seguridad y autorización`) |
| Estado QA | pendiente con el equipo de QA; batería propia **35 peticiones / 119 aserciones** (ver «Evidencia») |

## Qué se construyó

CRUD completo de clientes. Leer el módulo es de todo el mundo con permiso de lectura; escribir
(alta, edición, activación y desactivación) es de `ADMIN` y `VENTAS`.

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| GET | `/api/v1/clientes` | 200/400/401/403 | Listado paginado con filtro por nombre (prefijo) y por estado |
| GET | `/api/v1/clientes/{id}` | 200/400/401/403/404 | Obtener un cliente por id |
| POST | `/api/v1/clientes` | 201/400/401/403 | Registrar un cliente activo |
| PUT | `/api/v1/clientes/{id}` | 200/400/401/403/404 | Reemplazar nombre, contacto y teléfono |
| PATCH | `/api/v1/clientes/{id}/estado` | 200/400/401/403/404/409 | Activar o desactivar |

Piezas internas:

| Pieza | Rol |
|---|---|
| `Cliente` | Entidad JPA sobre la tabla `clientes` (`id_cliente`, `nombre`, `contacto`, `telefono`, `estado`) |
| `ClienteRepository` | `JpaRepository`: búsqueda paginada con filtro por nombre y estado, y consulta nativa de pedidos en curso |
| `ClienteService` | 5 operaciones (crear, listar, obtener, actualizar, cambiar estado) + la regla configurable de pedidos en curso |
| `ClienteController` | Los 5 endpoints, con `@PreAuthorize` por rol y la documentación de Swagger |
| DTOs | `CrearClienteRequest`, `ActualizarClienteRequest`, `ClienteResponse` y `common/dto/CambiarEstadoRequest` (compartido) |

**No hubo migración de esquema**: la tabla `clientes` ya existía desde el esquema base (V1) y su
índice `idx_clientes_nombre` llegó en V2. La entidad se mapea sobre esa tabla tal cual.

## Reglas de negocio implementadas

- **R-01 · nunca se elimina.** No hay endpoint `DELETE`: `DELETE /api/v1/clientes/{id}` devuelve
  `405 METODO_NO_PERMITIDO`. La baja es la desactivación (`estado = 0`), que conserva el historial
  de pedidos.
- **R-02 · un cliente inactivo no puede recibir pedidos nuevos.** La desactivación no cancela los
  pedidos ya creados. Además, desactivar un cliente **con pedidos en curso** se rechaza con
  `409 CLIENTE_CON_PEDIDOS_ACTIVOS`; la regla es configurable con
  `app.clientes.bloquear-desactivacion-con-pedidos` (por defecto `true`).
- **R-03 · se permiten nombres duplicados.** No hay `UNIQUE` sobre `nombre`; el aviso de duplicado
  del formulario es informativo y no bloqueante, y el frontend lo resuelve consultando el listado
  por prefijo.
- **R-04 · el teléfono se guarda tal como se escribe.** `varchar(20)` sin validación de formato:
  solo se comprueba la longitud.

## Contrato resumido

Ejemplos reales (ejecución del 2026-10-20 sobre el backend local, `SERVER_PORT=9211`):

**POST /api/v1/clientes → 201**

```json
{
  "idCliente": 1,
  "nombre": "Tienda La Esquina",
  "contacto": null,
  "telefono": null,
  "activo": true
}
```

**GET /api/v1/clientes?page=0&size=2 → 200**

```json
{
  "content": [
    { "idCliente": 3, "nombre": "Almacén Central",
      "contacto": null, "telefono": null, "activo": true },
    { "idCliente": 2, "nombre": "Panadería El Trigo",
      "contacto": "Ana Restrepo", "telefono": "310 444 2211", "activo": true }
  ],
  "page": 0, "size": 2, "totalElements": 3, "totalPages": 2,
  "first": true, "last": false
}
```

**400 · nombre vacío**

```json
{"status":400,"error":"Bad Request","code":"VALIDACION_FALLIDA","message":"Los datos enviados no son válidos.","path":"/api/v1/clientes","fieldErrors":[{"field":"nombre","message":"El nombre es obligatorio"}]}
```

**404 · cliente inexistente**

```json
{"status":404,"error":"Not Found","code":"RECURSO_NO_ENCONTRADO","message":"No se encontró el cliente con id 9999.","path":"/api/v1/clientes/9999","fieldErrors":[]}
```

**405 · el DELETE que no existe**

```json
{"status":405,"error":"Method Not Allowed","code":"METODO_NO_PERMITIDO","message":"El método 'DELETE' no está permitido para este recurso.","path":"/api/v1/clientes/1","fieldErrors":[]}
```

**409 · el cliente tiene pedidos en curso**

```json
{"status":409,"error":"Conflict","code":"CLIENTE_CON_PEDIDOS_ACTIVOS","message":"El cliente tiene pedidos en curso y no se puede desactivar. Desactive o cancele los pedidos pendientes primero.","path":"/api/v1/clientes/2/estado","fieldErrors":[]}
```

## Criterios de aceptación cubiertos

| CA | Enunciado | Dónde |
|---|---|---|
| CA-01 | Nombre obligatorio, máx. 100 | `@NotBlank` + `@Size(max=100)` en `CrearClienteRequest` y `ActualizarClienteRequest`; si falta, `400 VALIDACION_FALLIDA` con el campo marcado |
| CA-02 | Contacto y teléfono opcionales | Sin `@NotBlank`, solo `@Size` (100 y 20). Si no vienen, se guardan y se devuelven como `null` |
| CA-03 | Estado por defecto activo | `ClienteService.crear` fija `estado = true` de forma explícita (la columna es `NOT NULL`); el `DEFAULT 1` de la tabla es la red de seguridad para lo que se inserte fuera de la aplicación |
| CA-04 | Listar con paginación y filtrar por nombre | `GET /api/v1/clientes?nombre=&page=&size=&sort=`: filtro por prefijo, paginación y orden con lista blanca (`nombre`, `id`) |
| CA-05 | No se permite eliminar | No existe endpoint `DELETE`; `DELETE /api/v1/clientes/{id}` → `405 METODO_NO_PERMITIDO`. La baja es el `PATCH` de estado |

## Evidencia (verificación backend)

- **Suite unitaria integrada:** `mvn -o test` → **178/178 en verde** (132 previos + 46 nuevos de
  HU-05: 18 del servicio, 7 del contrato de DTO, 9 del contrato de auditoría y 12 de HTTP y
  seguridad).
- **Colección de Postman con `newman`** contra el backend arrancado con MySQL real: **35
  peticiones, 119 aserciones, 0 fallos**. Cubre el CRUD, la validación, `size`/`sort` fuera de
  rango, el comodín `%`, la inyección SQL en el filtro y la batería SEC-01..SEC-05.
- **Batería de humo con `curl`** (MySQL real, 9211): **CP-01 a CP-20 y SEC-01, SEC-02 y SEC-05 en
  verde**, entre ellos: `contacto` y `telefono` como `null` explícito, el filtro `%` devolviendo
  `content: []` (no la tabla entera), la inyección SQL devolviendo `200` con `content: []`, el 403
  de CONSULTA al escribir, el 403 `USUARIO_INACTIVO`, el 405 del `DELETE`, el 404 de un id
  inexistente y el 409 `CLIENTE_CON_PEDIDOS_ACTIVOS`.
- **R-01 comprobado en base de datos:** `SELECT COUNT(*) FROM clientes` dio **4 antes y 4 después**
  de desactivar un cliente: la fila no se borra, solo cambia `estado`.
- **Arranque real:** Hibernate `ddl-auto: validate` acepta el mapeo de `clientes` sobre el esquema
  real, y Spring Data JPA parsea la consulta JPQL sin error. `EXPLAIN SELECT id_cliente, nombre
  FROM clientes WHERE nombre LIKE 'Tien%'` → `key = idx_clientes_nombre`, `Using index`.
- **Auditoría (HU-04):** las tres escrituras quedaron registradas con la tabla `clientes` y el
  detalle correcto (`Cliente creado: Supermercado Central`, `Cliente editado: Panadería El Trigo
  Editado`, `Cliente 1 estado true`).
- **Las pruebas de HTTP no necesitan base de datos:** verificado parando el contenedor de MySQL y
  reejecutando la clase → 12 en verde.

## Entregado en

| PR | Unidad | Contenido |
|---|---|---|
| #31 | Refactor | `CambiarEstadoRequest` promovido de `usuario/dto/` a `common/dto/` (compartido por los cinco CRUD maestros) |
| #32 | Dominio | `Cliente`, `ClienteRepository`, los tres DTOs |
| #33 | Servicio | `ClienteService` + regla configurable de pedidos en curso |
| #34 | Controlador | `ClienteController` + Swagger + `@PreAuthorize` |
| #35 | Pruebas del servicio | 18 pruebas unitarias |
| #36 | Pruebas de contrato | 7 del DTO de alta + 9 del contrato de auditoría |
| #37 | Pruebas HTTP | 12 de HTTP y seguridad + los módulos de test de Spring Boot 4 en el `pom.xml` |
| #38 | Documentación | Manual de consumo + colección de Postman |

## Desviaciones vs especificación

1. **Spring Data JPA en lugar de JdbcTemplate.** La tarea de backend prescribe `ClienteRowMapper`
   y SQL explícito; el código ya entregado en el repositorio (HU-01 a HU-04) usa JPA, y `pom.xml`
   no incluye `spring-boot-starter-jdbc`. Se siguió el código existente: `Cliente` es una entidad
   `@Entity` y `ClienteRepository` un `JpaRepository`. El mapeo de `clientes` es 1:1
   (`id_cliente int unsigned` → `Integer`, `estado tinyint(1)` → `Boolean`), el mismo que ya usa
   `usuarios`. La divergencia con la documentación base (`00-base/01-CONVENCIONES.md` §2 y
   `00-base/06-ARQUITECTURA-BACKEND.md` §4) queda anotada para la entrega 2.
2. **`@JsonInclude(ALWAYS)` en `ClienteResponse`.** La configuración global
   `spring.jackson.default-property-inclusion: non_null` hace que un campo nulo **desaparezca** del
   JSON en lugar de aparecer como `null`; el contrato de HU-05 y el caso CP-01 exigen que `contacto`
   y `telefono` se vean como `null`. Es un override por DTO: el ajuste global se conserva para el
   resto de módulos.
3. **El 409 `CLIENTE_CON_PEDIDOS_ACTIVOS` es bloqueante y configurable.** La especificación §5 lo
   describe como «advertencia configurable» y no fija ningún caso de prueba para él; se implementó
   bloqueante, con la regla apagable por configuración, y se documentó el criterio exacto de
   «pedido en curso» (`PENDIENTE` y `EN_PREPARACION`).
4. **La consulta de pedidos en curso es SQL nativo.** La tabla `pedidos` existe en el esquema pero
   todavía no tiene entidad JPA (eso llega en HU-16), así que la cuenta es una consulta nativa
   sobre la tabla. Queda anotado en el javadoc que debe migrar al repositorio de pedidos cuando
   exista la entidad.
5. **`CambiarEstadoRequest` se promovió a `common/dto/`.** La tarea lo pedía dentro de
   `cliente/dto/`, pero el record es idéntico al de usuarios y cinco CRUD lo copiarían; duplicarlo
   cinco veces es justo lo que una plantilla debe evitar.
6. **Dos dependencias de test nuevas en `pom.xml`.** Spring Boot 4 separó los slices de prueba en
   módulos propios: `spring-boot-webmvc-test` (sin él no existe `@WebMvcTest`) y
   `spring-boot-security-test` (sin él el slice no aplica la cadena de seguridad a MockMvc y las
   pruebas de autorización no probarían nada).
7. **El `PUT` ignora un `activo` en el cuerpo.** El DTO de actualización no lleva `estado`; un
   `activo` extra en el JSON no cambia el estado del cliente (verificado en runtime). El estado solo
   cambia por el `PATCH` dedicado.
8. **El filtro por nombre pasa el texto a minúsculas antes de buscar.** La comparación en base de
   datos es insensible a mayúsculas y acentos por la collation `utf8mb4_0900_ai_ci`; el servicio
   normaliza con `toLowerCase(Locale.ROOT)` por simetría con `UsuarioService`. El **nombre
   almacenado** no se normaliza: se guarda tal como lo escribió el usuario, solo recortado.

## Pendientes / deuda

- Batería de QA formal (`tarea-qa.md`) y corrida de la colección con `newman`: las ejecuta QA.
- **La consulta nativa de pedidos en curso debe migrar** al repositorio de pedidos cuando exista
  la entidad `Pedido` (HU-16). No cambia el contrato HTTP.
- **La definición de «pedido en curso» depende de los estados de `pedidos.estado`**, que HU-16
  todavía no ha fijado en código. Si HU-16 introduce un estado nuevo, hay que revisar la lista de
  la consulta.
- **El frontend tiene que avisar del nombre duplicado** con el listado por prefijo: el backend no
  expone un endpoint de duplicados (R-03 dice que el aviso es informativo, no bloqueante).
- **HU-05 es la plantilla**: HU-06 (proveedores), HU-07 (unidades), HU-08 (materias primas) y HU-11
  (productos) copian su estructura. Conviene decidir pronto si repiten el `@JsonInclude(ALWAYS)` o
  si el ajuste global se cambia en `common`.
