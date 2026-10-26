package co.edu.corposucre.productionfood.compra.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta de la recepción de una compra (HU-13).
 *
 * <p>Se devuelve en el endpoint que pasa la compra de {@code PENDIENTE} a {@code RECIBIDA}: además
 * del estado final y del total, lista los movimientos de inventario que la recepción generó, para
 * que el efecto sobre el kardex sea visible en el momento sin una consulta extra.
 *
 * <p>{@code estado} viaja como {@code String}, igual que en {@link CompraResponse}.
 */
public record RecepcionResponse(
        Integer idCompra,
        String estado,
        BigDecimal totalCompra,
        List<MovimientoGeneradoResponse> movimientosGenerados) {
}
