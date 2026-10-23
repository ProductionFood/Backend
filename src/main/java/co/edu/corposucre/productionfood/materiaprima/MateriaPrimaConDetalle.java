package co.edu.corposucre.productionfood.materiaprima;

import java.math.BigDecimal;

/**
 * Proyección de la consulta que devuelve una materia prima con su unidad de medida y su cantidad
 * disponible en inventario.
 *
 * <p>Es una interfaz y no un record porque Spring Data la rellena a partir de los alias de las
 * columnas en la consulta nativa; los alias deben coincidir con el nombre de cada propiedad
 * (sin el prefijo {@code get}).
 *
 * <p>{@code cantidadDisponible} sale envuelta en un {@code COALESCE(..., 0)}: una materia prima
 * recién creada todavía no tiene fila en {@code inventario} y, sin ese coalesce, el
 * {@code LEFT JOIN} la devolvería como {@code null} y la materia prima «desaparecería» del
 * listado. Con el coalesce, una MP transitoria se reporta con stock 0.
 */
public interface MateriaPrimaConDetalle {

    Integer getIdMateriaPrima();

    String getNombre();

    Integer getIdUnidad();

    String getUnidadNombre();

    String getUnidadAbreviatura();

    BigDecimal getStockMinimo();

    BigDecimal getCostoUnitario();

    Boolean getEstado();

    BigDecimal getCantidadDisponible();
}
