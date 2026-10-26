package co.edu.corposucre.productionfood.lote.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Respuesta de un lote de producto terminado (HU-19).
 *
 * <p>El producto se embebe como objeto {@link ProductoRefResponse} para que el consumidor no
 * necesite una llamada extra para mostrar su nombre y la abreviatura de su unidad.
 *
 * <p>{@code cantidadIngresada} y {@code cantidadDisponible} viajan <strong>por separado</strong>
 * (R-03): al crear el lote coinciden, pero después divergen y la diferencia es lo vendido.
 * {@code cantidadIngresada} es {@code lotes.cantidad} y nunca cambia; {@code cantidadDisponible}
 * es el saldo del lote en {@code inventario_producto_terminado}. Devolver solo una de las dos
 * escondería que un lote vencido puede conservar unidades en el estante.
 *
 * <p>{@code stockTotalProducto} es el agregado del producto en
 * {@code v_stock_producto_terminado} y excluye los lotes vencidos: no cuenta como disponible para
 * vender (R-04).
 *
 * <p>{@code diasParaVencer} y {@code vencido} son calculados y de solo lectura: se derivan de la
 * fecha de vencimiento y de la fecha de hoy, no se persisten. {@code vencido} marca visualmente el
 * lote; aquí sí se bloquea el registro de un lote ya vencido (R-05), pero uno puede vencer después
 * de haberse registrado y seguir con saldo.
 */
public record LoteProductoResponse(
    Integer idLote,
    String codigoLote,
    ProductoRefResponse producto,
    LocalDate fechaProduccion,
    LocalDate fechaVencimiento,
    BigDecimal cantidadIngresada,
    BigDecimal cantidadDisponible,
    long diasParaVencer,
    boolean vencido,
    BigDecimal stockTotalProducto) {}
