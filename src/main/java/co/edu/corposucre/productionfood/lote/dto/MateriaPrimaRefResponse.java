package co.edu.corposucre.productionfood.lote.dto;

/**
 * Referencia embebida a la materia prima de un lote (HU-09).
 *
 * <p>{@code unidad} es la <strong>abreviatura</strong> de la unidad de medida (por ejemplo
 * {@code "kg"}), no su nombre: así la muestra el contrato de HU-09 y es lo que el consumidor
 * necesita para presentar la cantidad sin resolver la unidad por otra llamada.
 */
public record MateriaPrimaRefResponse(
    Integer idMateriaPrima,
    String nombre,
    String unidad) {}
