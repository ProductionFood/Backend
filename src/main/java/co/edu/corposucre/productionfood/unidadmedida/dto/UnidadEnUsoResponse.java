package co.edu.corposucre.productionfood.unidadmedida.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Conteo de referencias a una unidad de medida en las tablas que la usan.
 *
 * <p>El total es la suma de los tres conteos: sirve para saber de un vistazo si la unidad está
 * libre y, por tanto, si el catálogo puede modificarse sin romper las referencias existentes.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnidadEnUsoResponse(
    int enMateriasPrimas,
    int enProductos,
    int enRecetas,
    int total) {

    /**
     * Construye el conteo a partir de los tres conteos por tabla y calcula el total.
     *
     * <p>Los parámetros son {@code long} porque los conteos de la consulta nativa son BIGINT.
     *
     * @param enMateriasPrimas referencias en {@code materias_primas}
     * @param enProductos      referencias en {@code productos}
     * @param enRecetas        referencias en {@code recetas}
     * @return el conteo con su total
     */
    public static UnidadEnUsoResponse de(long enMateriasPrimas, long enProductos, long enRecetas) {
        int total = (int) (enMateriasPrimas + enProductos + enRecetas);
        return new UnidadEnUsoResponse(
                (int) enMateriasPrimas, (int) enProductos, (int) enRecetas, total);
    }
}
