package co.edu.corposucre.productionfood.producto;

import java.math.BigDecimal;

/**
 * Proyección de la consulta que devuelve un producto con su unidad de venta, si tiene receta y su
 * stock agregado de producto terminado (HU-11).
 *
 * <p>Es una interfaz y no un record porque Spring Data la rellena a partir de los alias de las
 * columnas de la consulta nativa; los alias deben coincidir con el nombre de cada propiedad (sin
 * el prefijo {@code get}).
 *
 * <p>{@code idReceta} y {@code stockDisponible} son de solo lectura y se calculan en la misma
 * consulta para que el catálogo sea útil sin navegar a otras pantallas (R-02): un producto sin
 * receta puede venderse si hay stock, pero no puede planificarse, y el hueco debe ser visible.
 * {@code idReceta} llega como el identificador de la receta (o {@code null} si no tiene) y no como
 * un booleano: MySQL devuelve un entero para {@code (r.id_receta is not null)} y Spring Data no lo
 * convierte a {@code Boolean} en una proyección de interfaz; la derivación del flag es del servicio.
 * {@code stockDisponible} sale envuelto en un {@code COALESCE(..., 0)} porque el stock vive en
 * lotes (HU-19) y un producto sin lotes no tiene fila en la vista de stock.
 */
public interface ProductoConDetalle {

    Integer getIdProducto();

    String getNombre();

    String getDescripcion();

    BigDecimal getPrecio();

    Integer getIdUnidad();

    String getUnidadNombre();

    String getUnidadAbreviatura();

    Boolean getEstado();

    Integer getIdReceta();

    BigDecimal getStockDisponible();
}
