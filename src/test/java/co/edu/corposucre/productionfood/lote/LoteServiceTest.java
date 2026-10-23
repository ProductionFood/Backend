package co.edu.corposucre.productionfood.lote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.inventario.SaldosInventario;
import co.edu.corposucre.productionfood.inventario.TipoMovimiento;
import co.edu.corposucre.productionfood.lote.dto.CrearLoteMateriaPrimaRequest;
import co.edu.corposucre.productionfood.lote.dto.LoteMateriaPrimaResponse;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;

class LoteServiceTest {

    private LoteRepository loteRepository;
    private MateriaPrimaRepository materiaPrimaRepository;
    private InventarioService inventarioService;
    private LoteService servicio;

    @BeforeEach
    void setUp() {
        loteRepository = mock(LoteRepository.class);
        materiaPrimaRepository = mock(MateriaPrimaRepository.class);
        inventarioService = mock(InventarioService.class);
        servicio = new LoteService(loteRepository, materiaPrimaRepository, inventarioService);
        // El id solo existe tras el flush: el mock simula la asignación del IDENTITY para poder
        // registrar el kardex con id_origen y releer la fila creada.
        when(loteRepository.insertar(any())).thenAnswer(inv -> {
            var lote = inv.getArgument(0, Lote.class);
            lote.setIdLote(14);
            return lote;
        });
    }

    @Test
    @DisplayName("CP-01/CP-02/CP-03: crear sin detalle de compra suma el inventario y devuelve los saldos")
    void crearSinDetalleCompraSumaElInventarioYDevuelveLosSaldos() {
        var hoy = LocalDate.now();
        var produccion = hoy.minusDays(20);
        var vencimiento = hoy.plusDays(173);
        cuandoElAltaEsPosible();
        cuandoLaRelecturaDevuelve(fila(14, "H-2026-0912", 5, "Harina de trigo", "kg",
                produccion, vencimiento, new BigDecimal("100.00")));
        when(inventarioService.registrarEntrada(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(
                        new BigDecimal("12.50"), new BigDecimal("112.50")));

        var r = servicio.crear(solicitud("H-2026-0912", 5, null, produccion, vencimiento,
                new BigDecimal("100.00")));

        assertThat(r.idLote()).isEqualTo(14);
        assertThat(r.codigoLote()).isEqualTo("H-2026-0912");
        assertThat(r.materiaPrima().idMateriaPrima()).isEqualTo(5);
        assertThat(r.materiaPrima().nombre()).isEqualTo("Harina de trigo");
        assertThat(r.materiaPrima().unidad()).isEqualTo("kg");
        assertThat(r.diasParaVencer()).isEqualTo(173);
        assertThat(r.vencido()).isFalse();
        assertThat(r.inventarioActualizado()).isNotNull();
        assertThat(r.inventarioActualizado().anterior()).isEqualByComparingTo("12.50");
        assertThat(r.inventarioActualizado().posterior()).isEqualByComparingTo("112.50");

        // R-01: la suma y el kardex salen del único componente autorizado a tocar inventario.
        verify(inventarioService).registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, "Lote H-2026-0912");

        var captor = ArgumentCaptor.forClass(Lote.class);
        verify(loteRepository).insertar(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getCodigoLote()).isEqualTo("H-2026-0912");
        assertThat(guardado.getIdProducto()).isNull();
        assertThat(guardado.getIdMateriaPrima()).isEqualTo(5);
        assertThat(guardado.getIdDetalleCompra()).isNull();
        assertThat(guardado.getCantidad()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("CP-04: crear con detalle de compra NO toca el inventario (R-02)")
    void crearConDetalleCompraNoSumaNiTocaElInventario() {
        var hoy = LocalDate.now();
        var produccion = hoy.minusDays(20);
        var vencimiento = hoy.plusDays(173);
        cuandoElAltaEsPosible();
        cuandoLaRelecturaDevuelve(fila(14, "H-2026-0912", 5, "Harina de trigo", "kg",
                produccion, vencimiento, new BigDecimal("100.00")));

        var r = servicio.crear(solicitud("H-2026-0912", 5, 42, produccion, vencimiento,
                new BigDecimal("100.00")));

        assertThat(r.idLote()).isEqualTo(14);
        assertThat(r.inventarioActualizado()).isNull();
        // El caso más importante de la historia: sumar aquí marcaría el doble de existencias.
        verifyNoInteractions(inventarioService);

        var captor = ArgumentCaptor.forClass(Lote.class);
        verify(loteRepository).insertar(captor.capture());
        assertThat(captor.getValue().getIdDetalleCompra()).isEqualTo(42);
    }

    @ParameterizedTest
    @MethodSource("codigosDuplicados")
    @DisplayName("CP-05/CP-06: el código duplicado se detecta ya normalizado y no escribe nada")
    void crearConCodigoDuplicadoResponde409(String codigo) {
        when(loteRepository.existsByCodigoLote("H-2026-0912")).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(solicitud(codigo, 5, null,
                LocalDate.now(), LocalDate.now().plusDays(30), new BigDecimal("100.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CODIGO_LOTE_DUPLICADO");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("CP-08: materia prima inexistente responde 409 MATERIA_PRIMA_INEXISTENTE")
    void crearConMateriaPrimaInexistenteResponde409() {
        when(loteRepository.existsByCodigoLote("H-2026-0912")).thenReturn(false);
        when(materiaPrimaRepository.existsById(9999)).thenReturn(false);

        assertThatThrownBy(() -> servicio.crear(solicitud("H-2026-0912", 9999, null,
                LocalDate.now(), LocalDate.now().plusDays(30), new BigDecimal("100.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_INEXISTENTE");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("CP-09: vencimiento anterior a producción responde 400 FECHAS_INVALIDAS")
    void crearConVencimientoAnteriorAProduccionResponde400() {
        cuandoElAltaEsPosible();

        assertThatThrownBy(() -> servicio.crear(solicitud("H-2026-0912", 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 1),
                new BigDecimal("100.00"))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FECHAS_INVALIDAS");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("CP-10: vencimiento igual a producción es válido (R-04)")
    void crearConVencimientoIgualAProduccionEsValido() {
        var dia = LocalDate.now().plusDays(30);
        cuandoElAltaEsPosible();
        cuandoLaRelecturaDevuelve(fila(14, "H-2026-0912", 5, "Harina de trigo", "kg",
                dia, dia, new BigDecimal("100.00")));
        when(inventarioService.registrarEntrada(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(
                        new BigDecimal("12.50"), new BigDecimal("112.50")));

        var r = servicio.crear(solicitud("H-2026-0912", 5, null, dia, dia,
                new BigDecimal("100.00")));

        assertThat(r.idLote()).isEqualTo(14);
        assertThat(r.diasParaVencer()).isEqualTo(30);
        assertThat(r.vencido()).isFalse();
        verify(inventarioService).registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, "Lote H-2026-0912");
    }

    @ParameterizedTest
    @MethodSource("cantidadesNoPositivas")
    @DisplayName("CP-12/CP-13: cantidad cero o negativa responde 400 CANTIDAD_INVALIDA")
    void crearConCantidadNoPositivaResponde400(BigDecimal cantidad) {
        cuandoElAltaEsPosible();

        assertThatThrownBy(() -> servicio.crear(solicitud("H-2026-0912", 5, null,
                LocalDate.now(), LocalDate.now().plusDays(30), cantidad)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verify(loteRepository, never()).insertar(any());
        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("R-06: el código se recorta y se guarda en mayúsculas antes de consultarlo")
    void crearNormalizaElCodigoAntesDeConsultarYGuardar() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();
        cuandoLaRelecturaDevuelve(fila(14, "H-2026-0912", 5, "Harina de trigo", "kg",
                hoy, hoy.plusDays(30), new BigDecimal("100.00")));
        when(inventarioService.registrarEntrada(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(
                        new BigDecimal("0.00"), new BigDecimal("100.00")));

        servicio.crear(solicitud("  h-2026-0912  ", 5, null, hoy, hoy.plusDays(30),
                new BigDecimal("100.00")));

        verify(loteRepository).existsByCodigoLote("H-2026-0912");
        var captor = ArgumentCaptor.forClass(Lote.class);
        verify(loteRepository).insertar(captor.capture());
        assertThat(captor.getValue().getCodigoLote()).isEqualTo("H-2026-0912");
        verify(inventarioService).registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, "Lote H-2026-0912");
    }

    @Test
    @DisplayName("CP-15: un lote ya vencido se marca y aun así suma al inventario (R-05)")
    void crearLoteVencidoLoMarcaYSigueSumando() {
        var hoy = LocalDate.now();
        var produccion = hoy.minusDays(30);
        var vencimiento = hoy.minusDays(5);
        cuandoElAltaEsPosible();
        cuandoLaRelecturaDevuelve(fila(14, "H-2026-0912", 5, "Harina de trigo", "kg",
                produccion, vencimiento, new BigDecimal("100.00")));
        when(inventarioService.registrarEntrada(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(
                        new BigDecimal("12.50"), new BigDecimal("112.50")));

        var r = servicio.crear(solicitud("H-2026-0912", 5, null, produccion, vencimiento,
                new BigDecimal("100.00")));

        assertThat(r.vencido()).isTrue();
        assertThat(r.diasParaVencer()).isNegative();
        verify(inventarioService).registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_LOTE, "lotes", 14, "Lote H-2026-0912");
    }

    @Test
    @DisplayName("CP-14/CP-16: listar traspasa los filtros y normaliza soloVencidos=false a null")
    void listarTraspasaFiltrosYNormalizaSoloVencidos() {
        var hoy = LocalDate.now();
        var f1 = fila(1, "H-2026-0001", 5, "Harina de trigo", "kg",
                hoy.minusDays(10), hoy.plusDays(6), new BigDecimal("100.00"));
        when(loteRepository.listarConDetalle(eq(5), isNull(), eq(7), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(f1),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        var r = servicio.listar(5, false, 7, 0, 20, null);

        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0).codigoLote()).isEqualTo("H-2026-0001");
        assertThat(r.content().get(0).materiaPrima().unidad()).isEqualTo("kg");
        assertThat(r.content().get(0).diasParaVencer()).isEqualTo(6);
        // Una lectura no mueve stock: la respuesta no informa saldos.
        assertThat(r.content().get(0).inventarioActualizado()).isNull();
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(1);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(loteRepository).listarConDetalle(eq(5), isNull(), eq(7), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("fechaVencimiento").isAscending())
                .isTrue();
    }

    @Test
    @DisplayName("Listar con soloVencidos=true y orden pedido usa esos valores y la página indicada")
    void listarConSoloVencidosYOrdenPedido() {
        when(loteRepository.listarConDetalle(isNull(), eq(true), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        servicio.listar(null, true, null, 1, 5, "codigoLote,desc");

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(loteRepository).listarConDetalle(isNull(), eq(true), isNull(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getSort().getOrderFor("codigoLote").isDescending()).isTrue();
    }

    @Test
    @DisplayName("Listar con proximosAVencer menor que uno responde 400 PARAMETRO_INVALIDO")
    void listarConProximosAVencerInvalidoResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, 0, 0, 20, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        verify(loteRepository, never()).listarConDetalle(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Obtener un lote existente mapea la proyección y no informa saldos")
    void obtenerLoteExistente() {
        var hoy = LocalDate.now();
        when(loteRepository.buscarDetallePorId(7)).thenReturn(Optional.of(
                fila(7, "H-2026-0007", 5, "Harina de trigo", "kg",
                        hoy.minusDays(10), hoy.plusDays(20), new BigDecimal("50.00"))));

        var r = servicio.obtener(7);

        assertThat(r.idLote()).isEqualTo(7);
        assertThat(r.codigoLote()).isEqualTo("H-2026-0007");
        assertThat(r.materiaPrima().idMateriaPrima()).isEqualTo(5);
        assertThat(r.materiaPrima().nombre()).isEqualTo("Harina de trigo");
        assertThat(r.diasParaVencer()).isEqualTo(20);
        assertThat(r.vencido()).isFalse();
        assertThat(r.inventarioActualizado()).isNull();
    }

    @Test
    @DisplayName("Obtener un lote inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerLoteInexistenteResponde404() {
        when(loteRepository.buscarDetallePorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró el lote con id 9999.");
    }

    @Test
    @DisplayName("CP-18: si falla el kardex la excepción se propaga para revertir la transacción")
    void crearPropagaElFalloDelInventarioParaRevertirLaTransaccion() {
        var hoy = LocalDate.now();
        cuandoElAltaEsPosible();
        doThrow(new DataIntegrityViolationException("kardex caído"))
                .when(inventarioService).registrarEntrada(any(), any(), any(), any(), any(),
                        any());

        assertThatThrownBy(() -> servicio.crear(solicitud("H-2026-0912", 5, null, hoy,
                hoy.plusDays(30), new BigDecimal("100.00"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // El servicio no captura el fallo: el INSERT ya se ejecutó y es la transacción del
        // llamador la que lo revierte junto con el saldo y el kardex.
        verify(loteRepository).insertar(any());
    }

    private static Stream<String> codigosDuplicados() {
        return Stream.of("H-2026-0912", "  h-2026-0912  ");
    }

    private static Stream<BigDecimal> cantidadesNoPositivas() {
        return Stream.of(BigDecimal.ZERO, new BigDecimal("-10.00"));
    }

    private static CrearLoteMateriaPrimaRequest solicitud(String codigo, Integer idMateriaPrima,
                                                          Integer idDetalleCompra,
                                                          LocalDate produccion,
                                                          LocalDate vencimiento,
                                                          BigDecimal cantidad) {
        return new CrearLoteMateriaPrimaRequest(codigo, idMateriaPrima, idDetalleCompra,
                produccion, vencimiento, cantidad);
    }

    private void cuandoElAltaEsPosible() {
        when(loteRepository.existsByCodigoLote("H-2026-0912")).thenReturn(false);
        when(materiaPrimaRepository.existsById(5)).thenReturn(true);
    }

    private void cuandoLaRelecturaDevuelve(LoteConDetalle fila) {
        when(loteRepository.buscarDetallePorId(14)).thenReturn(Optional.of(fila));
    }

    private static LoteConDetalle fila(int idLote, String codigoLote, int idMateriaPrima,
                                       String materiaPrimaNombre, String unidad,
                                       LocalDate fechaProduccion, LocalDate fechaVencimiento,
                                       BigDecimal cantidad) {
        return new FilaConDetalle(idLote, codigoLote, idMateriaPrima, materiaPrimaNombre, 1,
                "Kilogramo", unidad, fechaProduccion, fechaVencimiento, cantidad);
    }

    /**
     * Fila de la proyección {@link LoteConDetalle}: como es una interfaz de Spring Data, este
     * record la implementa para los tests sin tener que stubear sus diez getters.
     */
    private record FilaConDetalle(Integer idLote, String codigoLote, Integer idMateriaPrima,
                                  String materiaPrimaNombre, Integer idUnidad, String unidadNombre,
                                  String unidadAbreviatura, LocalDate fechaProduccion,
                                  LocalDate fechaVencimiento, BigDecimal cantidad)
            implements LoteConDetalle {

        @Override
        public Integer getIdLote() {
            return idLote;
        }

        @Override
        public String getCodigoLote() {
            return codigoLote;
        }

        @Override
        public Integer getIdMateriaPrima() {
            return idMateriaPrima;
        }

        @Override
        public String getMateriaPrimaNombre() {
            return materiaPrimaNombre;
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
        public BigDecimal getCantidad() {
            return cantidad;
        }
    }
}
