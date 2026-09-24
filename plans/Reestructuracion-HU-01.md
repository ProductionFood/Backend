# Reestructuración HU-01 — plan propuesto

| | |
|---|---|
| Fecha | 2026-09-24 |
| Alcance | Diagnóstico del 409 falso, migración a Flyway, base de datos en blanco, carpeta `Docs/` |
| Estado del plan | **Aprobado, NO implementado** — solo se crearon `Docs/` y este archivo |
| Enlace | Defecto y contexto en `Docs/Manuales_Consumo_Frontend/HU-01-registro-usuarios.md` |

## 1. Diagnóstico del 409 falso (causa raíz confirmada)

**Síntoma:** `POST /api/v1/usuarios` devuelve `409 CORREO_DUPLICADO` aunque el correo sea
nuevo (probado con `cocheroc@productionfood.local`).

**Evidencia recogida:**

1. `productionfood.usuarios` tiene **1 sola fila**: la semilla
   `admin@productionfood.local` (V3). `cocheroc@...` → 0 filas. No hay duplicado posible.
2. `UsuarioService.crear` **nunca asigna `estado`** (`UsuarioService.java:47-51` solo
   setea nombre, correo, password, rol) → Hibernate envía `estado = NULL`.
3. La columna es `NOT NULL DEFAULT 1`; el DEFAULT **no aplica ante un NULL explícito**.
   Reproducido en SQL (con `ROLLBACK`, sin dejar datos):

   ```
   ERROR 1048 (23000): Column 'estado' cannot be null
   ```

4. El `catch (DataIntegrityViolationException)` de `UsuarioService.java:53-59` convierte
   **cualquier** violación de integridad en `CORREO_DUPLICADO` → el mensaje "Ya existe un
   usuario registrado con el correo indicado" es **falso**: enmascara la falla real.
   (`GlobalExceptionHandler` ni siquiera llega a ver la excepción.)

**Corrección pautada (fase A):**

- Asignar `usuario.setEstado(Boolean.TRUE)` en `crear()`.
- Dejar de tradear ciegamente `DataIntegrityViolationException`: solo mapear a
  `CORREO_DUPLICADO` si la causa es la unique key de `correo`; el resto →
  `CONFLICTO_INTEGRIDAD` (ya existe en `GlobalExceptionHandler:46-53`).
- Verificación: correo fresco → 201; repetido → 409 real; `admin@productionfood.local` → 409 real.

## 2. Decisiones tomadas

| Tema | Decisión | Motivo |
|---|---|---|
| Migraciones de esquema | **Flyway** (`flyway-core` + `flyway-mysql`) | La DB no puede ser estática: el esquema evoluciona con cada HU y debe versionarse |
| `ddl-auto` | Se mantiene **`validate`** | El ORM valida contra el esquema migrado; nunca lo genera |
| DB estática (`initdb.d`) | **Se retira** el montaje de `../../docs/00-base/migraciones` en MySQL | Hoy las migraciones solo corren la primera vez que se crea el volumen; después ningún cambio de esquema se aplica |
| Papel de las SQL existentes (V1/V2/V3) | **Guía de contenido**, no mecanismo: su modelo E/R y correcciones alimentan las primeras migraciones Flyway, autoradas por la app | Mismo modelo, ciclo de vida correcto; el nombre `Vn__` ya es compatible con Flyway |
| Base de datos | **En blanco**, migrada por la app al arrancar | Un entorno cuyos cambios de esquema no aplican nunca no sirve para iterar HU |
| Fuente de verdad del esquema | `src/main/resources/db/migration/` (junto al código, viaja con el jar) | `filesystem:../docs/...` rompe al empaquetar/desplegar (la ruta es relativa al working dir, no al jar) |
| Evolución por HU | Cada HU que toque esquema = una versión nueva (`V4`, `V5`…); nada de SQL suelto | Regla 4 de `../../docs/README.md`, ahora con ruta dentro del backend |
| Compose | Nuevo `docker-compose.yml` **en Backend**, con `.env` y sin `initdb.d` | El compose de la raíz no está en ningún repo git y monta SQL dentro de MySQL |
| Documentación | `Docs/` vive **en Backend** (Hechos, Manuales_Consumo_Frontend, Postman) | Queda versionada con el código que documenta |

## 3. Fases de implementación

### Fase A — Corregir el 409 (bug, no feature)

1. `UsuarioService.crear`: `setEstado(TRUE)` + mapeo honesto de
   `DataIntegrityViolationException` (solo unique de correo → `CORREO_DUPLICADO`).
2. Colección Postman: parametrizar el correo de prueba con `{{correo}}` — hoy
   `maria@productionfood.local` está hardcodeado en "Crear Usuario" y en "Correo Duplicado",
   así que la segunda corrida produce un 409 inesperado.
3. Verificación: POST fresco → 201; repetido → 409 `CORREO_DUPLICADO`; rol 999 → 409
   `ROL_INEXISTENTE`; casos 400/403 intactos.

### Fase B — Flyway

1. `pom.xml`: `flyway-core` + `flyway-mysql` (desde Flyway 10 el soporte MySQL va aparte).
2. `application.yml`:
   ```yaml
   spring:
     flyway:
       locations: classpath:db/migration
       baseline-on-migrate: true     # BD pre-existente sin historial → parte de V3
       baseline-version: 3
       connect-retries: 30           # espera a MySQL en el arranque
   ```
3. Crear `src/main/resources/db/migration/` con las primeras versiones, **guiándose en**
   `../../docs/00-base/migraciones/` (esquema E/R + correcciones + semilla mínima:
   roles 1-5 y unidades son FK obligatoria de todo lo demás).
4. Quitar el montaje `initdb.d` del compose (fase C) — nunca deben competir dos mecanismos.

### Fase C — Compose en blanco con `.env`

`Backend/docker-compose.yml`:

- Servicio `mysql:8.4` con healthcheck, `MYSQL_DATABASE`/`MYSQL_ROOT_PASSWORD` tomados de
  `.env` (Compose auto-carga `.env` para interpolar `${DB_NAME}`, `${DB_PASSWORD}`).
- **Sin** volumen de init: la DB nace vacía; Flyway la construye.
- `phpMyAdmin` opcional en 8081.
- Eliminar `ProductionFood/docker-compose.yml` (fuera de git, obsoleto).
- Primer arranque: `docker compose down -v` — hoy solo hay la semilla, cero datos reales.
- Actualizar `../../docs/00-base/09-SETUP-ENTORNO.md` (documenta `docker run` + `mysql < V1`).

### Fase D — Documentación (hecha, salvo lo indicado)

- [x] `Docs/README.md` — índice y reglas
- [x] `Docs/Hechos/README.md` — criterios de cierre QA + plantilla + índice (HU-01 en curso)
- [x] `Docs/Manuales_Consumo_Frontend/README.md` — guía de manuales
- [x] `Docs/Manuales_Consumo_Frontend/HU-01-registro-usuarios.md` — manual con limitaciones
- [x] `Docs/Postman/` — colección movida desde `postman/` (`git mv`)
- [x] `plans/Reestructuracion-HU-01.md` — este archivo
- [ ] Actualizar `../../docs/00-base/09-SETUP-ENTORNO.md` y la ruta de migraciones de la
      regla 4 en `../../docs/README.md` (repo `docs`, se hace en fase C)

## 4. Orden de ejecución (commits atómicos)

| # | Commit | Repo |
|---|---|---|
| 1 | `fix(HU-01): estado nulo en creación de usuario — 409 falso` | Backend |
| 2 | `fix(postman): parametrizar correo con {{correo}}` | Backend |
| 3 | `feat(backend): Flyway — dependencias y configuración` | Backend |
| 4 | `refactor(backend): migraciones a src/main/resources/db/migration` | Backend |
| 5 | `feat(backend): docker-compose con MySQL en blanco desde .env` | Backend |
| 6 | `docs: actualizar 09-SETUP-ENTORNO y ubicación de migraciones` | docs |

Los renombrados de SQL no cuentan para el recuento de líneas (movimientos sin cambio de
contenido); el bug y su test caben en commits < 50 líneas.

## 5. Criterios de verificación global

- [ ] `docker compose down -v` → compose nuevo → DB vacía al entrar (solo `information_schema` + `productionfood` sin tablas)
- [ ] Arranque de la app → `flyway_schema_history` con las versiones iniciales aplicadas
- [ ] `ddl-auto: validate` sin error de arranque (entidades ↔ esquema Flyway)
- [ ] `POST /api/v1/usuarios` con correo fresco → **201**; repetido → 409 real
- [ ] Colección Postman: dos corridas seguidas sin colisiones
- [ ] Ningún script se ejecuta vía `docker-entrypoint-initdb.d`

## 6. Fuera de alcance de este plan

- Nuevas funcionalidades de HU-01 (HU-02 gestión de usuarios es otra historia).
- Despliegue a AWS RDS (`../../docs/00-base/11-DESPLIEGUE-AWS-RDS.md`) — Flyway quedará
  listo para apuntar allí, pero el despliegue no entra aquí.
- Corregir el desfase JdbcTemplate→JPA documentado en `00-base/01-CONVENCIONES.md` y
  `06-ARQUITECTURA-BACKEND.md` (tarea del repo `docs`, separada).
