package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.inventario.LoteConsumido;
import co.edu.corposucre.productionfood.inventario.LoteDisponible;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarCantidadRequest;
import co.edu.corposucre.productionfood.pedido.dto.AgregarDetallePedidoRequest;
import co.edu.corposucre.productionfood.producto.ProductoConDetalle;
import co.edu.corposucre.productionfood.producto.ProductoRepository;

class DetallePedidoServiceTest {

    private static final Integer ID_PEDIDO = 47;
    private static final Integer ID_PRODUCTO = 3;
    private static final Integer ID_CLIENTE = 7;
    private static final Integer ID_DETALLE = 88;

    private PedidoRepository pedidoRepository;
    private DetallePedidoRepository detallePedidoRepository;
    private ProductoRepository productoRepository;
    private InventarioPtService inventarioPtService;
    private InventarioPtRepository inventarioPtRepository;
    private DetallePedidoService service;

    @BeforeEach
    void setUp() {
        pedidoRepository = mock(PedidoRepository.class);
        detallePedidoRepository = mock(DetallePedidoRepository.class);
        productoRepository = mock(ProductoRepository.class);
        inventarioPtService = mock(InventarioPtService.class);
        inventarioPtRepository = mock(InventarioPtRepository.class);
        service = new DetallePedidoService(pedidoRepository, detallePedidoRepository,
                productoRepository, inventarioPtService, inventarioPtRepository);
    }

    private static Pedido pedido(Integer id, EstadoPedido estado) {
        var pedido = new Pedido();
        pedido.setIdPedido(id);
        pedido.setIdCliente(ID_CLIENTE);
        pedido.setFechaPedido(LocalDate.now());
        pedido.setEstado(estado);
        return pedido;
    }

    private void dadoPedido(Integer id, EstadoPedido estado) {
        when(pedidoRepository.buscarPorId(id)).thenReturn(Optional.of(pedido(id, estado)));
    }

    private static DetallePedido linea(Integer idDetalle, Integer idProducto, String cantidad,
                                       String precio, String nombre, String unidad) {
        return new DetallePedido(idDetalle, ID_PEDIDO, idProducto, nombre, unidad,
                new BigDecimal(cantidad), new BigDecimal(precio));
    }

    private ProductoConDetalle producto(String nombre, String precio, boolean activo) {
        var producto = mock(ProductoConDetalle.class);
        when(producto.getIdProducto()).thenReturn(ID_PRODUCTO);
        when(producto.getNombre()).thenReturn(nombre);
        when(producto.getPrecio()).thenReturn(new BigDecimal(precio));
        when(producto.getEstado()).thenReturn(activo);
        return producto;
    }

    private static LoteConsumido lote(Integer idLote, String codigo, String vencimiento,
                                      String cantidad) {
        return new LoteConsumido(idLote, codigo, LocalDate.parse(vencimiento),
                new BigDecimal(cantidad));
    }

    @Test
    @DisplayName("listar el detalle de un pedido inexistente responde 404 PEDIDO_INEXISTENTE")
    void listarUnPedidoInexistenteResponde404() {
        when(pedidoRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listar(9999))
                .isInstanceOf(PedidoInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "PEDIDO_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND)
                .hasMessage("No se encontró el pedido con id 9999.");

        verifyNoInteractions(detallePedidoRepository, productoRepository, inventarioPtService,
                inventarioPtRepository);
    }

    @Test
    @DisplayName("listar devuelve subtotales, total e ítems con los lotes consumidos del kardex")
    void listarDevuelveSubtotalesTotalItemsYLotesConsumidos() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        when(detallePedidoRepository.listarPorPedido(ID_PEDIDO)).thenReturn(List.of(
                linea(1, 3, "30.00", "1200.00", "Pan francés", "und"),
                linea(2, 4, "5.50", "800.00", "Pan de queso", "und")));
        when(detallePedidoRepository.calcularTotal(ID_PEDIDO))
                .thenReturn(new BigDecimal("40400.0000"));
        when(inventarioPtRepository.movimientosNetosDeOrigen("detalle_pedido", 1)).thenReturn(
                List.of(lote(10, "PF-0919", "2026-09-19", "12.00"),
                        lote(11, "PF-0921", "2026-09-21", "18.00")));
        when(inventarioPtRepository.movimientosNetosDeOrigen("detalle_pedido", 2))
                .thenReturn(List.of());

        var r = service.listar(ID_PEDIDO);

        assertThat(r.idPedido()).isEqualTo(ID_PEDIDO);
        assertThat(r.estado()).isEqualTo("PENDIENTE");
        assertThat(r.editable()).isTrue();
        assertThat(r.detalles()).hasSize(2);
        assertThat(r.cantidadItems()).isEqualTo(2);
        // El total sale del SUM de la base, no de sumar los subtotales en memoria.
        assertThat(r.totalPedido()).isEqualByComparingTo("40400.00");
        assertThat(r.totalPedido().scale()).isEqualTo(2);
        verify(detallePedidoRepository).calcularTotal(ID_PEDIDO);

        var pan = r.detalles().get(0);
        assertThat(pan.idDetallePedido()).isEqualTo(1);
        assertThat(pan.producto().idProducto()).isEqualTo(3);
        assertThat(pan.producto().nombre()).isEqualTo("Pan francés");
        assertThat(pan.producto().unidad()).isEqualTo("und");
        assertThat(pan.cantidad()).isEqualByComparingTo("30.00");
        assertThat(pan.cantidad().scale()).isEqualTo(2);
        assertThat(pan.subtotal()).isEqualByComparingTo("36000.00");
        assertThat(pan.subtotal().scale()).isEqualTo(2);
        // En el listado el total agregado viaja una sola vez, en el wrapper.
        assertThat(pan.totalPedido()).isNull();

        assertThat(pan.lotesConsumidos()).hasSize(2);
        assertThat(pan.lotesConsumidos().get(0).codigoLote()).isEqualTo("PF-0919");
        assertThat(pan.lotesConsumidos().get(0).vence()).isEqualTo(LocalDate.parse("2026-09-19"));
        assertThat(pan.lotesConsumidos().get(0).cantidad()).isEqualByComparingTo("12.00");
        assertThat(pan.lotesConsumidos().get(0).cantidad().scale()).isEqualTo(2);
        assertThat(pan.lotesConsumidos().get(1).codigoLote()).isEqualTo("PF-0921");
        assertThat(pan.lotesConsumidos().get(1).cantidad()).isEqualByComparingTo("18.00");

        var queso = r.detalles().get(1);
        assertThat(queso.subtotal()).isEqualByComparingTo("4400.00");
        assertThat(queso.lotesConsumidos()).isEmpty();
    }

    @Test
    @DisplayName("R-09: editable es true en PENDIENTE y EN_PREPARACION y false en los terminales")
    void listarCalculaEditableSegunR09() {
        for (var estado : List.of(EstadoPedido.PENDIENTE, EstadoPedido.EN_PREPARACION,
                EstadoPedido.ENTREGADO, EstadoPedido.CANCELADO)) {
            var id = estado.ordinal() + 100;
            dadoPedido(id, estado);
            when(detallePedidoRepository.listarPorPedido(id)).thenReturn(List.of());
            when(detallePedidoRepository.calcularTotal(id)).thenReturn(BigDecimal.ZERO);

            var r = service.listar(id);

            var esperado = estado == EstadoPedido.PENDIENTE
                    || estado == EstadoPedido.EN_PREPARACION;
            assertThat(r.editable()).as("editable en %s", estado).isEqualTo(esperado);
            assertThat(r.estado()).isEqualTo(estado.name());
        }
    }

    @Test
    @DisplayName("CP-01: agregar inserta antes de la salida FEFO, copia el precio y responde lotes")
    void agregarInsertaCopiaElPrecioYDescuentaFefo() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var producto = producto("Pan francés", "1200.00", true);
        when(productoRepository.buscarConDetalle(ID_PRODUCTO))
                .thenReturn(Optional.of(producto));
        when(detallePedidoRepository.existeProducto(ID_PEDIDO, ID_PRODUCTO)).thenReturn(false);
        when(inventarioPtService.stockDisponible(ID_PRODUCTO))
                .thenReturn(new BigDecimal("77.00"));
        when(detallePedidoRepository.insertar(any(DetallePedido.class))).thenReturn(88);
        when(inventarioPtService.registrarSalidaFefo(ID_PRODUCTO, new BigDecimal("30.00"),
                "detalle_pedido", 88)).thenReturn(List.of(
                lote(10, "PF-0919", "2026-09-19", "12.00"),
                lote(11, "PF-0921", "2026-09-21", "18.00")));
        when(detallePedidoRepository.obtenerPorId(88)).thenReturn(Optional.of(
                linea(88, ID_PRODUCTO, "30.00", "1200.00", "Pan francés", "und")));
        when(detallePedidoRepository.calcularTotal(ID_PEDIDO))
                .thenReturn(new BigDecimal("36000.0000"));
        when(inventarioPtRepository.movimientosNetosDeOrigen("detalle_pedido", 88)).thenReturn(
                List.of(lote(10, "PF-0919", "2026-09-19", "12.00"),
                        lote(11, "PF-0921", "2026-09-21", "18.00")));

        var r = service.agregar(ID_PEDIDO,
                new AgregarDetallePedidoRequest(ID_PRODUCTO, new BigDecimal("30")));

        var captor = ArgumentCaptor.forClass(DetallePedido.class);
        verify(detallePedidoRepository).insertar(captor.capture());
        var escrita = captor.getValue();
        assertThat(escrita.idPedido()).isEqualTo(ID_PEDIDO);
        assertThat(escrita.idProducto()).isEqualTo(ID_PRODUCTO);
        assertThat(escrita.cantidad()).isEqualByComparingTo("30.00");
        assertThat(escrita.cantidad().scale()).isEqualTo(2);
        // R-04: el precio es el del catálogo en el momento de agregar, no una referencia.
        assertThat(escrita.precioUnitario()).isEqualByComparingTo("1200.00");

        // R-10: el kardex necesita id_origen = id_detalle_pedido, por eso el INSERT va primero.
        var orden = inOrder(detallePedidoRepository, inventarioPtService);
        orden.verify(detallePedidoRepository).insertar(any(DetallePedido.class));
        orden.verify(inventarioPtService).registrarSalidaFefo(ID_PRODUCTO,
                new BigDecimal("30.00"), "detalle_pedido", 88);

        assertThat(r.idDetallePedido()).isEqualTo(88);
        assertThat(r.producto().idProducto()).isEqualTo(ID_PRODUCTO);
        assertThat(r.producto().nombre()).isEqualTo("Pan francés");
        assertThat(r.subtotal()).isEqualByComparingTo("36000.00");
        assertThat(r.subtotal().scale()).isEqualTo(2);
        assertThat(r.totalPedido()).isEqualByComparingTo("36000.00");
        assertThat(r.totalPedido().scale()).isEqualTo(2);
        assertThat(r.lotesConsumidos()).hasSize(2);
        assertThat(r.lotesConsumidos().get(0).codigoLote()).isEqualTo("PF-0919");
        assertThat(r.lotesConsumidos().get(0).cantidad()).isEqualByComparingTo("12.00");
        assertThat(r.lotesConsumidos().get(1).codigoLote()).isEqualTo("PF-0921");
        assertThat(r.lotesConsumidos().get(1).cantidad()).isEqualByComparingTo("18.00");
    }

    @Test
    @DisplayName("agregar a un pedido inexistente responde 404 PEDIDO_INEXISTENTE sin escribir")
    void agregarAPedidoInexistenteResponde404() {
        when(pedidoRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.agregar(9999,
                new AgregarDetallePedidoRequest(ID_PRODUCTO, new BigDecimal("1.00"))))
                .isInstanceOf(PedidoInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "PEDIDO_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        verifyNoInteractions(detallePedidoRepository, productoRepository, inventarioPtService);
    }

    @Test
    @DisplayName("CP-20/CP-21: agregar a un pedido ENTREGADO o CANCELADO responde PEDIDO_NO_EDITABLE")
    void agregarAPedidoCerradoResponde409() {
        dadoPedido(7, EstadoPedido.ENTREGADO);
        dadoPedido(8, EstadoPedido.CANCELADO);

        for (var id : List.of(7, 8)) {
            assertThatThrownBy(() -> service.agregar(id,
                    new AgregarDetallePedidoRequest(ID_PRODUCTO, new BigDecimal("1.00"))))
                    .isInstanceOf(ConflictoNegocioException.class)
                    .hasFieldOrPropertyWithValue("codigo", "PEDIDO_NO_EDITABLE")
                    .hasMessageContaining("PENDIENTE")
                    .hasMessageContaining("EN_PREPARACION");
        }

        verify(detallePedidoRepository, never()).insertar(any(DetallePedido.class));
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verifyNoInteractions(productoRepository);
    }

    @Test
    @DisplayName("CP-10: agregar un producto inexistente responde 409 sin insertar ni descontar")
    void agregarProductoInexistenteResponde409() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        when(productoRepository.buscarConDetalle(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.agregar(ID_PEDIDO,
                new AgregarDetallePedidoRequest(9999, new BigDecimal("1.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_INEXISTENTE");

        verify(detallePedidoRepository, never()).insertar(any(DetallePedido.class));
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
    }

    @Test
    @DisplayName("CP-11: agregar un producto inactivo responde 409 sin insertar ni descontar")
    void agregarProductoInactivoResponde409() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var producto = producto("Pan francés", "1200.00", false);
        when(productoRepository.buscarConDetalle(ID_PRODUCTO))
                .thenReturn(Optional.of(producto));

        assertThatThrownBy(() -> service.agregar(ID_PEDIDO,
                new AgregarDetallePedidoRequest(ID_PRODUCTO, new BigDecimal("1.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_INACTIVO");

        verify(detallePedidoRepository, never()).insertar(any(DetallePedido.class));
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("CP-15/R-06: agregar un producto ya presente responde 409 sin insertar")
    void agregarProductoDuplicadoResponde409() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var producto = producto("Pan francés", "1200.00", true);
        when(productoRepository.buscarConDetalle(ID_PRODUCTO))
                .thenReturn(Optional.of(producto));
        when(detallePedidoRepository.existeProducto(ID_PEDIDO, ID_PRODUCTO)).thenReturn(true);

        assertThatThrownBy(() -> service.agregar(ID_PEDIDO,
                new AgregarDetallePedidoRequest(ID_PRODUCTO, new BigDecimal("1.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_DUPLICADO")
                .hasMessageContaining("edite la cantidad de la línea existente");

        verify(detallePedidoRepository, never()).insertar(any(DetallePedido.class));
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-5", "0.001"})
    @DisplayName("CP-12/CP-13: una cantidad nula, cero o negativa responde 400 CANTIDAD_INVALIDA")
    void agregarConCantidadInvalidaResponde400(BigDecimal cantidad) {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);

        assertThatThrownBy(() -> service.agregar(ID_PEDIDO,
                new AgregarDetallePedidoRequest(ID_PRODUCTO, cantidad)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verifyNoInteractions(productoRepository, detallePedidoRepository, inventarioPtService,
                inventarioPtRepository);
    }

    @Test
    @DisplayName("CP-05: stock insuficiente responde 409 con el nombre y las cifras del mensaje")
    void agregarSinStockSuficienteResponde409() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var producto = producto("Pan francés", "1200.00", true);
        when(productoRepository.buscarConDetalle(ID_PRODUCTO))
                .thenReturn(Optional.of(producto));
        when(detallePedidoRepository.existeProducto(ID_PEDIDO, ID_PRODUCTO)).thenReturn(false);
        when(inventarioPtService.stockDisponible(ID_PRODUCTO))
                .thenReturn(new BigDecimal("77.00"));

        assertThatThrownBy(() -> service.agregar(ID_PEDIDO,
                new AgregarDetallePedidoRequest(ID_PRODUCTO, new BigDecimal("100.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasMessage("Solo hay 77 unidades disponibles de Pan francés. "
                        + "Se solicitaron 100.");

        verify(detallePedidoRepository, never()).insertar(any(DetallePedido.class));
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
    }

    @Test
    @DisplayName("R-06: una carrera que viola uk_detalle_pedido se traduce a PRODUCTO_DUPLICADO")
    void agregarConDuplicateKeyResponde409() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var producto = producto("Pan francés", "1200.00", true);
        when(productoRepository.buscarConDetalle(ID_PRODUCTO))
                .thenReturn(Optional.of(producto));
        when(detallePedidoRepository.existeProducto(ID_PEDIDO, ID_PRODUCTO)).thenReturn(false);
        when(inventarioPtService.stockDisponible(ID_PRODUCTO))
                .thenReturn(new BigDecimal("77.00"));
        when(detallePedidoRepository.insertar(any(DetallePedido.class)))
                .thenThrow(new DuplicateKeyException("uk_detalle_pedido"));

        assertThatThrownBy(() -> service.agregar(ID_PEDIDO,
                new AgregarDetallePedidoRequest(ID_PRODUCTO, new BigDecimal("1.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_DUPLICADO");

        // La línea no llegó a existir: no hay a qué ligar una salida de kardex.
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verify(detallePedidoRepository, never()).obtenerPorId(any());
        verify(detallePedidoRepository, never()).calcularTotal(any());
    }

    @Test
    @DisplayName("CP-16/R-07: aumentar la cantidad descuenta solo la diferencia y actualiza la línea")
    void actualizarCantidadAlAumentarDescuentaSoloLaDiferencia() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var actual = linea(ID_DETALLE, ID_PRODUCTO, "10.00", "1200.00", "Pan francés", "und");
        var editada = linea(ID_DETALLE, ID_PRODUCTO, "15.00", "1200.00", "Pan francés", "und");
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.of(actual));
        when(inventarioPtService.stockDisponible(ID_PRODUCTO))
                .thenReturn(new BigDecimal("100.00"));
        when(detallePedidoRepository.obtenerPorId(ID_DETALLE)).thenReturn(Optional.of(editada));
        when(detallePedidoRepository.calcularTotal(ID_PEDIDO))
                .thenReturn(new BigDecimal("18000.0000"));

        var r = service.actualizarCantidad(ID_PEDIDO, ID_DETALLE,
                new ActualizarCantidadRequest(new BigDecimal("15")));

        var orden = inOrder(inventarioPtService, detallePedidoRepository);
        orden.verify(inventarioPtService).registrarSalidaFefo(ID_PRODUCTO,
                new BigDecimal("5.00"), "detalle_pedido", ID_DETALLE);
        orden.verify(detallePedidoRepository).actualizarCantidad(ID_DETALLE,
                new BigDecimal("15.00"));
        verify(inventarioPtService, never()).registrarDevolucion(any(), any(), any());

        assertThat(r.idDetallePedido()).isEqualTo(ID_DETALLE);
        assertThat(r.cantidad()).isEqualByComparingTo("15.00");
        assertThat(r.cantidad().scale()).isEqualTo(2);
        assertThat(r.subtotal()).isEqualByComparingTo("18000.00");
        assertThat(r.totalPedido()).isEqualByComparingTo("18000.00");
        assertThat(r.totalPedido().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("CP-17/R-07: reducir la cantidad devuelve solo la diferencia a los lotes de origen")
    void actualizarCantidadAlReducirDevuelveSoloLaDiferencia() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var actual = linea(ID_DETALLE, ID_PRODUCTO, "10.00", "1200.00", "Pan francés", "und");
        var editada = linea(ID_DETALLE, ID_PRODUCTO, "6.00", "1200.00", "Pan francés", "und");
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.of(actual));
        when(detallePedidoRepository.obtenerPorId(ID_DETALLE)).thenReturn(Optional.of(editada));
        when(detallePedidoRepository.calcularTotal(ID_PEDIDO))
                .thenReturn(new BigDecimal("7200.00"));

        var r = service.actualizarCantidad(ID_PEDIDO, ID_DETALLE,
                new ActualizarCantidadRequest(new BigDecimal("6")));

        var orden = inOrder(inventarioPtService, detallePedidoRepository);
        orden.verify(inventarioPtService).registrarDevolucion("detalle_pedido", ID_DETALLE,
                new BigDecimal("4.00"));
        orden.verify(detallePedidoRepository).actualizarCantidad(ID_DETALLE,
                new BigDecimal("6.00"));
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verify(inventarioPtService, never()).stockDisponible(any());

        assertThat(r.cantidad()).isEqualByComparingTo("6.00");
        assertThat(r.subtotal()).isEqualByComparingTo("7200.00");
    }

    @Test
    @DisplayName("R-07: repetir la misma cantidad no mueve stock y la operación es idempotente")
    void actualizarCantidadSinDiferenciaNoMueveStock() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var actual = linea(ID_DETALLE, ID_PRODUCTO, "10.00", "1200.00", "Pan francés", "und");
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.of(actual));
        when(detallePedidoRepository.obtenerPorId(ID_DETALLE)).thenReturn(Optional.of(actual));
        when(detallePedidoRepository.calcularTotal(ID_PEDIDO))
                .thenReturn(new BigDecimal("12000.00"));

        service.actualizarCantidad(ID_PEDIDO, ID_DETALLE,
                new ActualizarCantidadRequest(new BigDecimal("10.00")));

        verify(inventarioPtService, never()).stockDisponible(any());
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verify(inventarioPtService, never()).registrarDevolucion(any(), any(), any());
        verify(detallePedidoRepository).actualizarCantidad(ID_DETALLE, new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("actualizar la cantidad de un pedido inexistente responde 404 sin escribir")
    void actualizarCantidadDePedidoInexistenteResponde404() {
        when(pedidoRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizarCantidad(9999, ID_DETALLE,
                new ActualizarCantidadRequest(new BigDecimal("15.00"))))
                .isInstanceOf(PedidoInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "PEDIDO_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        verifyNoInteractions(detallePedidoRepository, productoRepository, inventarioPtService,
                inventarioPtRepository);
    }

    @Test
    @DisplayName("R-09: editar la cantidad de un pedido ENTREGADO o CANCELADO responde 409 sin escribir")
    void actualizarCantidadDePedidoCerradoResponde409() {
        dadoPedido(7, EstadoPedido.ENTREGADO);
        dadoPedido(8, EstadoPedido.CANCELADO);

        for (var id : List.of(7, 8)) {
            assertThatThrownBy(() -> service.actualizarCantidad(id, ID_DETALLE,
                    new ActualizarCantidadRequest(new BigDecimal("15.00"))))
                    .isInstanceOf(ConflictoNegocioException.class)
                    .hasFieldOrPropertyWithValue("codigo", "PEDIDO_NO_EDITABLE");
        }

        verify(detallePedidoRepository, never()).obtenerPorIdParaActualizar(any());
        verify(detallePedidoRepository, never()).actualizarCantidad(any(), any());
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verify(inventarioPtService, never()).registrarDevolucion(any(), any(), any());
    }

    @Test
    @DisplayName("actualizar una línea inexistente responde 404 sin escribir")
    void actualizarCantidadDeLineaInexistenteResponde404() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizarCantidad(ID_PEDIDO, ID_DETALLE,
                new ActualizarCantidadRequest(new BigDecimal("15.00"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND)
                .hasMessageContaining("88");

        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verify(inventarioPtService, never()).registrarDevolucion(any(), any(), any());
        verify(detallePedidoRepository, never()).actualizarCantidad(any(), any());
    }

    @Test
    @DisplayName("actualizar una línea de otro pedido responde 404 sin escribir")
    void actualizarCantidadDeLineaDeOtroPedidoResponde404() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var ajena = new DetallePedido(ID_DETALLE, 999, ID_PRODUCTO, "Pan francés", "und",
                new BigDecimal("10.00"), new BigDecimal("1200.00"));
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.of(ajena));

        assertThatThrownBy(() -> service.actualizarCantidad(ID_PEDIDO, ID_DETALLE,
                new ActualizarCantidadRequest(new BigDecimal("15.00"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");

        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verify(detallePedidoRepository, never()).actualizarCantidad(any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-5", "0.001"})
    @DisplayName("una cantidad nueva nula, cero o negativa responde 400 sin mover stock")
    void actualizarCantidadConCantidadInvalidaResponde400(BigDecimal cantidad) {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var actual = linea(ID_DETALLE, ID_PRODUCTO, "10.00", "1200.00", "Pan francés", "und");
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.of(actual));

        assertThatThrownBy(() -> service.actualizarCantidad(ID_PEDIDO, ID_DETALLE,
                new ActualizarCantidadRequest(cantidad)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verifyNoInteractions(inventarioPtService, inventarioPtRepository);
        verify(detallePedidoRepository, never()).actualizarCantidad(any(), any());
    }

    @Test
    @DisplayName("CP-18: aumentar sin stock responde 409 con el nombre y no toca ni stock ni línea")
    void actualizarCantidadAlAumentarSinStockResponde409() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var actual = linea(ID_DETALLE, ID_PRODUCTO, "10.00", "1200.00", "Pan francés", "und");
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.of(actual));
        when(inventarioPtService.stockDisponible(ID_PRODUCTO))
                .thenReturn(new BigDecimal("3.00"));

        assertThatThrownBy(() -> service.actualizarCantidad(ID_PEDIDO, ID_DETALLE,
                new ActualizarCantidadRequest(new BigDecimal("15.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "STOCK_INSUFICIENTE")
                .hasMessage("Solo hay 3 unidades disponibles de Pan francés. "
                        + "Se solicitaron 5.");

        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
        verify(detallePedidoRepository, never()).actualizarCantidad(any(), any());
        verify(detallePedidoRepository, never()).obtenerPorId(any());
        verify(detallePedidoRepository, never()).calcularTotal(any());
    }

    @Test
    @DisplayName("CP-19/R-08: eliminar devuelve la cantidad completa y borra la línea")
    void eliminarDevuelveLaCantidadCompletaYBorraLaLinea() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var actual = linea(ID_DETALLE, ID_PRODUCTO, "10.00", "1200.00", "Pan francés", "und");
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.of(actual));

        service.eliminar(ID_PEDIDO, ID_DETALLE);

        var orden = inOrder(inventarioPtService, detallePedidoRepository);
        orden.verify(inventarioPtService).registrarDevolucion("detalle_pedido", ID_DETALLE,
                new BigDecimal("10.00"));
        orden.verify(detallePedidoRepository).eliminar(ID_DETALLE);
        verify(inventarioPtService, never()).registrarSalidaFefo(any(), any(), any(), any());
    }

    @Test
    @DisplayName("R-09: eliminar una línea de un pedido ENTREGADO o CANCELADO responde 409")
    void eliminarLineaDePedidoCerradoResponde409() {
        dadoPedido(7, EstadoPedido.ENTREGADO);
        dadoPedido(8, EstadoPedido.CANCELADO);

        for (var id : List.of(7, 8)) {
            assertThatThrownBy(() -> service.eliminar(id, ID_DETALLE))
                    .isInstanceOf(ConflictoNegocioException.class)
                    .hasFieldOrPropertyWithValue("codigo", "PEDIDO_NO_EDITABLE");
        }

        verify(detallePedidoRepository, never()).obtenerPorIdParaActualizar(any());
        verify(detallePedidoRepository, never()).eliminar(any());
        verify(inventarioPtService, never()).registrarDevolucion(any(), any(), any());
    }

    @Test
    @DisplayName("eliminar una línea inexistente o de otro pedido responde 404 sin escribir")
    void eliminarLineaInexistenteODeOtroPedidoResponde404() {
        dadoPedido(ID_PEDIDO, EstadoPedido.PENDIENTE);
        var ajena = new DetallePedido(ID_DETALLE, 999, ID_PRODUCTO, "Pan francés", "und",
                new BigDecimal("10.00"), new BigDecimal("1200.00"));
        when(detallePedidoRepository.obtenerPorIdParaActualizar(ID_DETALLE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(ajena));

        for (var intento = 0; intento < 2; intento++) {
            assertThatThrownBy(() -> service.eliminar(ID_PEDIDO, ID_DETALLE))
                    .isInstanceOf(RecursoNoEncontradoException.class)
                    .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");
        }

        verify(inventarioPtService, never()).registrarDevolucion(any(), any(), any());
        verify(detallePedidoRepository, never()).eliminar(any());
    }

    @Test
    @DisplayName("obtener el stock de un producto inexistente responde 404 sin consultar lotes")
    void obtenerStockDeProductoInexistenteResponde404() {
        when(productoRepository.buscarConDetalle(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtenerStock(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND)
                .hasMessageContaining("9999");

        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("obtener el stock suma los lotes, cuenta los activos y conserva el orden FEFO")
    void obtenerStockSumaLotesCuentaActivosYConservaFefo() {
        // Un producto desactivado sigue teniendo stock consultable: la regla de actividad solo
        // aplica al agregar una línea (CA-02), no a consultar el inventario.
        var producto = producto("Pan francés", "1200.00", false);
        when(productoRepository.buscarConDetalle(ID_PRODUCTO)).thenReturn(Optional.of(producto));
        when(inventarioPtRepository.lotesDisponiblesFefo(ID_PRODUCTO)).thenReturn(List.of(
                new LoteDisponible(10, "PF-0915", LocalDate.parse("2026-09-19"),
                        new BigDecimal("12.00")),
                new LoteDisponible(11, "PF-0917", LocalDate.parse("2026-09-21"),
                        new BigDecimal("65.50"))));

        var r = service.obtenerStock(ID_PRODUCTO);

        assertThat(r.idProducto()).isEqualTo(ID_PRODUCTO);
        assertThat(r.nombre()).isEqualTo("Pan francés");
        assertThat(r.cantidadDisponible()).isEqualByComparingTo("77.50");
        assertThat(r.cantidadDisponible().scale()).isEqualTo(2);
        assertThat(r.lotesActivos()).isEqualTo(2);
        assertThat(r.proximoVencimiento()).isEqualTo(LocalDate.parse("2026-09-19"));

        assertThat(r.lotes()).hasSize(2);
        // El orden FEFO lo garantiza la consulta; el servicio no lo reordena.
        assertThat(r.lotes().get(0).codigoLote()).isEqualTo("PF-0915");
        assertThat(r.lotes().get(0).vence()).isEqualTo(LocalDate.parse("2026-09-19"));
        assertThat(r.lotes().get(0).disponible()).isEqualByComparingTo("12.00");
        assertThat(r.lotes().get(0).disponible().scale()).isEqualTo(2);
        assertThat(r.lotes().get(1).codigoLote()).isEqualTo("PF-0917");
        assertThat(r.lotes().get(1).vence()).isEqualTo(LocalDate.parse("2026-09-21"));
        assertThat(r.lotes().get(1).disponible()).isEqualByComparingTo("65.50");
    }
}
