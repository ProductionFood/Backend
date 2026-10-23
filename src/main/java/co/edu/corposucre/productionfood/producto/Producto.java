package co.edu.corposucre.productionfood.producto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Producto terminado del catálogo, mapeado sobre la tabla {@code productos} (HU-11).
 *
 * <p>Es la entidad gemela de {@code MateriaPrima}, con tres diferencias de dominio:
 * <ul>
 *   <li>Tiene {@code descripcion} opcional (máximo 150 caracteres); la materia prima no.</li>
 *   <li>Su {@code idUnidad} es la unidad de <strong>venta</strong> (unidad, docena, bandeja),
 *       distinta de la unidad de <strong>producción</strong> que lleva la receta (HU-12, R-04).</li>
 *   <li>No tiene stock propio: el stock de producto terminado es <strong>por lote</strong>
 *       ({@code inventario_producto_terminado}, HU-19) y se agrega en la vista
 *       {@code v_stock_producto_terminado}. Por eso el alta de un producto NO crea fila de
 *       inventario, a diferencia de la materia prima (HU-08 R-01).</li>
 * </ul>
 *
 * <p>El dinero se modela con {@link BigDecimal}, nunca con {@code double}: el precio es
 * {@code decimal(10,2)} y el punto flotante introduce errores de redondeo que en importes se
 * acumulan. Un precio de 0 es válido (R-06): sirve para muestras y promociones, y el
 * {@code CHECK (precio >= 0)} de la base lo admite.
 *
 * <p>La unidad de medida se guarda como la llave foránea {@code id_unidad} sin mapear la entidad
 * {@code UnidadMedida}, siguiendo el patrón plano de {@code Cliente} y {@code MateriaPrima}: la
 * integridad referencial la garantiza la base y no un {@code @ManyToOne}.
 */
@Entity
@Table(name = "productos")
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_producto")
    private Integer idProducto;

    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;

    @Column(name = "descripcion", length = 150)
    private String descripcion;

    @Column(name = "precio", nullable = false, precision = 10, scale = 2)
    private BigDecimal precio;

    @Column(name = "id_unidad", nullable = false)
    private Integer idUnidad;

    @Column(name = "estado", nullable = false)
    private Boolean estado;

    public Producto() {
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

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public BigDecimal getPrecio() {
        return precio;
    }

    public void setPrecio(BigDecimal precio) {
        this.precio = precio;
    }

    public Integer getIdUnidad() {
        return idUnidad;
    }

    public void setIdUnidad(Integer idUnidad) {
        this.idUnidad = idUnidad;
    }

    public Boolean getEstado() {
        return estado;
    }

    public void setEstado(Boolean estado) {
        this.estado = estado;
    }
}
