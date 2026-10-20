package co.edu.corposucre.productionfood.proveedor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProveedorRepository extends JpaRepository<Proveedor, Integer> {

    /**
     * Búsqueda paginada de proveedores con filtro opcional por nombre y por estado.
     *
     * <p>El {@code countQuery} repite exactamente la cláusula {@code where} del {@code value}: si
     * divergieran, {@code totalElements} contaría una población distinta de la que se pagina y el
     * total mostrado mentiría sobre cuántos proveedores cumplen el filtro.
     *
     * @param nombre  texto buscado por prefijo (con comodín) o {@code null} para no filtrar
     * @param activo  estado buscado o {@code null} para no filtrar
     * @param pageable paginación y orden ya validados por el llamador contra su lista blanca
     * @return página de proveedores que cumplen los filtros
     */
    @Query(value = """
        select p from Proveedor p
        where (:nombre is null or p.nombre like :nombre escape '\\')
          and (:activo is null or p.estado = :activo)
        """,
        countQuery = """
        select count(p) from Proveedor p
        where (:nombre is null or p.nombre like :nombre escape '\\')
          and (:activo is null or p.estado = :activo)
        """)
    Page<Proveedor> buscar(@Param("nombre") String nombre,
                           @Param("activo") Boolean activo,
                           Pageable pageable);

    /**
     * Indica si existe un proveedor activo con el identificador dado.
     *
     * <p>Lo usará HU-13 para validar que el proveedor de una compra esté activo; el estado del
     * proveedor no debe consultarse por otra vía para no duplicar la regla.
     *
     * @param id identificador del proveedor
     * @return {@code true} si el proveedor existe y está activo
     */
    @Query("select count(p) > 0 from Proveedor p where p.idProveedor = :id and p.estado = true")
    boolean existeActivo(@Param("id") Integer id);
}
