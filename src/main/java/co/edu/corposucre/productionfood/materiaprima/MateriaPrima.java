package co.edu.corposucre.productionfood.materiaprima;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Materia prima del sistema, mapeada sobre la tabla {@code materias_primas}.
 *
 * <p>Reglas del dominio (HU-08):
 * <ul>
 *   <li>Cantidades y dinero se modelan con {@link BigDecimal}, nunca con {@code double}: el
 *       precio unitario y el stock mínimo tienen decimales ({@code decimal(10,2)}) y el punto
 *       flotante introduce errores de redondeo que en costos se acumulan.</li>
 *   <li>Una materia prima nace activa: la columna {@code estado} es {@code NOT NULL DEFAULT 1}
 *       y la aplicación la fija de forma explícita al crearla.</li>
 *   <li>No se elimina, solo se desactiva ({@code estado = false}), para conservar la trazabilidad
 *       de compras, recetas y movimientos de inventario que la referencian.</li>
 *   <li>El stock real no vive aquí sino en {@code inventario} (una fila por materia prima, con
 *       {@code stock_minimo = 0} como umbral de alerta, no como cantidad en almacén).</li>
 * </ul>
 *
 * <p>La unidad de medida se guarda como la llave foránea {@code id_unidad} sin mapear la entidad
 * {@code UnidadMedida}: sigue el patrón plano de {@code Cliente}, donde la integridad
 * referencial la garantiza la restricción de la base de datos y no un {@code @ManyToOne}.
 */
@Entity
@Table(name = "materias_primas")
public class MateriaPrima {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_materia_prima")
    private Integer idMateriaPrima;

    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;

    @Column(name = "id_unidad", nullable = false)
    private Integer idUnidad;

    @Column(name = "stock_minimo", nullable = false, precision = 10, scale = 2)
    private BigDecimal stockMinimo;

    @Column(name = "costo_unitario", nullable = false, precision = 10, scale = 2)
    private BigDecimal costoUnitario;

    @Column(name = "estado", nullable = false)
    private Boolean estado;

    public MateriaPrima() {
    }

    public Integer getIdMateriaPrima() {
        return idMateriaPrima;
    }

    public void setIdMateriaPrima(Integer idMateriaPrima) {
        this.idMateriaPrima = idMateriaPrima;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public Integer getIdUnidad() {
        return idUnidad;
    }

    public void setIdUnidad(Integer idUnidad) {
        this.idUnidad = idUnidad;
    }

    public BigDecimal getStockMinimo() {
        return stockMinimo;
    }

    public void setStockMinimo(BigDecimal stockMinimo) {
        this.stockMinimo = stockMinimo;
    }

    public BigDecimal getCostoUnitario() {
        return costoUnitario;
    }

    public void setCostoUnitario(BigDecimal costoUnitario) {
        this.costoUnitario = costoUnitario;
    }

    public Boolean getEstado() {
        return estado;
    }

    public void setEstado(Boolean estado) {
        this.estado = estado;
    }
}
