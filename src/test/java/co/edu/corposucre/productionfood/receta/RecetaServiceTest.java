package co.edu.corposucre.productionfood.receta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.producto.ProductoRepository;
import co.edu.corposucre.productionfood.receta.dto.AgregarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.CrearRecetaRequest;
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

    private static UnidadMedida unidad(int id, String nombre, String abreviatura) {
        var unidad = new UnidadMedida();
        unidad.setIdUnidad(id);
        unidad.setNombre(nombre);
        unidad.setAbreviatura(abreviatura);
        return unidad;
    }

    private static Receta receta(int cantidadProducir) {
        var receta = new Receta();
        receta.setIdReceta(2);
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
        var detalle = new DetalleReceta();
        detalle.setIdDetalleReceta(idDetalle);
        detalle.setIdReceta(2);
        detalle.setIdMateriaPrima(idMateriaPrima);
        detalle.setCantidad(new BigDecimal(cantidad));
        detalle.setNombreMateriaPrima(nombreMateriaPrima);
        detalle.setAbreviaturaUnidad(abreviaturaUnidad);
        detalle.setCostoUnitario(new BigDecimal(costoUnitario));
        return detalle;
    }
}
