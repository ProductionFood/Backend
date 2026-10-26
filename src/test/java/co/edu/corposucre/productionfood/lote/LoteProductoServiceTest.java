package co.edu.corposucre.productionfood.lote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.TipoMovimientoPt;
import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;
import co.edu.corposucre.productionfood.inventario.dto.OrigenResponse;
import co.edu.corposucre.productionfood.lote.dto.CrearLoteProductoRequest;
import co.edu.corposucre.productionfood.lote.dto.LoteProductoResponse;
import co.edu.corposucre.productionfood.producto.ProductoRepository;

class LoteProductoServiceTest {

    private LoteRepository loteRepository;
    private ProductoRepository productoRepository;
    private InventarioPtRepository inventarioPtRepository;
    private LoteProductoService servicio;

    @BeforeEach
    void setUp() {
        loteRepository = mock(LoteRepository.class);
        productoRepository = mock(ProductoRepository.class);
        inventarioPtRepository = mock(InventarioPtRepository.class);
        servicio = new LoteProductoService(
                loteRepository, productoRepository, inventarioPtRepository);
        // El id solo existe tras el flush: el mock simula la asignación del IDENTITY para poder
        // crear la fila de inventario y registrar el kardex con el id del lote.
        when(loteRepository.insertar(any())).thenAnswer(inv -> {
            var lote = inv.getArgument(0, Lote.class);
            lote.setIdLote(14);
            return lote;
        });
    }

    @Test
    @DisplayName("CP-01/CP-02/CP-03: el alta crea lote, inventario y movimiento de entrada")
    void crearLoteProductoValidoCreaLasTresFilas() {
        var hoy = LocalDate.now();
        var produccion = hoy.minusDays(20);
        var vencimiento = hoy.plusDays(4);
        cuandoElAltaEsPosible();
        cuandoLaRelecturaDevuelve(fila(14, "PF-2026-0918", 3, produccion, vencimiento,
                new BigDecimal("25.00"), new BigDecimal("25.00"), new BigDecimal("102.00")));

        var r = servicio.crearLoteProducto(solicitud("PF-2026-0918", 3, produccion, vencimiento,
                new BigDecimal("25")));

        assertThat(r.idLote()).isEqualTo(14);
        assertThat(r.codigoLote()).isEqualTo("PF-2026-0918");
        assertThat(r.producto().idProducto()).isEqualTo(3);
        assertThat(r.producto().nombre()).isEqualTo("Pan francés");
        assertThat(r.producto().unidad()).isEqualTo("und");
        assertThat(r.fechaProduccion()).isEqualTo(produccion);
        assertThat(r.fechaVencimiento()).isEqualTo(vencimiento);
        assertThat(r.cantidadIngresada()).isEqualByComparingTo("25.00");
        assertThat(r.cantidadDisponible()).isEqualByComparingTo("25.00");
        assertThat(r.stockTotalProducto()).isEqualByComparingTo("102.00");
        assertThat(r.diasParaVencer()).isEqualTo(4);
        assertThat(r.vencido()).isFalse();

        // CA-05/R-02: el saldo inicial coincide con la cantidad de ingreso y el kardex deja el
        // movimiento ENTRADA_PRODUCCION con saldos 0.00 -> cantidad y origen en el propio lote.
        verify(inventarioPtRepository).crearParaLote(14, new BigDecimal("25.00"));
        verify(inventarioPtRepository).registrarMovimiento(14, TipoMovimientoPt.ENTRADA_PRODUCCION,
                new BigDecimal("25.00"), new BigDecimal("0.00"), new BigDecimal("25.00"),
                "lotes", 14, "Lote PF-2026-0918");

        // R-06: el lote es de producto y deja las referencias de materia prima y compra en NULL.
        var captor = ArgumentCaptor.forClass(Lote.class);
        verify(loteRepository).insertar(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getCodigoLote()).isEqualTo("PF-2026-0918");
        assertThat(guardado.getIdProducto()).isEqualTo(3);
        assertThat(guardado.getIdMateriaPrima()).isNull();
        assertThat(guardado.getIdDetalleCompra()).isNull();
        // R-03: el lote conserva la cantidad recibida; solo el inventario se normaliza a escala 2.
        assertThat(guardado.getCantidad()).isEqualTo(new BigDecimal("25"));
    }

    @Test
    @DisplayName("R-08: el código se recorta y se guarda en mayúsculas antes de consultarlo")
    void crearNormalizaElCodigoAntesDeConsultarYGuardar() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();
        cuandoLaRelecturaDevuelve(fila(14, "PF-2026-0918", 3,
                hoy.minusDays(1), hoy.plusDays(10), new BigDecimal("25.00"),
                new BigDecimal("25.00"), new BigDecimal("25.00")));

        servicio.crearLoteProducto(solicitud("  pf-2026-0918  ", 3,
                hoy.minusDays(1), hoy.plusDays(10), new BigDecimal("25.00")));

        verify(loteRepository).existsByCodigoLote("PF-2026-0918");
        var captor = ArgumentCaptor.forClass(Lote.class);
        verify(loteRepository).insertar(captor.capture());
        assertThat(captor.getValue().getCodigoLote()).isEqualTo("PF-2026-0918");
        verify(inventarioPtRepository).crearParaLote(14, new BigDecimal("25.00"));
    }

    @ParameterizedTest
    @MethodSource("codigosDuplicados")
    @DisplayName("CP-05/CP-06: el código duplicado se detecta ya normalizado y no escribe nada")
    void crearConCodigoDuplicadoResponde409(String codigo) {
        var hoy = LocalDate.now();
        when(loteRepository.existsByCodigoLote("PF-2026-0918")).thenReturn(true);

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud(codigo, 3, hoy, hoy.plusDays(10), new BigDecimal("25.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CODIGO_LOTE_DUPLICADO");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("CP-08: producto inexistente responde 409 PRODUCTO_INEXISTENTE")
    void crearConProductoInexistenteResponde409() {
        var hoy = LocalDate.now();
        when(loteRepository.existsByCodigoLote("PF-2026-0918")).thenReturn(false);
        when(productoRepository.existsById(9999)).thenReturn(false);

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 9999, hoy, hoy.plusDays(10),
                        new BigDecimal("25.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_INEXISTENTE");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("CP-09: vencimiento anterior a producción gana FECHAS_INVALIDAS aunque esté vencido")
    void crearConVencimientoAnteriorAProduccionResponde400() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 3, hoy.minusDays(10), hoy.minusDays(20),
                        new BigDecimal("25.00"))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FECHAS_INVALIDAS");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("CP-10: vencimiento de ayer responde 400 LOTE_YA_VENCIDO (R-05)")
    void crearConVencimientoAyerResponde400() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 3, hoy.minusDays(5), hoy.minusDays(1),
                        new BigDecimal("25.00"))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "LOTE_YA_VENCIDO");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("CP-11: un lote que vence hoy es válido (R-05)")
    void crearConVencimientoHoyEsValido() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();
        cuandoLaRelecturaDevuelve(fila(14, "PF-2026-0918", 3, hoy.minusDays(1), hoy,
                new BigDecimal("25.00"), new BigDecimal("25.00"), new BigDecimal("25.00")));

        var r = servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 3, hoy.minusDays(1), hoy, new BigDecimal("25.00")));

        assertThat(r.idLote()).isEqualTo(14);
        assertThat(r.diasParaVencer()).isZero();
        assertThat(r.vencido()).isFalse();
        verify(inventarioPtRepository).crearParaLote(14, new BigDecimal("25.00"));
    }

    @ParameterizedTest
    @MethodSource("cantidadesNoPositivas")
    @DisplayName("CP-12/CP-13: cantidad cero o negativa responde 400 CANTIDAD_INVALIDA")
    void crearConCantidadNoPositivaResponde400(BigDecimal cantidad) {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 3, hoy, hoy.plusDays(10), cantidad)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("Carrera de código: la violación del UNIQUE de codigo_lote responde 409")
    void crearConCarreraDeCodigoResponde409() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();
        // doThrow y no when(...).thenThrow: el stub del setUp tiene un Answer que ya se
        // ejecutaría con el argumento null de any() antes de sustituirse.
        doThrow(new DataIntegrityViolationException(
                "Duplicate entry 'PF-2026-0918' for key 'lotes.codigo_lote'"))
                .when(loteRepository).insertar(any());

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 3, hoy, hoy.plusDays(10),
                        new BigDecimal("25.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CODIGO_LOTE_DUPLICADO");

        // El 409 sale del catch del INSERT, antes de tocar el inventario.
        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("Otra violación de integridad se propaga sin disfrazarse de código duplicado")
    void crearConOtraViolacionDeIntegridadPropagaLaOriginal() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();
        var original = new DataIntegrityViolationException(
                "Duplicate entry 'X' for key 'lotes.otra_restriccion'");
        doThrow(original).when(loteRepository).insertar(any());

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 3, hoy, hoy.plusDays(10),
                        new BigDecimal("25.00"))))
                .isSameAs(original);

        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("Si falla la fila de inventario la excepción se propaga para revertir la transacción")
    void crearPropagaElFalloDelInventario() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();
        doThrow(new DataIntegrityViolationException("inventario caído"))
                .when(inventarioPtRepository).crearParaLote(any(), any());

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 3, hoy, hoy.plusDays(10),
                        new BigDecimal("25.00"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("inventario caído");

        // El servicio no captura el fallo: el lote ya se insertó y la transacción del llamador
        // revierte las tres escrituras juntas.
        verify(loteRepository).insertar(any());
        verify(inventarioPtRepository, never()).registrarMovimiento(any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("Si la relectura no encuentra el lote recién creado responde 404")
    void crearSinRelecturaDelLoteResponde404() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();
        when(loteRepository.buscarProductoPorId(14)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.crearLoteProducto(
                solicitud("PF-2026-0918", 3, hoy, hoy.plusDays(10),
                        new BigDecimal("25.00"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró el lote de producto con id 14.");
    }

    @Test
    @DisplayName("CA-06: el listado por defecto no filtra y ordena por fecha de vencimiento")
    void listarPorDefectoNoFiltraYOrdenaPorVencimiento() {
        var hoy = LocalDate.now();
        var f1 = fila(14, "PF-2026-0918", 3, hoy.minusDays(20), hoy.plusDays(4),
                new BigDecimal("25.00"), new BigDecimal("10.00"), new BigDecimal("102.00"));
        when(loteRepository.listarProductos(isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(f1),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        var r = servicio.listar(null, null, null, null, 0, 20, null);

        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0).idLote()).isEqualTo(14);
        assertThat(r.content().get(0).codigoLote()).isEqualTo("PF-2026-0918");
        assertThat(r.content().get(0).producto().nombre()).isEqualTo("Pan francés");
        assertThat(r.content().get(0).cantidadIngresada()).isEqualByComparingTo("25.00");
        // R-03: el listado no confunde el ingreso con el saldo.
        assertThat(r.content().get(0).cantidadDisponible()).isEqualByComparingTo("10.00");
        assertThat(r.content().get(0).stockTotalProducto()).isEqualByComparingTo("102.00");
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(1);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(loteRepository).listarProductos(isNull(), isNull(), isNull(), isNull(),
                captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("fechaVencimiento").isAscending())
                .isTrue();
    }

    @Test
    @DisplayName("CA-06: los filtros true llegan al repositorio tal cual")
    void listarTraspasaLosFiltrosActivos() {
        when(loteRepository.listarProductos(eq(3), eq(true), eq(7), eq(true),
                any(Pageable.class))).thenReturn(Page.empty());

        servicio.listar(3, true, 7, true, 0, 20, null);

        verify(loteRepository).listarProductos(eq(3), eq(true), eq(7), eq(true),
                any(Pageable.class));
    }

    @Test
    @DisplayName("CA-06: soloVencidos=false y conStock=false se normalizan a null")
    void listarNormalizaLosBooleanosFalseANull() {
        when(loteRepository.listarProductos(isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class))).thenReturn(Page.empty());

        servicio.listar(null, false, null, false, 0, 20, null);

        // FALSE no significa «no filtrar» sino «exigir no vencido» o «exigir sin stock»: el
        // repositorio solo debe recibir TRUE o null.
        verify(loteRepository).listarProductos(isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class));
    }

    @Test
    @DisplayName("CA-06: proximosAVencer menor que uno responde 400 PARAMETRO_INVALIDO")
    void listarConProximosAVencerInvalidoResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, 0, null, 0, 20, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        verify(loteRepository, never()).listarProductos(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("CA-06: un campo de orden fuera de la lista blanca responde 400")
    void listarConOrdenInvalidoResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, null, null, 0, 20, "cantidad,desc"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verify(loteRepository, never()).listarProductos(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("CA-06: el orden id,desc se traduce al alias idLote descendente")
    void listarConOrdenIdDescUsaElAliasIdLote() {
        when(loteRepository.listarProductos(isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class))).thenReturn(Page.empty());

        servicio.listar(null, null, null, null, 0, 20, "id,desc");

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(loteRepository).listarProductos(isNull(), isNull(), isNull(), isNull(),
                captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("idLote").isDescending()).isTrue();
    }

    @Test
    @DisplayName("CA-06: page negativa o size fuera de 1..100 responde 400")
    void listarConPaginacionInvalidaResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, null, null, -1, 20, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        assertThatThrownBy(() -> servicio.listar(null, null, null, null, 0, 0, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        assertThatThrownBy(() -> servicio.listar(null, null, null, null, 0, 101, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        verify(loteRepository, never()).listarProductos(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Obtener un lote de producto existente mapea todos los campos, incluido el stock")
    void obtenerLoteProductoMapeaTodosLosCampos() {
        var hoy = LocalDate.now();
        when(loteRepository.buscarProductoPorId(14)).thenReturn(Optional.of(
                fila(14, "PF-2026-0918", 3, hoy.minusDays(20), hoy.plusDays(4),
                        new BigDecimal("25.00"), new BigDecimal("10.00"),
                        new BigDecimal("102.00"))));

        var r = servicio.obtener(14);

        assertThat(r.idLote()).isEqualTo(14);
        assertThat(r.codigoLote()).isEqualTo("PF-2026-0918");
        assertThat(r.producto().idProducto()).isEqualTo(3);
        assertThat(r.producto().nombre()).isEqualTo("Pan francés");
        assertThat(r.producto().unidad()).isEqualTo("und");
        assertThat(r.fechaProduccion()).isEqualTo(hoy.minusDays(20));
        assertThat(r.fechaVencimiento()).isEqualTo(hoy.plusDays(4));
        assertThat(r.cantidadIngresada()).isEqualByComparingTo("25.00");
        assertThat(r.cantidadDisponible()).isEqualByComparingTo("10.00");
        assertThat(r.diasParaVencer()).isEqualTo(4);
        assertThat(r.vencido()).isFalse();
        assertThat(r.stockTotalProducto()).isEqualByComparingTo("102.00");
    }

    @Test
    @DisplayName("Obtener un lote de producto inexistente responde 404")
    void obtenerLoteProductoInexistenteResponde404() {
        when(loteRepository.buscarProductoPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró el lote de producto con id 9999.");
    }

    @Test
    @DisplayName("Kardex: devuelve la página de movimientos del lote y su total")
    void movimientosDevuelveLaPaginaDelKardex() {
        var movimiento = movimiento();
        when(loteRepository.esLoteProducto(14)).thenReturn(true);
        when(inventarioPtRepository.buscarMovimientos(14, 20, 0L))
                .thenReturn(List.of(movimiento));
        when(inventarioPtRepository.contarMovimientos(14)).thenReturn(1L);

        var r = servicio.movimientos(14, 0, 20);

        assertThat(r.content()).containsExactly(movimiento);
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(1);
        verify(inventarioPtRepository).buscarMovimientos(14, 20, 0L);
        verify(inventarioPtRepository).contarMovimientos(14);
    }

    @ParameterizedTest
    @ValueSource(ints = {9999, 5})
    @DisplayName("Kardex: un lote inexistente o de materia prima responde 404 sin leer el kardex")
    void movimientosDeLoteNoProductoResponde404(int id) {
        when(loteRepository.esLoteProducto(id)).thenReturn(false);

        assertThatThrownBy(() -> servicio.movimientos(id, 0, 20))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró el lote de producto con id " + id + ".");

        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("Kardex: un lote existente sin movimientos devuelve una página vacía, no 404")
    void movimientosDeLoteSinMovimientosDevuelvePaginaVacia() {
        when(loteRepository.esLoteProducto(14)).thenReturn(true);
        when(inventarioPtRepository.buscarMovimientos(14, 20, 0L)).thenReturn(List.of());
        when(inventarioPtRepository.contarMovimientos(14)).thenReturn(0L);

        var r = servicio.movimientos(14, 0, 20);

        assertThat(r.content()).isEmpty();
        assertThat(r.totalElements()).isZero();
        assertThat(r.totalPages()).isZero();
    }

    @Test
    @DisplayName("Kardex: page negativa o size fuera de 1..100 responde 400 sin consultar nada")
    void movimientosConPaginacionInvalidaResponde400() {
        assertThatThrownBy(() -> servicio.movimientos(14, -1, 20))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        assertThatThrownBy(() -> servicio.movimientos(14, 0, 0))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");
        assertThatThrownBy(() -> servicio.movimientos(14, 0, 101))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        verify(loteRepository, never()).esLoteProducto(any());
        verifyNoInteractions(inventarioPtRepository);
    }

    @Test
    @DisplayName("Kardex: el offset se calcula en long con page * size")
    void movimientosCalculaElOffsetEnLong() {
        when(loteRepository.esLoteProducto(14)).thenReturn(true);
        when(inventarioPtRepository.buscarMovimientos(eq(14), eq(20), anyLong()))
                .thenReturn(List.of());
        when(inventarioPtRepository.contarMovimientos(14)).thenReturn(0L);

        servicio.movimientos(14, 3, 20);

        var captor = ArgumentCaptor.forClass(Long.class);
        verify(inventarioPtRepository).buscarMovimientos(eq(14), eq(20), captor.capture());
        assertThat(captor.getValue()).isEqualTo(60L);
    }

    private static Stream<String> codigosDuplicados() {
        return Stream.of("PF-2026-0918", "  pf-2026-0918  ");
    }

    private static Stream<BigDecimal> cantidadesNoPositivas() {
        return Stream.of(BigDecimal.ZERO, new BigDecimal("-10.00"));
    }

    private static CrearLoteProductoRequest solicitud(String codigo, Integer idProducto,
                                                      LocalDate produccion,
                                                      LocalDate vencimiento,
                                                      BigDecimal cantidad) {
        return new CrearLoteProductoRequest(codigo, idProducto, produccion, vencimiento, cantidad);
    }

    private void cuandoElAltaEsPosible() {
        when(loteRepository.existsByCodigoLote("PF-2026-0918")).thenReturn(false);
        when(productoRepository.existsById(3)).thenReturn(true);
    }

    private void cuandoLaRelecturaDevuelve(LoteProductoConDetalle fila) {
        when(loteRepository.buscarProductoPorId(14)).thenReturn(Optional.of(fila));
    }

    private static LoteProductoConDetalle fila(int idLote, String codigoLote, int idProducto,
                                               LocalDate fechaProduccion,
                                               LocalDate fechaVencimiento,
                                               BigDecimal cantidadIngresada,
                                               BigDecimal cantidadDisponible,
                                               BigDecimal stockTotalProducto) {
        return new FilaProducto(idLote, codigoLote, idProducto, "Pan francés", 2, "Unidad", "und",
                fechaProduccion, fechaVencimiento, cantidadIngresada, cantidadDisponible,
                stockTotalProducto);
    }

    private static MovimientoResponse movimiento() {
        return new MovimientoResponse(
                LocalDateTime.of(2026, 9, 18, 9, 12, 44),
                "ENTRADA_PRODUCCION",
                new BigDecimal("25.000"),
                new BigDecimal("0.00"),
                new BigDecimal("25.00"),
                new OrigenResponse("lotes", 14),
                "Sistema");
    }

    /**
     * Fila de la proyección {@link LoteProductoConDetalle}: como es una interfaz de Spring Data,
     * este record la implementa para los tests sin tener que stubear sus doce getters.
     */
    private record FilaProducto(Integer idLote, String codigoLote, Integer idProducto,
                                String productoNombre, Integer idUnidad, String unidadNombre,
                                String unidadAbreviatura, LocalDate fechaProduccion,
                                LocalDate fechaVencimiento, BigDecimal cantidadIngresada,
                                BigDecimal cantidadDisponible, BigDecimal stockTotalProducto)
            implements LoteProductoConDetalle {

        @Override
        public Integer getIdLote() {
            return idLote;
        }

        @Override
        public String getCodigoLote() {
            return codigoLote;
        }

        @Override
        public Integer getIdProducto() {
            return idProducto;
        }

        @Override
        public String getProductoNombre() {
            return productoNombre;
        }

        @Override
        public Integer getIdUnidad() {
            return idUnidad;
        }

        @Override
        public String getUnidadNombre() {
            return unidadNombre;
        }

        @Override
        public String getUnidadAbreviatura() {
            return unidadAbreviatura;
        }

        @Override
        public LocalDate getFechaProduccion() {
            return fechaProduccion;
        }

        @Override
        public LocalDate getFechaVencimiento() {
            return fechaVencimiento;
        }

        @Override
        public BigDecimal getCantidadIngresada() {
            return cantidadIngresada;
        }

        @Override
        public BigDecimal getCantidadDisponible() {
            return cantidadDisponible;
        }

        @Override
        public BigDecimal getStockTotalProducto() {
            return stockTotalProducto;
        }
    }
}
