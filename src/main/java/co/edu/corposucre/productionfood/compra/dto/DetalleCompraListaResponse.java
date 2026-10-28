package co.edu.corposucre.productionfood.compra.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta del listado del detalle de una compra (HU-14).
 *
 * <p>{@code totalCompra} se recalcula desde la base con {@code SUM(cantidad * precio_unitario)}
 * en cada respuesta (R-03): el consumidor nunca suma los subtotales por su cuenta y la cifra
 * mostrada es siempre la del servidor.
 *
 * <p>{@code estado} viaja como {@code String} (el nombre canónico, por ejemplo
 * {@code "PENDIENTE"}), igual que en {@link CompraResponse}, y {@code editable} le ahorra al
 * consumidor reimplementar la regla de R-01: solo una compra {@code PENDIENTE} admite cambios
 * en su detalle.
 */
public record DetalleCompraListaResponse(
    Integer idCompra,
    String estado,
    boolean editable,
    List<DetalleCompraResponse> detalles,
    BigDecimal totalCompra,
    int cantidadItems) {}
