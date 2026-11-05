package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Consumo de materia prima registrado sobre una línea de un plan de producción (HU-22), mapeado
 * sobre la tabla {@code consumo_materia_prima}.
 *
 * <p>Es un POJO y no una entidad JPA por la misma razón que el resto del módulo: el modelo ya
 * está descrito por el esquema y el módulo usa JDBC explícito, así que no debe duplicarse en
 * Hibernate.
 *
 * <p>{@code idPlan}, {@code nombreProducto}, {@code unidad} y {@code estadoPlan} son
 * proyecciones de solo lectura que llegan del {@code JOIN} de {@link ConsumoRowMapper} con
 * {@code detalle_plan_produccion}, {@code productos}, {@code planes_produccion} y
 * {@code unidades_medida}; no existen como columnas en {@code consumo_materia_prima}.
 * {@code unidad} es la abreviatura de la unidad de la materia prima, la misma que muestran los
 * demás detalles del sistema, y {@code estadoPlan} es el estado canónico del plan al que
 * pertenece la línea.
 *
 * <p>{@code cantidadTeorica} y {@code cantidadReal} se guardan con escala 3, la del
 * {@code decimal(10,3)} del esquema. {@code cantidadReal} es obligatoria desde V2 (§C-08) y
 * {@code fechaRegistro} la completa la base con su {@code DEFAULT CURRENT_TIMESTAMP}.
 */
public class ConsumoMateriaPrima {

    private Integer idConsumo;

    private Integer idDetallePlan;

    private Integer idPlan;

    private String nombreProducto;

    private Integer idMateriaPrima;

    private String nombreMateriaPrima;

    private String unidad;

    private BigDecimal cantidadTeorica;

    private BigDecimal cantidadReal;

    private LocalDateTime fechaRegistro;

    private EstadoPlan estadoPlan;

    public ConsumoMateriaPrima() {
    }

    public Integer getIdConsumo() {
        return idConsumo;
    }

    public void setIdConsumo(Integer idConsumo) {
        this.idConsumo = idConsumo;
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

    public String getNombreProducto() {
        return nombreProducto;
    }

    public void setNombreProducto(String nombreProducto) {
        this.nombreProducto = nombreProducto;
    }

    public Integer getIdMateriaPrima() {
        return idMateriaPrima;
    }

    public void setIdMateriaPrima(Integer idMateriaPrima) {
        this.idMateriaPrima = idMateriaPrima;
    }

    public String getNombreMateriaPrima() {
        return nombreMateriaPrima;
    }

    public void setNombreMateriaPrima(String nombreMateriaPrima) {
        this.nombreMateriaPrima = nombreMateriaPrima;
    }

    public String getUnidad() {
        return unidad;
    }

    public void setUnidad(String unidad) {
        this.unidad = unidad;
    }

    public BigDecimal getCantidadTeorica() {
        return cantidadTeorica;
    }

    public void setCantidadTeorica(BigDecimal cantidadTeorica) {
        this.cantidadTeorica = cantidadTeorica;
    }

    public BigDecimal getCantidadReal() {
        return cantidadReal;
    }

    public void setCantidadReal(BigDecimal cantidadReal) {
        this.cantidadReal = cantidadReal;
    }

    public LocalDateTime getFechaRegistro() {
        return fechaRegistro;
    }

    public void setFechaRegistro(LocalDateTime fechaRegistro) {
        this.fechaRegistro = fechaRegistro;
    }

    public EstadoPlan getEstadoPlan() {
        return estadoPlan;
    }

    public void setEstadoPlan(EstadoPlan estadoPlan) {
        this.estadoPlan = estadoPlan;
    }
}
