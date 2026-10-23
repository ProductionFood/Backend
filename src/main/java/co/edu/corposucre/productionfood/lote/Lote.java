package co.edu.corposucre.productionfood.lote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lote de materia prima, mapeado sobre la tabla {@code lotes} (HU-09).
 *
 * <p>La tabla es compartida con los lotes de producto terminado (HU-19). El {@code CHECK}
 * {@code ck_lotes_exclusividad} de V2 obliga a que un lote sea de materia prima <strong>o</strong>
 * de producto, nunca ambos ni ninguno: en esta historia {@code idProducto} queda {@code null} e
 * {@code idMateriaPrima} va informado (R-03).
 *
 * <p>{@code cantidad} es la cantidad <strong>con la que el lote ingresó</strong> y no se modifica
 * después: el saldo disponible de materia prima vive agregado en {@code inventario}, no por lote.
 * Tampoco se valida aquí el orden de las fechas ni que la cantidad sea positiva: los
 * {@code CHECK} de V2 son la última red de seguridad y las reglas legibles las aplica el servicio
 * para responder con los códigos {@code FECHAS_INVALIDAS} y {@code CANTIDAD_INVALIDA}.
 *
 * <p>{@code idDetalleCompra} es la referencia que evita la doble suma (R-02): un lote nacido de
 * una compra ya recibida la lleva informada y no vuelve a sumar stock.
 */
@Entity
@Table(name = "lotes")
public class Lote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_lote")
    private Integer idLote;

    @Column(name = "codigo_lote", nullable = false, length = 30)
    private String codigoLote;

    @Column(name = "id_producto")
    private Integer idProducto;

    @Column(name = "id_materia_prima")
    private Integer idMateriaPrima;

    @Column(name = "id_detalle_compra")
    private Integer idDetalleCompra;

    @Column(name = "fecha_produccion", nullable = false)
    private LocalDate fechaProduccion;

    @Column(name = "fecha_vencimiento", nullable = false)
    private LocalDate fechaVencimiento;

    @Column(name = "cantidad", nullable = false, precision = 10, scale = 2)
    private BigDecimal cantidad;

    public Lote() {
    }

    public Integer getIdLote() {
        return idLote;
    }

    public void setIdLote(Integer idLote) {
        this.idLote = idLote;
    }

    public String getCodigoLote() {
        return codigoLote;
    }

    public void setCodigoLote(String codigoLote) {
        this.codigoLote = codigoLote;
    }

    public Integer getIdProducto() {
        return idProducto;
    }

    public void setIdProducto(Integer idProducto) {
        this.idProducto = idProducto;
    }

    public Integer getIdMateriaPrima() {
        return idMateriaPrima;
    }

    public void setIdMateriaPrima(Integer idMateriaPrima) {
        this.idMateriaPrima = idMateriaPrima;
    }

    public Integer getIdDetalleCompra() {
        return idDetalleCompra;
    }

    public void setIdDetalleCompra(Integer idDetalleCompra) {
        this.idDetalleCompra = idDetalleCompra;
    }

    public LocalDate getFechaProduccion() {
        return fechaProduccion;
    }

    public void setFechaProduccion(LocalDate fechaProduccion) {
        this.fechaProduccion = fechaProduccion;
    }

    public LocalDate getFechaVencimiento() {
        return fechaVencimiento;
    }

    public void setFechaVencimiento(LocalDate fechaVencimiento) {
        this.fechaVencimiento = fechaVencimiento;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public void setCantidad(BigDecimal cantidad) {
        this.cantidad = cantidad;
    }
}
