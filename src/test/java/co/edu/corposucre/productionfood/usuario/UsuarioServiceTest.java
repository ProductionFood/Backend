package co.edu.corposucre.productionfood.usuario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.rol.RolRepository;
import co.edu.corposucre.productionfood.security.UsuarioAutenticado;
import co.edu.corposucre.productionfood.usuario.dto.ActualizarUsuarioRequest;
import co.edu.corposucre.productionfood.usuario.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.usuario.dto.CrearUsuarioRequest;
import co.edu.corposucre.productionfood.usuario.dto.UsuarioResponse;

class UsuarioServiceTest {

    private UsuarioRepository usuarioRepository;
    private RolRepository rolRepository;
    private PasswordEncoder passwordEncoder;
    private UsuarioService usuarioService;

    @BeforeEach
    void setUp() {
        usuarioRepository = mock(UsuarioRepository.class);
        rolRepository = mock(RolRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        usuarioService = new UsuarioService(usuarioRepository, rolRepository, passwordEncoder);

        var rol = new Rol(4, "ADMIN", "Administrador del sistema");
        when(rolRepository.findById(4)).thenReturn(Optional.of(rol));
        when(passwordEncoder.encode(any())).thenReturn("$2a$hash-generado");
        when(usuarioRepository.save(any())).thenAnswer(invocation -> {
            var guardado = (Usuario) invocation.getArgument(0);
            guardado.setIdUsuario(10);
            return guardado;
        });
    }

    private CrearUsuarioRequest solicitud(String nombre, String correo, String password) {
        return new CrearUsuarioRequest(nombre, correo, password, 4);
    }

    @Test
    @DisplayName("Crear usuario válido devuelve activo=true y persiste estado TRUE")
    void crearUsuarioValidoDevuelveActivo() {
        when(usuarioRepository.existsByCorreo("nuevo@productionfood.local")).thenReturn(false);

        var r = usuarioService.crear(
                solicitud("Usuario Nuevo", "nuevo@productionfood.local", "Clave2026*"));

        assertThat(r.idUsuario()).isEqualTo(10);
        assertThat(r.nombre()).isEqualTo("Usuario Nuevo");
        assertThat(r.correo()).isEqualTo("nuevo@productionfood.local");
        assertThat(r.activo()).isTrue();
        assertThat(r.rol().idRol()).isEqualTo(4);
        assertThat(r.rol().nombre()).isEqualTo("ADMIN");

        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertThat(captor.getValue().getEstado()).isTrue();
    }

    @Test
    @DisplayName("Correo ya registrado responde CORREO_DUPLICADO sin guardar")
    void crearCorreoDuplicadoRespondeConflicto() {
        when(usuarioRepository.existsByCorreo("repetido@productionfood.local")).thenReturn(true);

        assertThatThrownBy(() -> usuarioService.crear(
                solicitud("Otro Usuario", "repetido@productionfood.local", "Clave2026*")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CORREO_DUPLICADO");
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("Clave única violada en tiempo de corrida (carrera) responde CORREO_DUPLICADO")
    void crearClaveUnicaVioladaRespondeCorreoDuplicado() {
        when(usuarioRepository.existsByCorreo("carrera@productionfood.local")).thenReturn(false);
        doThrow(new DuplicateKeyException("correo_uq"))
                .when(usuarioRepository).save(any());

        assertThatThrownBy(() -> usuarioService.crear(
                solicitud("Usuario Carrera", "carrera@productionfood.local", "Clave2026*")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CORREO_DUPLICADO");
    }

    @Test
    @DisplayName("Violación de integridad distinta al correo se propaga sin convertir en conflicto")
    void crearViolacionDeIntegridadNoSeConvierteEnConflicto() {
        when(usuarioRepository.existsByCorreo("otro@productionfood.local")).thenReturn(false);
        doThrow(new DataIntegrityViolationException("columna_nula"))
                .when(usuarioRepository).save(any());

        assertThatThrownBy(() -> usuarioService.crear(
                solicitud("Usuario Robusto", "otro@productionfood.local", "Clave2026*")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Rol inexistente responde ROL_INEXISTENTE antes de consultar el correo")
    void crearRolInexistenteRespondeConflicto() {
        assertThatThrownBy(() -> usuarioService.crear(
                new CrearUsuarioRequest(
                        "Usuario Sin Rol", "sinrol@productionfood.local", "Clave2026*", 99)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "ROL_INEXISTENTE");
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("Correo se normaliza a minúsculas y sin espacios antes de persistir")
    void crearNormalizaElCorreoAntesDePersistir() {
        when(usuarioRepository.existsByCorreo("correo@test.com")).thenReturn(false);

        var r = usuarioService.crear(
                solicitud("Mayúsculas Test", "  Correo@Test.Com ", "Clave2026*"));

        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertThat(captor.getValue().getCorreo()).isEqualTo("correo@test.com");
        assertThat(r.correo()).isEqualTo("correo@test.com");
    }

    @Test
    @DisplayName("La contraseña se guarda hasheada y nunca en texto plano")
    void crearHasheaLaPassword() {
        when(usuarioRepository.existsByCorreo("hash@productionfood.local")).thenReturn(false);

        usuarioService.crear(solicitud("Usuario Hash", "hash@productionfood.local", "Clave2026*"));

        verify(passwordEncoder).encode("Clave2026*");
        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword())
                .isNotEqualTo("Clave2026*")
                .isEqualTo("$2a$hash-generado");
    }

    @Test
    @DisplayName("Listar pagina por defecto, ordena por nombre y convierte a UsuarioResponse")
    void listarPaginaOrdenaPorDefectoYConvierte() {
        var admin = new Rol(4, "ADMIN", "");
        var ventas = new Rol(2, "VENTAS", "");
        var u1 = usuarioCon(1, "Ana Pérez", "ana@pf.local", true, admin);
        var u2 = usuarioCon(2, "Bruno Díaz", "bruno@pf.local", false, ventas);
        when(usuarioRepository.buscar(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(u1, u2),
                        org.springframework.data.domain.PageRequest.of(0, 20), 45));

        var r = usuarioService.listar(null, null, null, 0, 20, null);

        assertThat(r.content()).extracting(UsuarioResponse::correo)
                .containsExactly("ana@pf.local", "bruno@pf.local");
        assertThat(r.content().get(0).activo()).isTrue();
        assertThat(r.content().get(1).activo()).isFalse();
        assertThat(r.content().get(1).rol().nombre()).isEqualTo("VENTAS");
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(45);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(usuarioRepository).buscar(isNull(), isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("nombre").isAscending()).isTrue();
    }

    @Test
    @DisplayName("Listar con filtros envía el prefijo escapado y el orden pedido")
    void listarConFiltrosEnviaPrefijoYOrden() {
        when(usuarioRepository.buscar(any(), isNull(), eq(true), any(Pageable.class)))
                .thenReturn(Page.empty());

        usuarioService.listar("  Ana ", null, true, 0, 20, "nombre,desc");

        var prefijo = ArgumentCaptor.forClass(String.class);
        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(usuarioRepository).buscar(prefijo.capture(), isNull(), eq(true), captor.capture());
        assertThat(prefijo.getValue()).isEqualTo("ana%");
        assertThat(captor.getValue().getSort().getOrderFor("nombre").isDescending()).isTrue();
    }

    @Test
    @DisplayName("patronPrefijo devuelve null sin filtro y escapa % y _ del comodín SQL")
    void patronPrefijoSinFiltroYConComodines() {
        assertThat(UsuarioService.patronPrefijo(null)).isNull();
        assertThat(UsuarioService.patronPrefijo("   ")).isNull();
        assertThat(UsuarioService.patronPrefijo("100%")).isEqualTo("100\\%%");
        assertThat(UsuarioService.patronPrefijo("a_b")).isEqualTo("a\\_b%");
        assertThat(UsuarioService.patronPrefijo("c\\d")).isEqualTo("c\\\\d%");
    }

    @Test
    @DisplayName("Obtener un usuario existente lo convierte a UsuarioResponse")
    void obtenerUsuarioExistente() {
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(
                usuarioCon(7, "Ana Pérez", "ana@pf.local", true,
                        new Rol(4, "ADMIN", ""))));

        var r = usuarioService.obtener(7);

        assertThat(r.idUsuario()).isEqualTo(7);
        assertThat(r.nombre()).isEqualTo("Ana Pérez");
        assertThat(r.correo()).isEqualTo("ana@pf.local");
        assertThat(r.activo()).isTrue();
        assertThat(r.rol().nombre()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Obtener un usuario inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerUsuarioInexistenteResponde404() {
        when(usuarioRepository.findConRolById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioService.obtener(999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró el usuario con id 999.");
    }

    @AfterEach
    void limpiarSeguridad() {
        SecurityContextHolder.clearContext();
    }

    private void autenticarComo(Integer idUsuario, String rol) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new UsuarioAutenticado(idUsuario, "admin@pf.local", "Admin", rol),
                        null, List.of()));
    }

    @Test
    @DisplayName("Editar sin cambiar el correo no dispara CORREO_DUPLICADO (CP-10 / R-04)")
    void editarSinCambiarCorreoNoEsDuplicado() {
        var admin = new Rol(1, "ADMIN", "");
        when(usuarioRepository.findConRolById(1)).thenReturn(Optional.of(
                usuarioCon(1, "Administrador Inicial", "admin@pf.local", true, admin)));
        when(rolRepository.findById(1)).thenReturn(Optional.of(admin));
        when(usuarioRepository.existsByCorreoAndIdUsuarioNot("admin@pf.local", 1))
                .thenReturn(false);
        when(usuarioRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var r = usuarioService.actualizar(1, new ActualizarUsuarioRequest(
                "Administrador Editado", "admin@pf.local", 1));

        assertThat(r.nombre()).isEqualTo("Administrador Editado");
        verify(usuarioRepository).existsByCorreoAndIdUsuarioNot("admin@pf.local", 1);
        verify(usuarioRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("Editar con correo de otro usuario responde 409 CORREO_DUPLICADO (CP-11)")
    void editarConCorreoAjenoResponde409() {
        var admin = new Rol(1, "ADMIN", "");
        when(usuarioRepository.findConRolById(5)).thenReturn(Optional.of(
                usuarioCon(5, "Ana Pérez", "ana@pf.local", true, admin)));
        when(rolRepository.findById(1)).thenReturn(Optional.of(admin));
        when(usuarioRepository.existsByCorreoAndIdUsuarioNot("ocupado@pf.local", 5))
                .thenReturn(true);

        assertThatThrownBy(() -> usuarioService.actualizar(5, new ActualizarUsuarioRequest(
                "Ana Pérez", "ocupado@pf.local", 1)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CORREO_DUPLICADO");
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Editar con rol inexistente responde 409 ROL_INEXISTENTE")
    void editarConRolInexistenteResponde409() {
        when(usuarioRepository.findConRolById(5)).thenReturn(Optional.of(
                usuarioCon(5, "Ana Pérez", "ana@pf.local", true, new Rol(1, "ADMIN", ""))));
        when(rolRepository.findById(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usuarioService.actualizar(5, new ActualizarUsuarioRequest(
                "Ana Pérez", "ana@pf.local", 999)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "ROL_INEXISTENTE");
    }

    @Test
    @DisplayName("Cambiar el propio rol responde 409 AUTO_DEGRADACION (CP-20)")
    void cambiarElPropioRolRespondeAutoDegradacion() {
        autenticarComo(1, "ADMIN");
        var admin = new Rol(1, "ADMIN", "");
        when(usuarioRepository.findConRolById(1)).thenReturn(Optional.of(
                usuarioCon(1, "Administrador", "admin@pf.local", true, admin)));
        when(rolRepository.findById(2)).thenReturn(Optional.of(new Rol(2, "PRODUCCION", "")));

        assertThatThrownBy(() -> usuarioService.actualizar(1, new ActualizarUsuarioRequest(
                "Administrador", "admin@pf.local", 2)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "AUTO_DEGRADACION");
    }

    @Test
    @DisplayName("Degradar al único administrador activo responde 409 ULTIMO_ADMIN (CP-19)")
    void degradarAlUnicoAdminRespondeUltimoAdmin() {
        var admin = new Rol(1, "ADMIN", "");
        var ventas = new Rol(4, "VENTAS", "");
        var objetivo = usuarioCon(5, "Admin Dos", "admin2@pf.local", true, admin);
        when(usuarioRepository.findConRolById(5)).thenReturn(Optional.of(objetivo));
        when(rolRepository.findById(4)).thenReturn(Optional.of(ventas));
        when(usuarioRepository.bloquearAdminsActivos()).thenReturn(List.of(objetivo));

        assertThatThrownBy(() -> usuarioService.actualizar(5, new ActualizarUsuarioRequest(
                "Admin Dos", "admin2@pf.local", 4)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "ULTIMO_ADMIN");
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Degradar a un admin con otro administrador activo guarda con rol nuevo")
    void degradarAdminConOtroActivoPermiteGuardar() {
        var admin = new Rol(1, "ADMIN", "");
        var ventas = new Rol(4, "VENTAS", "");
        var objetivo = usuarioCon(5, "Admin Dos", "admin2@pf.local", true, admin);
        var otroAdmin = usuarioCon(1, "Admin Uno", "admin1@pf.local", true, admin);
        when(usuarioRepository.findConRolById(5)).thenReturn(Optional.of(objetivo));
        when(rolRepository.findById(4)).thenReturn(Optional.of(ventas));
        when(usuarioRepository.bloquearAdminsActivos()).thenReturn(List.of(objetivo, otroAdmin));
        when(usuarioRepository.existsByCorreoAndIdUsuarioNot(any(), eq(5))).thenReturn(false);
        when(usuarioRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var r = usuarioService.actualizar(5, new ActualizarUsuarioRequest(
                "  Admin Dos Editado ", " Admin2@PF.Local ", 4));

        assertThat(r.rol().idRol()).isEqualTo(4);
        assertThat(r.nombre()).isEqualTo("Admin Dos Editado");
        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCorreo()).isEqualTo("admin2@pf.local");
    }

    @Test
    @DisplayName("El correo y el nombre del PUT se normalizan antes de guardar")
    void editarNormalizaCorreoYNombre() {
        var ventas = new Rol(4, "VENTAS", "");
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(
                usuarioCon(7, "Ana Pérez", "ana@pf.local", true, ventas)));
        when(rolRepository.findById(4)).thenReturn(Optional.of(ventas));
        when(usuarioRepository.existsByCorreoAndIdUsuarioNot("maria.perez@example.com", 7))
                .thenReturn(false);
        when(usuarioRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        usuarioService.actualizar(7, new ActualizarUsuarioRequest(
                "  María Pérez ", "  Maria.Perez@Example.COM ", 4));

        var captor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCorreo()).isEqualTo("maria.perez@example.com");
        assertThat(captor.getValue().getNombre()).isEqualTo("María Pérez");
    }

    @Test
    @DisplayName("Desactivar a otro usuario guarda activo=false (CP-13)")
    void desactivarAOtroUsuario() {
        var ventas = new Rol(4, "VENTAS", "");
        var objetivo = usuarioCon(9, "Ana Pérez", "ana@pf.local", true, ventas);
        when(usuarioRepository.findConRolById(9)).thenReturn(Optional.of(objetivo));
        when(usuarioRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var r = usuarioService.cambiarEstado(9, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        verify(usuarioRepository).saveAndFlush(any());
        verify(usuarioRepository, never()).bloquearAdminsActivos();
    }

    @Test
    @DisplayName("Desactivarse a uno mismo responde 409 AUTO_DESACTIVACION (CP-15)")
    void desactivarseAMismoRespondeAutoDesactivacion() {
        autenticarComo(1, "ADMIN");
        var admin = new Rol(1, "ADMIN", "");
        when(usuarioRepository.findConRolById(1)).thenReturn(Optional.of(
                usuarioCon(1, "Administrador", "admin@pf.local", true, admin)));

        assertThatThrownBy(() -> usuarioService.cambiarEstado(1, new CambiarEstadoRequest(false)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "AUTO_DESACTIVACION");
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Desactivar al único administrador activo responde 409 ULTIMO_ADMIN")
    void desactivarAlUnicoAdminRespondeUltimoAdmin() {
        autenticarComo(2, "ADMIN");
        var admin = new Rol(1, "ADMIN", "");
        var objetivo = usuarioCon(1, "Administrador", "admin@pf.local", true, admin);
        when(usuarioRepository.findConRolById(1)).thenReturn(Optional.of(objetivo));
        when(usuarioRepository.bloquearAdminsActivos()).thenReturn(List.of(objetivo));

        assertThatThrownBy(() -> usuarioService.cambiarEstado(1, new CambiarEstadoRequest(false)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "ULTIMO_ADMIN");
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Reactivar un usuario no consulta administradores")
    void reactivarUsuarioNoConsultaAdmins() {
        var ventas = new Rol(4, "VENTAS", "");
        when(usuarioRepository.findConRolById(9)).thenReturn(Optional.of(
                usuarioCon(9, "Ana Pérez", "ana@pf.local", false, ventas)));
        when(usuarioRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var r = usuarioService.cambiarEstado(9, new CambiarEstadoRequest(true));

        assertThat(r.activo()).isTrue();
        verify(usuarioRepository, never()).bloquearAdminsActivos();
    }

    @Test
    @DisplayName("Desactivar a un usuario ya inactivo es idempotente (200)")
    void desactivarYaInactivoEsIdempotente() {
        var ventas = new Rol(4, "VENTAS", "");
        when(usuarioRepository.findConRolById(9)).thenReturn(Optional.of(
                usuarioCon(9, "Ana Pérez", "ana@pf.local", false, ventas)));
        when(usuarioRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var r = usuarioService.cambiarEstado(9, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        verify(usuarioRepository).saveAndFlush(any());
    }

    private Usuario usuarioCon(int id, String nombre, String correo,
                               boolean activo, Rol rol) {
        var u = new Usuario();
        u.setIdUsuario(id);
        u.setNombre(nombre);
        u.setCorreo(correo);
        u.setEstado(activo);
        u.setRol(rol);
        return u;
    }
}
