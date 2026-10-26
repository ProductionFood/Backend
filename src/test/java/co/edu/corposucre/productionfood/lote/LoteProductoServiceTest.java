package co.edu.corposucre.productionfood.lote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.TipoMovimientoPt;
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
