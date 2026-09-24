# Manuales de consumo para el frontend

Cada manual explica **cómo llamar la API desde Angular**: autenticación, endpoints con
request/response de ejemplo, códigos de error y casos límite. Son el contrato práctico para
quien consume, complemento de `../../../docs/00-base/04-CONTRATO-API.md`.

## Índice

| Manual | Módulo | Estado |
|---|---|---|
| [HU-01 — Registro de usuarios](HU-01-registro-usuarios.md) | Auth + Usuarios + Roles | En curso (ver defecto 409 en `../../plans/Reestructuracion-HU-01.md`) |

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
