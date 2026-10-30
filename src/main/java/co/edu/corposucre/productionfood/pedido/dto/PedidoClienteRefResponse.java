package co.edu.corposucre.productionfood.pedido.dto;

/**
 * Referencia embebida al cliente de un pedido (HU-16).
 *
 * <p>Se embebe como objeto para que el consumidor no necesite una llamada extra para mostrar el
 * nombre del cliente junto al pedido.
 */
public record PedidoClienteRefResponse(
        Integer idCliente,
        String nombre) {
}
