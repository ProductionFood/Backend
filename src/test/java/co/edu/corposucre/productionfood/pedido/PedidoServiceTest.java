package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;

import co.edu.corposucre.productionfood.cliente.ClienteRepository;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CrearPedidoRequest;

class PedidoServiceTest {

    private static final Integer ID_CLIENTE = 7;

    private PedidoRepository pedidoRepository;
    private ClienteRepository clienteRepository;
    private PedidoService service;

    @BeforeEach
    void setUp() {
        pedidoRepository = mock(PedidoRepository.class);
        clienteRepository = mock(ClienteRepository.class);
        service = new PedidoService(pedidoRepository, clienteRepository);
    }

    private void dadoClienteActivo(Integer idCliente) {
        when(clienteRepository.existsById(idCliente)).thenReturn(true);
        when(clienteRepository.existeActivo(idCliente)).thenReturn(true);
    }

    private void dadoInsertarConId(Integer id) {
        when(pedidoRepository.insertar(any(Pedido.class))).thenReturn(id);
    }

    private static Pedido fila(Integer id, EstadoPedido estado, LocalDate fechaEntrega,
                               BigDecimal total, int items) {
        var pedido = new Pedido();
        pedido.setIdPedido(id);
        pedido.setIdCliente(ID_CLIENTE);
        pedido.setNombreCliente("Tienda La Esquina");
        pedido.setFechaPedido(LocalDate.now());
        pedido.setFechaEntrega(fechaEntrega);
        pedido.setEstado(estado);
        pedido.setTotalPedido(total);
        pedido.setCantidadItems(items);
        return pedido;
    }

    @Test
    @DisplayName("crear inserta PENDIENTE sin fechaPedido y responde total 0, ítems 0 y editable")
    void crearValidoInsertaPendiente() {
        var entrega = LocalDate.now().plusDays(3);
        dadoClienteActivo(ID_CLIENTE);
        dadoInsertarConId(42);
        when(pedidoRepository.buscarPorId(42)).thenReturn(Optional.of(
                fila(42, EstadoPedido.PENDIENTE, entrega, new BigDecimal("0.00"), 0)));

        var r = service.crear(new CrearPedidoRequest(ID_CLIENTE, entrega));

        var captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepository).insertar(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(guardado.getEstado()).isEqualTo(EstadoPedido.PENDIENTE);
        // R-01: la fecha del pedido la pone la base; la aplicación no la envía.
        assertThat(guardado.getFechaPedido()).isNull();

        assertThat(r.idPedido()).isEqualTo(42);
        assertThat(r.cliente().idCliente()).isEqualTo(ID_CLIENTE);
        assertThat(r.cliente().nombre()).isEqualTo("Tienda La Esquina");
        assertThat(r.fechaEntrega()).isEqualTo(entrega);
        assertThat(r.estado()).isEqualTo("PENDIENTE");
        // Un pedido recién registrado no tiene detalle: su total es 0 con escala 2.
        assertThat(r.totalPedido()).isEqualByComparingTo("0.00");
        assertThat(r.totalPedido().scale()).isEqualTo(2);
        assertThat(r.cantidadItems()).isZero();
        assertThat(r.editable()).isTrue();
    }

    @Test
    @DisplayName("crear con cliente inexistente responde 409 CLIENTE_INEXISTENTE sin insertar")
    void crearConClienteInexistenteResponde409() {
        when(clienteRepository.existsById(9999)).thenReturn(false);

        assertThatThrownBy(() -> service.crear(new CrearPedidoRequest(9999, null)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CLIENTE_INEXISTENTE");

        verifyNoInteractions(pedidoRepository);
    }

    @Test
    @DisplayName("crear con cliente inactivo responde 409 CLIENTE_INACTIVO sin insertar")
    void crearConClienteInactivoResponde409() {
        when(clienteRepository.existsById(ID_CLIENTE)).thenReturn(true);
        when(clienteRepository.existeActivo(ID_CLIENTE)).thenReturn(false);

        assertThatThrownBy(() -> service.crear(new CrearPedidoRequest(ID_CLIENTE, null)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CLIENTE_INACTIVO");

        verifyNoInteractions(pedidoRepository);
    }

    @Test
    @DisplayName("crear con fecha de entrega de ayer responde 400 FECHA_ENTREGA_INVALIDA sin insertar")
    void crearConFechaDeEntregaAyerResponde400() {
        dadoClienteActivo(ID_CLIENTE);

        assertThatThrownBy(() -> service.crear(
                new CrearPedidoRequest(ID_CLIENTE, LocalDate.now().minusDays(1))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FECHA_ENTREGA_INVALIDA");

        verifyNoInteractions(pedidoRepository);
    }

    @Test
    @DisplayName("R-06: crear con fecha de entrega de hoy es válido")
    void crearConFechaDeEntregaHoyEsValido() {
        var hoy = LocalDate.now();
        dadoClienteActivo(ID_CLIENTE);
        dadoInsertarConId(42);
        when(pedidoRepository.buscarPorId(42)).thenReturn(Optional.of(
                fila(42, EstadoPedido.PENDIENTE, hoy, new BigDecimal("0.00"), 0)));

        var r = service.crear(new CrearPedidoRequest(ID_CLIENTE, hoy));

        assertThat(r.fechaEntrega()).isEqualTo(hoy);
        verify(pedidoRepository).insertar(any(Pedido.class));
    }

    @Test
    @DisplayName("crear sin fecha de entrega deja el campo en null y no edita la cabecera")
    void crearSinFechaDeEntregaDejaNull() {
        dadoClienteActivo(ID_CLIENTE);
        dadoInsertarConId(42);
        when(pedidoRepository.buscarPorId(42)).thenReturn(Optional.of(
                fila(42, EstadoPedido.PENDIENTE, null, new BigDecimal("0.00"), 0)));

        var r = service.crear(new CrearPedidoRequest(ID_CLIENTE, null));

        assertThat(r.fechaEntrega()).isNull();
        verify(pedidoRepository).insertar(any(Pedido.class));
        // Sin fecha de entrega no hace falta una escritura adicional.
        verify(pedidoRepository, never()).actualizar(any(Pedido.class));
    }

    @Test
    @DisplayName("crear persiste la fecha de entrega en el mismo INSERT")
    void crearPersisteFechaDeEntrega() {
        var entrega = LocalDate.now().plusDays(5);
        dadoClienteActivo(ID_CLIENTE);
        dadoInsertarConId(42);
        when(pedidoRepository.buscarPorId(42)).thenReturn(Optional.of(
                fila(42, EstadoPedido.PENDIENTE, entrega, new BigDecimal("0.00"), 0)));

        service.crear(new CrearPedidoRequest(ID_CLIENTE, entrega));

        // El INSERT lleva cliente, estado y fecha de entrega en una sola sentencia.
        var captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepository).insertar(captor.capture());
        verify(pedidoRepository, never()).actualizar(any(Pedido.class));
        assertThat(captor.getValue().getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(captor.getValue().getFechaEntrega()).isEqualTo(entrega);
    }

    @Test
    @DisplayName("listar delega en buscar y contar con el orden por defecto fechaPedido,desc")
    void listarDelegaEnBuscarYContar() {
        var inicio = LocalDate.now().minusDays(10);
        var fin = LocalDate.now().minusDays(1);
        when(pedidoRepository.buscar(eq(ID_CLIENTE), eq(EstadoPedido.PENDIENTE), eq(inicio),
                eq(fin), eq("p.fecha_pedido"), eq(false), eq(10), eq(20L)))
                .thenReturn(List.of(fila(7, EstadoPedido.PENDIENTE, null,
                        new BigDecimal("150.00"), 2)));
        when(pedidoRepository.contar(ID_CLIENTE, EstadoPedido.PENDIENTE, inicio, fin))
                .thenReturn(21L);

        var r = service.listar(ID_CLIENTE, EstadoPedido.PENDIENTE, inicio, fin, 2, 10, null);

        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0).idPedido()).isEqualTo(7);
        assertThat(r.content().get(0).estado()).isEqualTo("PENDIENTE");
        assertThat(r.page()).isEqualTo(2);
        assertThat(r.size()).isEqualTo(10);
        assertThat(r.totalElements()).isEqualTo(21L);
        // Por defecto "fechaPedido,desc": page 2 con size 10 es offset 20, calculado en long.
        verify(pedidoRepository).buscar(eq(ID_CLIENTE), eq(EstadoPedido.PENDIENTE), eq(inicio),
                eq(fin), eq("p.fecha_pedido"), eq(false), eq(10), eq(20L));
        verify(pedidoRepository).contar(ID_CLIENTE, EstadoPedido.PENDIENTE, inicio, fin);
    }

    @Test
    @DisplayName("listar con el rango de fechas invertido responde 400 RANGO_FECHAS_INVALIDO")
    void listarConRangoInvertidoResponde400() {
        var hoy = LocalDate.now();

        assertThatThrownBy(() -> service.listar(null, null, hoy, hoy.minusDays(1), 0, 10, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");

        verifyNoInteractions(pedidoRepository);
    }

    @Test
    @DisplayName("listar con un campo de orden fuera de la lista blanca responde 400")
    void listarConCampoDeOrdenNoListadoResponde400() {
        assertThatThrownBy(() -> service.listar(null, null, null, null, 0, 10, "cliente"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verifyNoInteractions(pedidoRepository);
    }

    @Test
    @DisplayName("obtener un pedido inexistente responde 404")
    void obtenerInexistenteResponde404() {
        when(pedidoRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró el pedido con id 9999.");
    }

    @Test
    @DisplayName("R-05: actualizar un pedido no PENDIENTE responde 409 sin escribir")
    void actualizarNoPendienteResponde409() {
        when(pedidoRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, EstadoPedido.EN_PREPARACION, null, new BigDecimal("150.00"), 2)));

        assertThatThrownBy(() -> service.actualizar(7,
                new ActualizarPedidoRequest(ID_CLIENTE, LocalDate.now().plusDays(1))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PEDIDO_NO_EDITABLE");

        verify(pedidoRepository, never()).actualizar(any(Pedido.class));
        // El estado se valida antes que el cliente, así que ni se consulta.
        verifyNoInteractions(clienteRepository);
    }

    @Test
    @DisplayName("actualizar un pedido PENDIENTE escribe la cabecera y responde la relectura")
    void actualizarPendienteEscribeYRelee() {
        var hoy = LocalDate.now();
        when(pedidoRepository.buscarPorId(7)).thenReturn(
                Optional.of(fila(7, EstadoPedido.PENDIENTE, null,
                        new BigDecimal("150.00"), 2)),
                Optional.of(fila(7, EstadoPedido.PENDIENTE, hoy.plusDays(2),
                        new BigDecimal("150.00"), 2)));
        dadoClienteActivo(ID_CLIENTE);

        var r = service.actualizar(7,
                new ActualizarPedidoRequest(ID_CLIENTE, hoy.plusDays(2)));

        var captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoRepository).actualizar(captor.capture());
        var escrito = captor.getValue();
        assertThat(escrito.getIdPedido()).isEqualTo(7);
        assertThat(escrito.getIdCliente()).isEqualTo(ID_CLIENTE);
        assertThat(escrito.getFechaEntrega()).isEqualTo(hoy.plusDays(2));

        assertThat(r.idPedido()).isEqualTo(7);
        assertThat(r.cliente().nombre()).isEqualTo("Tienda La Esquina");
        assertThat(r.fechaEntrega()).isEqualTo(hoy.plusDays(2));
        assertThat(r.estado()).isEqualTo("PENDIENTE");
        // El total y los ítems salen de la base, no de la fila leída antes de escribir.
        assertThat(r.totalPedido()).isEqualByComparingTo("150.00");
        assertThat(r.cantidadItems()).isEqualTo(2);
        assertThat(r.editable()).isTrue();
    }

    @Test
    @DisplayName("actualizar a un cliente inexistente responde 409 sin escribir")
    void actualizarAClienteInexistenteResponde409() {
        when(pedidoRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, EstadoPedido.PENDIENTE, null, BigDecimal.ZERO, 0)));
        when(clienteRepository.existsById(9999)).thenReturn(false);

        assertThatThrownBy(() -> service.actualizar(7,
                new ActualizarPedidoRequest(9999, LocalDate.now())))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CLIENTE_INEXISTENTE");

        verify(pedidoRepository, never()).actualizar(any(Pedido.class));
    }

    @Test
    @DisplayName("actualizar a un cliente inactivo responde 409 sin escribir")
    void actualizarAClienteInactivoResponde409() {
        when(pedidoRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, EstadoPedido.PENDIENTE, null, BigDecimal.ZERO, 0)));
        when(clienteRepository.existsById(ID_CLIENTE)).thenReturn(true);
        when(clienteRepository.existeActivo(ID_CLIENTE)).thenReturn(false);

        assertThatThrownBy(() -> service.actualizar(7,
                new ActualizarPedidoRequest(ID_CLIENTE, LocalDate.now())))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CLIENTE_INACTIVO");

        verify(pedidoRepository, never()).actualizar(any(Pedido.class));
    }

    @Test
    @DisplayName("actualizar con fecha de entrega anterior a hoy responde 400 sin escribir")
    void actualizarConFechaDeEntregaDeAyerResponde400() {
        when(pedidoRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, EstadoPedido.PENDIENTE, null, BigDecimal.ZERO, 0)));
        dadoClienteActivo(ID_CLIENTE);

        assertThatThrownBy(() -> service.actualizar(7,
                new ActualizarPedidoRequest(ID_CLIENTE, LocalDate.now().minusDays(1))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FECHA_ENTREGA_INVALIDA");

        verify(pedidoRepository, never()).actualizar(any(Pedido.class));
    }

    @Test
    @DisplayName("esEditable solo es true para un pedido PENDIENTE existente")
    void esEditableSoloParaPendiente() {
        when(pedidoRepository.buscarPorId(1)).thenReturn(Optional.of(
                fila(1, EstadoPedido.PENDIENTE, null, BigDecimal.ZERO, 0)));
        when(pedidoRepository.buscarPorId(2)).thenReturn(Optional.of(
                fila(2, EstadoPedido.EN_PREPARACION, null, BigDecimal.ZERO, 0)));
        when(pedidoRepository.buscarPorId(9)).thenReturn(Optional.empty());

        assertThat(service.esEditable(1)).isTrue();
        assertThat(service.esEditable(2)).isFalse();
        // Un pedido inexistente no es editable y no lanza: HU-17 consulta esto antes de actuar.
        assertThat(service.esEditable(9)).isFalse();
    }
}
