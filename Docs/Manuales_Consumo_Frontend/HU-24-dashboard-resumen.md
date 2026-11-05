# HU-24 — Dashboard resumen · Manual de consumo para el frontend

Contrato práctico para pintar el dashboard del día (pedidos de hoy, compras pendientes, stock
bajo, producción de hoy y lotes por vencer) y sus dos detalles paginados. Complementa
`../../../PFDocs/00-base/04-CONTRATO-API.md` y la ficha
[`../Hechos/HU-24-dashboard-resumen.md`](../Hechos/HU-24-dashboard-resumen.md).

- **Base:** `{{baseUrl}}/api/v1` (en desarrollo `http://localhost:8085`).
- **Autenticación:** `Authorization: Bearer <token>` en **todas** las peticiones. El token se
  obtiene con `POST /api/v1/auth/login` (HU-03).
- **Roles:** `/dashboard` para cualquier rol autenticado; `/dashboard/pedidos-hoy` para `ADMIN`,
  `VENTAS` y `CONSULTA`; `/dashboard/vencimientos` para `ADMIN`, `VENTAS`, `PRODUCCION` y
  `CONSULTA`.
- **Dependencias:** el stock bajo es el de HU-27 (misma fuente que `/inventario/alertas`); los
  pedidos son de HU-16/HU-17, las compras de HU-13, los planes de HU-20/HU-21 y los lotes de
  producto terminado de HU-19. Esta historia es de **solo lectura**.

> **Estado:** backend verificado con QA ciego (CP-01…CP-21 y SEC-01…SEC-05 en los 3 endpoints,
> con SEC-04 no aplicable en `/dashboard`). El consumo desde Angular sigue pendiente. Los ids,
> nombres e importes dependen de la base.

> **Fechas y horas.** `fecha`, `generadoEn` y todo lo que el backend llama «hoy» son **de la base
> de datos**, en la zona America/Bogota (-05:00). El cliente **no** debe calcular «hoy» con el
> reloj del navegador ni convertir `generadoEn` a otra zona: son fechas locales sin zona. Un
> navegador en otra zona vería un día distinto al del dashboard.

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

| Endpoint | ADMIN | VENTAS | COMPRAS | PRODUCCION | CONSULTA |
|---|---|---|---|---|---|
| `GET /dashboard` | sí | sí | sí | sí | sí |
| `GET /dashboard/pedidos-hoy` | sí | sí | 403 | 403 | sí |
| `GET /dashboard/vencimientos` | sí | sí | 403 | sí | sí |

Ocultar una acción en la pantalla no sustituye al `403`, que el backend aplica igual.

---

## 2. Endpoints

| Método | Ruta | Roles | Códigos |
|---|---|---|---|
| GET | `/dashboard` | Cualquier rol autenticado | 200, 400, 401 |
| GET | `/dashboard/pedidos-hoy` | ADMIN / VENTAS / CONSULTA | 200, 400, 401, 403 |
| GET | `/dashboard/vencimientos` | ADMIN / VENTAS / PRODUCCION / CONSULTA | 200, 400, 401, 403 |

Los tres son `GET` sin cuerpo; cualquier otro método responde `405 METODO_NO_PERMITIDO`. **No hay
caché**: cada llamada refleja el estado actual de la base.

### 2.1. Resumen del día

```http
GET /api/v1/dashboard?diasVencimiento=7
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `diasVencimiento` | entero 0 a 365 | `7` | Horizonte de la tarjeta «Por vencer». Fuera de rango o no numérico → `400 PARAMETRO_INVALIDO`; vacío usa el valor por defecto |

```json
// 200 OK
{ "fecha": "2026-11-05",
  "generadoEn": "2026-11-05T15:06:21.318",
  "pedidosHoy": { "cantidad": 7, "total": 284500.00,
    "porEstado": { "PENDIENTE": 3, "EN_PREPARACION": 2, "ENTREGADO": 2, "CANCELADO": 0 } },
  "comprasPendientes": { "cantidad": 2, "total": 620000.00,
    "masAntigua": { "idCompra": 10, "fecha": "2026-10-20", "diasPendiente": 16 } },
  "stockBajo": { "cantidad": 2, "agotadas": 1,
    "items": [
      { "nombre": "Levadura", "disponible": 0.00, "minimo": 5.00,
        "faltante": 5.00, "nivel": "AGOTADO" },
      { "nombre": "Mantequilla", "disponible": 4.50, "minimo": 20.00,
        "faltante": 15.50, "nivel": "BAJO" } ] },
  "produccionHoy": { "cantidad": 2,
    "porEstado": { "PLANIFICADO": 1, "EN_PROCESO": 1, "COMPLETADO": 0, "CANCELADO": 0 },
    "avancePromedio": 45.50 },
  "porVencer": { "cantidad": 2, "vencidos": 1,
    "items": [
      { "codigoLote": "CR-2026-0914", "producto": "Croissant", "vence": "2026-11-03",
        "diasParaVencer": -2, "disponible": 8.00 },
      { "codigoLote": "PF-2026-0917", "producto": "Pan francés", "vence": "2026-11-08",
        "diasParaVencer": 3, "disponible": 40.00 } ] } }
```

Cómo leer cada tarjeta:

- **`pedidosHoy`.** `cantidad` cuenta **todos** los pedidos de hoy, cancelados incluidos, y es la
  suma de `porEstado` (siempre trae las cuatro claves). `total` es lo vendido y **excluye los
  cancelados**. Mostrar el total como «Vendido hoy» y la cantidad como «Pedidos».
- **`comprasPendientes`.** Compras `PENDIENTE` de **cualquier fecha** (una compra pendiente de hace
  un mes aparece). `masAntigua` es la de menor fecha y llega **`null`** si no hay pendientes (es
  la única clave que puede venir nula). `diasPendiente` se mide contra el día de la base.
- **`stockBajo`.** Es la misma lista que `/inventario/alertas` (HU-27): `AGOTADO` primero y mayor
  faltante primero. `cantidad` y `agotadas` cuentan la lista completa. **`items` no tiene tope**:
  con muchas alertas conviene mostrar solo los primeros 5 o 10 y un enlace «Ver todas».
- **`produccionHoy`.** `cantidad` incluye los planes cancelados; `porEstado` trae las cuatro
  claves. `avancePromedio` (porcentaje con 2 decimales) promedia solo los planes **no cancelados**;
  un plan sin productos cuenta 0 % y uno sobreproducido puede pasar de 100.
- **`porVencer`.** Solo **producto terminado con stock**, **vencidos incluidos**
  (`diasParaVencer` negativo, mostrar en rojo). `items` trae **como máximo 10** lotes, los más
  próximos a vencer; `cantidad` y `vencidos` cuentan **todos**. Para ver el resto, ir al detalle
  de `/dashboard/vencimientos`. El rango es inclusivo: vence en hoy + `diasVencimiento` entra.
- **Importes** (`total`) llegan como número JSON sin formato (`284500.0`): formatear con moneda
  local en el cliente.
- Base vacía: todas las cifras en `0`, `porEstado` con sus claves en `0`, listas `[]` y
  `masAntigua: null`.

### 2.2. Detalle de los pedidos de hoy

```http
GET /api/v1/dashboard/pedidos-hoy?estado=PENDIENTE&page=0&size=20&sort=total,desc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `estado` | texto | sin filtro | `PENDIENTE`, `EN_PREPARACION`, `ENTREGADO` o `CANCELADO` en mayúsculas. Minúsculas u otro valor → `400 PARAMETRO_INVALIDO`; vacío = sin filtro |
| `page` | entero ≥ 0 | `0` | Una página fuera de rango responde `200` con `content` vacío |
| `size` | entero 1 a 100 | `20` | Fuera de rango → `400 PARAMETRO_INVALIDO` |
| `sort` | `campo,dir` | `idPedido,desc` | Campos: `idPedido`, `cliente`, `estado`, `total`; `dir` `asc` o `desc`. Otro campo → `400 CAMPO_ORDEN_INVALIDO` |

```json
// 200 OK
{ "content": [
    { "idPedido": 41, "cliente": "Panadería La Esquina", "estado": "PENDIENTE",
      "fechaPedido": "2026-11-05", "fechaEntrega": null, "total": 84500.00 } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true }
```

- `fechaEntrega` puede ser `null` y llega explícito.
- `total` es el del propio pedido (`0.00` si no tiene líneas) y **también se muestra en los
  cancelados**: la exclusión de cancelados es solo del total agregado de la tarjeta, así que la
  suma de la columna puede superar `pedidosHoy.total`.
- Cada fila enlaza al detalle del pedido (HU-16/HU-17/HU-25).

### 2.3. Detalle de lotes por vencer

```http
GET /api/v1/dashboard/vencimientos?diasVencimiento=7&page=0&size=20&sort=vence,asc
```

| Parámetro | Tipo | Por defecto | Notas |
|---|---|---|---|
| `diasVencimiento` | entero 0 a 365 | `7` | Mismo horizonte que la tarjeta; usar el mismo valor en ambos |
| `page`, `size` | enteros | `0`, `20` | Como en pedidos de hoy |
| `sort` | `campo,dir` | `vence,asc` | Campos: `vence`, `producto`, `disponible`. Otro campo → `400 CAMPO_ORDEN_INVALIDO` |

```json
// 200 OK
{ "content": [
    { "idLote": 17, "codigoLote": "CR-2026-0914", "producto": "Croissant",
      "vence": "2026-11-03", "diasParaVencer": -2, "disponible": 8.00 } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true }
```

- Trae **todos** los lotes de producto terminado con stock dentro del horizonte (la tarjeta solo
  muestra 10). Los lotes de materia prima no aparecen.
- `idLote` sirve para enlazar al lote (HU-19).

---

## 3. Errores

Formato uniforme (`ErrorResponse`):

```json
{ "timestamp": "2026-11-05T15:08:41.123", "status": 400,
  "error": "Bad Request", "code": "PARAMETRO_INVALIDO",
  "message": "El parámetro 'diasVencimiento' debe estar entre 0 y 365.",
  "path": "/api/v1/dashboard", "fieldErrors": [] }
```

| `code` | HTTP | Cuándo | Qué hace el frontend |
|---|---|---|---|
| `PARAMETRO_INVALIDO` | 400 | `diasVencimiento` fuera de 0..365 o no numérico; `estado` no canónico; `page`/`size` inválidos | Corregir la petición; enviar solo valores de los selectores |
| `CAMPO_ORDEN_INVALIDO` | 400 | `sort` fuera de la lista blanca o con dirección inválida | Usar solo los campos ordenables de la tabla |
| `NO_AUTENTICADO` | 401 | Sin token, malformado o expirado | Redirigir al login |
| `SIN_PERMISO` | 403 | `COMPRAS`/`PRODUCCION` en pedidos de hoy; `COMPRAS` en vencimientos | Ocultar la acción o el enlace |
| `USUARIO_INACTIVO` | 403 | Usuario desactivado con token vigente | Cerrar sesión y avisar |
| `METODO_NO_PERMITIDO` | 405 | Método distinto de `GET` | No debería ocurrir desde la UI |

`fieldErrors` es un **array** de `{ field, message }`, no un objeto. El `message` de los `401`
sin token o con token inválido dice «Su sesión ha expirado»; no mostrarlo tal cual: el `code`
(`NO_AUTENTICADO`) es el que decide redirigir al login. El `Content-Type` de las respuestas es
`application/json` sin `charset=UTF-8`: si el cliente necesita el charset explícito, decodifica
como UTF-8.

---

## 4. Tarjetas según el rol (R-05)

`GET /dashboard` devuelve **las cinco tarjetas a cualquier rol**; el filtrado es de presentación y
lo hace el cliente. El backend mantiene su propia autorización en los detalles.

| Rol | Tarjetas que se muestran | Detalle al que enlaza |
|---|---|---|
| `ADMIN` | Las cinco | Pedidos de hoy, vencimientos |
| `CONSULTA` | Las cinco (solo lectura) | Pedidos de hoy, vencimientos |
| `VENTAS` | Pedidos de hoy y por vencer | Pedidos de hoy, vencimientos |
| `COMPRAS` | Compras pendientes y stock bajo | Ninguno de los dos detalles (le dan `403`) |
| `PRODUCCION` | Producción de hoy, stock bajo y por vencer | Vencimientos (pedidos de hoy le da `403`) |

Ejemplo de mapa de tarjetas por rol en TypeScript:

```ts
export type Rol = 'ADMIN' | 'VENTAS' | 'COMPRAS' | 'PRODUCCION' | 'CONSULTA';
export type Tarjeta = 'pedidosHoy' | 'comprasPendientes' | 'stockBajo' | 'produccionHoy' | 'porVencer';

export const TARJETAS_POR_ROL: Record<Rol, Tarjeta[]> = {
  ADMIN:      ['pedidosHoy', 'comprasPendientes', 'stockBajo', 'produccionHoy', 'porVencer'],
  CONSULTA:   ['pedidosHoy', 'comprasPendientes', 'stockBajo', 'produccionHoy', 'porVencer'],
  VENTAS:     ['pedidosHoy', 'porVencer'],
  COMPRAS:    ['comprasPendientes', 'stockBajo'],
  PRODUCCION: ['produccionHoy', 'stockBajo', 'porVencer'],
};

export const PUEDE_VER_PEDIDOS_HOY: Rol[] = ['ADMIN', 'VENTAS', 'CONSULTA'];
export const PUEDE_VER_VENCIMIENTOS: Rol[] = ['ADMIN', 'VENTAS', 'PRODUCCION', 'CONSULTA'];
```

---

## 5. Ejemplo en Angular

Modelos mínimos:

```ts
export interface Dashboard {
  fecha: string;            // 'YYYY-MM-DD', día de la base (America/Bogota)
  generadoEn: string;       // 'YYYY-MM-DDTHH:mm:ss.SSS', hora de la base, sin zona
  pedidosHoy: { cantidad: number; total: number; porEstado: Record<string, number> };
  comprasPendientes: {
    cantidad: number; total: number;
    masAntigua: { idCompra: number; fecha: string; diasPendiente: number } | null;
  };
  stockBajo: {
    cantidad: number; agotadas: number;
    items: { nombre: string; disponible: number; minimo: number; faltante: number;
             nivel: 'AGOTADO' | 'BAJO' }[];
  };
  produccionHoy: { cantidad: number; porEstado: Record<string, number>; avancePromedio: number };
  porVencer: {
    cantidad: number; vencidos: number;
    items: { codigoLote: string; producto: string; vence: string;
             diasParaVencer: number; disponible: number }[];
  };
}

export interface Pagina<T> {
  content: T[]; page: number; size: number; totalElements: number;
  totalPages: number; first: boolean; last: boolean;
}
```

Servicio:

```ts
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly base = `${environment.apiUrl}/dashboard`;
  constructor(private http: HttpClient) {}

  resumen(diasVencimiento = 7) {
    const params = new HttpParams().set('diasVencimiento', diasVencimiento);
    return this.http.get<Dashboard>(this.base, { params });
  }

  pedidosHoy(opts: { estado?: string; page?: number; size?: number; sort?: string } = {}) {
    let params = new HttpParams().set('page', opts.page ?? 0).set('size', opts.size ?? 20);
    if (opts.estado) params = params.set('estado', opts.estado);
    if (opts.sort) params = params.set('sort', opts.sort);
    return this.http.get<Pagina<PedidoHoy>>(`${this.base}/pedidos-hoy`, { params });
  }

  vencimientos(opts: { diasVencimiento?: number; page?: number; size?: number; sort?: string } = {}) {
    let params = new HttpParams()
      .set('diasVencimiento', opts.diasVencimiento ?? 7)
      .set('page', opts.page ?? 0).set('size', opts.size ?? 20);
    if (opts.sort) params = params.set('sort', opts.sort);
    return this.http.get<Pagina<Vencimiento>>(`${this.base}/vencimientos`, { params });
  }
}
```

Componente: una sola llamada al resumen y el filtrado por rol en la plantilla.

```ts
export class DashboardComponent implements OnInit {
  datos?: Dashboard;
  cargando = true;
  error = false;
  rol = this.auth.usuario().rol as Rol;
  tarjetas = new Set(TARJETAS_POR_ROL[this.rol]);

  constructor(private api: DashboardService, private auth: AuthService) {}

  ngOnInit() { this.cargar(); }

  cargar() {
    this.cargando = true; this.error = false;
    this.api.resumen().subscribe({
      next: d => { this.datos = d; this.cargando = false; },
      error: () => { this.error = true; this.cargando = false; },
    });
  }

  ver(t: Tarjeta) { return this.tarjetas.has(t); }
}
```

```html
<app-tarjeta-pedidos *ngIf="ver('pedidosHoy') && datos" [datos]="datos.pedidosHoy"></app-tarjeta-pedidos>
<app-tarjeta-compras *ngIf="ver('comprasPendientes') && datos" [datos]="datos.comprasPendientes"></app-tarjeta-compras>
<app-tarjeta-stock   *ngIf="ver('stockBajo') && datos" [datos]="datos.stockBajo"></app-tarjeta-stock>
```

Formato de fechas: tratar `fecha` y `vence` como fechas de calendario y mostrar `generadoEn` tal
cual (por ejemplo «Actualizado 15:06»). No pasar esas cadenas por `new Date(...)` con conversión de
zona: `new Date('2026-11-05')` se interpreta en UTC y en Colombia mostraría el día anterior. Con el
`DatePipe` de Angular, parsear la parte de fecha como local.

---

## 6. Pantalla

Cada tarjeta es independiente y tiene su propio estado. Como el resumen llega en una sola
respuesta, el estado de carga y de error es del conjunto; el vacío es por tarjeta.

| Estado | Qué se muestra |
|---|---|
| Cargando | Esqueleto de las tarjetas del rol + `MatProgressBar` |
| Pedidos de hoy vacío (`cantidad = 0`) | «Aún no hay pedidos hoy» y el total en `$0` |
| Compras pendientes vacío (`cantidad = 0`, `masAntigua = null`) | «No hay compras pendientes» |
| Stock bajo vacío (`cantidad = 0`) | «Todas las materias primas están por encima de su mínimo» |
| Producción vacía (`cantidad = 0`) | «No hay producción planificada para hoy» |
| Por vencer vacío (`cantidad = 0`) | «Ningún lote por vencer en los próximos N días» |
| Con datos | Cifra principal por tarjeta; lotes `diasParaVencer < 0` en rojo y `AGOTADO` en rojo, `BAJO` en ámbar |
| Error de red / 5xx | Mensaje + «Reintentar» (vuelve a llamar a `/dashboard`) |
| 401 | Redirigir al login |
| 403 en un detalle | Ocultar el enlace y mostrar «No tienes permiso para esta operación» |

Indicadores recomendados por tarjeta:

| Tarjeta | Cifra principal | Detalle |
|---|---|---|
| Pedidos de hoy | `cantidad` y `total` (vendido) | Chips por `porEstado`; enlace a `/dashboard/pedidos-hoy` |
| Compras pendientes | `cantidad` y `total` | «La más antigua: hace N días» con `masAntigua`; enlace a la compra (HU-13) |
| Stock bajo | `cantidad` y `agotadas` | Primeros 5 `items`; enlace a la pantalla de alertas (HU-27) |
| Producción de hoy | `cantidad` | `porEstado` y barra con `avancePromedio` |
| Por vencer | `cantidad` y `vencidos` | `items` (máx. 10); enlace a `/dashboard/vencimientos` |

**Links a pantallas.** Pedidos de hoy → listado de pedidos filtrado por hoy (HU-25) o su detalle
(HU-16/HU-17); compras pendientes → historial de compras con estado `PENDIENTE` (HU-15); stock
bajo → alertas de inventario (HU-27); producción → planes de producción (HU-20); por vencer →
lotes de producto terminado (HU-19). Mostrar cada enlace solo si el rol tiene acceso a esa pantalla.

**Actualización.** No hay caché que invalidar. Cargar `/dashboard` al entrar, ofrecer un botón
«Actualizar» y, si se desea, repetir la llamada cada 1 a 5 minutos. Mostrar siempre el
`generadoEn` para que el usuario sepa de cuándo es el dato. El resumen responde en milisegundos
con volúmenes de cientos de registros, pero conviene no repetirlo con una frecuencia mayor a la
de la operación.

---

## 7. Qué NO está en el contrato

- **No hay escrituras.** Solo `GET`; el dashboard no crea ni cambia pedidos, compras ni planes.
- **No hay filtrado de tarjetas por rol en el backend.** `/dashboard` devuelve las cinco a
  cualquier rol; ocultarlas es del cliente (R-05).
- **No hay tope en `stockBajo.items`.** Acotar la lista en la pantalla.
- **No hay selección de día.** «Hoy» es siempre el día de la base; no se puede consultar otra
  fecha. Solo `diasVencimiento` es configurable.
- **No hay paginación en el resumen.** Solo los dos detalles son paginados.
- **No hay notificaciones ni historial.** `generadoEn` es el instante de la consulta.

---

## 8. Limitaciones conocidas

- **Hora de la base en America/Bogota.** El día lo fija la base (el pool fija `-05:00`). Antes de
  la corrección D-01 una conexión del pool operaba en UTC y a partir de las 7 p.m. devolvía el día
  siguiente; con la corrección todas las conexiones están en `-05:00`. Las filas históricas
  escritas por esa conexión pueden tener la hora mezclada.
- **`generadoEn` de `/inventario/alertas`** lo calcula la JVM, no la base; coincide con el del
  dashboard solo si la JVM corre en America/Bogota. No compararlos entre pantallas.
- **Importes sin escala fija.** `total` llega como número (`284500.0`); formatear en el cliente.
- **`avancePromedio`.** Un plan sin productos cuenta 0 % y la sobreproducción no se acota a 100;
  confirmar con negocio si debe mostrarse acotado.
- **Cancelados.** `pedidosHoy.cantidad` y `produccionHoy.cantidad` los incluyen; el `total` de
  pedidos no. Mostrar «Cancelados» aparte con `porEstado.CANCELADO` evita confusión.
- **Filtro `estado` sensible a mayúsculas.** Usar los valores canónicos de los selectores.
- **Mensaje de los `401`.** Dice «Su sesión ha expirado» incluso sin token; decidir por `code`.
- **Backend verificado con QA ciego.** Cualquier cambio de contrato actualiza este manual en el
  mismo commit.
