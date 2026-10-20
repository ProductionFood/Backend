package co.edu.corposucre.productionfood.unidadmedida;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Unidad de medida del sistema, mapeada sobre la tabla {@code unidades_medida}.
 *
 * <p>El catálogo es cerrado: la tabla no tiene columna {@code estado}, de modo que una unidad no
 * se desactiva ni se elimina y la HU-07 no contempla DELETE ni cambio de estado. Solo el nombre
 * y la abreviatura se editan.
 *
 * <p>La abreviatura es única y la columna usa la collation {@code utf8mb4_0900_ai_ci}, así que
 * esa unicidad no distingue mayúsculas de minúsculas: {@code kg} y {@code KG} son la misma
 * abreviatura y no pueden coexistir en el catálogo.
 */
@Entity
@Table(name = "unidades_medida")
public class UnidadMedida {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_unidad")
    private Integer idUnidad;

    @Column(name = "nombre", nullable = false, length = 50)
    private String nombre;

    @Column(name = "abreviatura", nullable = false, length = 50)
    private String abreviatura;

    public UnidadMedida() {
    }

    public Integer getIdUnidad() {
        return idUnidad;
    }

    public void setIdUnidad(Integer idUnidad) {
        this.idUnidad = idUnidad;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getAbreviatura() {
        return abreviatura;
    }

    public void setAbreviatura(String abreviatura) {
        this.abreviatura = abreviatura;
    }
}
