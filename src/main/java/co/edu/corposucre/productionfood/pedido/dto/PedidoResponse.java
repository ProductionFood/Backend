package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
 * <p>HU-25 añade {@code totalBruto} (igual a {@code totalPedido}, que se conserva por
 * compatibilidad), {@code devoluciones} (devoluciones aprobadas valoradas al precio de la línea)
 * y {@code totalNeto} ({@code totalBruto - devoluciones}).
 *
 * <p>{@code editable} lo calcula el servicio a partir de la máquina de estados de
 * {@code EstadoPedido}: solo un pedido en un estado no terminal admite edición de cabecera o
 * cambio de estado, y el booleano le ahorra al consumidor reimplementar esa regla.
 *
 * <p>{@code detalle} es opcional (HU-25 CA-04): solo el listado con {@code incluirDetalle=true}
 * lo llena, con las líneas en la misma forma de {@code GET /pedidos/{id}/detalles} salvo que
 * {@code lotesConsumidos} y {@code totalPedido} van en {@code null} —los lotes salen del kardex
 * y el total ya viaja en la cabecera— y no se serializan. Un pedido sin líneas responde
 * {@code []}. En cualquier otra respuesta es {@code null} y, por la inclusión global
 * {@code non_null}, el campo no aparece en el JSON.
 */
public record PedidoResponse(
        Integer idPedido,
        PedidoClienteRefResponse cliente,
        LocalDate fechaPedido,
        LocalDate fechaEntrega,
        String estado,
        BigDecimal totalPedido,
        BigDecimal totalBruto,
        BigDecimal devoluciones,
        BigDecimal totalNeto,
        int cantidadItems,
        boolean editable,
        List<DetallePedidoResponse> detalle) {
}
