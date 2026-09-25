# Hechos — lo que el Backend entregó por HU

Este directorio registra lo que el **Backend** hizo por cada historia de usuario, sin
importar el estado de QA: qué se construyó, el contrato, evidencia de verificación y
deudas. La verificación de QA es un checklist aparte (§9 de
`../../../docs/00-base/05-ESTANDARES-QA.md`), referenciada en cada ficha.

## Criterios de cierre QA (referencia, los ejecuta QA)

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
| Fecha de entrega | YYYY-MM-DD |
| Commits / PR | <links> |
| Colección Postman | <archivo y carpeta> |
| Estado QA | <pendiente / ejecutado, con resultado> |

## Qué se construyó

| Método | Ruta | Códigos | Descripción |
|---|---|---|---|
| POST | /api/v1/... | 201/400/409 | ... |

Reglas de negocio implementadas (las que aplican de `especificacion.md`).

## Contrato resumido

Request y response de ejemplo reales (copiados de una ejecución pasada, no inventados).

## Evidencia (verificación backend)

Lo que el Backend corrió: `mvn test`, colección newman y chequeos de runtime, con los
resultados exactos. Estado de QA va en la fila `Estado QA` de la cabecera, no aquí.

## Desviaciones vs especificación

Lo que difiere del contrato documentado, o "ninguna".

## Pendientes / deuda

Defectos menores aceptados al cerrar, o "ninguno".
```

## Índice de HUs cerradas

| HU | Título | Entregada |
|---|---|---|
| HU-01 | Registro de usuarios | 2026-09-25 · [`HU-01-registro-usuarios.md`](HU-01-registro-usuarios.md) |
