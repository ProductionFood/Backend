package co.edu.corposucre.productionfood.cliente;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClienteRepository extends JpaRepository<Cliente, Integer> {

    /**
     * Búsqueda paginada de clientes con filtro opcional por nombre y por estado.
     *
     * <p>El {@code countQuery} repite exactamente la cláusula {@code where} del {@code value}: si
     * divergieran, {@code totalElements} contaría una población distinta de la que se pagina y el
     * total mostrado mentiría sobre cuántos clientes cumplen el filtro.
     *
     * @param nombre  texto buscado por prefijo (con comodín) o {@code null} para no filtrar
     * @param activo  estado buscado o {@code null} para no filtrar
     * @param pageable paginación y orden ya validados por el llamador contra su lista blanca
     * @return página de clientes que cumplen los filtros
     */
    @Query(value = """
        select c from Cliente c
        where (:nombre is null or c.nombre like :nombre escape '\\')
          and (:activo is null or c.estado = :activo)
        """,
        countQuery = """
        select count(c) from Cliente c
        where (:nombre is null or c.nombre like :nombre escape '\\')
          and (:activo is null or c.estado = :activo)
        """)
    Page<Cliente> buscar(@Param("nombre") String nombre,
                         @Param("activo") Boolean activo,
                         Pageable pageable);

    /**
     * Cuenta los pedidos en curso de un cliente, es decir, los que no están ni entregados ni
     * cancelados (los estados {@code PENDIENTE} y {@code EN_PREPARACION}).
     *
     * <p>Es una consulta nativa porque la tabla {@code pedidos} todavía no tiene entidad JPA.
     * Cuando exista la entidad {@code Pedido} (HU-16) esta cuenta debe migrarse al repositorio
     * de pedidos: mientras tanto, el SQL queda atado al esquema y no al modelo.
     *
     * @param idCliente identificador del cliente
     * @return número de pedidos en curso del cliente
     */
    @Query(value = """
        select count(*) from pedidos
        where id_cliente = :idCliente
          and estado in ('PENDIENTE', 'EN_PREPARACION')
        """, nativeQuery = true)
    long contarPedidosEnCurso(@Param("idCliente") Integer idCliente);

    /**
     * Indica si existe un cliente activo con el identificador dado.
     *
     * <p>Lo usará HU-16 para validar que el cliente de un pedido esté activo; el estado del
     * cliente no debe consultarse por otra vía para no duplicar la regla.
     *
     * @param id identificador del cliente
     * @return {@code true} si el cliente existe y está activo
     */
    @Query("select count(c) > 0 from Cliente c where c.idCliente = :id and c.estado = true")
    boolean existeActivo(@Param("id") Integer id);
}
