package co.edu.corposucre.productionfood.lote;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Proyección de la consulta que devuelve un lote de producto terminado con el nombre y la unidad de
 * su producto (HU-19).
 *
 * <p>Es una interfaz y no un record porque Spring Data la rellena a partir de los alias de las
 * columnas de la consulta nativa; cada alias debe coincidir exactamente con el nombre de la
 * propiedad (sin el prefijo {@code get}): {@code idLote}, {@code codigoLote}, {@code idProducto},
 * {@code productoNombre}, {@code idUnidad}, {@code unidadNombre}, {@code unidadAbreviatura},
 * {@code fechaProduccion}, {@code fechaVencimiento}, {@code cantidadIngresada},
 * {@code cantidadDisponible} y {@code stockTotalProducto}.
 *
 * <p>{@code cantidadIngresada} es el alias de {@code lotes.cantidad}: la cantidad con la que el
 * lote <strong>ingresó</strong> y que nunca se modifica (R-03). El saldo actual es
 * {@code cantidadDisponible}, que vive en {@code inventario_producto_terminado}, y
 * {@code stockTotalProducto} es el agregado del producto según
 * {@code v_stock_producto_terminado} (solo lotes vigentes, R-04).
 *
 * <p>{@code diasParaVencer} y {@code vencido} tampoco salen de la base, porque dependen de la
 * fecha de hoy y los calcula el servicio.
 */
public interface LoteProductoConDetalle {

    Integer getIdLote();

    String getCodigoLote();

    Integer getIdProducto();

    String getProductoNombre();

    Integer getIdUnidad();

    String getUnidadNombre();

    String getUnidadAbreviatura();

    LocalDate getFechaProduccion();

    LocalDate getFechaVencimiento();

    BigDecimal getCantidadIngresada();

    BigDecimal getCantidadDisponible();

    BigDecimal getStockTotalProducto();
}
