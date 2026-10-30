package co.edu.corposucre.productionfood.pedido;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.pedido.dto.ActualizarCantidadRequest;
import co.edu.corposucre.productionfood.pedido.dto.AgregarDetallePedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoListaResponse;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Líneas de detalle de un pedido (HU-17).
 *
 * <p>Subrecurso anidado bajo {@code /pedidos/{idPedido}/detalles}: expone los productos del
 * pedido con su precio copiado, sus lotes consumidos y el total recalculado, y sus cuatro
 * operaciones. El controlador solo despacha a {@link DetallePedidoService}: que el pedido exista
 * y sea editable, que el producto exista y esté activo, que no se repita y que haya stock
 * suficiente son decisiones del servicio (R-02 a R-10), no de aquí.
 *
 * <p>La consulta no exige estado: un pedido {@code ENTREGADO} o {@code CANCELADO} se puede
 * listar, y su {@code editable=false} es lo que le dice al consumidor que no escriba. Las tres
 * escrituras sí exigen {@code PENDIENTE} o {@code EN_PREPARACION} (R-09) y responden 409
 * {@code PEDIDO_NO_EDITABLE} en cualquier otro estado: la mercancía ya salió o ya volvió.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, VENTAS, PRODUCCION y
 * CONSULTA, y escritura a ADMIN y VENTAS.
 */
@RestController
@RequestMapping("/api/v1/pedidos/{idPedido}/detalles")
@Tag(name = "Detalle de pedido", description = "Productos del pedido, FEFO y trazabilidad por "
        + "lote (HU-17)")
public class DetallePedidoController {

    private final DetallePedidoService detallePedidoService;

    public DetallePedidoController(DetallePedidoService detallePedidoService) {
        this.detallePedidoService = detallePedidoService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar el detalle de un pedido",
               description = "Devuelve las líneas del pedido con su producto embebido, el "
                        + "precio copiado al agregarlas, su subtotal, los lotes consumidos del "
                        + "kardex, el total recalculado desde la base, el estado y si todavía es "
                        + "editable. No exige que el pedido esté abierto: un pedido ENTREGADO o "
                        + "CANCELADO se puede listar y su editable=false indica que no admite "
                        + "cambios. Responde 404 PEDIDO_INEXISTENTE si el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Detalle del pedido, con su total y su "
                + "estado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Pedido inexistente "
                + "(PEDIDO_INEXISTENTE)")
    })
    public DetallePedidoListaResponse listar(@PathVariable Integer idPedido) {
        return detallePedidoService.listar(idPedido);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Agregar un producto al pedido",
               description = "Registra una línea con su producto y cantidad mientras el pedido "
                        + "esté PENDIENTE o EN_PREPARACION (R-09); en cualquier otro estado "
                        + "responde 409 PEDIDO_NO_EDITABLE. El producto debe existir (409 "
                        + "PRODUCTO_INEXISTENTE) y estar activo (409 PRODUCTO_INACTIVO), no puede "
                        + "repetirse en el pedido (409 PRODUCTO_DUPLICADO, R-06) y debe haber "
                        + "stock suficiente (409 STOCK_INSUFICIENTE, CA-04). La cantidad debe ser "
                        + "mayor que cero (400 CANTIDAD_INVALIDA). El precio se copia del catálogo "
                        + "en este momento (R-04) y el stock se descuenta en orden FEFO (R-02), "
                        + "devolviendo en la respuesta los lotes consumidos para trazabilidad "
                        + "(R-10). Devuelve 201 con la cabecera Location apuntando a la línea "
                        + "creada.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Línea agregada, con sus lotes "
                + "consumidos y el total del pedido"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad no "
                + "positiva (VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Pedido inexistente "
                + "(PEDIDO_INEXISTENTE)"),
        @ApiResponse(responseCode = "409", description = "Pedido no editable, producto "
                + "inexistente o inactivo, producto duplicado o stock insuficiente "
                + "(PEDIDO_NO_EDITABLE, PRODUCTO_INEXISTENTE, PRODUCTO_INACTIVO, "
                + "PRODUCTO_DUPLICADO, STOCK_INSUFICIENTE)")
    })
    public ResponseEntity<DetallePedidoResponse> agregar(
            @PathVariable Integer idPedido,
            @Valid @RequestBody AgregarDetallePedidoRequest req) {
        var detalle = detallePedidoService.agregar(idPedido, req);
        return ResponseEntity
                .created(URI.create("/api/v1/pedidos/" + idPedido + "/detalles/"
                        + detalle.idDetallePedido()))
                .body(detalle);
    }

    @PutMapping("/{idDetalle}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Cambiar la cantidad de un producto del pedido",
               description = "Ajusta la cantidad de una línea mientras su pedido siga PENDIENTE "
                        + "o EN_PREPARACION (R-09); en cualquier otro estado responde 409 "
                        + "PEDIDO_NO_EDITABLE. El producto y el precio de la línea no se "
                        + "reemplazan: el precio se copió al agregarla (R-04) y el UNIQUE del "
                        + "pedido prohíbe repetir el producto (R-06). Aumentar descuenta solo la "
                        + "diferencia en FEFO y reducir devuelve solo la diferencia a los lotes "
                        + "de origen (R-07); repetir la misma cantidad no mueve stock. La cantidad "
                        + "nueva debe superar cero (400 CANTIDAD_INVALIDA) y, si aumenta, debe "
                        + "haber stock suficiente (409 STOCK_INSUFICIENTE). Devuelve la línea con "
                        + "su subtotal, sus lotes netos y el total del pedido recalculado. "
                        + "Responde 404 si el pedido no existe o la línea no pertenece a él.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Línea actualizada, con sus lotes y el "
                + "total del pedido"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad no "
                + "positiva (VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Pedido inexistente "
                + "(PEDIDO_INEXISTENTE), o línea inexistente o de otro pedido "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Pedido no editable o stock "
                + "insuficiente (PEDIDO_NO_EDITABLE, STOCK_INSUFICIENTE)")
    })
    public DetallePedidoResponse actualizar(@PathVariable Integer idPedido,
                                            @PathVariable Integer idDetalle,
                                            @Valid @RequestBody ActualizarCantidadRequest req) {
        return detallePedidoService.actualizarCantidad(idPedido, idDetalle, req);
    }

    @DeleteMapping("/{idDetalle}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Quitar un producto del pedido",
               description = "Elimina la línea mientras el pedido siga PENDIENTE o "
                        + "EN_PREPARACION (R-09); en cualquier otro estado responde 409 "
                        + "PEDIDO_NO_EDITABLE. Devuelve toda la cantidad neta de la línea a los "
                        + "lotes de origen con movimientos ENTRADA_DEVOLUCION (R-08). La línea "
                        + "debe pertenecer al pedido de la ruta; si no, se responde 404. Devuelve "
                        + "204 sin cuerpo.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Línea eliminada y stock devuelto"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Pedido inexistente "
                + "(PEDIDO_INEXISTENTE), o línea inexistente o de otro pedido "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Pedido no editable "
                + "(PEDIDO_NO_EDITABLE)")
    })
    public ResponseEntity<Void> eliminar(@PathVariable Integer idPedido,
                                         @PathVariable Integer idDetalle) {
        detallePedidoService.eliminar(idPedido, idDetalle);
        return ResponseEntity.noContent().build();
    }
}
