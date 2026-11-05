package co.edu.corposucre.productionfood.produccion.dto;

/**
 * Referencia embebida al producto de una línea de un plan de producción (HU-21).
 *
 * <p>{@code unidad} es la <strong>abreviatura</strong> de la unidad de medida (por ejemplo
 * {@code "und"}), no su nombre: es lo que el consumidor necesita para presentar la cantidad sin
 * resolver la unidad por otra llamada.
 *
 * @param idProducto identificador del producto
 * @param nombre     nombre del producto
 * @param unidad     abreviatura de la unidad de medida del producto
 */
public record ProductoRefResponse(
        Integer idProducto,
        String nombre,
        String unidad) {
}
