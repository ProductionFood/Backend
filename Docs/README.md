# Docs — Backend ProductionFood

Documentación operativa del backend: qué se ha terminado, cómo consumir la API desde el
frontend y con qué se prueba. La documentación de proyecto (reglas transversales, HU en
preparación) sigue viva en `../../docs/` — esta carpeta es del equipo de implementación.

## Estructura

```
Docs/
├── Hechos/                      Historias de usuario CERRADAS: qué se hizo y con qué evidencia
│   ├── README.md                    Criterios de cierre + plantilla para escribir una HU hecha
│   └── HU-NN-*.md                   Una entrada por HU terminada
├── Manuales_Consumo_Frontend/   Cómo consume el frontend la API (Angular llamará estos endpoints)
│   ├── README.md                    Índice y guía para escribir un manual
│   └── HU-NN-*.md                   Un manual por módulo/HU
└── Postman/                     Colecciones de pruebas (se versionan aquí, no en la raíz)
    └── ProductionFood-HU01.postman_collection.json
```

## Cómo usar estas carpetas

| Si quieres… | Ve a |
|---|---|
| Saber qué ya está terminado y probado | [`Hechos/`](Hechos/README.md) |
| Saber cómo llamar la API desde el frontend | [`Manuales_Consumo_Frontend/`](Manuales_Consumo_Frontend/README.md) |
| Probar un endpoint a mano | [`Postman/`](Postman/) — importar la colección en Postman |

## Reglas

1. **Solo una HU entra en `Hechos/` cuando cierra los criterios de QA**
   (`../../docs/00-base/05-ESTANDARES-QA.md` §9). Terminar el código no es terminar la HU.
2. **Cada manual refleja el contrato real**, no el deseado. Si la implementación difiere de
   `../../docs/00-base/04-CONTRATO-API.md`, se documenta la diferencia y se reporta el defecto.
3. **Los tokens nunca se pegan en la colección** ni en documentos: se obtienen del login.
4. **Migraciones y esquema no viven aquí** — ver `plans/` para el estado de la
   reestructuración de HU-01 (Flyway + base de datos en blanco).

## Estado actual

| HU | Estado |
|---|---|
| HU-01 Registro de usuarios | **En curso** — defecto 409 falso abierto (ver [`../plans/Reestructuracion-HU-01.md`](../plans/Reestructuracion-HU-01.md)) |
