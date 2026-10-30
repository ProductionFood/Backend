package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cabecera de un pedido, mapeada sobre la tabla {@code pedidos} (HU-16).
 *
 * <p>Es un POJO y no una entidad JPA porque el módulo usa JDBC explícito, igual que
 * {@code compras}: el modelo ya está descrito por el esquema y no debe duplicarse en Hibernate.
 *
 * <p>{@code fechaPedido} no se envía en la creación (R-01): la columna tiene
 * {@code DEFAULT (curdate())} desde V2 (§C-07) y la fecha la pone la base, nunca la aplicación.
 *
 * <p>{@code totalPedido} y {@code cantidadItems} son <strong>derivados</strong>: no existen como
 * columnas en {@code pedidos}, los calcula la consulta de {@link PedidoRepository} con dos
 * subconsultas sobre {@code detalle_pedido}. El total es la suma de
 * {@code cantidad * precio_unitario} de sus líneas (o {@code 0} si aún no tiene ninguna) y
 * {@code cantidadItems} cuenta esas líneas; el pedido se registra primero y el detalle llega
 * después, por lo que un pedido recién creado es válido con total cero.
 *
 * <p>{@code nombreCliente} es una proyección de solo lectura que llega del {@code JOIN} de
 * {@link PedidoRowMapper}; no existe como columna en {@code pedidos}.
 */
public class Pedido {

    private Integer idPedido;

    private Integer idCliente;

    private LocalDate fechaPedido;

    private LocalDate fechaEntrega;

    private EstadoPedido estado;

    private BigDecimal totalPedido;

    private int cantidadItems;

    private String nombreCliente;

    public Pedido() {
    }

    public Integer getIdPedido() {
        return idPedido;
    }

    public void setIdPedido(Integer idPedido) {
        this.idPedido = idPedido;
    }

    public Integer getIdCliente() {
        return idCliente;
    }

    public void setIdCliente(Integer idCliente) {
        this.idCliente = idCliente;
    }

    public LocalDate getFechaPedido() {
        return fechaPedido;
    }

    public void setFechaPedido(LocalDate fechaPedido) {
        this.fechaPedido = fechaPedido;
    }

    public LocalDate getFechaEntrega() {
        return fechaEntrega;
    }

    public void setFechaEntrega(LocalDate fechaEntrega) {
        this.fechaEntrega = fechaEntrega;
    }

    public EstadoPedido getEstado() {
        return estado;
    }

    public void setEstado(EstadoPedido estado) {
        this.estado = estado;
    }

    public BigDecimal getTotalPedido() {
        return totalPedido;
    }

    public void setTotalPedido(BigDecimal totalPedido) {
        this.totalPedido = totalPedido;
    }

    public int getCantidadItems() {
        return cantidadItems;
    }

    public void setCantidadItems(int cantidadItems) {
        this.cantidadItems = cantidadItems;
    }

    public String getNombreCliente() {
        return nombreCliente;
    }

    public void setNombreCliente(String nombreCliente) {
        this.nombreCliente = nombreCliente;
    }
}
