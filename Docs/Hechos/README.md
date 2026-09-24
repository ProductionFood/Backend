# Hechos — historias de usuario cerradas

Una HU está "hecha" cuando pasa los criterios de cierre de QA, no cuando el código está
escrito. Este criterio sale de `../../../docs/00-base/05-ESTANDARES-QA.md` §9.

## Criterios de cierre (resumen)

- [ ] 100% de los casos de `tarea-qa.md` ejecutados.
- [ ] 100% de los positivos pasan.
- [ ] 100% de los negativos devuelven el código y `code` correctos.
- [ ] La batería SEC-01..SEC-05 pasa en todos los endpoints de la HU.
- [ ] Cero defectos críticos o altos abiertos.
- [ ] La colección de Postman está actualizada y versionada en `Docs/Postman/`.

## Plantilla — copiar como `HU-NN-nombre.md`

```markdown
# HU-NN — <título> · HECHA

| | |
|---|---|
| Fecha de cierre | YYYY-MM-DD |
| Commits / PR | <links> |
| Colección Postman | <archivo y carpeta> |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| POST | /api/v1/... | 201/400/409 | ... |

Reglas de negocio implementadas (las que aplican de `especificacion.md`).

## Contrato resumido

Request y response de ejemplo reales (copiados de una ejecución pasada, no inventados).

## Evidencia QA

Checklist ejecutado contra `tarea-qa.md`:

- [ ] CP-01 ...
- [ ] CP-02 ...

Batería de seguridad SEC-01..SEC-05: resultado.

## Desviaciones vs especificación

Lo que difiere del contrato documentado, o "ninguna".

## Pendientes / deuda

Defectos menores aceptados al cerrar, o "ninguno".
```

## Índice de HUs cerradas

| HU | Título | Cerrada |
|---|---|---|
| — | (ninguna todavía) | — |

> **HU-01 está en curso**: defecto de 409 falso en creación de usuarios.
> Diagnóstico y plan en `../../plans/Reestructuracion-HU-01.md`.
