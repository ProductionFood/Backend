package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;

/**
 * Línea de detalle de un plan de producción, mapeada sobre la tabla
 * {@code detalle_plan_produccion} (HU-21).
 *
 * <p>Es un POJO y no una entidad JPA por la misma razón que el resto del módulo: el modelo ya
 * está descrito por el esquema y el módulo usa JDBC explícito, así que no debe duplicarse en
 * Hibernate.
 *
 * <p>{@code nombreProducto} y {@code unidad} son proyecciones de solo lectura que llegan del
 * {@code JOIN} con {@code productos} y {@code unidades_medida} de
 * {@link DetallePlanRowMapper}; no existen como columnas en {@code detalle_plan_produccion}.
 * {@code unidad} es la abreviatura de {@code unidades_medida}, la misma que muestran los demás
 * detalles del sistema.
 *
 * <p>{@code tieneReceta} viene del {@code LEFT JOIN} con {@code recetas}: indica si el producto
 * de la línea ya tiene receta registrada. Como {@code recetas} tiene una llave única por
 * {@code id_producto}, el {@code LEFT JOIN} no puede duplicar la línea.
 *
 * <p>{@code cantidadProducida} inicia en {@code 0.00} porque la columna tiene ese
 * {@code DEFAULT} en la base; el {@code INSERT} de {@link DetallePlanRepository} no la envía y
 * deja que MySQL la complete.
 *
 * <p>La cantidad planificada es el compromiso de producción de HU-26 y solo se edita mientras el
 * plan está en estado {@code PLANIFICADO}; la regla la valida el servicio, no este POJO.
 */
public class DetallePlanProduccion {

    private Integer idDetallePlan;

    private Integer idPlan;

    private Integer idProducto;

    private String nombreProducto;

    private String unidad;

    private BigDecimal cantidadPlanificada;

    private BigDecimal cantidadProducida;

    private boolean tieneReceta;

    public DetallePlanProduccion() {
    }

    public Integer getIdDetallePlan() {
        return idDetallePlan;
    }

    public void setIdDetallePlan(Integer idDetallePlan) {
        this.idDetallePlan = idDetallePlan;
    }

    public Integer getIdPlan() {
        return idPlan;
    }

    public void setIdPlan(Integer idPlan) {
        this.idPlan = idPlan;
    }

    public Integer getIdProducto() {
        return idProducto;
    }

    public void setIdProducto(Integer idProducto) {
        this.idProducto = idProducto;
    }

    public String getNombreProducto() {
        return nombreProducto;
    }

    public void setNombreProducto(String nombreProducto) {
        this.nombreProducto = nombreProducto;
    }

    public String getUnidad() {
        return unidad;
    }

    public void setUnidad(String unidad) {
        this.unidad = unidad;
    }

    public BigDecimal getCantidadPlanificada() {
        return cantidadPlanificada;
    }

    public void setCantidadPlanificada(BigDecimal cantidadPlanificada) {
        this.cantidadPlanificada = cantidadPlanificada;
    }

    public BigDecimal getCantidadProducida() {
        return cantidadProducida;
    }

    public void setCantidadProducida(BigDecimal cantidadProducida) {
        this.cantidadProducida = cantidadProducida;
    }

    public boolean isTieneReceta() {
        return tieneReceta;
    }

    public void setTieneReceta(boolean tieneReceta) {
        this.tieneReceta = tieneReceta;
    }
}
