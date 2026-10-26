package co.edu.corposucre.productionfood.lote.dto;

/**
 * Referencia embebida al producto de un lote (HU-19).
 *
 * <p>{@code unidad} es la <strong>abreviatura</strong> de la unidad de medida (por ejemplo
 * {@code "und"}), no su nombre: es lo que el consumidor necesita para presentar la cantidad sin
 * resolver la unidad por otra llamada.
 */
public record ProductoRefResponse(
    Integer idProducto,
    String nombre,
    String unidad) {}
