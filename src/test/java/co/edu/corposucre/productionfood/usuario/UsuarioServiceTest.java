package co.edu.corposucre.productionfood.usuario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.rol.RolRepository;
import co.edu.corposucre.productionfood.usuario.dto.CrearUsuarioRequest;

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
}
