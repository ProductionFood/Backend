package co.edu.corposucre.productionfood.devolucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.error.ReglaDeNegocioException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.devolucion.dto.CrearDevolucionRequest;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.pedido.DetallePedido;
import co.edu.corposucre.productionfood.pedido.DetallePedidoRepository;
import co.edu.corposucre.productionfood.pedido.EstadoPedido;
import co.edu.corposucre.productionfood.pedido.Pedido;
import co.edu.corposucre.productionfood.pedido.PedidoRepository;

/**
 * Pruebas unitarias de {@link DevolucionService} con Mockito (HU-18).
 *
 * <p>Cubren las validaciones en el orden de la especificación §5 —cantidad, línea, estado del
 * pedido y acumulado—, la semántica fija de {@code devueltoPreviamente} y
 * {@code disponibleParaDevolver} (R-03) y la paginación de los dos listados. La exclusión de
 * las {@code RECHAZADA} del acumulado vive en {@link DevolucionRepository} y se prueba en su
 * test de integración; aquí el servicio consume el acumulado que ese repositorio informa. El
 * cambio de estado con reposición de stock se prueba en {@link DevolucionAprobacionTest}.
 */
class DevolucionServiceTest {

    private static final Integer ID_DETALLE = 88;
    private static final Integer ID_PEDIDO = 47;
    private static final Integer ID_DEVOLUCION = 15;

    private DevolucionRepository devolucionRepository;
    private DetallePedidoRepository detallePedidoRepository;
    private PedidoRepository pedidoRepository;
    private InventarioPtRepository inventarioPtRepository;
    private InventarioPtService inventarioPtService;
    private DevolucionService service;

    @BeforeEach
    void setUp() {
        devolucionRepository = mock(DevolucionRepository.class);
        detallePedidoRepository = mock(DetallePedidoRepository.class);
        pedidoRepository = mock(PedidoRepository.class);
        inventarioPtRepository = mock(InventarioPtRepository.class);
        inventarioPtService = mock(InventarioPtService.class);
        service = new DevolucionService(devolucionRepository, detallePedidoRepository,
                pedidoRepository, inventarioPtRepository, inventarioPtService);
    }

    // ------------------------------------------------------------------ crear

    @Test
    @DisplayName("crear inserta PENDIENTE, relee con los JOIN y responde el contrato de la spec")
    void crearInsertaPendienteYRespondeElContrato() {
        dadoDetalleDelPedido(ID_DETALLE, ID_PEDIDO, EstadoPedido.ENTREGADO, "30.00");
        // Primera suma: el acumulado previo, con el que se valida. Segunda: el de la respuesta,
        // que ya incluye la devolución recién insertada, que nace PENDIENTE y cuenta (R-03).
        when(devolucionRepository.sumarDevueltoPorDetalle(ID_DETALLE))
                .thenReturn(new BigDecimal("0.00"), new BigDecimal("3.00"));
        when(devolucionRepository.insertar(any(Devolucion.class))).thenReturn(ID_DEVOLUCION);
        when(devolucionRepository.buscarPorId(ID_DEVOLUCION)).thenReturn(Optional.of(
                devolucion(ID_DEVOLUCION, ID_DETALLE, EstadoDevolucion.PENDIENTE,
                        "3.00", "30.00")));

        var r = service.crear(solicitud(ID_DETALLE, "3.00"));

        var captor = ArgumentCaptor.forClass(Devolucion.class);
        verify(devolucionRepository).insertar(captor.capture());
        var guardada = captor.getValue();
        assertThat(guardada.idDetallePedido()).isEqualTo(ID_DETALLE);
        assertThat(guardada.estado()).isEqualTo(EstadoDevolucion.PENDIENTE);
        assertThat(guardada.cantidadDevuelta()).isEqualByComparingTo("3.00");
        // R-06: la fecha la pone la base, el INSERT no la envía.
        assertThat(guardada.fechaDevolucion()).isNull();

        assertThat(r.idDevolucion()).isEqualTo(ID_DEVOLUCION);
        assertThat(r.pedido().idPedido()).isEqualTo(ID_PEDIDO);
        assertThat(r.pedido().cliente()).isEqualTo("Tienda La Esquina");
        assertThat(r.producto().idProducto()).isEqualTo(3);
        assertThat(r.producto().nombre()).isEqualTo("Pan francés");
        // La devolución no proyecta la unidad; Jackson omite el campo.
        assertThat(r.producto().unidad()).isNull();
        assertThat(r.cantidadPedida()).isEqualByComparingTo("30.00");
        assertThat(r.cantidadDevuelta()).isEqualByComparingTo("3.00");
        // Ejemplo de la spec §4: línea de 30 con devolución de 3.
        assertThat(r.devueltoPreviamente()).isEqualByComparingTo("0.00");
        assertThat(r.devueltoPreviamente().scale()).isEqualTo(2);
        assertThat(r.disponibleParaDevolver()).isEqualByComparingTo("27.00");
        assertThat(r.disponibleParaDevolver().scale()).isEqualTo(2);
        assertThat(r.motivo()).isEqualTo("Producto en mal estado");
        assertThat(r.fechaDevolucion()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(r.estado()).isEqualTo("PENDIENTE");

        verify(detallePedidoRepository).obtenerPorIdParaActualizar(ID_DETALLE);
        verify(pedidoRepository).buscarPorId(ID_PEDIDO);
        // R-01: registrar solo reporta la mercancía; no se mueve inventario, aunque el servicio
        // lo tenga inyectado para la reposición del cambio de estado.
        verifyNoInteractions(inventarioPtRepository, inventarioPtService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-5", "0.001"})
    @DisplayName("CA-04: cantidad nula, cero, negativa o redondeable a cero responde 400")
    void crearConCantidadNoPositivaResponde400(String cantidad) {
        assertThatThrownBy(() -> service.crear(solicitud(ID_DETALLE, cantidad)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        // La cantidad se valida antes de tocar la base (orden §5, paso a).
        verifyNoInteractions(detallePedidoRepository, pedidoRepository, devolucionRepository);
    }

    @Test
    @DisplayName("crear con un detalle inexistente responde 409 DETALLE_PEDIDO_INEXISTENTE")
    void crearConDetalleInexistenteResponde409() {
        when(detallePedidoRepository.obtenerPorIdParaActualizar(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(solicitud(9999, "3.00")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "DETALLE_PEDIDO_INEXISTENTE");

        verifyNoInteractions(pedidoRepository, devolucionRepository);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"ENTREGADO", "EN_PREPARACION"})
    @DisplayName("R-05: ENTREGADO y EN_PREPARACION admiten registrar la devolución")
    void crearEnPedidoDevolvibleInserta(EstadoPedido estado) {
        dadoDetalleDelPedido(ID_DETALLE, ID_PEDIDO, estado, "30.00");
        when(devolucionRepository.sumarDevueltoPorDetalle(ID_DETALLE))
                .thenReturn(new BigDecimal("0.00"), new BigDecimal("3.00"));
        when(devolucionRepository.insertar(any(Devolucion.class))).thenReturn(ID_DEVOLUCION);
        when(devolucionRepository.buscarPorId(ID_DEVOLUCION)).thenReturn(Optional.of(
                devolucion(ID_DEVOLUCION, ID_DETALLE, EstadoDevolucion.PENDIENTE,
                        "3.00", "30.00")));

        var r = service.crear(solicitud(ID_DETALLE, "3.00"));

        assertThat(r.estado()).isEqualTo("PENDIENTE");
        verify(devolucionRepository).insertar(any(Devolucion.class));
    }

    @ParameterizedTest
    @EnumSource(value = EstadoPedido.class, names = {"PENDIENTE", "CANCELADO"})
    @DisplayName("R-05: un pedido PENDIENTE o CANCELADO responde 409 PEDIDO_NO_DEVOLVIBLE")
    void crearEnPedidoNoDevolvibleResponde409(EstadoPedido estado) {
        dadoDetalleDelPedido(ID_DETALLE, ID_PEDIDO, estado, "30.00");

        assertThatThrownBy(() -> service.crear(solicitud(ID_DETALLE, "3.00")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PEDIDO_NO_DEVOLVIBLE");

        // El estado se valida antes que el acumulado, así que ni se consulta.
        verifyNoInteractions(devolucionRepository);
    }

    @Test
    @DisplayName("CP-09: acumulado con previas en trámite excede lo pedido y responde 422")
    void crearConAcumuladoPrevioResponde422() {
        dadoDetalleDelPedido(ID_DETALLE, ID_PEDIDO, EstadoPedido.ENTREGADO, "10.00");
        when(devolucionRepository.sumarDevueltoPorDetalle(ID_DETALLE))
                .thenReturn(new BigDecimal("8.00"));

        assertThatThrownBy(() -> service.crear(solicitud(ID_DETALLE, "5.00")))
                .isInstanceOf(ReglaDeNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_EXCEDE_PEDIDO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNPROCESSABLE_ENTITY)
                .hasMessage("Se pidieron 10 unidades y ya hay 8 devueltas o en trámite. "
                        + "Solo puede devolver 2.");

        verify(devolucionRepository, never()).insertar(any(Devolucion.class));
    }

    @Test
    @DisplayName("CP-09: el acumulado justo en el límite (2 + 8 de 10) se registra")
    void crearEnElLimiteDelPedidoEsValido() {
        dadoDetalleDelPedido(ID_DETALLE, ID_PEDIDO, EstadoPedido.ENTREGADO, "10.00");
        when(devolucionRepository.sumarDevueltoPorDetalle(ID_DETALLE))
                .thenReturn(new BigDecimal("2.00"), new BigDecimal("10.00"));
        when(devolucionRepository.insertar(any(Devolucion.class))).thenReturn(ID_DEVOLUCION);
        when(devolucionRepository.buscarPorId(ID_DEVOLUCION)).thenReturn(Optional.of(
                devolucion(ID_DEVOLUCION, ID_DETALLE, EstadoDevolucion.PENDIENTE,
                        "8.00", "10.00")));

        var r = service.crear(solicitud(ID_DETALLE, "8.00"));

        verify(devolucionRepository).insertar(any(Devolucion.class));
        // El compromiso de 10 ya incluye estas 8: antes de esta devolución había 2.
        assertThat(r.devueltoPreviamente()).isEqualByComparingTo("2.00");
        assertThat(r.disponibleParaDevolver()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("CP-10: las previas RECHAZADA no ocupan cantidad y el registro procede")
    void crearConPreviasRechazadasNoLasCuenta() {
        // El repositorio excluye las RECHAZADA de sumarDevueltoPorDetalle (R-03): sobre
        // una línea de 10 con 2 aprobadas y 8 rechazadas informa 2, y 8 unidades más caben.
        dadoDetalleDelPedido(ID_DETALLE, ID_PEDIDO, EstadoPedido.ENTREGADO, "10.00");
        when(devolucionRepository.sumarDevueltoPorDetalle(ID_DETALLE))
                .thenReturn(new BigDecimal("2.00"), new BigDecimal("10.00"));
        when(devolucionRepository.insertar(any(Devolucion.class))).thenReturn(ID_DEVOLUCION);
        when(devolucionRepository.buscarPorId(ID_DEVOLUCION)).thenReturn(Optional.of(
                devolucion(ID_DEVOLUCION, ID_DETALLE, EstadoDevolucion.PENDIENTE,
                        "8.00", "10.00")));

        var r = service.crear(solicitud(ID_DETALLE, "8.00"));

        assertThat(r.estado()).isEqualTo("PENDIENTE");
        verify(devolucionRepository).insertar(any(Devolucion.class));
    }

    // ----------------------------------------------------------------- listar

    @Test
    @DisplayName("listar mapea las filas con la semántica del acumulado y usa el total de contar")
    void listarMapeaFilasYUsaElTotalDeContar() {
        var aprobada = devolucion(15, ID_DETALLE, EstadoDevolucion.APROBADA, "5.00", "10.00");
        var rechazada = devolucion(16, ID_DETALLE, EstadoDevolucion.RECHAZADA, "4.00", "10.00");
        // sort nulo → orden por defecto "fechaDevolucion,desc" → columna d.fecha_devolucion.
        when(devolucionRepository.buscar(null, null, null, null,
                new PageRequest(0, 10, "d.fecha_devolucion", false)))
                .thenReturn(List.of(
                        new DevolucionFila(aprobada, new BigDecimal("8.00")),
                        new DevolucionFila(rechazada, new BigDecimal("8.00"))));
        when(devolucionRepository.contar(null, null, null, null)).thenReturn(21L);

        var r = service.listar(null, null, null, null, 0, 10, null);

        assertThat(r.content()).hasSize(2);
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(10);
        // El total viene de contar, no del tamaño de la página.
        assertThat(r.totalElements()).isEqualTo(21L);

        // APROBADA: el compromiso de 8 incluye sus 5, así que antes de esta había 3.
        assertThat(r.content().get(0).devueltoPreviamente()).isEqualByComparingTo("3.00");
        assertThat(r.content().get(0).disponibleParaDevolver()).isEqualByComparingTo("2.00");
        assertThat(r.content().get(0).estado()).isEqualTo("APROBADA");
        // RECHAZADA: no cuenta en el acumulado; las 8 informadas son de devoluciones previas.
        assertThat(r.content().get(1).devueltoPreviamente()).isEqualByComparingTo("8.00");
        assertThat(r.content().get(1).disponibleParaDevolver()).isEqualByComparingTo("2.00");
        assertThat(r.content().get(1).estado()).isEqualTo("RECHAZADA");

        verify(devolucionRepository).buscar(null, null, null, null,
                new PageRequest(0, 10, "d.fecha_devolucion", false));
        verify(devolucionRepository).contar(null, null, null, null);
    }

    @Test
    @DisplayName("listar traduce el sort con la lista blanca del repositorio")
    void listarTraduceElSortConLaListaBlanca() {
        when(devolucionRepository.buscar(EstadoDevolucion.PENDIENTE, null, null, null,
                new PageRequest(0, 20, "d.estado", true))).thenReturn(List.of());
        when(devolucionRepository.contar(EstadoDevolucion.PENDIENTE, null, null, null))
                .thenReturn(0L);

        var r = service.listar(EstadoDevolucion.PENDIENTE, null, null, null, 0, 20,
                "estado,asc");

        assertThat(r.content()).isEmpty();
        verify(devolucionRepository).buscar(EstadoDevolucion.PENDIENTE, null, null, null,
                new PageRequest(0, 20, "d.estado", true));
    }

    @Test
    @DisplayName("listar con un campo de orden fuera de la lista blanca responde 400")
    void listarConCampoDeOrdenInvalidoResponde400() {
        // PageRequest.of lanza el 400 CAMPO_ORDEN_INVALIDO antes de consultar: un orden fuera
        // de la lista blanca nunca llega al SQL.
        assertThatThrownBy(() -> service.listar(null, null, null, null, 0, 10, "cliente"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verifyNoInteractions(devolucionRepository);
    }

    // ---------------------------------------------------------------- obtener

    @Test
    @DisplayName("obtener devuelve la devolución con el compromiso de su línea")
    void obtenerDevuelveLaDevolucion() {
        when(devolucionRepository.buscarPorId(ID_DEVOLUCION)).thenReturn(Optional.of(
                devolucion(ID_DEVOLUCION, ID_DETALLE, EstadoDevolucion.APROBADA,
                        "5.00", "10.00")));
        when(devolucionRepository.sumarDevueltoPorDetalle(ID_DETALLE))
                .thenReturn(new BigDecimal("8.00"));

        var r = service.obtener(ID_DEVOLUCION);

        assertThat(r.idDevolucion()).isEqualTo(ID_DEVOLUCION);
        assertThat(r.estado()).isEqualTo("APROBADA");
        // APROBADA cuenta: el compromiso de 8 incluye estas 5 → antes había 3.
        assertThat(r.devueltoPreviamente()).isEqualByComparingTo("3.00");
        assertThat(r.disponibleParaDevolver()).isEqualByComparingTo("2.00");
        verify(devolucionRepository).sumarDevueltoPorDetalle(ID_DETALLE);
    }

    @Test
    @DisplayName("obtener una devolución inexistente responde 404")
    void obtenerInexistenteResponde404() {
        when(devolucionRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la devolución con id 9999.");

        verifyNoInteractions(detallePedidoRepository, pedidoRepository);
    }

    // -------------------------------------------------------- listarPorPedido

    @Test
    @DisplayName("listarPorPedido pasa el filtro idPedido y mapea la página")
    void listarPorPedidoPasaElFiltroYMApea() {
        when(devolucionRepository.buscar(null, null, null, ID_PEDIDO,
                new PageRequest(0, 5, "d.id_devolucion", false)))
                .thenReturn(List.of(new DevolucionFila(
                        devolucion(ID_DEVOLUCION, ID_DETALLE, EstadoDevolucion.PENDIENTE,
                                "3.00", "30.00"),
                        new BigDecimal("3.00"))));
        when(devolucionRepository.contar(null, null, null, ID_PEDIDO)).thenReturn(1L);

        var r = service.listarPorPedido(ID_PEDIDO, 0, 5, "id,desc");

        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0).idDevolucion()).isEqualTo(ID_DEVOLUCION);
        assertThat(r.content().get(0).pedido().idPedido()).isEqualTo(ID_PEDIDO);
        assertThat(r.content().get(0).devueltoPreviamente()).isEqualByComparingTo("0.00");
        assertThat(r.content().get(0).disponibleParaDevolver()).isEqualByComparingTo("27.00");
        assertThat(r.totalElements()).isEqualTo(1L);
        verify(devolucionRepository).buscar(null, null, null, ID_PEDIDO,
                new PageRequest(0, 5, "d.id_devolucion", false));
        verify(devolucionRepository).contar(null, null, null, ID_PEDIDO);
    }

    @Test
    @DisplayName("listarPorPedido de un pedido sin devoluciones devuelve página vacía, sin 404")
    void listarPorPedidoSinDevolucionesDevuelvePaginaVacia() {
        // Sin stub de pedidoRepository: el listado no comprueba la existencia del pedido, así
        // que un id inexistente recorre el mismo camino que uno sin devoluciones.
        when(devolucionRepository.buscar(null, null, null, 9999,
                new PageRequest(0, 10, "d.fecha_devolucion", false))).thenReturn(List.of());
        when(devolucionRepository.contar(null, null, null, 9999)).thenReturn(0L);

        var r = service.listarPorPedido(9999, 0, 10, null);

        assertThat(r.content()).isEmpty();
        assertThat(r.totalElements()).isZero();
        assertThat(r.totalPages()).isZero();
        assertThat(r.page()).isZero();
    }

    // ----------------------------------------------------------------- datos

    /**
     * Deja la línea y su pedido en el estado dado, listos para las validaciones de {@code crear}.
     */
    private void dadoDetalleDelPedido(Integer idDetalle, Integer idPedido, EstadoPedido estado,
                                      String pedida) {
        when(detallePedidoRepository.obtenerPorIdParaActualizar(idDetalle))
                .thenReturn(Optional.of(new DetallePedido(idDetalle, idPedido, 3, null, null,
                        new BigDecimal(pedida), new BigDecimal("10.00"))));
        when(pedidoRepository.buscarPorId(idPedido))
                .thenReturn(Optional.of(pedido(idPedido, estado)));
    }

    private static CrearDevolucionRequest solicitud(Integer idDetalle, String cantidad) {
        return new CrearDevolucionRequest(idDetalle, "Producto en mal estado",
                "Tres panes llegaron aplastados",
                cantidad == null ? null : new BigDecimal(cantidad));
    }

    private static Pedido pedido(Integer id, EstadoPedido estado) {
        var pedido = new Pedido();
        pedido.setIdPedido(id);
        pedido.setEstado(estado);
        return pedido;
    }

    /**
     * Fila de devolución con los campos que aportan los {@code JOIN} del repositorio.
     */
    private static Devolucion devolucion(Integer id, Integer idDetalle, EstadoDevolucion estado,
                                         String cantidadDevuelta, String cantidadPedida) {
        return new Devolucion(id, idDetalle, "Producto en mal estado",
                "Tres panes llegaron aplastados", new BigDecimal(cantidadDevuelta),
                LocalDate.of(2026, 9, 18), estado, ID_PEDIDO, "Tienda La Esquina", 3,
                "Pan francés", new BigDecimal(cantidadPedida));
    }
}
