# HU-26 — Eficiencia de producción · Manual de consumo para el frontend

Contrato práctico para pintar el reporte de eficiencia de producción (por período, por plan y por
materia prima). Complementa `../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-26-eficiencia-produccion.md`](../Hechos/HU-26-eficiencia-produccion.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** `/reportes/eficiencia` y `/reportes/eficiencia/{idPlan}` para `ADMIN` y `PRODUCCION`;
  `/reportes/eficiencia/materias-primas` para `ADMIN`, `PRODUCCION` y `COMPRAS`.
- **Dependencias:** los planes y sus productos son de HU-20/HU-21 y los consumos de HU-22. Esta
  historia es de **solo lectura**.

> **Estado:** backend verificado con QA ciego (CP-01…CP-25 y SEC-01…SEC-05). El consumo desde
> Angular sigue pendiente. Los ids, nombres y cifras de los ejemplos son ilustrativos y dependen de
> la base.

> **Tres métricas, nunca una sola.** El reporte entrega por separado el **cumplimiento** del plan
> (¿se fabricó lo prometido?), el **rendimiento** de materia prima (¿se gastó lo previsto?) y el
> **rendimiento ajustado** (el rendimiento corregido por lo que realmente se produjo). No las sumes,
> no las promedies entre sí ni las muestres bajo una etiqueta «eficiencia» única.

---

## 1. Autenticación

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "correo": "admin@productionfood.local", "password": "Admin123*" }
```

```json
{ "token": "eyJhbGciOiJIUzM4NCJ9...",
  "tipo": "Bearer",
  "expiraEn": 3600,
  "usuario": { "idUsuario": 1, "nombre": "Administrador Inicial",
               "correo": "admin@productionfood.local", "rol": "ADMIN" } }
```

El frontend guarda el token y lo envía en cada llamada. Un `401 NO_AUTENTICADO` significa token
ausente, malformado o expirado: forzar el login de nuevo.

| Endpoint | ADMIN | PRODUCCION | COMPRAS | VENTAS | CONSULTA |
|---|---|---|---|---|---|
| `GET /reportes/eficiencia` | sí | sí | 403 | 403 | 403 |
| `GET /reportes/eficiencia/{idPlan}` | sí | sí | 403 | 403 | 403 |
| `GET /reportes/eficiencia/materias-primas` | sí | sí | sí | 403 | 403 |

Ocultar una acción en la pantalla no sustituye al `403`, que el backend aplica igual.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/reportes/eficiencia` | ADMIN / PRODUCCION | 200, 400, 401, 403 |
| GET | `/reportes/eficiencia/{idPlan}` | ADMIN / PRODUCCION | 200, 400, 401, 403, 404 |
| GET | `/reportes/eficiencia/materias-primas` | ADMIN / PRODUCCION / COMPRAS | 200, 400, 401, 403 |

Los tres son `GET` sin cuerpo; cualquier otro método responde `405 METODO_NO_PERMITIDO`. **No hay
caché**: cada llamada refleja el estado actual de la base. **No hay paginación**: el reporte y el
acumulado devuelven todo el período en una sola respuesta.

**Parámetros de fechas** (reporte y materias primas):

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `fechaInicio` | fecha `YYYY-MM-DD` | sin límite inferior | Inclusiva. Se filtra sobre la **fecha de producción** del plan |
| `fechaFin` | fecha `YYYY-MM-DD` | sin límite superior | Inclusiva |

- Ambas son opcionales y se pueden enviar solas. Un día suelto se pide con `fechaInicio` =
  `fechaFin`. Vacío (`?fechaInicio=`) se trata como ausente.
- `fechaInicio` posterior a `fechaFin` → `400 RANGO_FECHAS_INVALIDO`.
- Fecha mal formada (`2026-13-01`, `01-09-2026`, con hora…) → `400 PARAMETRO_INVALIDO`.
- `periodo.desde` y `periodo.hasta` devuelven lo que se envió, y `null` si no se envió.
- Un parámetro desconocido se ignora.

### 2.1. Reporte por período

```http
GET /api/v1/reportes/eficiencia?fechaInicio=2026-09-01&fechaFin=2026-09-30
```

```json
// 200 OK
{ "periodo": { "desde": "2026-09-01", "hasta": "2026-09-30" },
  "resumen": {
    "planesAnalizados": 2,
    "planesCancelados": 1,
    "cumplimientoPromedio": 83.34,
    "rendimientoPromedio": 96.30,
    "rendimientoAjustadoPromedio": 80.87,
    "registrosSinRendimiento": 1,
    "desviacionMateriaPrima": -30.67
  },
  "planes": [
    { "idPlan": 1, "fechaProduccion": "2026-09-18", "estado": "COMPLETADO",
      "responsable": "Ismael Batalla",
      "cumplimiento": 66.67,
      "rendimiento": 92.59,
      "rendimientoAjustado": 61.73,
      "productos": [
        { "producto": "Pan francés", "planificada": 90.00, "producida": 60.00,
          "cumplimiento": 66.67 } ],
      "consumos": [
        { "materiaPrima": "Harina de trigo", "unidad": "kg", "teorica": 15.000, "real": 16.200,
          "desviacion": 8.00, "eficiencia": 92.59, "rendimientoAjustado": 61.73 },
        { "materiaPrima": "Sal", "unidad": "kg", "teorica": 0.300, "real": 0.000,
          "desviacion": -100.00, "eficiencia": null, "rendimientoAjustado": null } ] },
    { "idPlan": 2, "fechaProduccion": "2026-09-20", "estado": "COMPLETADO",
      "responsable": "Ismael Batalla",
      "cumplimiento": 100.00,
      "rendimiento": 100.00,
      "rendimientoAjustado": 100.00,
      "productos": [
        { "producto": "Croissant", "planificada": 30.00, "producida": 30.00,
          "cumplimiento": 100.00 } ],
      "consumos": [
        { "materiaPrima": "Mantequilla", "unidad": "kg", "teorica": 3.000, "real": 3.000,
          "desviacion": 0.00, "eficiencia": 100.00, "rendimientoAjustado": 100.00 } ] },
    { "idPlan": 3, "fechaProduccion": "2026-09-25", "estado": "CANCELADO",
      "responsable": "Ismael Batalla",
      "cumplimiento": 0.00,
      "rendimiento": 100.00,
      "rendimientoAjustado": 0.00,
      "productos": [
        { "producto": "Pan francés", "planificada": 50.00, "producida": 0.00,
          "cumplimiento": 0.00 } ],
      "consumos": [
        { "materiaPrima": "Harina de trigo", "unidad": "kg", "teorica": 8.000, "real": 8.000,
          "desviacion": 0.00, "eficiencia": 100.00, "rendimientoAjustado": 0.00 } ] }
  ],
  "sinReceta": [
    { "producto": "Pan integral", "planes": 2,
      "nota": "Sin receta: no se puede calcular consumo teórico" } ] }
```

> Los valores del ejemplo son ilustrativos y se armaron para que el resumen cuadre con los planes:
> el plan `CANCELADO` (id 3) aparece en `planes[]` pero **no** entra en ningún promedio del
> `resumen`.

Cómo leer el reporte:

- **`resumen`.**
  - `planesAnalizados` son los planes **no cancelados** con al menos un consumo registrado;
    `planesCancelados` cuenta los cancelados (con consumo) del período.
  - `cumplimientoPromedio` es la media del cumplimiento de los planes analizados.
    `rendimientoPromedio` y `rendimientoAjustadoPromedio` son la media de los **consumos** con
    valor: los `null` **no se promedian como cero** (92, 100 y `null` dan 96, no 64).
  - `registrosSinRendimiento` cuenta los consumos que quedaron fuera de esos promedios porque su
    cantidad real fue 0. Muéstralo como nota («1 registro sin rendimiento»), sin tratarlo como error.
  - `desviacionMateriaPrima` es un indicador general de desviación: la **media sin ponderar** de las
    desviaciones acumuladas por materia prima. Un insumo de consumo muy pequeño pesa igual que la
    harina, así que muéstralo como referencia y no como conclusión (ver §8).
  - Sin planes en el período: contadores en `0` y promedios en `null`, sin error.
- **`planes[]`.** Un elemento por plan con consumo registrado, con su estado. Los `CANCELADO` se
  listan marcados (chip «Cancelado»): su cumplimiento bajo es esperable.
- **`planes[].productos[]`.** `planificada` frente a `producida` y su `cumplimiento`
  (`producida / planificada × 100`): puede pasar de 100 si se produjo de más y es `0.00` si no se
  produjo nada. Cantidades con 2 decimales.
- **`planes[].consumos[]`.** `teorica` frente a `real` (3 decimales, con su `unidad`), la
  `desviacion` y las dos lecturas de rendimiento (`eficiencia` y `rendimientoAjustado`).
- **`sinReceta`.** Productos de los planes del período que no tienen receta activa; para ellos no
  hay consumo teórico. Muéstralos en un aviso aparte para que el hueco sea visible; vacío si no hay.
- Los porcentajes salen como número JSON sin ceros finales (`66.67`, `96.0`, `0.0`): formatear con
  1 o 2 decimales en el cliente.

### 2.2. Detalle de un plan

```http
GET /api/v1/reportes/eficiencia/1
```

```json
// 200 OK  (la misma forma de un elemento de planes[], sin envoltorio)
{ "idPlan": 1, "fechaProduccion": "2026-09-18", "estado": "COMPLETADO",
  "responsable": "Ismael Batalla",
  "cumplimiento": 66.67,
  "rendimiento": 92.59,
  "rendimientoAjustado": 61.73,
  "productos": [
    { "producto": "Pan francés", "planificada": 90.00, "producida": 60.00,
      "cumplimiento": 66.67 } ],
  "consumos": [
    { "materiaPrima": "Harina de trigo", "unidad": "kg", "teorica": 15.000, "real": 16.200,
      "desviacion": 8.00, "eficiencia": 92.59, "rendimientoAjustado": 61.73 },
    { "materiaPrima": "Sal", "unidad": "kg", "teorica": 0.300, "real": 0.000,
      "desviacion": -100.00, "eficiencia": null, "rendimientoAjustado": null } ] }
```

- `idPlan` es un entero positivo. Un valor no numérico o fuera del rango de enteros
  (`abc`, `1.5`, `2147483648`) → `400 PARAMETRO_INVALIDO`.
- Plan que no existe (también `0` o negativo) → `404 PLAN_INEXISTENTE`.
- Plan que existe pero **no tiene consumos registrados** → `404 PLAN_SIN_CONSUMOS`. No es un error
  de la pantalla: mostrar «Este plan aún no tiene consumos registrados».
- `rendimiento` y `rendimientoAjustado` del plan son la media de los valores no nulos de sus
  consumos, y `null` si ninguno tiene valor.

### 2.3. Desviación acumulada por materia prima

```http
GET /api/v1/reportes/eficiencia/materias-primas?fechaInicio=2026-09-01&fechaFin=2026-09-30
```

```json
// 200 OK  (arreglo, sin paginación)
[ { "materiaPrima": "Harina de trigo", "unidad": "kg",
    "teoricaAcumulada": 180.000, "realAcumulada": 192.600,
    "desviacion": 7.00, "planes": 12,
    "tendencia": "CONSUMO_EXCESIVO" },
  { "materiaPrima": "Levadura", "unidad": "kg",
    "teoricaAcumulada": 1.800, "realAcumulada": 1.795,
    "desviacion": -0.28, "planes": 12,
    "tendencia": "NORMAL" },
  { "materiaPrima": "Azúcar", "unidad": "kg",
    "teoricaAcumulada": 0.000, "realAcumulada": 2.000,
    "desviacion": null, "planes": 1,
    "tendencia": "SIN_BASE" } ]
```

- Suma, por materia prima, la teórica y la real de los consumos de planes **no cancelados** del
  período. `desviacion = (real − teórica) / teórica × 100` (positiva = se gastó de más) y llega
  `null` si la teórica acumulada es 0.
- `planes` cuenta los planes distintos que consumieron esa materia prima.
- Orden: mayor desviación en valor absoluto primero y `desviacion: null` al final.
- Sin consumos en el período: `[]` con `200`.

**`tendencia`** (umbral fijo de ±5 puntos porcentuales; los extremos cuentan como normales):

| Valor | Condición | Sugerencia de presentación |
|---|---|---|
| `CONSUMO_EXCESIVO` | `desviacion > 5` | Rojo: se gasta de más de forma sostenida |
| `CONSUMO_REDUCIDO` | `desviacion < -5` | Ámbar: se gasta de menos; revisar receta o registro |
| `NORMAL` | `-5 ≤ desviacion ≤ 5` | Verde |
| `SIN_BASE` | `desviacion = null` | Gris «—»: no hay teórico contra el cual comparar |

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-11-05T15:08:41.123", "status": 400,
  "error": "Bad Request", "code": "RANGO_FECHAS_INVALIDO",
  "message": "La fecha de inicio no puede ser posterior a la fecha de fin.",
  "path": "/api/v1/reportes/eficiencia", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `RANGO_FECHAS_INVALIDO` | 400 | `fechaInicio` posterior a `fechaFin` | Validar en el selector antes de enviar; marcar el rango |
| `PARAMETRO_INVALIDO` | 400 | Fecha mal formada; `idPlan` no numérico o fuera de rango | Corregir la petición; enviar fechas `YYYY-MM-DD` de un selector |
| `PLAN_INEXISTENTE` | 404 | El plan no existe | «El plan no existe»; volver al listado |
| `PLAN_SIN_CONSUMOS` | 404 | El plan existe pero no tiene consumos registrados | Mostrar el estado vacío del plan, no un error de sistema |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | `COMPRAS` en reporte y detalle; `VENTAS` y `CONSULTA` en los tres | Ocultar la opción de menú o mostrar «No tienes permiso para esta operación» |
| `USUARIO_INACTIVO` | 403 | Usuario desactivado con token vigente | Cerrar sesión y avisar |
| `METODO_NO_PERMITIDO` | 405 | Método distinto de `GET` | No debería ocurrir desde la UI |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. El `message` de los `401`
dice «Su sesión ha expirado» incluso cuando no hay token: decidir por el `code`
(`NO_AUTENTICADO`), no por el texto. El `Content-Type` de las respuestas es `application/json`
sin `charset=UTF-8`: si el cliente necesita el charset explícito, decodifica como UTF-8.

---

## 4. Campos `null`: mostrar «—», nunca 0

Estos campos viajan **explícitos como `null`** (no se omiten) cuando no hay dato calculable:

| Campo | Cuándo es `null` | Mostrar |
|---|---|---|
| `consumos[].eficiencia` | La cantidad real es 0 (no se puede dividir) | «—» |
| `consumos[].rendimientoAjustado` | La cantidad real es 0 | «—» |
| `consumos[].desviacion` | La teórica es 0 (consumo fuera de receta) | «—» |
| `planes[].rendimiento` / `.rendimientoAjustado` | Ningún consumo del plan tiene valor | «—» |
| `resumen.cumplimientoPromedio`, `.rendimientoPromedio`, `.rendimientoAjustadoPromedio`, `.desviacionMateriaPrima` | No hay planes o valores en el período | «—» |
| `materias-primas[].desviacion` | La teórica acumulada es 0 | «—» (tendencia `SIN_BASE`) |
| `periodo.desde` / `periodo.hasta` | No se envió esa fecha | «Sin límite» |

**Nunca conviertas un `null` en `0`**: «sin dato» y «0 %» significan cosas distintas. Un consumo
en cero no es una eficiencia del 0 %: es un registro sin rendimiento calculable. Los promedios
del `resumen` ya los excluyen; el cliente no debe recalcularlos. En TypeScript, usar siempre
`number | null` y un pipe o función de formato que devuelva «—» para `null`.

---

## 5. Frase explicativa y lectura de las tres métricas

Mostrar siempre junto al reporte una nota breve, con estas ideas:

> **Cumplimiento** mide si se fabricó lo planificado. **Rendimiento** compara el consumo teórico
> con el real, pero el teórico se calcula sobre lo *planificado*: si se produjo menos de lo
> planificado, el rendimiento sale artificialmente alto. **Rendimiento ajustado** corrige ese sesgo
> comparando contra lo que realmente se produjo, y es el único comparable entre planes.

| Métrica | Fórmula | Pregunta que responde | Problema que señala |
|---|---|---|---|
| Cumplimiento | `producida / planificada × 100` | ¿Se hizo lo prometido? | Planificación o capacidad |
| Rendimiento (`eficiencia`) | `teórica / real × 100` | ¿Se gastó lo previsto? | Proceso (sesgado por el cumplimiento) |
| Rendimiento ajustado | `(teórica × producida / planificada) / real × 100` | ¿Se gastó lo previsto **para lo que se produjo**? | Proceso, comparable entre planes |

Ejemplo (plan 1 de arriba): se hicieron 60 de 90 panes (**cumplimiento 66.67**), la harina
consumió 16.2 kg contra 15 teóricos (**rendimiento 92.59**) y, ajustado a los 60 panes
producidos, el teórico baja a 10 kg y el **rendimiento ajustado es 61.73**: gastó más de lo que
parecía.

Recomendaciones de presentación:

- **Desviación primero.** La desviación (`(real − teórica) / teórica × 100`; positiva = se gastó
  de más) es la métrica que la gente entiende; muéstrala como columna principal y el rendimiento
  como columna secundaria. Una eficiencia del 200 % se interpreta al revés con facilidad.
- Tres columnas o tres tarjetas separadas, cada una con su etiqueta; **no** un único «% de
  eficiencia» ni un promedio entre ellas.
- Para comparar planes entre sí, ordenar y colorear por `rendimientoAjustado`, no por `rendimiento`.
- Un rendimiento mayor de 100 significa que se gastó **menos** de lo previsto; una desviación
  positiva, que se gastó **más**. Evitar colores que mezclen ambas lecturas.

---

## 6. Ejemplo en Angular

Modelos mínimos:

```ts
export interface PeriodoEficiencia { desde: string | null; hasta: string | null; }

export interface ResumenEficiencia {
  planesAnalizados: number;
  planesCancelados: number;
  cumplimientoPromedio: number | null;
  rendimientoPromedio: number | null;
  rendimientoAjustadoPromedio: number | null;
  registrosSinRendimiento: number;
  desviacionMateriaPrima: number | null;
}

export interface ProductoPlan {
  producto: string; planificada: number; producida: number; cumplimiento: number;
}

export interface ConsumoPlan {
  materiaPrima: string; unidad: string; teorica: number; real: number;
  desviacion: number | null; eficiencia: number | null; rendimientoAjustado: number | null;
}

export interface PlanEficiencia {
  idPlan: number; fechaProduccion: string; estado: string; responsable: string;
  cumplimiento: number | null; rendimiento: number | null; rendimientoAjustado: number | null;
  productos: ProductoPlan[]; consumos: ConsumoPlan[];
}

export interface SinReceta { producto: string; planes: number; nota: string; }

export interface Eficiencia {
  periodo: PeriodoEficiencia; resumen: ResumenEficiencia;
  planes: PlanEficiencia[]; sinReceta: SinReceta[];
}

export type Tendencia = 'CONSUMO_EXCESIVO' | 'CONSUMO_REDUCIDO' | 'NORMAL' | 'SIN_BASE';

export interface DesviacionMateriaPrima {
  materiaPrima: string; unidad: string; teoricaAcumulada: number; realAcumulada: number;
  desviacion: number | null; planes: number; tendencia: Tendencia;
}
```

Servicio y formato de `null`:

```ts
@Injectable({ providedIn: 'root' })
export class EficienciaService {
  private readonly base = `${environment.apiUrl}/reportes/eficiencia`;
  constructor(private http: HttpClient) {}

  private fechas(desde?: string, hasta?: string) {
    let params = new HttpParams();
    if (desde) params = params.set('fechaInicio', desde);
    if (hasta) params = params.set('fechaFin', hasta);
    return params;
  }

  reporte(desde?: string, hasta?: string) {
    return this.http.get<Eficiencia>(this.base, { params: this.fechas(desde, hasta) });
  }

  detalle(idPlan: number) {
    return this.http.get<PlanEficiencia>(`${this.base}/${idPlan}`);
  }

  materiasPrimas(desde?: string, hasta?: string) {
    return this.http.get<DesviacionMateriaPrima[]>(`${this.base}/materias-primas`,
      { params: this.fechas(desde, hasta) });
  }
}

// «—» para null o undefined; nunca 0.
export const porcentaje = (v: number | null | undefined, decimales = 2) =>
  v === null || v === undefined ? '—' : `${v.toFixed(decimales)} %`;
```

Componente: carga con el rango del selector y valida el orden de las fechas antes de pedir.

```ts
export class EficienciaComponent {
  datos?: Eficiencia;
  cargando = false;
  error?: string;

  constructor(private api: EficienciaService) {}

  consultar(desde?: string, hasta?: string) {
    if (desde && hasta && desde > hasta) {
      this.error = 'La fecha de inicio no puede ser posterior a la de fin.';
      return;
    }
    this.cargando = true; this.error = undefined;
    this.api.reporte(desde, hasta).subscribe({
      next: d => { this.datos = d; this.cargando = false; },
      error: (e: HttpErrorResponse) => { this.error = e.error?.code ?? 'ERROR'; this.cargando = false; },
    });
  }
}
```

Formato de fechas: `fechaProduccion`, `periodo.desde` y `periodo.hasta` son fechas de
calendario (`YYYY-MM-DD`). No pasarlas por `new Date(...)` con conversión de zona
(`new Date('2026-09-18')` se interpreta en UTC y en Colombia mostraría el día anterior); parsear la
parte de fecha como local o mostrar la cadena tal cual.

---

## 7. Pantalla

| Estado | Qué se muestra |
|---|---|
| Cargando | Esqueleto de las tarjetas y la tabla + `MatProgressBar` |
| Sin planes en el período (`planesAnalizados = 0`, `planes = []`) | «No hay planes con consumo registrado en este período» y promedios en «—» |
| Con datos | Tarjetas de resumen (cumplimiento, rendimiento, rendimiento ajustado), tabla de planes y tabla de materias primas |
| Plan cancelado | Fila con chip «Cancelado» y nota «No entra en los promedios» |
| Consumo sin rendimiento | «—» en eficiencia y rendimiento ajustado, con tooltip «Se registró consumo cero» |
| Consumo fuera de receta | «—» en desviación, con tooltip «Sin teórico: el insumo no está en la receta» |
| `sinReceta` no vacío | Aviso destacado: «Productos sin receta: no se puede calcular su consumo teórico» y la lista |
| Detalle `PLAN_SIN_CONSUMOS` | «Este plan aún no tiene consumos registrados» (estado vacío, no error) |
| Detalle `PLAN_INEXISTENTE` | «El plan no existe» y volver al listado |
| `RANGO_FECHAS_INVALIDO` | Marcar el selector de fechas y pedir corregir el rango |
| Error de red / 5xx | Mensaje + «Reintentar» |
| 401 | Redirigir al login |
| 403 | Ocultar la opción de menú; si se llega por URL, «No tienes permiso para esta operación» |

Estructura sugerida:

| Zona | Contenido | Endpoint |
|---|---|---|
| Filtro | Selector de rango de fechas (`fechaInicio`, `fechaFin`) | los tres |
| Resumen | `planesAnalizados`, `planesCancelados`, los tres promedios y `registrosSinRendimiento` | `/reportes/eficiencia` |
| Planes | Tabla con cumplimiento, rendimiento y rendimiento ajustado por plan; al abrir una fila, productos y consumos | `/reportes/eficiencia` y `/{idPlan}` |
| Materias primas | Tabla con desviación y chip de `tendencia` | `/materias-primas` (visible también para `COMPRAS`) |
| Avisos | `sinReceta` | `/reportes/eficiencia` |

`COMPRAS` solo ve la zona de materias primas; `ADMIN` y `PRODUCCION` ven todo. Mostrar cada zona
solo si el rol tiene acceso a su endpoint.

---

## 8. Qué NO está en el contrato y limitaciones

- **No hay escrituras.** Solo `GET`; el reporte no crea ni cambia planes ni consumos.
- **No hay paginación ni orden configurable.** El reporte lista todos los planes del período; las
  materias primas llegan ya ordenadas por desviación.
- **No hay un único «porcentaje de eficiencia general».** Se entregan tres métricas separadas.
- **Planes en curso.** Los planes `EN_PROCESO` con consumo entran en los promedios y su
  cumplimiento parcial puede bajar `cumplimientoPromedio`; aclararlo en la pantalla si se desea.
- **`desviacionMateriaPrima` es una media sin ponderar.** Un insumo de consumo mínimo con un
  registro en cero puede dominarla e incluso invertir su signo. Para el hallazgo real usar la tabla
  de `/materias-primas`. La fórmula está pendiente de confirmar con negocio.
- **Consumo fuera de receta.** Su `desviacion` es `null` pero su `eficiencia` sale `0.0` (regla de
  HU-22) y entra en el promedio de rendimiento del plan; está pendiente de decisión si debe quedar
  fuera.
- **Cancelados.** Aparecen en `planes[]` pero no en los promedios ni en `/materias-primas`.
- **Umbral de `tendencia` fijo en ±5.** Lo definió la implementación; no es configurable.
- **Hora de registro de los consumos (HU-22).** `fechaRegistro` de los consumos puede salir en UTC;
  no afecta a este reporte, que filtra por la fecha de producción.
- **Mensaje de los `401`.** Dice «Su sesión ha expirado» incluso sin token; decidir por `code`.
- **Backend verificado con QA ciego.** Cualquier cambio de contrato actualiza este manual en el
  mismo commit.
