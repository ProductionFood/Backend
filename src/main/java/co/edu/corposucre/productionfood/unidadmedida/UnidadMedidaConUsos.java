package co.edu.corposucre.productionfood.unidadmedida;

/**
 * Proyección de la consulta nativa que devuelve una unidad de medida junto con los conteos de
 * uso en las tablas que la referencian.
 *
 * <p>Es una interfaz y no un record porque Spring Data la rellena a partir de los alias de las
 * columnas en la consulta nativa; los alias deben coincidir con el nombre de cada propiedad.
 *
 * <p>Los tres conteos son {@code Long} porque en SQL {@code count(*)} devuelve BIGINT.
 *
 * <p>Los conteos salen de SQL nativo porque las tablas {@code materias_primas}, {@code productos}
 * y {@code recetas} todavía no tienen entidad JPA. Cuando exista la entidad de cada módulo estas
 * cuentas deberán migrar a su repositorio: mientras tanto, el SQL queda atado al esquema y no al
 * modelo, igual que {@code ClienteRepository.contarPedidosEnCurso}.
 */
public interface UnidadMedidaConUsos {

    Integer getIdUnidad();

    String getNombre();

    String getAbreviatura();

    Long getEnMateriasPrimas();

    Long getEnProductos();

    Long getEnRecetas();
}
