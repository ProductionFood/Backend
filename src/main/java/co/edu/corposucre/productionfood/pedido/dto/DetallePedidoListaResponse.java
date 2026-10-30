package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta del listado del detalle de un pedido (HU-17).
 *
 * <p>{@code totalPedido} se recalcula desde la base con {@code SUM(cantidad * precio_unitario)}
 * en cada respuesta: el consumidor nunca suma los subtotales por su cuenta y la cifra mostrada
 * es siempre la del servidor.
 *
 * <p>{@code estado} viaja como {@code String} (el nombre canónico, por ejemplo
 * {@code "EN_PREPARACION"}), igual que en {@code PedidoResponse}. {@code editable} le ahorra al
 * consumidor reimplementar R-09: el detalle solo se modifica mientras el pedido esté
 * {@code PENDIENTE} o {@code EN_PREPARACION}; {@code ENTREGADO} y {@code CANCELADO} ya no admiten
 * cambios. Es una regla propia del detalle de HU-17, más amplia que la de la cabecera de HU-16.
 */
public record DetallePedidoListaResponse(
    Integer idPedido,
    String estado,
    boolean editable,
    List<DetallePedidoResponse> detalles,
    BigDecimal totalPedido,
    int cantidadItems) {}
