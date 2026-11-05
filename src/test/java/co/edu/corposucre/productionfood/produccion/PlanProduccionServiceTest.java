package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
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
import co.edu.corposucre.productionfood.produccion.dto.ActualizarPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CambiarEstadoPlanRequest;
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

    // -------------------------------------------------------------- actualizar

    @Test
    @DisplayName("actualizar un plan PLANIFICADO sin idUsuario conserva el responsable actual")
    void actualizarPlanificadoConservaElResponsableActual() {
        autenticarComo(ID_AUTENTICADO, "PRODUCCION");
        var antes = fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                7, "Responsable previo", 0);
        var despues = fila(ID_PLAN, LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 12),
                7, "Responsable previo", 0);
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(antes), Optional.of(despues));
        when(planProduccionRepository.actualizarSiEstado(any(PlanProduccion.class),
                eq(EstadoPlan.PLANIFICADO))).thenReturn(1);

        var r = service.actualizar(ID_PLAN, new ActualizarPlanRequest(
                LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 12), "Editado", null));

        var captor = ArgumentCaptor.forClass(PlanProduccion.class);
        verify(planProduccionRepository).actualizarSiEstado(captor.capture(),
                eq(EstadoPlan.PLANIFICADO));
        var guardado = captor.getValue();
        assertThat(guardado.getFechaPlanificacion()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(guardado.getFechaProduccion()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(guardado.getObservaciones()).isEqualTo("Editado");
        // Sin idUsuario la edición no reasigna: conserva el dueño actual del plan.
        assertThat(guardado.getIdUsuario()).isEqualTo(7);

        assertThat(r.fechaPlanificacion()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(r.fechaProduccion()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(r.responsable().idUsuario()).isEqualTo(7);
        verifyNoInteractions(usuarioRepository);
    }

    @Test
    @DisplayName("R-04: ADMIN con idUsuario explícito cambia el responsable del plan")
    void actualizarComoAdminConIdUsuarioCambiaElResponsable() {
        autenticarComo(1, "ADMIN");
        when(usuarioRepository.existsById(99)).thenReturn(true);
        var antes = fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                7, "Responsable previo", 0);
        var despues = fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                99, "Otro Operario", 0);
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(antes), Optional.of(despues));
        when(planProduccionRepository.actualizarSiEstado(any(PlanProduccion.class),
                eq(EstadoPlan.PLANIFICADO))).thenReturn(1);

        var r = service.actualizar(ID_PLAN, new ActualizarPlanRequest(
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10), null, 99));

        var captor = ArgumentCaptor.forClass(PlanProduccion.class);
        verify(planProduccionRepository).actualizarSiEstado(captor.capture(),
                eq(EstadoPlan.PLANIFICADO));
        assertThat(captor.getValue().getIdUsuario()).isEqualTo(99);
        assertThat(r.responsable().idUsuario()).isEqualTo(99);
        assertThat(r.responsable().nombre()).isEqualTo("Otro Operario");
    }

    @Test
    @DisplayName("R-05: actualizar un plan EN_PROCESO responde 409 PLAN_NO_EDITABLE")
    void actualizarPlanNoEditableRespondeConflicto() {
        autenticarComo(ID_AUTENTICADO, "PRODUCCION");
        var plan = fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                ID_AUTENTICADO, "Operario", 2);
        plan.setEstado(EstadoPlan.EN_PROCESO);
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.actualizar(ID_PLAN, new ActualizarPlanRequest(
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10), null, null)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_NO_EDITABLE");

        verify(planProduccionRepository, never())
                .actualizarSiEstado(any(PlanProduccion.class), any(EstadoPlan.class));
    }

    @Test
    @DisplayName("R-03: actualizar con fechaProduccion anterior responde 400 FECHAS_INVALIDAS")
    void actualizarConFechasInvertidasRespondeFechasInvalidas() {
        autenticarComo(ID_AUTENTICADO, "PRODUCCION");
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        ID_AUTENTICADO, "Operario", 0)));

        assertThatThrownBy(() -> service.actualizar(ID_PLAN, new ActualizarPlanRequest(
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 9), null, null)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FECHAS_INVALIDAS");

        verify(planProduccionRepository, never())
                .actualizarSiEstado(any(PlanProduccion.class), any(EstadoPlan.class));
    }

    @Test
    @DisplayName("actualizar que pierde la carrera (UPDATE 0 filas) responde PLAN_NO_EDITABLE")
    void actualizarQuePierdeLaCarreraRespondePlanNoEditable() {
        autenticarComo(ID_AUTENTICADO, "PRODUCCION");
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        ID_AUTENTICADO, "Operario", 0)));
        when(planProduccionRepository.actualizarSiEstado(any(PlanProduccion.class),
                eq(EstadoPlan.PLANIFICADO))).thenReturn(0);

        assertThatThrownBy(() -> service.actualizar(ID_PLAN, new ActualizarPlanRequest(
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10), null, null)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_NO_EDITABLE");
    }

    // ----------------------------------------------------------- cambiarEstado

    @Test
    @DisplayName("cambiarEstado PLANIFICADO a EN_PROCESO con líneas responde 200")
    void cambiarEstadoValidoConLineas() {
        var planificado = fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                ID_AUTENTICADO, "Operario", 2);
        var enProceso = fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                ID_AUTENTICADO, "Operario", 2);
        enProceso.setEstado(EstadoPlan.EN_PROCESO);
        when(planProduccionRepository.buscarPorId(ID_PLAN))
                .thenReturn(Optional.of(planificado), Optional.of(enProceso));
        when(planProduccionRepository.contarDetalles(ID_PLAN)).thenReturn(2);
        when(planProduccionRepository.cambiarEstado(ID_PLAN, EstadoPlan.PLANIFICADO,
                EstadoPlan.EN_PROCESO)).thenReturn(1);

        var r = service.cambiarEstado(ID_PLAN, new CambiarEstadoPlanRequest("EN_PROCESO"));

        assertThat(r.estado()).isEqualTo("EN_PROCESO");
        assertThat(r.editable()).isFalse();
        assertThat(r.cantidadProductos()).isEqualTo(2);
        verify(planProduccionRepository).cambiarEstado(ID_PLAN, EstadoPlan.PLANIFICADO,
                EstadoPlan.EN_PROCESO);
        // R-06: la transición no consulta ni escribe nada de inventario.
        verifyNoInteractions(usuarioRepository);
    }

    @Test
    @DisplayName("R-02: iniciar un plan sin líneas responde 409 PLAN_SIN_PRODUCTOS")
    void cambiarEstadoSinProductosRespondePlanSinProductos() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        ID_AUTENTICADO, "Operario", 0)));
        when(planProduccionRepository.contarDetalles(ID_PLAN)).thenReturn(0);

        assertThatThrownBy(() -> service.cambiarEstado(ID_PLAN,
                new CambiarEstadoPlanRequest("EN_PROCESO")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PLAN_SIN_PRODUCTOS");

        verify(planProduccionRepository, never())
                .cambiarEstado(any(Integer.class), any(EstadoPlan.class), any(EstadoPlan.class));
    }

    @Test
    @DisplayName("R-01: PLANIFICADO a COMPLETADO es un salto prohibido (TRANSICION_INVALIDA)")
    void cambiarEstadoConSaltoProhibidoRespondeTransicionInvalida() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        ID_AUTENTICADO, "Operario", 2)));

        assertThatThrownBy(() -> service.cambiarEstado(ID_PLAN,
                new CambiarEstadoPlanRequest("COMPLETADO")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA")
                .hasMessageContaining("PLANIFICADO")
                .hasMessageContaining("EN_PROCESO");

        verify(planProduccionRepository, never())
                .cambiarEstado(any(Integer.class), any(EstadoPlan.class), any(EstadoPlan.class));
    }

    @Test
    @DisplayName("R-01: COMPLETADO es terminal y no puede volver a EN_PROCESO")
    void cambiarEstadoDesdeUnEstadoTerminalRespondeTransicionInvalida() {
        var completado = fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                ID_AUTENTICADO, "Operario", 2);
        completado.setEstado(EstadoPlan.COMPLETADO);
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(completado));

        assertThatThrownBy(() -> service.cambiarEstado(ID_PLAN,
                new CambiarEstadoPlanRequest("EN_PROCESO")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA")
                .hasMessageContaining("no hay transiciones disponibles");

        verify(planProduccionRepository, never())
                .cambiarEstado(any(Integer.class), any(EstadoPlan.class), any(EstadoPlan.class));
    }

    @Test
    @DisplayName("un literal de estado no canónico responde 400 VALIDACION_FALLIDA")
    void cambiarEstadoConLiteralInvalidoRespondeValidacionFallida() {
        assertThatThrownBy(() -> service.cambiarEstado(ID_PLAN,
                new CambiarEstadoPlanRequest("en_proceso")))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "VALIDACION_FALLIDA");

        verifyNoInteractions(planProduccionRepository, usuarioRepository);
    }

    @Test
    @DisplayName("cambiarEstado que pierde la carrera (UPDATE 0 filas) responde TRANSICION_INVALIDA")
    void cambiarEstadoQuePierdeLaCarreraRespondeTransicionInvalida() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        ID_AUTENTICADO, "Operario", 2)));
        when(planProduccionRepository.contarDetalles(ID_PLAN)).thenReturn(2);
        when(planProduccionRepository.cambiarEstado(ID_PLAN, EstadoPlan.PLANIFICADO,
                EstadoPlan.EN_PROCESO)).thenReturn(0);

        assertThatThrownBy(() -> service.cambiarEstado(ID_PLAN,
                new CambiarEstadoPlanRequest("EN_PROCESO")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");
    }

    // -------------------------------------------------------------- esEditable

    @Test
    @DisplayName("R-05: esEditable es true solo para un plan PLANIFICADO")
    void esEditableEsTrueParaUnPlanPlanificado() {
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(
                fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                        ID_AUTENTICADO, "Operario", 0)));

        assertThat(service.esEditable(ID_PLAN)).isTrue();
    }

    @Test
    @DisplayName("R-05: esEditable es false para un plan EN_PROCESO")
    void esEditableEsFalseParaUnPlanEnProceso() {
        var plan = fila(ID_PLAN, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 10),
                ID_AUTENTICADO, "Operario", 2);
        plan.setEstado(EstadoPlan.EN_PROCESO);
        when(planProduccionRepository.buscarPorId(ID_PLAN)).thenReturn(Optional.of(plan));

        assertThat(service.esEditable(ID_PLAN)).isFalse();
    }

    @Test
    @DisplayName("esEditable de un plan inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void esEditableDeUnPlanInexistenteResponde404() {
        when(planProduccionRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.esEditable(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("R-06: el servicio no tiene ninguna dependencia de inventario")
    void elServicioNoDependeDeInventario() {
        // La regla se cumple por construcción: si el servicio no declara un colaborador de
        // inventario, cambiarEstado no puede mover stock.
        assertThat(Arrays.stream(PlanProduccionService.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getSimpleName))
                .noneMatch(tipo -> tipo.contains("Inventario"));
    }
}
