package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.inventario.TipoMovimientoPt;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CrearPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.PedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.ProductoVendidoResponse;

class PedidoServiceTest {

    private static final Integer ID_CLIENTE = 7;

    private PedidoRepository pedidoRepository;
    private ClienteRepository clienteRepository;
    private InventarioPtService inventarioPtService;
    private PedidoService service;

    @BeforeEach
    void setUp() {
        pedidoRepository = mock(PedidoRepository.class);
        clienteRepository = mock(ClienteRepository.class);
        inventarioPtService = mock(InventarioPtService.class);
        service = new PedidoService(pedidoRepository, clienteRepository, inventarioPtService);
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
        pedido.setTotalBruto(total);
        pedido.setDevoluciones(BigDecimal.ZERO.setScale(2));
        pedido.setTotalNeto(total);
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
        when(clienteRepository.existsById(ID_CLIENTE)).thenReturn(true);
        when(pedidoRepository.buscar(eq(ID_CLIENTE), eq(EstadoPedido.PENDIENTE), eq(inicio),
                eq(fin), eq("p.fecha_pedido"), eq(false), eq(10), eq(20L)))
                .thenReturn(List.of(fila(7, EstadoPedido.PENDIENTE, null,
                        new BigDecimal("150.00"), 2)));
        when(pedidoRepository.contar(ID_CLIENTE, EstadoPedido.PENDIENTE, inicio, fin))
                .thenReturn(21L);

        var r = service.listar(ID_CLIENTE, EstadoPedido.PENDIENTE, inicio, fin, 2, 10, null,
                false);

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
    @DisplayName("HU-25: listar mapea totalBruto, devoluciones y totalNeto sin tocar totalPedido")
    void listarMapeaLosImportesNuevos() {
        var f = fila(7, EstadoPedido.ENTREGADO, null, new BigDecimal("500.00"), 2);
        f.setTotalBruto(new BigDecimal("500.00"));
        f.setDevoluciones(new BigDecimal("120.00"));
        f.setTotalNeto(new BigDecimal("380.00"));
        when(pedidoRepository.buscar(any(), any(), any(), any(), any(), eq(false), eq(20), eq(0L)))
                .thenReturn(List.of(f));
        when(pedidoRepository.contar(null, null, null, null)).thenReturn(1L);

        var r = service.listar(null, null, null, null, 0, 20, null, false)
                .content().get(0);

        assertThat(r.totalPedido()).isEqualByComparingTo("500.00");
        assertThat(r.totalBruto()).isEqualByComparingTo("500.00");
        assertThat(r.devoluciones()).isEqualByComparingTo("120.00");
        assertThat(r.totalNeto()).isEqualByComparingTo("380.00");
    }

    @Test
    @DisplayName("HU-25: obtener mapea también los importes nuevos")
    void obtenerMapeaLosImportesNuevos() {
        var f = fila(7, EstadoPedido.PENDIENTE, null, new BigDecimal("150.00"), 1);
        f.setDevoluciones(new BigDecimal("50.00"));
        f.setTotalNeto(new BigDecimal("100.00"));
        when(pedidoRepository.buscarPorId(7)).thenReturn(Optional.of(f));

        var r = service.obtener(7);

        assertThat(r.totalPedido()).isEqualByComparingTo("150.00");
        assertThat(r.devoluciones()).isEqualByComparingTo("50.00");
        assertThat(r.totalNeto()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("HU-25: sort=total ordena por la columna calculada total_pedido")
    void listarOrdenaPorTotal() {
        when(pedidoRepository.buscar(null, null, null, null, "total_pedido", true, 20, 0L))
                .thenReturn(List.of());
        when(pedidoRepository.contar(null, null, null, null)).thenReturn(0L);

        service.listar(null, null, null, null, 0, 20, "total,asc", false);

        verify(pedidoRepository).buscar(null, null, null, null, "total_pedido", true, 20, 0L);
    }

    @Test
    @DisplayName("HU-25: listar con un idCliente inexistente responde 404 CLIENTE_INEXISTENTE "
            + "sin consultar pedidos")
    void listarConClienteInexistenteResponde404() {
        when(clienteRepository.existsById(9999)).thenReturn(false);

        assertThatThrownBy(() -> service.listar(9999, null, null, null, 0, 10, null, false))
                .isInstanceOf(ClienteInexistenteFiltroException.class)
                .hasFieldOrPropertyWithValue("codigo", "CLIENTE_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);

        verifyNoInteractions(pedidoRepository);
    }

    @Test
    @DisplayName("HU-25: un cliente inactivo pero existente no es 404 en el listado")
    void listarConClienteInactivoNoEs404() {
        when(clienteRepository.existsById(ID_CLIENTE)).thenReturn(true);
        when(pedidoRepository.buscar(eq(ID_CLIENTE), any(), any(), any(), any(), eq(false),
                eq(20), eq(0L))).thenReturn(List.of());
        when(pedidoRepository.contar(ID_CLIENTE, null, null, null)).thenReturn(0L);

        var r = service.listar(ID_CLIENTE, null, null, null, 0, 20, null, false);

        assertThat(r.totalElements()).isZero();
        verify(clienteRepository, never()).existeActivo(any());
    }

    @Test
    @DisplayName("HU-25: el rango invertido sigue ganando sobre el cliente inexistente")
    void listarRangoInvertidoSeValidaAntesQueElCliente() {
        var hoy = LocalDate.now();

        assertThatThrownBy(() -> service.listar(9999, null, hoy, hoy.minusDays(1),
                0, 10, null, false))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");
    }

    @Test
    @DisplayName("listar con el rango de fechas invertido responde 400 RANGO_FECHAS_INVALIDO")
    void listarConRangoInvertidoResponde400() {
        var hoy = LocalDate.now();

        assertThatThrownBy(() -> service.listar(null, null, hoy, hoy.minusDays(1),
                0, 10, null, false))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");

        verifyNoInteractions(pedidoRepository);
    }

    @Test
    @DisplayName("listar con un campo de orden fuera de la lista blanca responde 400")
    void listarConCampoDeOrdenNoListadoResponde400() {
        assertThatThrownBy(() -> service.listar(null, null, null, null, 0, 10, "cliente", false))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verifyNoInteractions(pedidoRepository);
    }

    private static PedidoDetalleFila filaConLinea(int idPedido, Integer idLinea, String cantidad,
                                                  String precio) {
        var cabecera = fila(idPedido, EstadoPedido.PENDIENTE, null, new BigDecimal("150.00"), 1);
        var linea = idLinea == null ? null
                : new DetallePedido(idLinea, idPedido, 5, "Pan integral", "u",
                        new BigDecimal(cantidad), new BigDecimal(precio));
        return new PedidoDetalleFila(cabecera, linea);
    }

    @Test
    @DisplayName("HU-25 CA-04: listar sin incluirDetalle nunca consulta el detalle y deja "
            + "detalle en null")
    void listarSinIncluirDetalleNoTocaElDetalle() {
        when(pedidoRepository.buscar(eq(null), eq(null), eq(null), eq(null),
                eq("p.fecha_pedido"), eq(false), eq(20), eq(0L)))
                .thenReturn(List.of(fila(7, EstadoPedido.PENDIENTE, null,
                        new BigDecimal("150.00"), 2)));

        var r = service.listar(null, null, null, null, 0, 20, null, false);

        assertThat(r.content().get(0).detalle()).isNull();
        verify(pedidoRepository, never()).buscarConDetalle(any(), any(), any(), any(), any(),
                eq(true), anyInt(), anyLong());
        verify(pedidoRepository, never()).buscarConDetalle(any(), any(), any(), any(), any(),
                eq(false), anyInt(), anyLong());
    }

    @Test
    @DisplayName("HU-25 R-06: con incluirDetalle agrupa las líneas por pedido en el orden de la "
            + "página, con subtotal y precio histórico")
    void listarConIncluirDetalleAgrupaLasLineasPorPedido() {
        when(pedidoRepository.buscarConDetalle(eq(null), eq(null), eq(null), eq(null),
                eq("p.fecha_pedido"), eq(false), eq(20), eq(0L))).thenReturn(List.of(
                filaConLinea(9, 101, "3.00", "10.50"),
                filaConLinea(9, 102, "2.00", "4.00"),
                filaConLinea(4, null, null, null),
                filaConLinea(8, 103, "1.50", "2.00")));
        when(pedidoRepository.contar(null, null, null, null)).thenReturn(3L);

        var r = service.listar(null, null, null, null, 0, 20, null, true);

        assertThat(r.content()).extracting(PedidoResponse::idPedido).containsExactly(9, 4, 8);
        var primero = r.content().get(0).detalle();
        assertThat(primero).hasSize(2);
        assertThat(primero.get(0).idDetallePedido()).isEqualTo(101);
        assertThat(primero.get(0).producto().nombre()).isEqualTo("Pan integral");
        assertThat(primero.get(0).producto().unidad()).isEqualTo("u");
        assertThat(primero.get(0).cantidad()).isEqualByComparingTo("3.00");
        assertThat(primero.get(0).precioUnitario()).isEqualByComparingTo("10.50");
        assertThat(primero.get(0).subtotal()).isEqualByComparingTo("31.50");
        assertThat(primero.get(0).subtotal().scale()).isEqualTo(2);
        assertThat(primero.get(0).lotesConsumidos()).isNull();
        assertThat(primero.get(0).totalPedido()).isNull();
        assertThat(primero.get(1).idDetallePedido()).isEqualTo(102);
        // Un pedido sin líneas conserva la lista vacía, no null.
        assertThat(r.content().get(1).detalle()).isEmpty();
        assertThat(r.content().get(2).detalle()).hasSize(1);
        assertThat(r.totalElements()).isEqualTo(3L);
        verify(pedidoRepository, never()).buscar(any(), any(), any(), any(), any(),
                anyBoolean(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("HU-25 CP-10: con incluirDetalle un size mayor que 20 se recorta a 20 sin error "
            + "y la respuesta refleja el tamaño recortado")
    void listarConIncluirDetalleRecortaElSizeA20() {
        when(pedidoRepository.buscarConDetalle(any(), any(), any(), any(), any(), anyBoolean(),
                anyInt(), anyLong())).thenReturn(List.of());

        var r = service.listar(null, null, null, null, 1, 100, null, true);

        assertThat(r.size()).isEqualTo(20);
        assertThat(r.page()).isEqualTo(1);
        // El offset se calcula con el size recortado: página 1 de 20 es offset 20, no 100.
        verify(pedidoRepository).buscarConDetalle(null, null, null, null, "p.fecha_pedido",
                false, 20, 20L);
    }

    @Test
    @DisplayName("HU-25: sin incluirDetalle un size de 100 se respeta, el recorte es solo del "
            + "detalle")
    void listarSinDetalleNoRecortaElSize() {
        var r = service.listar(null, null, null, null, 0, 100, null, false);

        assertThat(r.size()).isEqualTo(100);
        verify(pedidoRepository).buscar(null, null, null, null, "p.fecha_pedido", false, 100,
                0L);
    }

    @Test
    @DisplayName("HU-25: con incluirDetalle siguen aplicando las validaciones de rango, cliente "
            + "y orden")
    void listarConIncluirDetalleConservaLasValidaciones() {
        var hoy = LocalDate.now();
        assertThatThrownBy(() -> service.listar(null, null, hoy, hoy.minusDays(1), 0, 10, null,
                true))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");
        when(clienteRepository.existsById(9999)).thenReturn(false);
        assertThatThrownBy(() -> service.listar(9999, null, null, null, 0, 10, null, true))
                .isInstanceOf(ClienteInexistenteFiltroException.class);
        assertThatThrownBy(() -> service.listar(null, null, null, null, 0, 10, "cliente", true))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verify(pedidoRepository, never()).buscarConDetalle(any(), any(), any(), any(), any(),
                anyBoolean(), anyInt(), anyLong());
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

    @Test
    @DisplayName("cambiarEstado PENDIENTE -> EN_PREPARACION aplica la transición y relee")
    void cambiarEstadoPendienteAEnPreparacion() {
        dadoPedidoEnEstado(7, EstadoPedido.PENDIENTE);
        cuandoReleeraEnEstado(7, EstadoPedido.PENDIENTE, EstadoPedido.EN_PREPARACION);

        var r = service.cambiarEstado(7,
                new CambiarEstadoPedidoRequest(EstadoPedido.EN_PREPARACION));

        assertThat(r.estado()).isEqualTo("EN_PREPARACION");
        assertThat(r.editable()).isFalse();
        verify(pedidoRepository).cambiarEstado(7, EstadoPedido.PENDIENTE, EstadoPedido.EN_PREPARACION);
        // Solo la cancelación devuelve stock.
        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("cambiarEstado EN_PREPARACION -> ENTREGADO aplica la transición y relee")
    void cambiarEstadoEnPreparacionAEntregado() {
        dadoPedidoEnEstado(7, EstadoPedido.EN_PREPARACION);
        cuandoReleeraEnEstado(7, EstadoPedido.EN_PREPARACION, EstadoPedido.ENTREGADO);

        var r = service.cambiarEstado(7, new CambiarEstadoPedidoRequest(EstadoPedido.ENTREGADO));

        assertThat(r.estado()).isEqualTo("ENTREGADO");
        assertThat(r.editable()).isFalse();
        verify(pedidoRepository).cambiarEstado(7, EstadoPedido.EN_PREPARACION, EstadoPedido.ENTREGADO);
        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("cambiarEstado ENTREGADO -> PENDIENTE responde 409 TRANSICION_INVALIDA")
    void cambiarEstadoEntregadoAPendienteResponde409() {
        dadoPedidoEnEstado(7, EstadoPedido.ENTREGADO);

        assertThatThrownBy(() -> service.cambiarEstado(7,
                new CambiarEstadoPedidoRequest(EstadoPedido.PENDIENTE)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");

        verify(pedidoRepository, never()).cambiarEstado(any(), any(), any());
        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("cambiarEstado ENTREGADO -> CANCELADO responde 409 y no devuelve stock")
    void cambiarEstadoEntregadoACanceladoResponde409() {
        dadoPedidoEnEstado(7, EstadoPedido.ENTREGADO);

        assertThatThrownBy(() -> service.cambiarEstado(7,
                new CambiarEstadoPedidoRequest(EstadoPedido.CANCELADO)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");

        verify(pedidoRepository, never()).cambiarEstado(any(), any(), any());
        verify(pedidoRepository, never()).lineasParaDevolucion(any());
        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("cambiarEstado PENDIENTE -> ENTREGADO (salto) responde 409 TRANSICION_INVALIDA")
    void cambiarEstadoConSaltoResponde409() {
        dadoPedidoEnEstado(7, EstadoPedido.PENDIENTE);

        assertThatThrownBy(() -> service.cambiarEstado(7,
                new CambiarEstadoPedidoRequest(EstadoPedido.ENTREGADO)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");

        verify(pedidoRepository, never()).cambiarEstado(any(), any(), any());
        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("CP-18: cancelar devuelve cada línea al lote con ENTRADA_DEVOLUCION")
    void cancelarConLineasDevuelveStockPorLote() {
        var idLoteA = 11;
        var idLoteB = 12;
        dadoPedidoEnEstado(7, EstadoPedido.PENDIENTE);
        cuandoReleeraEnEstado(7, EstadoPedido.PENDIENTE, EstadoPedido.CANCELADO);
        when(pedidoRepository.lineasParaDevolucion(7)).thenReturn(List.of(
                new LineaDevolucion(idLoteA, new BigDecimal("3.00")),
                new LineaDevolucion(idLoteB, new BigDecimal("2.00"))));

        var r = service.cambiarEstado(7, new CambiarEstadoPedidoRequest(EstadoPedido.CANCELADO));

        assertThat(r.estado()).isEqualTo("CANCELADO");
        verify(inventarioPtService).registrarEntrada(eq(idLoteA), eq(new BigDecimal("3.00")),
                eq(TipoMovimientoPt.ENTRADA_DEVOLUCION), eq("pedidos"), eq(7),
                eq("Devolucion por cancelacion del pedido 7"));
        verify(inventarioPtService).registrarEntrada(eq(idLoteB), eq(new BigDecimal("2.00")),
                eq(TipoMovimientoPt.ENTRADA_DEVOLUCION), eq("pedidos"), eq(7),
                eq("Devolucion por cancelacion del pedido 7"));
        verify(inventarioPtService, times(2)).registrarEntrada(any(), any(), any(), any(),
                any(), any());
        verify(pedidoRepository).cambiarEstado(7, EstadoPedido.PENDIENTE, EstadoPedido.CANCELADO);
    }

    @Test
    @DisplayName("CP-18: cancelar un pedido sin líneas no genera movimientos de inventario")
    void cancelarSinLineasNoTocaInventario() {
        dadoPedidoEnEstado(7, EstadoPedido.PENDIENTE);
        cuandoReleeraEnEstado(7, EstadoPedido.PENDIENTE, EstadoPedido.CANCELADO);
        when(pedidoRepository.lineasParaDevolucion(7)).thenReturn(List.of());

        var r = service.cambiarEstado(7, new CambiarEstadoPedidoRequest(EstadoPedido.CANCELADO));

        assertThat(r.estado()).isEqualTo("CANCELADO");
        verifyNoInteractions(inventarioPtService);
        verify(pedidoRepository).cambiarEstado(7, EstadoPedido.PENDIENTE, EstadoPedido.CANCELADO);
    }

    @Test
    @DisplayName("carrera: si el UPDATE condicional afecta 0 filas responde 409 sin tocar stock")
    void cambiarEstadoPierdeLaCarreraResponde409SinTocarInventario() {
        dadoPedidoEnEstado(7, EstadoPedido.PENDIENTE);
        when(pedidoRepository.cambiarEstado(7, EstadoPedido.PENDIENTE, EstadoPedido.CANCELADO))
                .thenReturn(0);

        assertThatThrownBy(() -> service.cambiarEstado(7,
                new CambiarEstadoPedidoRequest(EstadoPedido.CANCELADO)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");

        // La transición perdió la carrera: no se leen líneas ni se repone stock.
        verify(pedidoRepository, never()).lineasParaDevolucion(any());
        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("cambiarEstado de un pedido inexistente responde 404")
    void cambiarEstadoInexistenteResponde404() {
        when(pedidoRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cambiarEstado(9999,
                new CambiarEstadoPedidoRequest(EstadoPedido.EN_PREPARACION)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró el pedido con id 9999.");

        verify(pedidoRepository, never()).cambiarEstado(any(), any(), any());
        verifyNoInteractions(inventarioPtService);
    }

    @Test
    @DisplayName("transicionInvalida devuelve la excepción con el estado actual y los permitidos")
    void transicionInvalidaDevuelveLaExcepcionConstruida() {
        dadoPedidoEnEstado(7, EstadoPedido.ENTREGADO);

        var excepcion = service.transicionInvalida(7, EstadoPedido.PENDIENTE);

        assertThat(excepcion.getCodigo()).isEqualTo("TRANSICION_INVALIDA");
        assertThat(excepcion.getMessage())
                .contains("ENTREGADO", "PENDIENTE")
                .contains("no hay transiciones disponibles");
    }

    /**
     * Deja la primera lectura de {@code buscarPorId} en el estado dado.
     */
    private void dadoPedidoEnEstado(Integer id, EstadoPedido estado) {
        when(pedidoRepository.buscarPorId(id)).thenReturn(Optional.of(
                fila(id, estado, null, BigDecimal.ZERO, 0)));
    }

    /**
     * Encadena la carga inicial y la relectura posterior a {@code cambiarEstado}, con la que el
     * servicio monta la respuesta.
     */
    private void cuandoReleeraEnEstado(Integer id, EstadoPedido antes, EstadoPedido despues) {
        when(pedidoRepository.buscarPorId(id)).thenReturn(
                Optional.of(fila(id, antes, null, BigDecimal.ZERO, 0)),
                Optional.of(fila(id, despues, null, BigDecimal.ZERO, 0)));
        // El UPDATE condicional por estado afecta una fila cuando la transición gana la carrera.
        when(pedidoRepository.cambiarEstado(id, antes, despues)).thenReturn(1);
    }

    // ---- HU-25 PR3: agregaciones ----

    private static ResumenPedidoEstadoFila filaResumen(EstadoPedido estado, long cantidad,
                                                       String total, String devoluciones) {
        return new ResumenPedidoEstadoFila(estado, cantidad, new BigDecimal(total),
                new BigDecimal(devoluciones));
    }

    @Test
    @DisplayName("HU-25: listar acepta un rango de un solo día (el chequeo extraído no cambió)")
    void listarAceptaUnRangoDeUnSoloDia() {
        var hoy = LocalDate.now();
        when(pedidoRepository.buscar(any(), any(), any(), any(), any(), anyBoolean(), anyInt(),
                anyLong())).thenReturn(List.of());

        var r = service.listar(null, null, hoy, hoy, 0, 10, null, false);

        assertThat(r.content()).isEmpty();
    }

    @Test
    @DisplayName("RANGO_FECHAS_INVALIDO: las tres agregaciones lo validan sin tocar la base")
    void agregacionesConRangoInvertidoResponden400SinConsultar() {
        var hoy = LocalDate.now();
        var ayer = hoy.minusDays(1);

        assertThatThrownBy(() -> service.resumen(hoy, ayer))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");
        assertThatThrownBy(() -> service.ventasPorCliente(hoy, ayer))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");
        assertThatThrownBy(() -> service.productosMasVendidos(hoy, ayer))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");

        verifyNoInteractions(pedidoRepository);
    }

    @Test
    @DisplayName("CP-17/18/19: entregados y enCurso van aparte, cancelados no es venta, ticket "
            + "76666.67")
    void resumenSeparaEntregadosEnCursoYCancelados() {
        var inicio = LocalDate.of(2026, 9, 1);
        var fin = LocalDate.of(2026, 9, 30);
        when(pedidoRepository.resumenPorPeriodo(inicio, fin)).thenReturn(List.of(
                filaResumen(EstadoPedido.ENTREGADO, 24, "1840000.0000", "62000.0000"),
                filaResumen(EstadoPedido.PENDIENTE, 4, "200000.00", "0"),
                filaResumen(EstadoPedido.EN_PREPARACION, 2, "120000.00", "0"),
                filaResumen(EstadoPedido.CANCELADO, 2, "45000.00", "0")));

        var r = service.resumen(inicio, fin);

        assertThat(r.periodo().desde()).isEqualTo(inicio);
        assertThat(r.periodo().hasta()).isEqualTo(fin);
        assertThat(r.entregados().cantidad()).isEqualTo(24);
        assertThat(r.entregados().total()).isEqualByComparingTo("1840000.00");
        assertThat(r.enCurso().cantidad()).isEqualTo(6);
        assertThat(r.enCurso().total()).isEqualByComparingTo("320000.00");
        assertThat(r.cancelados().cantidad()).isEqualTo(2);
        assertThat(r.cancelados().total()).isEqualByComparingTo("45000.00");
        assertThat(r.ventaBruta()).isEqualByComparingTo("1840000.00");
        assertThat(r.devoluciones()).isEqualByComparingTo("62000.00");
        assertThat(r.ventaNeta()).isEqualByComparingTo("1778000.00");
        assertThat(r.ticketPromedio()).isEqualByComparingTo("76666.67");
        assertThat(r.ticketPromedio().scale()).isEqualTo(2);
        assertThat(r.ventaBruta().scale()).isEqualTo(2);
        assertThat(r.ventaNeta().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("R-04: las devoluciones de pedidos que no son entregados no restan a la venta")
    void resumenSoloDescuentaLasDevolucionesDeLosEntregados() {
        var hoy = LocalDate.now();
        when(pedidoRepository.resumenPorPeriodo(hoy, hoy)).thenReturn(List.of(
                filaResumen(EstadoPedido.ENTREGADO, 1, "100.00", "10.00"),
                filaResumen(EstadoPedido.CANCELADO, 1, "500.00", "99.00")));

        var r = service.resumen(hoy, hoy);

        assertThat(r.devoluciones()).isEqualByComparingTo("10.00");
        assertThat(r.ventaNeta()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("ticketPromedio es null sin entregados y la venta neta queda en 0.00")
    void resumenSinEntregadosTieneTicketNulo() {
        var hoy = LocalDate.now();
        when(pedidoRepository.resumenPorPeriodo(hoy, hoy)).thenReturn(List.of(
                filaResumen(EstadoPedido.PENDIENTE, 3, "300.00", "0")));

        var r = service.resumen(hoy, hoy);

        assertThat(r.ticketPromedio()).isNull();
        assertThat(r.entregados().cantidad()).isZero();
        assertThat(r.entregados().total()).isEqualByComparingTo("0.00");
        assertThat(r.entregados().total().scale()).isEqualTo(2);
        assertThat(r.enCurso().total()).isEqualByComparingTo("300.00");
        assertThat(r.ventaBruta()).isEqualByComparingTo("0.00");
        assertThat(r.devoluciones()).isEqualByComparingTo("0.00");
        assertThat(r.ventaNeta()).isEqualByComparingTo("0.00");
        assertThat(r.ventaNeta().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("ticketPromedio redondea HALF_UP a escala 2")
    void ticketPromedioRedondeaHalfUp() {
        var hoy = LocalDate.now();
        // 10.04 / 8 = 1.255 exacto: HALF_UP sube a 1.26; un redondeo hacia abajo daría 1.25.
        when(pedidoRepository.resumenPorPeriodo(hoy, hoy)).thenReturn(List.of(
                filaResumen(EstadoPedido.ENTREGADO, 8, "10.04", "0")));

        assertThat(service.resumen(hoy, hoy).ticketPromedio()).isEqualByComparingTo("1.26");
    }

    @Test
    @DisplayName("CP-22: un período sin pedidos responde ceros, sin error, y periodo nulo sin "
            + "fechas")
    void resumenSinPedidosNiFechas() {
        when(pedidoRepository.resumenPorPeriodo(null, null)).thenReturn(List.of());

        var r = service.resumen(null, null);

        verify(pedidoRepository).resumenPorPeriodo(null, null);
        assertThat(r.periodo()).isNull();
        assertThat(r.entregados().cantidad()).isZero();
        assertThat(r.enCurso().cantidad()).isZero();
        assertThat(r.cancelados().cantidad()).isZero();
        assertThat(r.cancelados().total()).isEqualByComparingTo("0.00");
        assertThat(r.ventaBruta()).isEqualByComparingTo("0.00");
        assertThat(r.ticketPromedio()).isNull();
    }

    @Test
    @DisplayName("resumen con una sola fecha conserva el periodo con el otro límite nulo")
    void resumenConUnaSolaFechaConservaElPeriodoParcial() {
        var inicio = LocalDate.of(2026, 9, 1);
        when(pedidoRepository.resumenPorPeriodo(inicio, null)).thenReturn(List.of());

        var r = service.resumen(inicio, null);

        assertThat(r.periodo().desde()).isEqualTo(inicio);
        assertThat(r.periodo().hasta()).isNull();
    }

    @Test
    @DisplayName("CP-21: ventas por cliente conserva el orden y calcula el porcentaje HALF_UP")
    void ventasPorClienteCalculaElPorcentaje() {
        when(pedidoRepository.ventasPorCliente(null, null)).thenReturn(List.of(
                new VentaClienteFila(1, "Tienda La Esquina", 2, new BigDecimal("200.0000")),
                new VentaClienteFila(2, "Panadería Sol", 1, new BigDecimal("100.00")),
                new VentaClienteFila(3, "Café Luna", 1, new BigDecimal("50.00"))));

        var r = service.ventasPorCliente(null, null);

        assertThat(r).extracting(v -> v.cliente().nombre())
                .containsExactly("Tienda La Esquina", "Panadería Sol", "Café Luna");
        assertThat(r.get(0).cliente().idCliente()).isEqualTo(1);
        assertThat(r.get(0).cantidadPedidos()).isEqualTo(2);
        assertThat(r.get(0).total()).isEqualByComparingTo("200.00");
        // 200/350 = 57.142857 -> 57.14; 100/350 = 28.571428 -> 28.57; 50/350 = 14.2857 -> 14.29
        assertThat(r.get(0).porcentaje()).isEqualByComparingTo("57.14");
        assertThat(r.get(1).porcentaje()).isEqualByComparingTo("28.57");
        assertThat(r.get(2).porcentaje()).isEqualByComparingTo("14.29");
        assertThat(r.get(0).porcentaje().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("ventas por cliente con suma en cero responde porcentaje 0.00 y sin filas, lista "
            + "vacía")
    void ventasPorClienteConSumaCeroYVacio() {
        var hoy = LocalDate.now();
        when(pedidoRepository.ventasPorCliente(hoy, hoy)).thenReturn(List.of(
                new VentaClienteFila(1, "Cliente sin líneas", 1, BigDecimal.ZERO)));

        var r = service.ventasPorCliente(hoy, hoy);

        assertThat(r).hasSize(1);
        assertThat(r.get(0).porcentaje()).isEqualByComparingTo("0.00");
        assertThat(r.get(0).porcentaje().scale()).isEqualTo(2);

        when(pedidoRepository.ventasPorCliente(hoy.minusDays(1), hoy)).thenReturn(List.of());
        assertThat(service.ventasPorCliente(hoy.minusDays(1), hoy)).isEmpty();
    }

    @Test
    @DisplayName("R-07: productos más vendidos conserva el orden del repositorio y las dos "
            + "columnas")
    void productosMasVendidosMapeaLasFilas() {
        var hoy = LocalDate.now();
        when(pedidoRepository.productosMasVendidos(hoy, hoy)).thenReturn(List.of(
                new ProductoVendidoFila("Pan francés", new BigDecimal("1240.0000"),
                        new BigDecimal("1488000"), 18),
                new ProductoVendidoFila("Croissant", new BigDecimal("320"),
                        new BigDecimal("800000.00"), 12)));

        var r = service.productosMasVendidos(hoy, hoy);

        assertThat(r).extracting(ProductoVendidoResponse::producto)
                .containsExactly("Pan francés", "Croissant");
        assertThat(r.get(0).unidadesVendidas()).isEqualByComparingTo("1240.00");
        assertThat(r.get(0).unidadesVendidas().scale()).isEqualTo(2);
        assertThat(r.get(0).importe()).isEqualByComparingTo("1488000.00");
        assertThat(r.get(0).pedidos()).isEqualTo(18);
    }
}
