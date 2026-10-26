package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cabecera de una compra, mapeada sobre la tabla {@code compras} (HU-13).
 *
 * <p>Es un POJO y no una entidad JPA porque el módulo usa JDBC explícito, igual que
 * {@code recetas}: el modelo ya está descrito por el esquema y no debe duplicarse en Hibernate.
 *
 * <p>{@code totalCompra} y {@code cantidadItems} son <strong>derivados</strong>: no existen como
 * columnas en {@code compras}, los calcula la consulta de {@link CompraRepository} con dos
 * subconsultas sobre {@code detalle_compra}. El total es la suma de
 * {@code cantidad * precio_unitario} de sus líneas (o {@code 0} si aún no tiene ninguna) y
 * {@code cantidadItems} cuenta esas líneas; la compra se registra primero y el detalle llega
 * después (HU-14), por lo que una compra recién creada es válida con total cero.
 *
 * <p>{@code nombreProveedor} es una proyección de solo lectura que llega del {@code JOIN} de
 * {@link CompraRowMapper}; no existe como columna en {@code compras}.
 */
public class Compra {

    private Integer idCompra;

    private Integer idProveedor;

    private LocalDate fecha;

    private EstadoCompra estado;

    private BigDecimal totalCompra;

    private int cantidadItems;

    private String nombreProveedor;

    public Compra() {
    }

    public Integer getIdCompra() {
        return idCompra;
    }

    public void setIdCompra(Integer idCompra) {
        this.idCompra = idCompra;
    }

    public Integer getIdProveedor() {
        return idProveedor;
    }

    public void setIdProveedor(Integer idProveedor) {
        this.idProveedor = idProveedor;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }

    public EstadoCompra getEstado() {
        return estado;
    }

    public void setEstado(EstadoCompra estado) {
        this.estado = estado;
    }

    public BigDecimal getTotalCompra() {
        return totalCompra;
    }

    public void setTotalCompra(BigDecimal totalCompra) {
        this.totalCompra = totalCompra;
    }

    public int getCantidadItems() {
        return cantidadItems;
    }

    public void setCantidadItems(int cantidadItems) {
        this.cantidadItems = cantidadItems;
    }

    public String getNombreProveedor() {
        return nombreProveedor;
    }

    public void setNombreProveedor(String nombreProveedor) {
        this.nombreProveedor = nombreProveedor;
    }
}
