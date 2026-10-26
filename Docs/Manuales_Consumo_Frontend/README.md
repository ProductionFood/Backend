# Manuales de consumo para el frontend

Cada manual explica **cómo llamar la API desde Angular**: autenticación, endpoints con
request/response de ejemplo, códigos de error y casos límite. Son el contrato práctico para
quien consume, complemento de `../../../PFDocs/00-base/04-CONTRATO-API.md`.

## Índice

| Manual | Módulo | Estado |
|---|---|---|
| [HU-01 — Registro de usuarios](HU-01-registro-usuarios.md) | Auth + Usuarios + Roles | Obsoleto (2026-10-01): ver aviso en su cabecera. Para autenticar, usar HU-03 |
| [HU-02 — Gestión de usuarios](HU-02-gestion-usuarios.md) | Usuarios (consulta, edición, estado) | Entregado 2026-10-01 |
| [HU-03 — Inicio de sesión](HU-03-inicio-sesion.md) | Auth (login + perfil) | Entregado 2026-10-12 |
| [HU-04 — Bitácora de auditoría](HU-04-bitacora-auditoria.md) | Auditoría (consulta solo ADMIN) | Entregado 2026-10-12 |
| [HU-05 — Gestión de clientes](HU-05-gestion-clientes.md) | Clientes (alta, edición, consulta y estado) | Entregado 2026-10-20 |
| [HU-06 — Gestión de proveedores](HU-06-gestion-proveedores.md) | Proveedores (escritura ADMIN y COMPRAS) | Backend entregado 2026-10-19 |
| [HU-07 — Unidades de medida](HU-07-unidades-medida.md) | Unidades de medida (catálogo, alta y edición) | Backend entregado 2026-10-20 |
| [HU-08 — Gestión de materias primas](HU-08-materias-primas.md) | Materias primas (listado paginado, alta, edición y estado) | Backend entregado 2026-10-22 |
| [HU-09 — Lotes de materia prima](HU-09-lotes-materia-prima.md) | Lotes (listado con filtros, detalle y alta manual o desde compra) | Backend entregado 2026-10-23 · frontend pendiente |
| [HU-10 — Consulta de inventario](HU-10-consulta-inventario.md) | Inventario de materia prima (listado, detalle y kardex) | Backend entregado 2026-10-25 · frontend pendiente |
| [HU-11 — Gestión de productos](HU-11-productos.md) | Productos (catálogo, alta, edición y estado) | Backend entregado 2026-10-23 |
| [HU-12 — Gestión de recetas](HU-12-gestion-recetas.md) | Recetas (cabecera, ingredientes, costos y estado) | Backend verificado en caliente 2026-10-25 · frontend pendiente |
| [HU-13 — Registro de compras](HU-13-registro-compras.md) | Compras (cabecera, estados y recepción con entrada de stock) | Backend verificado en caliente 2026-10-26 · frontend pendiente |
| [HU-19 — Lotes de producto terminado](HU-19-lotes-producto-terminado.md) | Lotes de producto (listado con filtros, alta, detalle y kardex) | Backend verificado en caliente 2026-10-25 · frontend pendiente |

## Qué debe tener un manual

1. **Autenticación** — cómo obtener el token para este módulo y dónde ponerlo.
2. **Endpoints** — método, ruta, códigos que devuelve, body y respuesta de ejemplo reales.
3. **Errores** — tabla de `code` esperados (`VALIDACION_FALLIDA`, `CORREO_DUPLICADO`, …)
   con qué hace el frontend en cada caso.
4. **Casos límite** — lo que el frontend debe validar antes de llamar (longitudes, formatos).
5. **Limitaciones conocidas** — bugs abiertos que afectan el consumo, con link al plan.

## Reglas

- Los ejemplos salen de respuestas **reales** (Postman/Swagger), no del deseo.
- Nada de tokens ni credenciales reales en los ejemplos: solo la semilla pública de
  desarrollo (`admin@productionfood.local` / `Admin123*`) cuando haga falta ilustrar.
- Si el backend cambia de contrato, el manual se actualiza en el **mismo commit**.
