package co.edu.corposucre.productionfood.receta;

import java.math.BigDecimal;

/**
 * Línea de detalle de una receta, mapeada sobre la tabla {@code detalle_receta} (HU-12).
 *
 * <p>Es un POJO y no una entidad JPA por la misma razón que {@link Receta}: el módulo usa JDBC
 * explícito.
 *
 * <p>{@code cantidad} es la cantidad de la materia prima que consume <strong>una tanda
 * completa</strong> de la receta y lleva escala 3 (R-06), a diferencia de casi todas las demás
 * cantidades del sistema, que son {@code decimal(10,2)}.
 *
 * <p>Un mismo ingrediente no puede repetirse en una receta: lo garantiza
 * {@code uk_detalle_receta} en la base (CA-05, R-01) y el servicio lo comprueba antes para
 * devolver un 409 legible. Esta línea es lo único que el proyecto elimina físicamente (R-07).
 *
 * <p>{@code nombreMateriaPrima}, {@code abreviaturaUnidad} y {@code costoUnitario} son
 * proyecciones de solo lectura que llegan del {@code JOIN} de {@link DetalleRecetaRowMapper}. El
 * costo es el de referencia de la materia prima ({@code materias_primas.costo_unitario}, HU-08
 * R-03), no el costo real de cada compra.
 */
public class DetalleReceta {

    private Integer idDetalleReceta;

    private Integer idReceta;

    private Integer idMateriaPrima;

    private BigDecimal cantidad;

    private String nombreMateriaPrima;

    private String abreviaturaUnidad;

    private BigDecimal costoUnitario;

    public DetalleReceta() {
    }

    public Integer getIdDetalleReceta() {
        return idDetalleReceta;
    }

    public void setIdDetalleReceta(Integer idDetalleReceta) {
        this.idDetalleReceta = idDetalleReceta;
    }

    public Integer getIdReceta() {
        return idReceta;
    }

    public void setIdReceta(Integer idReceta) {
        this.idReceta = idReceta;
    }

    public Integer getIdMateriaPrima() {
        return idMateriaPrima;
    }

    public void setIdMateriaPrima(Integer idMateriaPrima) {
        this.idMateriaPrima = idMateriaPrima;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public void setCantidad(BigDecimal cantidad) {
        this.cantidad = cantidad;
    }

    public String getNombreMateriaPrima() {
        return nombreMateriaPrima;
    }

    public void setNombreMateriaPrima(String nombreMateriaPrima) {
        this.nombreMateriaPrima = nombreMateriaPrima;
    }

    public String getAbreviaturaUnidad() {
        return abreviaturaUnidad;
    }

    public void setAbreviaturaUnidad(String abreviaturaUnidad) {
        this.abreviaturaUnidad = abreviaturaUnidad;
    }

    public BigDecimal getCostoUnitario() {
        return costoUnitario;
    }

    public void setCostoUnitario(BigDecimal costoUnitario) {
        this.costoUnitario = costoUnitario;
    }
}
