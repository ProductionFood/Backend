package co.edu.corposucre.productionfood.receta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;

import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.producto.ProductoRepository;
import co.edu.corposucre.productionfood.receta.dto.ActualizarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.ActualizarRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.AgregarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.CrearRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.DetalleRecetaResponse;
import co.edu.corposucre.productionfood.receta.dto.RecetaResponse;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedida;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedidaRepository;

class RecetaServiceTest {

    private RecetaRepository recetaRepository;
    private DetalleRecetaRepository detalleRecetaRepository;
    private ProductoRepository productoRepository;
    private MateriaPrimaRepository materiaPrimaRepository;
    private UnidadMedidaRepository unidadMedidaRepository;
    private RecetaService servicio;

    @BeforeEach
    void setUp() {
        recetaRepository = mock(RecetaRepository.class);
        detalleRecetaRepository = mock(DetalleRecetaRepository.class);
        productoRepository = mock(ProductoRepository.class);
        materiaPrimaRepository = mock(MateriaPrimaRepository.class);
        unidadMedidaRepository = mock(UnidadMedidaRepository.class);
        servicio = new RecetaService(recetaRepository, detalleRecetaRepository, productoRepository,
                materiaPrimaRepository, unidadMedidaRepository);
    }

    @Test
    @DisplayName("Crear con tres ingredientes inserta la cabecera y los tres detalles")
    void crearConTresIngredientesInsertaCabeceraYDetalles() {
        prepararAltaValida();
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of(
                detalle(11, 5, "5.000", "Harina de trigo", "kg", "3200.00"),
                detalle(12, 7, "0.050", "Levadura", "g", "180.00"),
                detalle(13, 9, "0.100", "Sal", "g", "850.00")));

        var r = servicio.crear(recetaRequest(new BigDecimal("30"), List.of(
                new AgregarDetalleRequest(5, new BigDecimal("5.000")),
                new AgregarDetalleRequest(7, new BigDecimal("0.050")),
                new AgregarDetalleRequest(9, new BigDecimal("0.100")))));

        assertThat(r.idReceta()).isEqualTo(2);
        assertThat(r.activo()).isTrue();
        assertThat(r.completa()).isTrue();
        assertThat(r.detalles()).hasSize(3);
        assertThat(r.detalles().get(0).costoEstimado()).isEqualByComparingTo("16000.00");
        assertThat(r.detalles().get(1).costoEstimado()).isEqualByComparingTo("9.00");
        assertThat(r.detalles().get(2).costoEstimado()).isEqualByComparingTo("85.00");
        assertThat(r.costoTotalEstimado()).isEqualByComparingTo("16094.00");
        assertThat(r.costoPorUnidad()).isEqualByComparingTo("536.47");
        assertThat(r.cantidadProducir().scale()).isEqualTo(2);
        assertThat(r.costoTotalEstimado().scale()).isEqualTo(2);
        assertThat(r.costoPorUnidad().scale()).isEqualTo(2);
        assertThat(r.detalles().get(0).cantidad().scale()).isEqualTo(3);

        var cabecera = ArgumentCaptor.forClass(Receta.class);
        verify(recetaRepository).insertar(cabecera.capture());
        assertThat(cabecera.getValue().getActivo()).isTrue();
        assertThat(cabecera.getValue().getCantidadProducir()).isEqualByComparingTo("30.00");

        var lineas = ArgumentCaptor.forClass(DetalleReceta.class);
        verify(detalleRecetaRepository, times(3)).insertar(lineas.capture());
        assertThat(lineas.getAllValues()).extracting(DetalleReceta::getIdMateriaPrima)
                .containsExactly(5, 7, 9);
    }

    @ParameterizedTest
    @MethodSource("cantidadesProducirInvalidas")
    @DisplayName("cantidadProducir cero o negativa responde 400 CANTIDAD_PRODUCIR_INVALIDA sin insertar")
    void cantidadProducirInvalidaNoInserta(BigDecimal cantidad) {
        when(productoRepository.existeActivo(3)).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(recetaRequest(cantidad, List.of())))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_PRODUCIR_INVALIDA");

        verify(recetaRepository, never()).insertar(any());
        verify(detalleRecetaRepository, never()).insertar(any());
    }

    @Test
    @DisplayName("Producto inexistente o inactivo responde 409 PRODUCTO_INEXISTENTE")
    void productoInexistenteResponde409() {
        when(productoRepository.existeActivo(3)).thenReturn(false);

        assertThatThrownBy(() -> servicio.crear(recetaRequest(new BigDecimal("30"), List.of())))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_INEXISTENTE");

        verifyNoInteractions(unidadMedidaRepository);
        verify(recetaRepository, never()).insertar(any());
    }

    @Test
    @DisplayName("Producto que ya tiene receta responde 409 PRODUCTO_YA_TIENE_RECETA sin insertar")
    void productoConRecetaResponde409() {
        when(productoRepository.existeActivo(3)).thenReturn(true);
        when(unidadMedidaRepository.findById(5)).thenReturn(Optional.of(unidad(5, "Unidad", "und")));
        when(recetaRepository.existePorProducto(3)).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(recetaRequest(new BigDecimal("30"), List.of())))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_YA_TIENE_RECETA");

        verify(recetaRepository, never()).insertar(any());
    }

    @Test
    @DisplayName("Unidad inexistente responde 409 UNIDAD_INEXISTENTE sin insertar")
    void unidadInexistenteResponde409() {
        when(productoRepository.existeActivo(3)).thenReturn(true);
        when(unidadMedidaRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.crear(recetaRequest(new BigDecimal("30"), List.of())))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "UNIDAD_INEXISTENTE");

        verify(recetaRepository, never()).insertar(any());
    }

    @Test
    @DisplayName("Materia prima inexistente o inactiva responde 409 MATERIA_PRIMA_INEXISTENTE")
    void materiaPrimaInexistenteResponde409() {
        when(productoRepository.existeActivo(3)).thenReturn(true);
        when(unidadMedidaRepository.findById(5)).thenReturn(Optional.of(unidad(5, "Unidad", "und")));
        when(recetaRepository.existePorProducto(3)).thenReturn(false);
        when(materiaPrimaRepository.existeActiva(5)).thenReturn(false);

        assertThatThrownBy(() -> servicio.crear(recetaRequest(new BigDecimal("30"),
                List.of(new AgregarDetalleRequest(5, new BigDecimal("1.000"))))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_INEXISTENTE");

        verify(recetaRepository, never()).insertar(any());
        verify(detalleRecetaRepository, never()).insertar(any());
    }

    @ParameterizedTest
    @MethodSource("cantidadesDetalleInvalidas")
    @DisplayName("Una cantidad de detalle que no cabe en decimal(10,3) responde 400 sin insertar nada")
    void cantidadDeDetalleFueraDeRangoNoInserta(BigDecimal cantidad) {
        prepararAltaValida();

        assertThatThrownBy(() -> servicio.crear(recetaRequest(new BigDecimal("30"),
                List.of(new AgregarDetalleRequest(5, cantidad)))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "VALIDACION_FALLIDA");

        verify(recetaRepository, never()).insertar(any());
        verify(detalleRecetaRepository, never()).insertar(any());
    }

    @Test
    @DisplayName("Una cantidad con más de tres decimales se redondea HALF_UP: 0.0005 queda 0.001")
    void cantidadConCuatroDecimalesSeRedondea() {
        prepararAltaValida();
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of());

        servicio.crear(recetaRequest(new BigDecimal("30"),
                List.of(new AgregarDetalleRequest(5, new BigDecimal("0.0005")))));

        var captor = ArgumentCaptor.forClass(DetalleReceta.class);
        verify(detalleRecetaRepository).insertar(captor.capture());
        assertThat(captor.getValue().getCantidad()).isEqualByComparingTo("0.001");
        assertThat(captor.getValue().getCantidad().scale()).isEqualTo(3);
    }

    @Test
    @DisplayName("Una carrera que viola el UNIQUE id_producto se traduce a 409 PRODUCTO_YA_TIENE_RECETA")
    void crearConDuplicateKeyResponde409ProductoYaTieneReceta() {
        prepararAltaValida();
        when(recetaRepository.insertar(any()))
                .thenThrow(new DuplicateKeyException("uk_receta_producto"));

        assertThatThrownBy(() -> servicio.crear(recetaRequest(new BigDecimal("30"), List.of())))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PRODUCTO_YA_TIENE_RECETA");

        verify(detalleRecetaRepository, never()).insertar(any());
    }

    @Test
    @DisplayName("Materia prima repetida en la lista responde 409 y no inserta la cabecera")
    void materiaPrimaRepetidaEnLaListaNoInsertaCabecera() {
        when(productoRepository.existeActivo(3)).thenReturn(true);
        when(unidadMedidaRepository.findById(5)).thenReturn(Optional.of(unidad(5, "Unidad", "und")));
        when(recetaRepository.existePorProducto(3)).thenReturn(false);
        when(materiaPrimaRepository.existeActiva(5)).thenReturn(true);
        when(materiaPrimaRepository.existeActiva(7)).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(recetaRequest(new BigDecimal("30"), List.of(
                new AgregarDetalleRequest(5, new BigDecimal("1.000")),
                new AgregarDetalleRequest(7, new BigDecimal("0.500")),
                new AgregarDetalleRequest(5, new BigDecimal("2.000"))))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_DUPLICADA");

        verify(recetaRepository, never()).insertar(any());
        verify(detalleRecetaRepository, never()).insertar(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Una receta sin ingredientes se crea incompleta y con costos en cero")
    void crearSinDetallesEsValida(List<AgregarDetalleRequest> detalles) {
        when(productoRepository.existeActivo(3)).thenReturn(true);
        when(unidadMedidaRepository.findById(5)).thenReturn(Optional.of(unidad(5, "Unidad", "und")));
        when(recetaRepository.existePorProducto(3)).thenReturn(false);
        when(recetaRepository.insertar(any())).thenReturn(2);
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of());

        var r = servicio.crear(new CrearRecetaRequest(3, "Masa de pan francés",
                new BigDecimal("30.00"), 5, detalles));

        assertThat(r.activo()).isTrue();
        assertThat(r.completa()).isFalse();
        assertThat(r.detalles()).isEmpty();
        assertThat(r.costoTotalEstimado()).isEqualByComparingTo("0.00");
        assertThat(r.costoPorUnidad()).isEqualByComparingTo("0.00");
        assertThat(r.costoTotalEstimado().scale()).isEqualTo(2);
        assertThat(r.costoPorUnidad().scale()).isEqualTo(2);
        verify(detalleRecetaRepository, never()).insertar(any());
    }

    @Test
    @DisplayName("CP-20/CP-21: 5 kg a 3200 más 0.05 a 180 son 16009.00 por tanda y 533.63 por unidad")
    void calculaCostoEstimadoDelEjemplo() {
        prepararAltaValida();
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of(
                detalle(11, 5, "5.000", "Harina de trigo", "kg", "3200.00"),
                detalle(12, 7, "0.050", "Levadura", "g", "180.00")));

        var r = servicio.crear(recetaRequest(new BigDecimal("30"), List.of(
                new AgregarDetalleRequest(5, new BigDecimal("5.000")),
                new AgregarDetalleRequest(7, new BigDecimal("0.050")))));

        assertThat(r.costoTotalEstimado()).isEqualByComparingTo("16009.00");
        assertThat(r.costoPorUnidad()).isEqualByComparingTo("533.63");
    }

    @Test
    @DisplayName("Crear conserva 0.005 con escala 3 al insertar el detalle (R-06)")
    void crearConservaTresDecimales() {
        prepararAltaValida();
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of(
                detalle(11, 7, "0.005", "Sal", "kg", "850.00")));

        servicio.crear(recetaRequest(new BigDecimal("30"),
                List.of(new AgregarDetalleRequest(7, new BigDecimal("0.005")))));

        var captor = ArgumentCaptor.forClass(DetalleReceta.class);
        verify(detalleRecetaRepository).insertar(captor.capture());
        assertThat(captor.getValue().getCantidad()).isEqualByComparingTo("0.005");
        assertThat(captor.getValue().getCantidad().scale()).isEqualTo(3);
    }

    @Test
    @DisplayName("Agregar detalle inserta la línea y devuelve la receta recalculada")
    void agregarDetalleInsertaYDevuelveLaRecetaRecalculada() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(materiaPrimaRepository.existeActiva(7)).thenReturn(true);
        when(detalleRecetaRepository.existeMateriaPrima(2, 7)).thenReturn(false);
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of(
                detalle(11, 5, "5.000", "Harina de trigo", "kg", "3200.00"),
                detalle(12, 7, "0.050", "Levadura", "g", "180.00")));

        var r = servicio.agregarDetalle(2,
                new AgregarDetalleRequest(7, new BigDecimal("0.050")));

        assertThat(r.idReceta()).isEqualTo(2);
        assertThat(r.completa()).isTrue();
        assertThat(r.detalles()).hasSize(2);
        assertThat(r.costoTotalEstimado()).isEqualByComparingTo("16009.00");
        assertThat(r.costoPorUnidad()).isEqualByComparingTo("533.63");

        var captor = ArgumentCaptor.forClass(DetalleReceta.class);
        verify(detalleRecetaRepository).insertar(captor.capture());
        assertThat(captor.getValue().getIdReceta()).isEqualTo(2);
        assertThat(captor.getValue().getIdMateriaPrima()).isEqualTo(7);
        assertThat(captor.getValue().getCantidad()).isEqualByComparingTo("0.050");
        assertThat(captor.getValue().getCantidad().scale()).isEqualTo(3);
    }

    @Test
    @DisplayName("Agregar una materia prima que ya está en la receta responde 409 sin insertar")
    void agregarDetalleDuplicadoResponde409SinInsertar() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(materiaPrimaRepository.existeActiva(7)).thenReturn(true);
        when(detalleRecetaRepository.existeMateriaPrima(2, 7)).thenReturn(true);

        assertThatThrownBy(() -> servicio.agregarDetalle(2,
                new AgregarDetalleRequest(7, new BigDecimal("0.050"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_DUPLICADA");

        verify(detalleRecetaRepository, never()).insertar(any());
    }

    @ParameterizedTest
    @MethodSource("cantidadesDetalleInvalidas")
    @DisplayName("Agregar un detalle con cantidad inválida responde 400 sin insertar")
    void agregarDetalleConCantidadInvalidaResponde400SinInsertar(BigDecimal cantidad) {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(materiaPrimaRepository.existeActiva(7)).thenReturn(true);
        when(detalleRecetaRepository.existeMateriaPrima(2, 7)).thenReturn(false);

        assertThatThrownBy(() -> servicio.agregarDetalle(2,
                new AgregarDetalleRequest(7, cantidad)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "VALIDACION_FALLIDA");

        verify(detalleRecetaRepository, never()).insertar(any());
    }

    @Test
    @DisplayName("Una carrera que viola uk_detalle_receta se traduce a 409 MATERIA_PRIMA_DUPLICADA")
    void agregarDetalleConDuplicateKeyResponde409() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(materiaPrimaRepository.existeActiva(7)).thenReturn(true);
        when(detalleRecetaRepository.existeMateriaPrima(2, 7)).thenReturn(false);
        when(detalleRecetaRepository.insertar(any()))
                .thenThrow(new DuplicateKeyException("uk_detalle_receta"));

        assertThatThrownBy(() -> servicio.agregarDetalle(2,
                new AgregarDetalleRequest(7, new BigDecimal("0.050"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_DUPLICADA");
    }

    @Test
    @DisplayName("Agregar detalle a una receta inexistente responde 404")
    void agregarDetalleARecetaInexistenteResponde404() {
        when(recetaRepository.buscarPorId(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.agregarDetalle(99,
                new AgregarDetalleRequest(7, new BigDecimal("0.050"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró la receta con id 99.");

        verifyNoInteractions(materiaPrimaRepository, detalleRecetaRepository);
    }

    @Test
    @DisplayName("Listar arma la página con una sola consulta de detalle y orden por defecto")
    void listarArmaLaPaginaConUnaSolaConsultaDeDetalle() {
        when(recetaRepository.listar(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(receta(2, 30), receta(3, 10)));
        when(recetaRepository.contar(isNull(), isNull())).thenReturn(45L);
        when(detalleRecetaRepository.listarPorRecetas(List.of(2, 3))).thenReturn(List.of(
                detalle(11, 2, 5, "5.000", "Harina de trigo", "kg", "3200.00"),
                detalle(12, 3, 7, "0.050", "Levadura", "g", "180.00"),
                detalle(13, 2, 9, "0.050", "Sal", "g", "180.00")));

        var r = servicio.listar(null, null, 0, 20, null);

        assertThat(r.content()).hasSize(2);
        assertThat(r.content().get(0).idReceta()).isEqualTo(2);
        assertThat(r.content().get(0).detalles())
                .extracting(DetalleRecetaResponse::idDetalleReceta)
                .containsExactly(11, 13);
        assertThat(r.content().get(0).costoTotalEstimado()).isEqualByComparingTo("16009.00");
        assertThat(r.content().get(0).costoPorUnidad()).isEqualByComparingTo("533.63");
        assertThat(r.content().get(1).idReceta()).isEqualTo(3);
        assertThat(r.content().get(1).detalles()).hasSize(1);
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(45);
        assertThat(r.totalPages()).isEqualTo(3);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recetaRepository).listar(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("r.nombre").isAscending()).isTrue();
        verify(detalleRecetaRepository, times(1)).listarPorRecetas(anyList());
        verify(detalleRecetaRepository, never()).listarPorReceta(any());
    }

    @Test
    @DisplayName("Listar cualifica con r. el orden pedido por el cliente")
    void listarCualificaElOrdenConElAliasDeLaCabecera() {
        when(recetaRepository.listar(any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(recetaRepository.contar(any(), any())).thenReturn(0L);

        servicio.listar(null, null, 0, 20, "nombre,desc");

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recetaRepository).listar(any(), any(), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("r.nombre").isDescending()).isTrue();
    }

    @Test
    @DisplayName("La lista blanca de orden admite id y cantidadProducir")
    void listarAceptaLosCamposDeLaListaBlanca() {
        when(recetaRepository.listar(any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(recetaRepository.contar(any(), any())).thenReturn(0L);

        servicio.listar(null, null, 0, 20, "id,asc");
        servicio.listar(null, null, 0, 20, "cantidadProducir,desc");

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recetaRepository, times(2)).listar(any(), any(), captor.capture());
        var pageables = captor.getAllValues();
        assertThat(pageables.get(0).getSort().getOrderFor("r.id_receta").isAscending()).isTrue();
        assertThat(pageables.get(1).getSort().getOrderFor("r.cantidad_producir").isDescending())
                .isTrue();
    }

    @Test
    @DisplayName("Listar con un campo de orden fuera de la lista blanca responde 400")
    void listarConCampoDeOrdenNoPermitidoResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, 0, 20, "estado,asc"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verify(recetaRepository, never()).listar(any(), any(), any());
        verify(recetaRepository, never()).contar(any(), any());
    }

    @Test
    @DisplayName("Obtener una receta existente devuelve su detalle y sus costos")
    void obtenerRecetaExistente() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of(
                detalle(11, 5, "5.000", "Harina de trigo", "kg", "3200.00"),
                detalle(12, 7, "0.050", "Levadura", "g", "180.00")));

        var r = servicio.obtener(2);

        assertThat(r.idReceta()).isEqualTo(2);
        assertThat(r.completa()).isTrue();
        assertThat(r.detalles()).hasSize(2);
        assertThat(r.costoTotalEstimado()).isEqualByComparingTo("16009.00");
        assertThat(r.costoPorUnidad()).isEqualByComparingTo("533.63");
    }

    @Test
    @DisplayName("Obtener una receta inexistente responde 404 y no consulta el detalle")
    void obtenerRecetaInexistenteResponde404() {
        when(recetaRepository.buscarPorId(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtener(99))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró la receta con id 99.");

        verifyNoInteractions(detalleRecetaRepository);
    }

    @Test
    @DisplayName("Consultar la receta de un producto existente devuelve su detalle")
    void porProductoExistente() {
        when(recetaRepository.buscarPorProducto(3)).thenReturn(Optional.of(receta(30)));
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of(
                detalle(11, 5, "5.000", "Harina de trigo", "kg", "3200.00")));

        var r = servicio.porProducto(3);

        assertThat(r.idReceta()).isEqualTo(2);
        assertThat(r.producto().idProducto()).isEqualTo(3);
        assertThat(r.detalles()).hasSize(1);
    }

    @Test
    @DisplayName("Consultar la receta de un producto sin receta responde 404")
    void porProductoSinRecetaResponde404() {
        when(recetaRepository.buscarPorProducto(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.porProducto(99))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la receta del producto con id 99.");

        verifyNoInteractions(detalleRecetaRepository);
    }

    @Test
    @DisplayName("Actualizar la cabecera cambia nombre, rendimiento y unidad y recalcula costos")
    void actualizarCabeceraRecalculaCostos() {
        var editada = receta(2, 15);
        editada.setNombre("Masa integral");
        editada.setIdUnidad(6);
        editada.setNombreUnidad("Kilogramo");
        editada.setAbreviaturaUnidad("kg");
        when(recetaRepository.buscarPorId(2)).thenReturn(
                Optional.of(receta(30)), Optional.of(editada));
        when(unidadMedidaRepository.findById(6))
                .thenReturn(Optional.of(unidad(6, "Kilogramo", "kg")));
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of(
                detalle(11, 5, "5.000", "Harina de trigo", "kg", "3200.00"),
                detalle(12, 7, "0.050", "Levadura", "g", "180.00")));

        var r = servicio.actualizar(2, new ActualizarRecetaRequest(
                "  Masa integral  ", new BigDecimal("15"), 6));

        assertThat(r.nombre()).isEqualTo("Masa integral");
        assertThat(r.cantidadProducir()).isEqualByComparingTo("15.00");
        assertThat(r.cantidadProducir().scale()).isEqualTo(2);
        assertThat(r.unidad().idUnidad()).isEqualTo(6);
        assertThat(r.unidad().abreviatura()).isEqualTo("kg");
        assertThat(r.producto().idProducto()).isEqualTo(3);
        assertThat(r.costoTotalEstimado()).isEqualByComparingTo("16009.00");
        assertThat(r.costoPorUnidad()).isEqualByComparingTo("1067.27");

        var captor = ArgumentCaptor.forClass(Receta.class);
        verify(recetaRepository).actualizar(captor.capture());
        assertThat(captor.getValue().getIdReceta()).isEqualTo(2);
        assertThat(captor.getValue().getNombre()).isEqualTo("Masa integral");
        assertThat(captor.getValue().getCantidadProducir()).isEqualByComparingTo("15.00");
        assertThat(captor.getValue().getCantidadProducir().scale()).isEqualTo(2);
        assertThat(captor.getValue().getIdUnidad()).isEqualTo(6);
        assertThat(captor.getValue().getIdProducto()).isEqualTo(3);
    }

    @ParameterizedTest
    @MethodSource("cantidadesProducirInvalidas")
    @DisplayName("Actualizar con cantidadProducir cero o negativa responde 400")
    void actualizarCantidadInvalidaResponde400(BigDecimal cantidad) {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));

        assertThatThrownBy(() -> servicio.actualizar(2,
                new ActualizarRecetaRequest("Masa de pan francés", cantidad, 5)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_PRODUCIR_INVALIDA");

        verify(recetaRepository, never()).actualizar(any());
        verifyNoInteractions(unidadMedidaRepository);
    }

    @Test
    @DisplayName("Actualizar con unidad inexistente responde 409 sin guardar")
    void actualizarConUnidadInexistenteResponde409() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(unidadMedidaRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizar(2,
                new ActualizarRecetaRequest("Masa de pan francés", new BigDecimal("30"), 99)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "UNIDAD_INEXISTENTE");

        verify(recetaRepository, never()).actualizar(any());
    }

    @Test
    @DisplayName("Actualizar una receta inexistente responde 404")
    void actualizarRecetaInexistenteResponde404() {
        when(recetaRepository.buscarPorId(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizar(99,
                new ActualizarRecetaRequest("Masa de pan francés", new BigDecimal("30"), 5)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la receta con id 99.");

        verifyNoInteractions(unidadMedidaRepository);
        verify(recetaRepository, never()).actualizar(any());
    }

    @Test
    @DisplayName("Cambiar estado desactiva y repetir la petición es idempotente")
    void cambiarEstadoEsIdempotente() {
        var activa = receta(30);
        var inactiva = receta(30);
        inactiva.setActivo(Boolean.FALSE);
        when(recetaRepository.buscarPorId(2)).thenReturn(
                Optional.of(activa), Optional.of(inactiva),
                Optional.of(inactiva), Optional.of(inactiva));
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of());

        var primera = servicio.cambiarEstado(2, new CambiarEstadoRequest(false));
        var segunda = servicio.cambiarEstado(2, new CambiarEstadoRequest(false));

        assertThat(primera.activo()).isFalse();
        assertThat(segunda.activo()).isFalse();
        verify(recetaRepository, times(2)).cambiarEstado(2, false);
    }

    @Test
    @DisplayName("Cambiar estado activa una receta inactiva")
    void cambiarEstadoActiva() {
        var activa = receta(30);
        when(recetaRepository.buscarPorId(2)).thenReturn(
                Optional.of(receta(30)), Optional.of(activa));
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of());

        var r = servicio.cambiarEstado(2, new CambiarEstadoRequest(true));

        assertThat(r.activo()).isTrue();
        verify(recetaRepository).cambiarEstado(2, true);
    }

    @Test
    @DisplayName("Cambiar estado de una receta inexistente responde 404")
    void cambiarEstadoRecetaInexistenteResponde404() {
        when(recetaRepository.buscarPorId(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.cambiarEstado(99, new CambiarEstadoRequest(false)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la receta con id 99.");

        verify(recetaRepository, never()).cambiarEstado(any(), anyBoolean());
    }

    @Test
    @DisplayName("Editar la cantidad del ingrediente recalcula el costo y conserva CP-21")
    void actualizarDetalleRecalculaCosto() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(detalleRecetaRepository.buscarPorId(11)).thenReturn(Optional.of(
                detalle(11, 2, 5, "4.000", "Harina de trigo", "kg", "3200.00")));
        when(detalleRecetaRepository.listarPorReceta(2)).thenReturn(List.of(
                detalle(11, 2, 5, "5.000", "Harina de trigo", "kg", "3200.00"),
                detalle(12, 2, 7, "0.050", "Levadura", "g", "180.00")));

        var r = servicio.actualizarDetalle(2, 11,
                new ActualizarDetalleRequest(new BigDecimal("5")));

        assertThat(r.detalles().get(0).cantidad()).isEqualByComparingTo("5.000");
        assertThat(r.costoTotalEstimado()).isEqualByComparingTo("16009.00");
        assertThat(r.costoPorUnidad()).isEqualByComparingTo("533.63");

        var captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(detalleRecetaRepository).actualizarCantidad(eq(11), captor.capture());
        assertThat(captor.getValue()).isEqualByComparingTo("5.000");
        assertThat(captor.getValue().scale()).isEqualTo(3);
    }

    @ParameterizedTest
    @MethodSource("cantidadesDetalleInvalidas")
    @DisplayName("Editar un detalle con cantidad inválida responde 400 sin actualizar")
    void actualizarDetalleConCantidadInvalidaResponde400SinActualizar(BigDecimal cantidad) {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(detalleRecetaRepository.buscarPorId(11)).thenReturn(Optional.of(
                detalle(11, 2, 5, "5.000", "Harina de trigo", "kg", "3200.00")));

        assertThatThrownBy(() -> servicio.actualizarDetalle(2, 11,
                new ActualizarDetalleRequest(cantidad)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "VALIDACION_FALLIDA");

        verify(detalleRecetaRepository, never()).actualizarCantidad(any(), any());
    }

    @Test
    @DisplayName("Editar un detalle de otra receta responde 404 y no actualiza")
    void actualizarDetalleDeOtraRecetaResponde404() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(detalleRecetaRepository.buscarPorId(11)).thenReturn(Optional.of(
                detalle(11, 99, 5, "5.000", "Harina de trigo", "kg", "3200.00")));

        assertThatThrownBy(() -> servicio.actualizarDetalle(2, 11,
                new ActualizarDetalleRequest(new BigDecimal("5"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la línea de detalle con id 11.");

        verify(detalleRecetaRepository, never()).actualizarCantidad(any(), any());
        verify(detalleRecetaRepository, never()).listarPorReceta(any());
    }

    @Test
    @DisplayName("Editar el detalle de una receta inexistente responde 404")
    void actualizarDetalleDeRecetaInexistenteResponde404() {
        when(recetaRepository.buscarPorId(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizarDetalle(99, 11,
                new ActualizarDetalleRequest(new BigDecimal("5"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la receta con id 99.");

        verifyNoInteractions(detalleRecetaRepository);
    }

    @Test
    @DisplayName("Eliminar un ingrediente de la receta lo borra del detalle")
    void eliminarDetalleFeliz() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(detalleRecetaRepository.buscarPorId(11)).thenReturn(Optional.of(
                detalle(11, 2, 5, "5.000", "Harina de trigo", "kg", "3200.00")));

        servicio.eliminarDetalle(2, 11);

        verify(detalleRecetaRepository).eliminar(11);
    }

    @Test
    @DisplayName("Eliminar un detalle de otra receta responde 404 y no elimina")
    void eliminarDetalleDeOtraRecetaResponde404() {
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
        when(detalleRecetaRepository.buscarPorId(11)).thenReturn(Optional.of(
                detalle(11, 99, 5, "5.000", "Harina de trigo", "kg", "3200.00")));

        assertThatThrownBy(() -> servicio.eliminarDetalle(2, 11))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la línea de detalle con id 11.");

        verify(detalleRecetaRepository, never()).eliminar(any());
    }

    private void prepararAltaValida() {
        when(productoRepository.existeActivo(3)).thenReturn(true);
        when(unidadMedidaRepository.findById(5)).thenReturn(Optional.of(unidad(5, "Unidad", "und")));
        when(recetaRepository.existePorProducto(3)).thenReturn(false);
        when(materiaPrimaRepository.existeActiva(5)).thenReturn(true);
        when(materiaPrimaRepository.existeActiva(7)).thenReturn(true);
        when(materiaPrimaRepository.existeActiva(9)).thenReturn(true);
        when(recetaRepository.insertar(any())).thenReturn(2);
        when(recetaRepository.buscarPorId(2)).thenReturn(Optional.of(receta(30)));
    }

    private static CrearRecetaRequest recetaRequest(BigDecimal cantidad,
                                                    List<AgregarDetalleRequest> detalles) {
        return new CrearRecetaRequest(3, "Masa de pan francés", cantidad, 5, detalles);
    }

    private static Stream<BigDecimal> cantidadesProducirInvalidas() {
        return Stream.of(BigDecimal.ZERO, new BigDecimal("-1.00"));
    }

    private static Stream<BigDecimal> cantidadesDetalleInvalidas() {
        return Stream.of(new BigDecimal("0.0004"), new BigDecimal("99999999.999"));
    }

    private static UnidadMedida unidad(int id, String nombre, String abreviatura) {
        var unidad = new UnidadMedida();
        unidad.setIdUnidad(id);
        unidad.setNombre(nombre);
        unidad.setAbreviatura(abreviatura);
        return unidad;
    }

    private static Receta receta(int cantidadProducir) {
        return receta(2, cantidadProducir);
    }

    private static Receta receta(int idReceta, int cantidadProducir) {
        var receta = new Receta();
        receta.setIdReceta(idReceta);
        receta.setIdProducto(3);
        receta.setNombreProducto("Pan francés");
        receta.setNombre("Masa de pan francés");
        receta.setCantidadProducir(new BigDecimal(cantidadProducir).setScale(2));
        receta.setIdUnidad(5);
        receta.setNombreUnidad("Unidad");
        receta.setAbreviaturaUnidad("und");
        receta.setActivo(Boolean.TRUE);
        return receta;
    }

    private static DetalleReceta detalle(int idDetalle, int idMateriaPrima, String cantidad,
                                         String nombreMateriaPrima, String abreviaturaUnidad,
                                         String costoUnitario) {
        return detalle(idDetalle, 2, idMateriaPrima, cantidad, nombreMateriaPrima,
                abreviaturaUnidad, costoUnitario);
    }

    private static DetalleReceta detalle(int idDetalle, int idReceta, int idMateriaPrima,
                                         String cantidad, String nombreMateriaPrima,
                                         String abreviaturaUnidad, String costoUnitario) {
        var detalle = new DetalleReceta();
        detalle.setIdDetalleReceta(idDetalle);
        detalle.setIdReceta(idReceta);
        detalle.setIdMateriaPrima(idMateriaPrima);
        detalle.setCantidad(new BigDecimal(cantidad));
        detalle.setNombreMateriaPrima(nombreMateriaPrima);
        detalle.setAbreviaturaUnidad(abreviaturaUnidad);
        detalle.setCostoUnitario(new BigDecimal(costoUnitario));
        return detalle;
    }
}
