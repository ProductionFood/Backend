package co.edu.corposucre.productionfood.usuario;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRepository extends JpaRepository<Usuario, Integer> {

    boolean existsByCorreo(String correo);

    boolean existsByCorreoAndIdUsuarioNot(String correo, Integer idUsuario);

    Optional<Usuario> findByCorreo(String correo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Usuario u where u.rol.nombre = 'ADMIN' and u.estado = true")
    List<Usuario> bloquearAdminsActivos();

    @Query("select u from Usuario u join fetch u.rol where u.idUsuario = :id")
    Optional<Usuario> findConRolById(@Param("id") Integer id);

    @Query(value = """
        select u from Usuario u join fetch u.rol r
        where (:q is null or u.nombre like :q escape '\\' or u.correo like :q escape '\\')
          and (:idRol is null or r.idRol = :idRol)
          and (:activo is null or u.estado = :activo)
        """,
        countQuery = """
        select count(u) from Usuario u join u.rol r
        where (:q is null or u.nombre like :q escape '\\' or u.correo like :q escape '\\')
          and (:idRol is null or r.idRol = :idRol)
          and (:activo is null or u.estado = :activo)
        """)
    Page<Usuario> buscar(@Param("q") String q, @Param("idRol") Integer idRol,
                         @Param("activo") Boolean activo, Pageable pageable);
}
