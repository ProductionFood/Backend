package co.edu.corposucre.productionfood.pedido;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CrearPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.PedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.ProductoVendidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.ResumenPedidosResponse;
import co.edu.corposucre.productionfood.pedido.dto.VentasClienteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints del ciclo de vida de la cabecera de un pedido (HU-16).
 *
 * <p>Esta colección expone el alta de pedidos a cliente, su edición de cabecera mientras siguen
 * {@code PENDIENTE}, la consulta paginada con filtros y las transiciones de la máquina de
 * estados R-02: pasar a preparación, entregar y cancelar, que es la que devuelve al inventario
 * las cantidades vendidas (R-04). El efecto no se decide aquí: lo garantiza
 * {@link PedidoService} —validación de cliente, fecha de entrega, edición solo en
 * {@code PENDIENTE} y devolución de stock al cancelar— y el controlador solo lo expone.
 *
 * <p>El PATCH de estado despacha por el estado pedido, igual que {@code CompraController}: el
 * servicio concentra la máquina de transiciones y la devolución de stock en un único
 * {@code cambiarEstado}, así que el controlador solo decide qué destinos tienen transición
 * propia. Un destino que no lo tiene —{@code PENDIENTE} no es destino de nadie, y un pedido
 * {@code ENTREGADO} o {@code CANCELADO} no admite saltos— responde 409
 * {@code TRANSICION_INVALIDA} con la excepción que construye el servicio (contrato §7).
 *
 * <p>No existe un DELETE y no debe añadirse: un pedido referencia a su cliente y su estado es el
 * registro de la venta; borrarlo rompería el historial comercial y la trazabilidad del kardex de
 * producto terminado que HU-17 alimenta con su detalle. El cierre correcto de un pedido es
 * cancelarlo, que además devuelve el stock comprometido.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, VENTAS, PRODUCCION y
 * CONSULTA, y escritura (alta, edición de cabecera y transiciones de estado) a ADMIN y VENTAS.
 *
 * <p>HU-25 añade tres consultas de agregación de solo lectura ({@code /resumen},
 * {@code /por-cliente} y {@code /productos-mas-vendidos}) reservadas a ADMIN y VENTAS: son cifras
 * de venta, más sensibles que el listado, que conserva su lectura amplia. Esos paths literales
 * están protegidos frente a cualquier escritura por {@link #escriturasNoPermitidas}.
 */
@RestController
@RequestMapping("/api/v1/pedidos")
@Tag(name = "Pedidos", description = "Registro, consulta y ciclo de estado de los pedidos a "
        + "cliente (HU-16)")
public class PedidoController {

    private final PedidoService pedidoService;

    public PedidoController(PedidoService pedidoService) {
        this.pedidoService = pedidoService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar pedidos",
               description = "Listado paginado de pedidos con su cliente embebido, su estado, "
                        + "el total derivado del detalle y el número de ítems. Los cuatro "
                        + "filtros son opcionales y se pueden combinar: idCliente acota a un "
                        + "cliente; estado, como valor canónico del enum, deja solo los "
                        + "pedidos en ese estado (un literal no canónico responde 400 "
                        + "PARAMETRO_INVALIDO); fechaInicio y fechaFin delimitan un rango "
                        + "inclusivo sobre la fecha del pedido y un rango invertido responde "
                        + "400 RANGO_FECHAS_INVALIDO. Se puede ordenar por fechaPedido, "
                        + "fechaEntrega, estado o id (por defecto fechaPedido descendente) y un "
                        + "campo fuera de esa lista blanca responde 400 CAMPO_ORDEN_INVALIDO. "
                        + "La validación de page, size y sort la hace el servicio "
                        + "(1 ≤ size ≤ 100). Con incluirDetalle=true cada pedido añade el campo "
                        + "detalle con sus líneas (producto, cantidad, precioUnitario histórico "
                        + "y subtotal; un pedido sin líneas responde []), se cargan en una sola "
                        + "consulta y size se recorta a 20 sin responder 400.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación, orden o "
                + "rango de fechas inválidos, o estado no canónico (PARAMETRO_INVALIDO, "
                + "CAMPO_ORDEN_INVALIDO, RANGO_FECHAS_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<PedidoResponse> listar(
            @RequestParam(required = false) Integer idCliente,
            @RequestParam(required = false) EstadoPedido estado,
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean incluirDetalle) {
        return pedidoService.listar(idCliente, estado, fechaInicio, fechaFin, page, size, sort,
                incluirDetalle);
    }

    @GetMapping("/resumen")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Resumen de pedidos por período",
               description = "Resume los pedidos del período agrupados por estado: cantidad y "
                        + "total de los entregados, de los que están en curso (PENDIENTE y "
                        + "EN_PREPARACION, venta comprometida) y de los cancelados, que se "
                        + "informan por separado. Los CANCELADO quedan fuera de la venta (R-03). "
                        + "La venta bruta es el total de los entregados, las devoluciones son "
                        + "solo las aprobadas de esos pedidos y la venta neta es bruta menos "
                        + "devoluciones (R-04); el ticket promedio se calcula sobre los "
                        + "entregados y es null si no hubo ninguno. Los importes salen siempre "
                        + "del precio histórico de cada línea (R-02). fechaInicio y fechaFin son "
                        + "opcionales, inclusivos y en formato ISO (yyyy-MM-dd); un rango "
                        + "invertido responde 400 RANGO_FECHAS_INVALIDO y una fecha mal formada "
                        + "400 PARAMETRO_INVALIDO. Un período sin pedidos responde 200 con las "
                        + "cifras en 0. A diferencia del listado, no admite PRODUCCION ni "
                        + "CONSULTA: se reserva a ADMIN y VENTAS.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Resumen del período, con pedidos o "
                + "con todo en cero"),
        @ApiResponse(responseCode = "400", description = "Rango de fechas invertido o fecha mal "
                + "formada (RANGO_FECHAS_INVALIDO, PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public ResumenPedidosResponse resumen(
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin) {
        return pedidoService.resumen(fechaInicio, fechaFin);
    }

    @GetMapping("/por-cliente")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Ventas de pedidos por cliente",
               description = "Agrupa por cliente la venta del período: cantidad de pedidos, "
                        + "total al precio histórico de cada línea (R-02) y porcentaje del total "
                        + "del cliente sobre el del período. Solo cuenta la venta: los pedidos "
                        + "CANCELADO quedan fuera (R-03); el total es bruto, sin descontar "
                        + "devoluciones (R-04 se informa en el resumen). El listado viene "
                        + "ordenado por total descendente. fechaInicio y fechaFin son "
                        + "opcionales, inclusivos y en formato ISO (yyyy-MM-dd); un rango "
                        + "invertido responde 400 RANGO_FECHAS_INVALIDO y una fecha mal formada "
                        + "400 PARAMETRO_INVALIDO. Un período sin pedidos responde 200 con la "
                        + "lista vacía. Igual que el resumen, se reserva a ADMIN y VENTAS.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de ventas por cliente, con "
                + "pedidos o vacía"),
        @ApiResponse(responseCode = "400", description = "Rango de fechas invertido o fecha mal "
                + "formada (RANGO_FECHAS_INVALIDO, PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public List<VentasClienteResponse> ventasPorCliente(
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin) {
        return pedidoService.ventasPorCliente(fechaInicio, fechaFin);
    }

    @GetMapping("/productos-mas-vendidos")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Ranking de productos más vendidos",
               description = "Ranking de productos de los pedidos entregados del período, "
                        + "ordenado por unidades vendidas y no por importe (R-07): un producto barato "
                        + "vendido muchas veces supera a uno caro vendido pocas. Cada fila trae las "
                        + "unidades, el importe como columna adicional y el número de pedidos "
                        + "distintos en que aparece. Solo cuentan los pedidos ENTREGADO (R-07): "
                        + "los PENDIENTE, EN_PREPARACION y CANCELADO no suman. "
                        + "fechaInicio y fechaFin son opcionales, inclusivos y en formato ISO "
                        + "(yyyy-MM-dd); un rango invertido responde 400 RANGO_FECHAS_INVALIDO y "
                        + "una fecha mal formada 400 PARAMETRO_INVALIDO. Un período sin pedidos "
                        + "entregados responde 200 con la lista vacía. "
                        + "Se reserva a ADMIN y VENTAS.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ranking de productos, con ventas o "
                + "vacío"),
        @ApiResponse(responseCode = "400", description = "Rango de fechas invertido o fecha mal "
                + "formada (RANGO_FECHAS_INVALIDO, PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public List<ProductoVendidoResponse> productosMasVendidos(
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin) {
        return pedidoService.productosMasVendidos(fechaInicio, fechaFin);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Obtener un pedido por id",
               description = "Devuelve el pedido con su cliente, su estado, el total derivado "
                        + "del detalle, el número de ítems y si todavía es editable. Una lectura "
                        + "no mueve stock ni cambia estados. Responde 404 si el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pedido encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Pedido inexistente "
                + "(RECURSO_NO_ENCONTRADO)")
    })
    public PedidoResponse obtener(@PathVariable Integer id) {
        return pedidoService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Registrar un pedido",
               description = "El cliente es obligatorio y debe existir (409 CLIENTE_INEXISTENTE) "
                        + "y estar activo (409 CLIENTE_INACTIVO, CA-01); la actividad solo se "
                        + "exige al crear (R-07). La fecha de entrega es opcional y, si se "
                        + "informa, no puede ser anterior a hoy (400 FECHA_ENTREGA_INVALIDA, "
                        + "R-06). El pedido nace siempre en estado PENDIENTE (R-02/CA-04) y sin "
                        + "líneas de detalle: el estado no se acepta en el cuerpo y la fecha de "
                        + "registro la pone la base (R-01). Devuelve 201 con la cabecera "
                        + "Location apuntando al pedido creado.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pedido registrado en estado "
                + "PENDIENTE"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos (VALIDACION_FALLIDA, "
                + "FECHA_ENTREGA_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "409", description = "Cliente inexistente o inactivo "
                + "(CLIENTE_INEXISTENTE, CLIENTE_INACTIVO)")
    })
    public ResponseEntity<PedidoResponse> crear(@Valid @RequestBody CrearPedidoRequest req) {
        var creado = pedidoService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/pedidos/" + creado.idPedido()))
                .body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Editar la cabecera de un pedido",
               description = "Cambia el cliente y la fecha de entrega de un pedido. Solo un "
                        + "pedido PENDIENTE es editable (R-05): en cualquier otro estado responde "
                        + "409 PEDIDO_NO_EDITABLE. El cliente nuevo debe existir (409 "
                        + "CLIENTE_INEXISTENTE) y estar activo (409 CLIENTE_INACTIVO). La fecha "
                        + "de entrega es opcional, puede quedar vacía y no puede ser anterior a "
                        + "hoy (400 FECHA_ENTREGA_INVALIDA). El estado no se modifica por aquí: "
                        + "tiene su propio endpoint. Responde 404 si el pedido no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pedido actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación "
                + "fallida (VALIDACION_FALLIDA, FECHA_ENTREGA_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Pedido inexistente "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Cliente inexistente o inactivo, o "
                + "pedido no editable (CLIENTE_INEXISTENTE, CLIENTE_INACTIVO, "
                + "PEDIDO_NO_EDITABLE)")
    })
    public PedidoResponse actualizar(@PathVariable Integer id,
                                     @Valid @RequestBody ActualizarPedidoRequest req) {
        return pedidoService.actualizar(id, req);
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Cambiar el estado de un pedido",
               description = "Transición de estado según la máquina R-02: desde PENDIENTE se "
                        + "puede pasar a EN_PREPARACION o CANCELADO, desde EN_PREPARACION a "
                        + "ENTREGADO o CANCELADO, y un pedido ENTREGADO o CANCELADO no admite "
                        + "ningún cambio más. El endpoint despacha por el estado pedido: "
                        + "EN_PREPARACION, ENTREGADO y CANCELADO tienen transición propia y "
                        + "CANCELADO devuelve al inventario el stock de las líneas vendidas "
                        + "(R-04). PENDIENTE no es destino de nadie y responde 409 "
                        + "TRANSICION_INVALIDA con el estado actual y los permitidos (contrato "
                        + "§7). Devuelve 200 con el pedido releído de la base en su estado "
                        + "nuevo; responde 404 si el pedido no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido, estado obligatorio "
                + "o no canónico (VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Pedido inexistente "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Transición prohibida "
                + "(TRANSICION_INVALIDA)")
    })
    public ResponseEntity<?> cambiarEstado(@PathVariable Integer id,
                                           @Valid @RequestBody CambiarEstadoPedidoRequest req) {
        return switch (req.estado()) {
            case EN_PREPARACION, ENTREGADO, CANCELADO ->
                    ResponseEntity.ok(pedidoService.cambiarEstado(id, req));
            default -> throw pedidoService.transicionInvalida(id, req.estado());
        };
    }

    /**
     * Reserva los paths de solo lectura de HU-25 frente a cualquier intento de escritura.
     *
     * <p>{@code /resumen}, {@code /por-cliente} y {@code /productos-mas-vendidos} no tienen
     * endpoint de escritura (HU-25 R-01). Sin este mapping explícito, un PUT —por ejemplo—
     * encajaría en el {@code @PutMapping("/{id}")} de HU-16 y fallaría al convertir el segmento
     * a entero, respondiendo 400 PARAMETRO_INVALIDO en lugar del 405 que exige el contrato. El
     * path literal es más específico que {@code /{id}} y solo declara los métodos de escritura,
     * de modo que los GET de los tres reportes siguen resolviéndose sin ambigüedad a sus
     * handlers, y el alta ({@code POST /pedidos}), la edición y el PATCH de estado por id no
     * cambian.
     *
     * <p>El método no hace nada: lanza {@link HttpRequestMethodNotSupportedException}, que
     * {@code GlobalExceptionHandler} ya serializa como 405 METODO_NO_PERMITIDO con el cuerpo
     * estándar. No lleva {@code @PreAuthorize} porque no toca datos ni pasa por el servicio; la
     * autenticación la exige la cadena de seguridad global, así que un anónimo recibe 401 y no
     * 405.
     *
     * @param request petición entrante, solo para leer el método HTTP que se debe rechazar
     * @throws HttpRequestMethodNotSupportedException siempre; es el mecanismo de respuesta 405
     */
    @RequestMapping(value = {"/resumen", "/por-cliente", "/productos-mas-vendidos"},
            method = {RequestMethod.POST, RequestMethod.PUT,
                      RequestMethod.PATCH, RequestMethod.DELETE})
    public void escriturasNoPermitidas(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod());
    }
}
