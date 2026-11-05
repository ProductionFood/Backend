package co.edu.corposucre.productionfood.produccion;

import java.time.LocalDate;

/**
 * Cabecera de un plan de producción, mapeada sobre la tabla {@code planes_produccion} (HU-20).
 *
 * <p>Es un POJO y no una entidad JPA porque el módulo usa JDBC explícito, igual que
 * {@code pedidos} y {@code compras}: el modelo ya está descrito por el esquema y no debe
 * duplicarse en Hibernate.
 *
 * <p>{@code nombreResponsable} es una proyección de solo lectura que llega del {@code JOIN} con
 * {@code usuarios} de {@link PlanProduccionRowMapper}; no existe como columna en
 * {@code planes_produccion}.
 *
 * <p>{@code cantidadProductos} tampoco es columna: lo calcula la subconsulta sobre
 * {@code detalle_plan_produccion} de {@link PlanProduccionRepository}. Un plan recién creado
 * responde {@code 0} —no tiene líneas todavía, y eso es normal (HU-21 las agrega después)— y la
 * cifra se actualiza en cada lectura del listado o del detalle.
 */
public class PlanProduccion {

    private Integer idPlan;

    private LocalDate fechaPlanificacion;

    private LocalDate fechaProduccion;

    private EstadoPlan estado;

    private String observaciones;

    private Integer idUsuario;

    private String nombreResponsable;

    private Integer cantidadProductos;

    public PlanProduccion() {
    }

    public Integer getIdPlan() {
        return idPlan;
    }

    public void setIdPlan(Integer idPlan) {
        this.idPlan = idPlan;
    }

    public LocalDate getFechaPlanificacion() {
        return fechaPlanificacion;
    }

    public void setFechaPlanificacion(LocalDate fechaPlanificacion) {
        this.fechaPlanificacion = fechaPlanificacion;
    }

    public LocalDate getFechaProduccion() {
        return fechaProduccion;
    }

    public void setFechaProduccion(LocalDate fechaProduccion) {
        this.fechaProduccion = fechaProduccion;
    }

    public EstadoPlan getEstado() {
        return estado;
    }

    public void setEstado(EstadoPlan estado) {
        this.estado = estado;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public void setObservaciones(String observaciones) {
        this.observaciones = observaciones;
    }

    public Integer getIdUsuario() {
        return idUsuario;
    }

    public void setIdUsuario(Integer idUsuario) {
        this.idUsuario = idUsuario;
    }

    public String getNombreResponsable() {
        return nombreResponsable;
    }

    public void setNombreResponsable(String nombreResponsable) {
        this.nombreResponsable = nombreResponsable;
    }

    public Integer getCantidadProductos() {
        return cantidadProductos;
    }

    public void setCantidadProductos(Integer cantidadProductos) {
        this.cantidadProductos = cantidadProductos;
    }
}
