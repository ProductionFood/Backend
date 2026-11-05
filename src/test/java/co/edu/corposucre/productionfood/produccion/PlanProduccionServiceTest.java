package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.produccion.dto.CrearPlanRequest;
import co.edu.corposucre.productionfood.security.UsuarioAutenticado;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

/**
 * Pruebas unitarias de {@link PlanProduccionService} con Mockito (HU-20).
 *
 * <p>Cubren el alta con sus dos reglas de negocio —fechas coherentes (R-03) y resolución del
 * responsable (R-04, CP-10, CP-11)—, el detalle con su 404 y el listado paginado. La
 * persistencia JDBC se prueba en {@link PlanProduccionRepositoryTest}; aquí el repositorio se
 * sustituye por un mock.
 *
 * <p>El usuario autenticado se simula con un {@link UsuarioAutenticado} en el
 * {@link SecurityContextHolder}, que es lo que leen {@code SecurityUtils.idUsuarioActual} y
 * {@code SecurityUtils.rolActual}. El contexto se limpia después de cada prueba para no
 * filtrar la identidad a las siguientes.
 */
class PlanProduccionServiceTest {

    private static final Integer ID_PLAN = 42;
    private static final Integer ID_AUTENTICADO = 5;

    private PlanProduccionRepository planProduccionRepository;
    private UsuarioRepository usuarioRepository;
    private PlanProduccionService service;

    @BeforeEach
    void setUp() {
        planProduccionRepository = mock(PlanProduccionRepository.class);
        usuarioRepository = mock(UsuarioRepository.class);
        service = new PlanProduccionService(planProduccionRepository, usuarioRepository);
    }

    @AfterEach
    void limpiarSeguridad() {
        SecurityContextHolder.clearContext();
    }

    private void autenticarComo(Integer idUsuario, String rol) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new UsuarioAutenticado(idUsuario, "operario@pf.local", "Operario", rol),
                        null, List.of()));
    }

    private static PlanProduccion fila(Integer id, LocalDate planificacion, LocalDate produccion,
                                       Integer idUsuario, String nombreResponsable,
                                       int cantidadProductos) {
        var plan = new PlanProduccion();
        plan.setIdPlan(id);
        plan.setFechaPlanificacion(planificacion);
        plan.setFechaProduccion(produccion);
        plan.setEstado(EstadoPlan.PLANIFICADO);
        plan.setObservaciones("Lote semanal");
        plan.setIdUsuario(idUsuario);
        plan.setNombreResponsable(nombreResponsable);
        plan.setCantidadProductos(cantidadProductos);
        return plan;
    }

    private void dadoInsertarConId(Integer id) {
        when(planProduccionRepository.insertar(any(PlanProduccion.class))).thenReturn(id);
    }

    // ------------------------------------------------------------------ crear

    @Test
    @DisplayName("crear con fechas válidas nace PLANIFICADO con el usuario autenticado")
    void crearConFechasValidasNacePlanificado() {
        autenticarComo(ID_AUTENTICADO, "PRODUCCION");
        var planificacion = LocalDate.of(2026, 11, 5);
        var produccion = LocalDate.of(2026, 11, 10);
        dadoInsertarConId(ID_PLAN);
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, planificacion, produccion, ID_AUTENTICADO, "Operario", 0)));

        var r = service.crear(new CrearPlanRequest(planificacion, produccion,
                "Lote semanal", null));

        var captor = ArgumentCaptor.forClass(PlanProduccion.class);
        verify(planProduccionRepository).insertar(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getFechaPlanificacion()).isEqualTo(planificacion);
        assertThat(guardado.getFechaProduccion()).isEqualTo(produccion);
        assertThat(guardado.getObservaciones()).isEqualTo("Lote semanal");
        assertThat(guardado.getIdUsuario()).isEqualTo(ID_AUTENTICADO);
        assertThat(guardado.getEstado()).isEqualTo(EstadoPlan.PLANIFICADO);

        assertThat(r.idPlan()).isEqualTo(ID_PLAN);
        assertThat(r.fechaPlanificacion()).isEqualTo(planificacion);
        assertThat(r.fechaProduccion()).isEqualTo(produccion);
        assertThat(r.estado()).isEqualTo("PLANIFICADO");
        assertThat(r.observaciones()).isEqualTo("Lote semanal");
        assertThat(r.responsable().idUsuario()).isEqualTo(ID_AUTENTICADO);
        assertThat(r.responsable().nombre()).isEqualTo("Operario");
        assertThat(r.cantidadProductos()).isZero();
        assertThat(r.editable()).isTrue();

        verifyNoInteractions(usuarioRepository);
    }

    @Test
    @DisplayName("R-03: fechaProduccion anterior a fechaPlanificacion responde 400 FECHAS_INVALIDAS")
    void crearConFechaProduccionAnteriorRespondeFechasInvalidas() {
        var planificacion = LocalDate.of(2026, 11, 10);
        var produccion = LocalDate.of(2026, 11, 9);

        assertThatThrownBy(() -> service.crear(new CrearPlanRequest(planificacion, produccion,
                null, null)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FECHAS_INVALIDAS");

        verifyNoInteractions(planProduccionRepository, usuarioRepository);
    }

    @Test
    @DisplayName("R-03: fechaProduccion igual a fechaPlanificacion es válida")
    void crearConFechasIgualesEsValido() {
        autenticarComo(ID_AUTENTICADO, "PRODUCCION");
        var dia = LocalDate.of(2026, 11, 10);
        dadoInsertarConId(ID_PLAN);
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, dia, dia, ID_AUTENTICADO, "Operario", 0)));

        var r = service.crear(new CrearPlanRequest(dia, dia, null, null));

        assertThat(r.fechaProduccion()).isEqualTo(dia);
        verify(planProduccionRepository).insertar(any(PlanProduccion.class));
    }

    @Test
    @DisplayName("CP-10: sin idUsuario el responsable es el usuario autenticado")
    void crearSinIdUsuarioUsaElUsuarioAutenticado() {
        autenticarComo(ID_AUTENTICADO, "PRODUCCION");
        dadoInsertarConId(ID_PLAN);
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        ID_AUTENTICADO, "Operario", 0)));

        service.crear(new CrearPlanRequest(LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 10), null, null));

        var captor = ArgumentCaptor.forClass(PlanProduccion.class);
        verify(planProduccionRepository).insertar(captor.capture());
        assertThat(captor.getValue().getIdUsuario()).isEqualTo(ID_AUTENTICADO);
    }

    @Test
    @DisplayName("CP-11: PRODUCCION con idUsuario ajeno lo ignora y usa el autenticado")
    void crearComoProduccionConIdUsuarioAjenoUsaElAutenticado() {
        autenticarComo(ID_AUTENTICADO, "PRODUCCION");
        dadoInsertarConId(ID_PLAN);
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        ID_AUTENTICADO, "Operario", 0)));

        var r = service.crear(new CrearPlanRequest(LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 10), null, 99));

        var captor = ArgumentCaptor.forClass(PlanProduccion.class);
        verify(planProduccionRepository).insertar(captor.capture());
        assertThat(captor.getValue().getIdUsuario()).isEqualTo(ID_AUTENTICADO);
        assertThat(r.responsable().idUsuario()).isEqualTo(ID_AUTENTICADO);
        // Un no administrador ni siquiera consulta el usuario ajeno.
        verifyNoInteractions(usuarioRepository);
    }

    @Test
    @DisplayName("R-04: ADMIN con idUsuario existente lo respeta como responsable")
    void crearComoAdminConIdUsuarioExistenteLoRespeta() {
        autenticarComo(1, "ADMIN");
        when(usuarioRepository.existsById(99)).thenReturn(true);
        dadoInsertarConId(ID_PLAN);
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        99, "Otro Operario", 0)));

        var r = service.crear(new CrearPlanRequest(LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 10), null, 99));

        var captor = ArgumentCaptor.forClass(PlanProduccion.class);
        verify(planProduccionRepository).insertar(captor.capture());
        assertThat(captor.getValue().getIdUsuario()).isEqualTo(99);
        assertThat(r.responsable().idUsuario()).isEqualTo(99);
        assertThat(r.responsable().nombre()).isEqualTo("Otro Operario");
    }

    @Test
    @DisplayName("R-04: ADMIN con idUsuario inexistente responde 409 USUARIO_INEXISTENTE")
    void crearComoAdminConIdUsuarioInexistenteRespondeUsuarioInexistente() {
        autenticarComo(1, "ADMIN");
        when(usuarioRepository.existsById(99)).thenReturn(false);

        assertThatThrownBy(() -> service.crear(new CrearPlanRequest(
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10), null, 99)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "USUARIO_INEXISTENTE");

        verify(planProduccionRepository, never()).insertar(any(PlanProduccion.class));
    }

    // ------------------------------------------------------------------ obtener

    @Test
    @DisplayName("obtener un plan inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerInexistenteResponde404() {
        when(planProduccionRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró plan de producción con id 9999.");
    }

    // ------------------------------------------------------------------ listar

    @Test
    @DisplayName("listar mapea a PageResponse con el total de contar y el orden por defecto")
    void listarMapeaYUsaElTotalDeContar() {
        var planificacion = LocalDate.of(2026, 11, 5);
        var produccion = LocalDate.of(2026, 11, 10);
        when(planProduccionRepository.buscar(isNull(), isNull(), isNull(), isNull(),
                any(PageRequest.class)))
                .thenReturn(List.of(fila(ID_PLAN, planificacion, produccion, ID_AUTENTICADO,
                        "Operario", 3)));
        when(planProduccionRepository.contar(isNull(), isNull(), isNull(), isNull()))
                .thenReturn(21L);

        var r = service.listar(null, null, null, null, 2, 10, null);

        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0).idPlan()).isEqualTo(ID_PLAN);
        assertThat(r.content().get(0).estado()).isEqualTo("PLANIFICADO");
        assertThat(r.content().get(0).responsable().nombre()).isEqualTo("Operario");
        assertThat(r.content().get(0).cantidadProductos()).isEqualTo(3);
        assertThat(r.content().get(0).editable()).isTrue();
        assertThat(r.page()).isEqualTo(2);
        assertThat(r.size()).isEqualTo(10);
        assertThat(r.totalElements()).isEqualTo(21L);
        assertThat(r.totalPages()).isEqualTo(3);

        var captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(planProduccionRepository).buscar(isNull(), isNull(), isNull(), isNull(),
                captor.capture());
        var orden = captor.getValue();
        assertThat(orden.page()).isEqualTo(2);
        assertThat(orden.size()).isEqualTo(10);
        // Por defecto "fechaProduccion,desc": la lista blanca la resuelve a la columna SQL.
        assertThat(orden.propiedad()).isEqualTo("p.fecha_produccion");
        assertThat(orden.ascendente()).isFalse();
    }
}
