package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Respuesta con un pedido y su cliente (HU-16).
 *
 * <p>El cliente se embebe como objeto {@link PedidoClienteRefResponse} para mostrarlo sin una
 * llamada extra, igual que las demás respuestas del proyecto.
 *
 * <p>{@code estado} viaja como {@code String} (el nombre canónico, por ejemplo
 * {@code "EN_PREPARACION"}): es lo que el contrato expone al consumidor y evita que un cambio de
 * empaquetado del enum se filtre al JSON.
 *
 * <p>{@code totalPedido} y {@code cantidadItems} son derivados de {@code detalle_pedido}, no
 * columnas de la cabecera: un pedido recién registrado responde {@code 0} y {@code 0} hasta que
 * su detalle se agrega.
 *
 * <p>{@code editable} lo calcula el servicio a partir de la máquina de estados de
 * {@code EstadoPedido}: solo un pedido en un estado no terminal admite edición de cabecera o
 * cambio de estado, y el booleano le ahorra al consumidor reimplementar esa regla.
 */
public record PedidoResponse(
        Integer idPedido,
        PedidoClienteRefResponse cliente,
        LocalDate fechaPedido,
        LocalDate fechaEntrega,
        String estado,
        BigDecimal totalPedido,
        int cantidadItems,
        boolean editable) {
}
