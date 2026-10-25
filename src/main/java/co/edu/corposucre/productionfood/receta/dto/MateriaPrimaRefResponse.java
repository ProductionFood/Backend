package co.edu.corposucre.productionfood.receta.dto;

/**
 * Referencia embebida a la materia prima de una línea de receta (HU-12).
 *
 * <p>{@code unidad} es la <strong>abreviatura</strong> de la unidad de medida (por ejemplo
 * {@code "kg"}), no su nombre: es lo que el contrato de HU-12 muestra junto a la cantidad.
 */
public record MateriaPrimaRefResponse(
    Integer idMateriaPrima,
    String nombre,
    String unidad) {}
