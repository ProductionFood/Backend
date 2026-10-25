package co.edu.corposucre.productionfood.receta;

import java.math.BigDecimal;

/**
 * Receta de un producto terminado, mapeada sobre la tabla {@code recetas} (HU-12).
 *
 * <p>Es un POJO y no una entidad JPA porque {@code recetas} se accede con JDBC explícito en
 * {@link RecetaRepository}: el modelo ya está descrito por el esquema y no debe duplicarse en
 * Hibernate.
 *
 * <p>{@code cantidadProducir} es el <strong>rendimiento de una tanda completa</strong>, no de una
 * unidad (R-02): una receta que rinde 30 panes con 5 kg de harina lleva 30, no 1. El consumo
 * teórico de HU-22 divide la cantidad planificada entre este valor, así que interpretarlo como
 * «por unidad» multiplicaría todos los cálculos de producción.
 *
 * <p>{@code idUnidad} es la unidad de <strong>producción</strong> (R-04) y puede diferir de la
 * unidad de venta del producto. Cada producto tiene como máximo una receta: la garantía es el
 * {@code UNIQUE KEY id_producto} de V1 (CA-01).
 *
 * <p>{@code nombreProducto}, {@code nombreUnidad} y {@code abreviaturaUnidad} son proyecciones de
 * solo lectura que llegan del {@code JOIN} de {@link RecetaRowMapper}; no existen como columnas en
 * {@code recetas}. {@code activo} mapea la columna {@code estado}.
 */
public class Receta {

    private Integer idReceta;

    private Integer idProducto;

    private String nombre;

    private BigDecimal cantidadProducir;

    private Integer idUnidad;

    private Boolean activo;

    private String nombreProducto;

    private String nombreUnidad;

    private String abreviaturaUnidad;

    public Receta() {
    }

    public Integer getIdReceta() {
        return idReceta;
    }

    public void setIdReceta(Integer idReceta) {
        this.idReceta = idReceta;
    }

    public Integer getIdProducto() {
        return idProducto;
    }

    public void setIdProducto(Integer idProducto) {
        this.idProducto = idProducto;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public BigDecimal getCantidadProducir() {
        return cantidadProducir;
    }

    public void setCantidadProducir(BigDecimal cantidadProducir) {
        this.cantidadProducir = cantidadProducir;
    }

    public Integer getIdUnidad() {
        return idUnidad;
    }

    public void setIdUnidad(Integer idUnidad) {
        this.idUnidad = idUnidad;
    }

    public Boolean getActivo() {
        return activo;
    }

    public void setActivo(Boolean activo) {
        this.activo = activo;
    }

    public String getNombreProducto() {
        return nombreProducto;
    }

    public void setNombreProducto(String nombreProducto) {
        this.nombreProducto = nombreProducto;
    }

    public String getNombreUnidad() {
        return nombreUnidad;
    }

    public void setNombreUnidad(String nombreUnidad) {
        this.nombreUnidad = nombreUnidad;
    }

    public String getAbreviaturaUnidad() {
        return abreviaturaUnidad;
    }

    public void setAbreviaturaUnidad(String abreviaturaUnidad) {
        this.abreviaturaUnidad = abreviaturaUnidad;
    }
}
