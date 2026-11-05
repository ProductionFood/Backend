# HU-23 — Actualización de inventario por producción · Manual de consumo para el frontend

Contrato práctico para mostrar el efecto de un consumo de materia prima sobre el inventario:
saldos antes y después, alertas de stock bajo, stock insuficiente e indicador global.
Complementa `../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-23-actualizacion-inventario.md`](../Hechos/HU-23-actualizacion-inventario.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones (login de HU-03).
- **No hay endpoints propios.** HU-23 es el comportamiento de los endpoints de HU-22
  (`POST /detalles-plan/{id}/consumos` y `PUT /consumos/{id}`) más la lectura de
  `GET /inventario/alertas` (HU-27). Ver
  [`HU-22`](HU-22-consumo-materia-prima.md) y [`HU-27`](HU-27-alerta-stock-bajo.md).
- **Roles:** registrar y corregir, `ADMIN` y `PRODUCCION`. `GET /inventario/alertas`:
  `ADMIN`, `PRODUCCION`, `COMPRAS` y `CONSULTA` (`VENTAS` recibe `403`).

> **Estado:** backend verificado con QA ciego. El consumo desde Angular sigue pendiente.

---

## 1. Qué cambia en las respuestas de HU-22

Las respuestas del `POST` (201) y del `PUT` (200) incluyen:

- `inventario: { anterior, posterior }`: saldos antes y después del movimiento (se **omite** si
  no hubo movimiento: consumo real cero, o una corrección cuya diferencia redondea a `0.00`).
- `alertas[]`: materias primas que quedaron en alerta tras la operación. **Siempre presente**;
  `[]` si no hay alerta. Con saldo **igual** al mínimo no hay alerta.

El `GET /planes-produccion/{id}/consumos` **no** trae `inventario` ni `alertas`.

```json
// 201 Created (fragmento)
{ "idConsumo": 120,
  "idDetallePlan": 20,
  "materiaPrima": { "idMateriaPrima": 5, "nombre": "Harina de trigo", "unidad": "kg" },
  "cantidadTeorica": 0.250,
  "cantidadReal": 16.200,
  "inventario": { "anterior": 62.50, "posterior": 46.30 },
  "alertas": [
    { "idMateriaPrima": 5, "nombre": "Harina de trigo", "unidad": "kg",
      "cantidadDisponible": 46.30, "stockMinimo": 50.00, "faltante": 3.70,
      "nivel": "BAJO", "costoUnitario": 4200.00, "costoReposicion": 15540.00,
      "ultimaActualizacion": "2026-11-05T15:20:11" } ] }
```

El objeto de `alertas[]` es el mismo de `GET /inventario/alertas`; los campos de costo pueden
ignorarse en el aviso. Sin alerta: `"alertas": []`.

---

## 2. Niveles y presentación

| `nivel` | Significado | Estilo |
|---|---|---|
| `BAJO` | Saldo mayor que cero pero por debajo del mínimo | `MatChip` ámbar |
| `AGOTADO` | Saldo en cero | `MatChip` rojo |

- La alerta es **informativa y posterior** a una operación ya hecha: mostrarla en un banner o
  snackbar, nunca como diálogo bloqueante ni como error.
- Con `faltante` se puede decir «Faltan 3,70 kg para llegar al mínimo». Enlazar a HU-27 y a crear
  una compra (HU-13).
- Si `alertas` es `[]`, no mostrar nada.

---

## 3. Antes y después del registro

- **Antes:** en el diálogo, consultar el teórico (`GET /detalles-plan/{id}/consumo-teorico`) y
  mostrar `disponible` junto a cada ingrediente. Advertir que **registrar descuenta stock**. El
  impacto previsto se calcula en el cliente como `disponible − cantidadReal`, solo como ayuda.
- **Después:** pintar `inventario.anterior → inventario.posterior` por ingrediente y, si hay,
  el banner de `alertas[]`. Si falta `inventario`, mostrar «Sin movimiento de inventario».
- **Redondeo:** el consumo guarda 3 decimales y el inventario 2 (`HALF_UP` por cada consumo).
  Con consumos muy pequeños (por ejemplo `0.005`) el inventario puede bajar más de lo que suma la
  cantidad real; mostrar los saldos del servidor, no recalcularlos.
- **Zona horaria:** `fechaRegistro` y `ultimaActualizacion` vienen en hora de Colombia (-05:00)
  sin offset en el texto; no convertirlas como UTC.

---

## 4. Errores propios de esta historia

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `STOCK_INSUFICIENTE` | 409 | No hay saldo suficiente, o la materia prima no tiene fila de inventario | Mostrar el `message`, indicar que **nada se registró** y sugerir un ajuste de inventario si físicamente sí había |
| `CONSUMO_DUPLICADO` | 409 | La materia prima ya tiene consumo en la línea (típico de un doble clic) | No reintentar; refrescar el listado y llevar a la corrección |
| `PLAN_NO_EN_PROCESO` | 409 | El plan no está `EN_PROCESO` | Modo lectura |
| `ERROR_INTERNO` | 500 | Fallo inesperado: la operación se revirtió por completo | «No se pudo registrar. Nada cambió.» con «Reintentar» |

Los demás códigos (`400`, `401`, `403`, `404`) son los de HU-22.

**`STOCK_INSUFICIENTE`.** Las cifras van dentro del `message` (por ejemplo «No hay suficiente
Levadura. Disponible: 0,15 kg. Requerido: 2,00 kg.»): **no hay campo `detalle` estructurado**, no
intentar parsearlo; mostrar el texto tal cual. En el `PUT` el «requerido» es la **diferencia** a
descontar, no la cantidad total. Una materia prima sin fila de inventario responde también
`STOCK_INSUFICIENTE` (no `500`). Como alternativa más rica, el `disponible` del teórico ya se
mostró antes de confirmar.

**Doble clic.** Deshabilitar el botón mientras la petición está en vuelo. Si aun así llega
`CONSUMO_DUPLICADO`, el primer envío ya se registró: tratarlo como «ya registrado».

Mensaje no bloqueante: la operación es **todo o nada**; ante cualquier error el inventario no
cambió.

---

## 5. Indicador global de alertas

```http
GET /api/v1/inventario/alertas/resumen
```

Devuelve `{ "total": 2, "agotadas": 1, "bajas": 1 }` y lo puede consultar cualquier rol
autenticado (ver el manual de HU-27). Para la pantalla de detalle:

| Endpoint | ADMIN | PRODUCCION | COMPRAS | CONSULTA | VENTAS |
|---|---|---|---|---|---|
| `GET /inventario/alertas` | sí | sí | sí | sí | 403 |
| `GET /inventario/alertas/resumen` | sí | sí | sí | sí | sí |

Refrescar el indicador tras cada `201`/`200` de consumo (o usar `alertas[]` de la respuesta para
actualizar el contador sin otra llamada) y de forma periódica.

---

## 6. Estados de pantalla

| Estado | Qué se muestra |
|---|---|
| Registrando | Botón con spinner y deshabilitado |
| Registro correcto sin alertas | Saldos `anterior → posterior` |
| Registro correcto con alertas | Saldos + banner ámbar/rojo con las materias primas en alerta |
| Sin movimiento | «Sin movimiento de inventario» (cantidad cero o diferencia nula) |
| `409 STOCK_INSUFICIENTE` | Rojo junto al ingrediente, mensaje del servidor y sugerencia de ajuste |
| `409 CONSUMO_DUPLICADO` | Aviso y refresco del listado |
| Error de red / 5xx | Mensaje + «Reintentar»; nada se registró |
| 401 / 403 | Login / ocultar la acción |

---

## 7. Limitaciones conocidas

- **Un ingrediente por petición.** El contrato de HU-22 no admite un lote; para varios
  ingredientes se envía un `POST` por cada uno. Cada `POST` es atómico, pero **el conjunto no**:
  si el tercero falla con `409`, los dos primeros ya quedaron registrados. La interfaz debe
  decirlo y dejar corregirlos.
- **`409` sin `detalle` estructurado.** Las cifras solo van en el `message`.
- **Sesgo de redondeo** con consumos muy pequeños (ver §3).
- **El kardex de salida no guarda usuario**; en el kardex de HU-10 aparece como «Sistema».
- **Frontend pendiente:** este manual es el contrato para Angular.
