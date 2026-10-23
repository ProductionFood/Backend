package co.edu.corposucre.productionfood.inventario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stock disponible de una materia prima, mapeado sobre la tabla {@code inventario}.
 *
 * <p>Hay como máximo una fila por materia prima ({@code id_materia_prima} es {@code UNIQUE}):
 * el inventario acumula la cantidad física disponible, mientras que la entidad
 * {@code MateriaPrima} guarda solo el umbral {@code stock_minimo} y el costo.
 *
 * <p>{@code fecha_actualizacion} la escribe la base de datos, no la aplicación: en V1 la columna
 * es {@code datetime NOT NULL} sin defecto y V2 (C-07) le añade
 * {@code DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP}. Por eso el campo está marcado
 * {@code insertable = false, updatable = false}: Hibernate no envía la columna en el INSERT ni en
 * el UPDATE y MySQL la rellena con su defecto; si se dejara insertable y el campo llegara como
 * {@code null}, el INSERT chocaría contra el {@code NOT NULL}. Tras persistir, el valor real se
 * recupera con una nueva consulta.
 */
@Entity
@Table(name = "inventario")
public class Inventario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_inventario")
    private Integer idInventario;

    @Column(name = "id_materia_prima", nullable = false, unique = true)
    private Integer idMateriaPrima;

    @Column(name = "cantidad_disponible", nullable = false, precision = 10, scale = 2)
    private BigDecimal cantidadDisponible;

    @Column(name = "fecha_actualizacion", insertable = false, updatable = false)
    private LocalDateTime fechaActualizacion;

    public Inventario() {
    }

    public Integer getIdInventario() {
        return idInventario;
    }

    public void setIdInventario(Integer idInventario) {
        this.idInventario = idInventario;
    }

    public Integer getIdMateriaPrima() {
        return idMateriaPrima;
    }

    public void setIdMateriaPrima(Integer idMateriaPrima) {
        this.idMateriaPrima = idMateriaPrima;
    }

    public BigDecimal getCantidadDisponible() {
        return cantidadDisponible;
    }

    public void setCantidadDisponible(BigDecimal cantidadDisponible) {
        this.cantidadDisponible = cantidadDisponible;
    }

    public LocalDateTime getFechaActualizacion() {
        return fechaActualizacion;
    }

    public void setFechaActualizacion(LocalDateTime fechaActualizacion) {
        this.fechaActualizacion = fechaActualizacion;
    }
}
