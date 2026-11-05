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
| [HU-14 — Detalle de compra](HU-14-detalle-compra.md) | Detalle de compra (líneas, totales y comparativo de costo) | Backend verificado en caliente 2026-10-28 · frontend pendiente |
| [HU-15 — Historial de compras](HU-15-historial-compras.md) | Historial de compras (filtros, resumen del período y gasto por proveedor) | Backend verificado en caliente 2026-10-29 · frontend pendiente |
| [HU-16 — Creación de pedidos](HU-16-creacion-pedidos.md) | Pedidos (cabecera, estados y devolución de stock al cancelar) | Backend verificado en caliente 2026-10-30 · frontend pendiente |
| [HU-17 — Productos del pedido](HU-17-productos-pedido.md) | Detalle del pedido (stock FEFO, alta, edición y eliminación de líneas) | Backend verificado 2026-10-30 (QA ciego 23/24 CP y 25/25 SEC) · frontend pendiente |
| [HU-18 — Registro de devoluciones](HU-18-registro-devoluciones.md) | Devoluciones (bandeja, registro, aprobación con reposición y kardex) | Backend verificado 2026-11-02 (QA ciego 25/25 CP y 25/25 SEC) · frontend pendiente |
| [HU-19 — Lotes de producto terminado](HU-19-lotes-producto-terminado.md) | Lotes de producto (listado con filtros, alta, detalle y kardex) | Backend verificado en caliente 2026-10-25 · frontend pendiente |
| [HU-20 — Planes de producción](HU-20-planes-produccion.md) | Planes de producción (listado con filtros, alta, edición y estados) | Backend verificado 2026-11-05 (QA ciego 23/23 CP y 25/25 SEC) · frontend pendiente |
| [HU-21 — Productos del plan de producción](HU-21-detalle-plan-produccion.md) | Detalle del plan (productos, cantidades, avance y consumo teórico) | Backend verificado 2026-11-05 (QA ciego 23/23 CP y 25/25 SEC) · frontend pendiente |
| [HU-22 — Consumo de materia prima](HU-22-consumo-materia-prima.md) | Consumo por línea (teórico, registro con descuento de inventario, corrección y listado) | Backend verificado 2026-11-05 (QA ciego 24/24 CP, 20/20 SEC y roles 20/20) · frontend pendiente |
| [HU-23 — Actualización de inventario](HU-23-actualizacion-inventario.md) | Efecto de los consumos de HU-22 sobre el inventario (saldos antes y después, `alertas[]`, stock insuficiente, indicador global; sin endpoints propios) | Backend verificado 2026-11-05 (QA ciego CP-01…CP-23 y SEC-01…05 en los 3 endpoints, con CP-03, CP-11, CP-12 y CP-20 parciales) · frontend pendiente |
| [HU-24 — Dashboard resumen](HU-24-dashboard-resumen.md) | Dashboard del día (cinco tarjetas, pedidos de hoy y lotes por vencer paginados, tarjetas por rol) | Backend verificado 2026-11-05 (QA ciego CP-01…CP-21 y SEC-01…05 en los 3 endpoints) · frontend pendiente |
| [HU-25 — Consulta de pedidos](HU-25-consulta-pedidos.md) | Pedidos (filtros combinables, importes bruto/devoluciones/neto, detalle opcional, resumen, ventas por cliente y ranking de productos) | Backend verificado 2026-11-05 (QA ciego 25/25 CP y SEC-01…05 con 2 reservas) · frontend pendiente |
| [HU-26 — Eficiencia de producción](HU-26-eficiencia-produccion.md) | Reporte de eficiencia (cumplimiento, rendimiento y rendimiento ajustado por separado, por plan, por período y desviación por materia prima con tendencia) | Backend verificado 2026-11-05 (QA ciego CP-01…CP-25 y SEC-01…05 en los 3 endpoints; ajustes A-1…A-9 aplicados: `planesEnProceso`, nulls por fuera de receta, tendencia configurable, mensajes 401) · frontend pendiente |
| [HU-27 — Alerta de stock bajo](HU-27-alerta-stock-bajo.md) | Alertas de stock (listado, resumen para el indicador global y sugerencia de compra por proveedor) | Backend verificado (QA ciego CP-01…CP-26 y SEC-01…05) · frontend pendiente |

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
