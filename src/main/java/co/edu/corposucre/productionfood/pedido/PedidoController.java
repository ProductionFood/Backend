package co.edu.corposucre.productionfood.pedido;

import java.net.URI;
import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CrearPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.PedidoResponse;
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
                        + "(1 ≤ size ≤ 100).")
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
            @RequestParam(required = false) String sort) {
        return pedidoService.listar(idCliente, estado, fechaInicio, fechaFin, page, size, sort);
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
}
