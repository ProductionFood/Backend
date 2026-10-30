package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lote del que salió la mercancía de una línea de pedido (HU-17).
 *
 * <p>Hace visible la aplicación de FEFO y sirve de comprobante de trazabilidad (R-10): una línea
 * puede consumir varios lotes, y cada uno se informa con su código, su vencimiento y la cantidad
 * tomada. La correspondencia vive en el kardex, no en {@code detalle_pedido}.
 *
 * @param codigoLote código legible del lote
 * @param vence      fecha de vencimiento del lote
 * @param cantidad   cantidad consumida de ese lote
 */
public record LoteConsumidoResponse(
    String codigoLote,
    LocalDate vence,
    BigDecimal cantidad) {}
