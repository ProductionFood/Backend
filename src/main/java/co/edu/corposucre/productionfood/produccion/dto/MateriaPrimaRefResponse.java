package co.edu.corposucre.productionfood.produccion.dto;

/**
 * Referencia embebida a la materia prima de un consumo registrado (HU-22).
 *
 * <p>{@code unidad} es la <strong>abreviatura</strong> de la unidad de medida (por ejemplo
 * {@code "kg"}), no su nombre: es lo que el consumidor necesita para presentar la cantidad sin
 * resolver la unidad por otra llamada.
 *
 * @param idMateriaPrima identificador de la materia prima
 * @param nombre         nombre de la materia prima
 * @param unidad         abreviatura de la unidad de medida de la materia prima
 */
public record MateriaPrimaRefResponse(
        Integer idMateriaPrima,
        String nombre,
        String unidad) {
}
