package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;
import java.util.List;

import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;

/**
 * Respuesta de una línea de detalle de pedido (HU-17).
 *
 * <p>El producto se embebe como {@link ProductoRefResponse} —el mismo tipo que usa HU-19— para
 * mostrar nombre y unidad sin una consulta extra por línea.
 *
 * <p>{@code precioUnitario} es el precio copiado al agregar la línea (R-04) y {@code subtotal} lo
 * calcula el backend como {@code cantidad * precioUnitario}: la cifra oficial del importe nunca
 * la entrega el cliente.
 *
 * <p>{@code lotesConsumidos} hace visible la aplicación de FEFO y sirve de comprobante de
 * trazabilidad (R-10): una línea puede consumir varios lotes y cada uno se informa con la
 * cantidad tomada.
 *
 * <p>{@code totalPedido} es el total del pedido en el momento de responder. Las respuestas de
 * alta y edición lo traen relleno para que el consumidor no tenga que pedirlo aparte; en los
 * ítems del listado queda en {@code null}, porque ahí el total agregado viaja una sola vez en
 * {@link DetallePedidoListaResponse} y un {@code null} ni siquiera se serializa (la aplicación
 * incluye solo propiedades no nulas).
 */
public record DetallePedidoResponse(
    Integer idDetallePedido,
    ProductoRefResponse producto,
    BigDecimal cantidad,
    BigDecimal precioUnitario,
    BigDecimal subtotal,
    List<LoteConsumidoResponse> lotesConsumidos,
    BigDecimal totalPedido) {}
