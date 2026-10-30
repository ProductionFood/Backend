package co.edu.corposucre.productionfood.pedido;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.pedido.dto.StockProductoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Consulta del stock vendible de un producto terminado (HU-17).
 *
 * <p>Comparte la base {@code /productos} con {@code ProductoController} porque el stock es un
 * dato del catálogo, pero vive en este controlador porque lo calcula el módulo de pedidos con la
 * política FEFO. El controlador solo despacha a {@link DetallePedidoService#obtenerStock(Integer)}:
 * la agregación por lote, la exclusión de vencidos y el orden son decisiones del servicio.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, VENTAS, PRODUCCION y
 * CONSULTA: los mismos roles que consultan el catálogo y arman pedidos.
 */
@RestController
@RequestMapping("/api/v1/productos")
@Tag(name = "Stock de producto terminado", description = "Consulta FEFO del stock vendible "
        + "(HU-17)")
public class StockProductoController {

    private final DetallePedidoService detallePedidoService;

    public StockProductoController(DetallePedidoService detallePedidoService) {
        this.detallePedidoService = detallePedidoService;
    }

    @GetMapping("/{id}/stock")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Consultar el stock de un producto terminado",
               description = "Devuelve la cantidad vendible agregada de todos los lotes, cuántos "
                        + "lotes contribuyen, la fecha del lote que vence antes y el detalle de "
                        + "cada lote en orden FEFO. Los lotes vencidos y los saldos en cero no "
                        + "cuentan como stock (R-03), así que cantidadDisponible es lo que "
                        + "realmente se puede vender. No exige que el producto esté activo: su "
                        + "stock sigue existiendo aunque no pueda agregarse a un pedido. Responde "
                        + "404 si el producto no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stock agregado y lotes en orden FEFO"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Producto inexistente "
                + "(RECURSO_NO_ENCONTRADO)")
    })
    public StockProductoResponse stock(@PathVariable Integer id) {
        return detallePedidoService.obtenerStock(id);
    }
}
