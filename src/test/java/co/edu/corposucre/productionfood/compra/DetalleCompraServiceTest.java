package co.edu.corposucre.productionfood.compra;

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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.compra.dto.ActualizarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.AgregarDetalleCompraRequest;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;

class DetalleCompraServiceTest {

    private static final Integer ID_COMPRA = 7;
    private static final Integer ID_PROVEEDOR = 3;
    private static final Integer ID_MATERIA_PRIMA = 5;

    private CompraRepository compraRepository;
    private DetalleCompraRepository detalleCompraRepository;
    private MateriaPrimaRepository materiaPrimaRepository;
    private DetalleCompraService service;

    @BeforeEach
    void setUp() {
        compraRepository = mock(CompraRepository.class);
        detalleCompraRepository = mock(DetalleCompraRepository.class);
        materiaPrimaRepository = mock(MateriaPrimaRepository.class);
        service = new DetalleCompraService(compraRepository, detalleCompraRepository,
                materiaPrimaRepository);
    }

    private void dadoQueExisteCompra(Integer idCompra, EstadoCompra estado) {
        when(compraRepository.buscarPorId(idCompra)).thenReturn(Optional.of(compra(idCompra,
                estado)));
    }

    private static Compra compra(Integer id, EstadoCompra estado) {
        var compra = new Compra();
        compra.setIdCompra(id);
        compra.setIdProveedor(ID_PROVEEDOR);
        compra.setFecha(LocalDate.now());
        compra.setEstado(estado);
        return compra;
    }

    private static DetalleCompra linea(Integer idDetalle, Integer idCompra,
                                       Integer idMateriaPrima, String cantidad, String precio,
                                       String nombre, String unidad) {
        return new DetalleCompra(idDetalle, idCompra, idMateriaPrima, new BigDecimal(cantidad),
                new BigDecimal(precio), nombre, unidad);
    }

    @Test
    @DisplayName("CP-12/CP-16: listar devuelve total, ítems, estado y editable")
    void listarDevuelveElTotalLosItemsElEstadoYEditable() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(detalleCompraRepository.listarPorCompra(ID_COMPRA)).thenReturn(List.of(
                linea(1, ID_COMPRA, 5, "100.00", "3200.00", "Harina de trigo", "kg"),
                linea(2, ID_COMPRA, 6, "50.00", "3200.00", "Azúcar", "kg")));
        when(detalleCompraRepository.calcularTotal(ID_COMPRA))
                .thenReturn(new BigDecimal("480000.00"));

        var r = service.listarDetalles(ID_COMPRA);

        assertThat(r.idCompra()).isEqualTo(ID_COMPRA);
        assertThat(r.estado()).isEqualTo("PENDIENTE");
        assertThat(r.editable()).isTrue();
        assertThat(r.detalles()).hasSize(2);
        assertThat(r.cantidadItems()).isEqualTo(2);
        // R-03: el total viene del SUM de la base, redondeado a escala 2, no de sumar subtotales.
        assertThat(r.totalCompra()).isEqualByComparingTo("480000.00");
        assertThat(r.totalCompra().scale()).isEqualTo(2);
        verify(detalleCompraRepository).calcularTotal(ID_COMPRA);

        var primera = r.detalles().get(0);
        assertThat(primera.idDetalleCompra()).isEqualTo(1);
        assertThat(primera.materiaPrima().nombre()).isEqualTo("Harina de trigo");
        assertThat(primera.materiaPrima().unidad()).isEqualTo("kg");
        assertThat(primera.subtotal()).isEqualByComparingTo("320000.00");
        // El total agregado del listado viaja una sola vez, en la respuesta de lista.
        assertThat(primera.totalCompra()).isNull();
    }

    @Test
    @DisplayName("CP-02: listar el detalle de una compra inexistente responde COMPRA_INEXISTENTE")
    void listarUnaCompraInexistenteResponde404() {
        when(compraRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listarDetalles(9999))
                .isInstanceOf(CompraInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "COMPRA_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND)
                .hasMessage("No se encontró la compra con id 9999.");

        verifyNoInteractions(detalleCompraRepository, materiaPrimaRepository);
    }

    @Test
    @DisplayName("CP-17: listar una compra sin líneas devuelve lista vacía y total 0.00")
    void listarUnaCompraSinLineasDevuelveVacioYTotalCero() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(detalleCompraRepository.listarPorCompra(ID_COMPRA)).thenReturn(List.of());
        when(detalleCompraRepository.calcularTotal(ID_COMPRA)).thenReturn(BigDecimal.ZERO);

        var r = service.listarDetalles(ID_COMPRA);

        assertThat(r.detalles()).isEmpty();
        assertThat(r.cantidadItems()).isZero();
        assertThat(r.totalCompra()).isEqualByComparingTo("0.00");
        assertThat(r.totalCompra().scale()).isEqualTo(2);
        assertThat(r.editable()).isTrue();
    }

    @Test
    @DisplayName("listar una compra RECIBIDA se consulta sin error pero no es editable")
    void listarUnaCompraRecibidaNoEsEditable() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.RECIBIDA);
        when(detalleCompraRepository.listarPorCompra(ID_COMPRA)).thenReturn(List.of(
                linea(1, ID_COMPRA, 5, "100.00", "3200.00", "Harina de trigo", "kg")));
        when(detalleCompraRepository.calcularTotal(ID_COMPRA))
                .thenReturn(new BigDecimal("320000.00"));

        var r = service.listarDetalles(ID_COMPRA);

        // R-01 no alcanza a la consulta: recibir no impide leer el detalle, solo escribirlo.
        assertThat(r.estado()).isEqualTo("RECIBIDA");
        assertThat(r.editable()).isFalse();
        assertThat(r.cantidadItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("CP-01/CP-11: agregar inserta la línea y devuelve subtotal y total recalculado")
    void agregarInsertaLaLineaYDevuelveSubtotalYTotal() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(detalleCompraRepository.existeMateriaPrimaEnCompra(ID_COMPRA,
                ID_MATERIA_PRIMA)).thenReturn(false);
        when(detalleCompraRepository.insertar(any(DetalleCompra.class))).thenReturn(28);
        when(detalleCompraRepository.obtenerPorId(28)).thenReturn(Optional.of(
                linea(28, ID_COMPRA, ID_MATERIA_PRIMA, "100.00", "3200.00",
                        "Harina de trigo", "kg")));
        when(detalleCompraRepository.calcularTotal(ID_COMPRA))
                .thenReturn(new BigDecimal("320000.00"));

        var r = service.agregarDetalle(ID_COMPRA, new AgregarDetalleCompraRequest(
                ID_MATERIA_PRIMA, new BigDecimal("100.00"), new BigDecimal("3200.00")));

        var captor = ArgumentCaptor.forClass(DetalleCompra.class);
        verify(detalleCompraRepository).insertar(captor.capture());
        var escrita = captor.getValue();
        assertThat(escrita.idCompra()).isEqualTo(ID_COMPRA);
        assertThat(escrita.idMateriaPrima()).isEqualTo(ID_MATERIA_PRIMA);
        assertThat(escrita.cantidad()).isEqualByComparingTo("100.00");
        assertThat(escrita.cantidad().scale()).isEqualTo(2);
        assertThat(escrita.precioUnitario()).isEqualByComparingTo("3200.00");

        assertThat(r.idDetalleCompra()).isEqualTo(28);
        assertThat(r.materiaPrima().idMateriaPrima()).isEqualTo(ID_MATERIA_PRIMA);
        assertThat(r.materiaPrima().nombre()).isEqualTo("Harina de trigo");
        assertThat(r.materiaPrima().unidad()).isEqualTo("kg");
        assertThat(r.subtotal()).isEqualByComparingTo("320000.00");
        assertThat(r.subtotal().scale()).isEqualTo(2);
        // El total se recalcula desde el repositorio en la misma respuesta (tarea, paso 6).
        verify(detalleCompraRepository).calcularTotal(ID_COMPRA);
        assertThat(r.totalCompra()).isEqualByComparingTo("320000.00");
        assertThat(r.totalCompra().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("CP-02: agregar a una compra inexistente responde 404 COMPRA_INEXISTENTE")
    void agregarAUnaCompraInexistenteResponde404() {
        when(compraRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.agregarDetalle(9999, new AgregarDetalleCompraRequest(
                ID_MATERIA_PRIMA, new BigDecimal("1.00"), BigDecimal.ZERO)))
                .isInstanceOf(CompraInexistenteException.class)
                .hasFieldOrPropertyWithValue("codigo", "COMPRA_INEXISTENTE")
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        verifyNoInteractions(detalleCompraRepository, materiaPrimaRepository);
    }

    @Test
    @DisplayName("CP-03/R-01: agregar a una compra RECIBIDA responde 409 COMPRA_NO_EDITABLE")
    void agregarAUnaCompraRecibidaResponde409() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.RECIBIDA);

        assertThatThrownBy(() -> service.agregarDetalle(ID_COMPRA,
                new AgregarDetalleCompraRequest(ID_MATERIA_PRIMA, new BigDecimal("100.00"),
                        new BigDecimal("3200.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "COMPRA_NO_EDITABLE");

        // El estado se valida antes de mirar la materia prima o escribir la línea.
        verifyNoInteractions(detalleCompraRepository, materiaPrimaRepository);
    }

    @Test
    @DisplayName("CP-06: agregar con materia prima inexistente o inactiva responde 409")
    void agregarConMateriaPrimaInexistenteResponde409() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(materiaPrimaRepository.existeActiva(9999)).thenReturn(false);

        assertThatThrownBy(() -> service.agregarDetalle(ID_COMPRA,
                new AgregarDetalleCompraRequest(9999, new BigDecimal("100.00"),
                        new BigDecimal("3200.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_INEXISTENTE");

        verify(detalleCompraRepository, never()).existeMateriaPrimaEnCompra(any(), any());
        verify(detalleCompraRepository, never()).insertar(any(DetalleCompra.class));
    }

    @Test
    @DisplayName("CP-18/R-02: agregar una materia prima ya presente responde 409 sin insertar")
    void agregarMateriaPrimaDuplicadaResponde409SinInsertar() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(detalleCompraRepository.existeMateriaPrimaEnCompra(ID_COMPRA,
                ID_MATERIA_PRIMA)).thenReturn(true);

        assertThatThrownBy(() -> service.agregarDetalle(ID_COMPRA,
                new AgregarDetalleCompraRequest(ID_MATERIA_PRIMA, new BigDecimal("100.00"),
                        new BigDecimal("3200.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_DUPLICADA")
                .hasMessageContaining("edite la línea existente");

        verify(detalleCompraRepository, never()).insertar(any(DetalleCompra.class));
    }

    @Test
    @DisplayName("CP-19: una carrera que viola uk_detalle_compra se traduce al mismo 409")
    void agregarConDuplicateKeyResponde409() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(detalleCompraRepository.existeMateriaPrimaEnCompra(ID_COMPRA,
                ID_MATERIA_PRIMA)).thenReturn(false);
        when(detalleCompraRepository.insertar(any(DetalleCompra.class)))
                .thenThrow(new DuplicateKeyException("uk_detalle_compra"));

        assertThatThrownBy(() -> service.agregarDetalle(ID_COMPRA,
                new AgregarDetalleCompraRequest(ID_MATERIA_PRIMA, new BigDecimal("100.00"),
                        new BigDecimal("3200.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_DUPLICADA");
    }

    @Test
    @DisplayName("CP-13/R-03: 33.33 × 3333.33 se informa como 111099.89 con HALF_UP")
    void agregarConDecimalesRedondeaElTotalAHalfUp() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(detalleCompraRepository.existeMateriaPrimaEnCompra(ID_COMPRA,
                ID_MATERIA_PRIMA)).thenReturn(false);
        when(detalleCompraRepository.insertar(any(DetalleCompra.class))).thenReturn(30);
        when(detalleCompraRepository.obtenerPorId(30)).thenReturn(Optional.of(
                linea(30, ID_COMPRA, ID_MATERIA_PRIMA, "33.33", "3333.33",
                        "Harina de trigo", "kg")));
        // Así devuelve MySQL el SUM: el producto de dos decimal(10,2) tiene escala 4.
        when(detalleCompraRepository.calcularTotal(ID_COMPRA))
                .thenReturn(new BigDecimal("111099.8889"));

        var r = service.agregarDetalle(ID_COMPRA, new AgregarDetalleCompraRequest(
                ID_MATERIA_PRIMA, new BigDecimal("33.33"), new BigDecimal("3333.33")));

        // CP-13: si un total llega como 111099.8889 o como double, la cifra no es la del servidor.
        assertThat(r.subtotal()).isEqualByComparingTo("111099.89");
        assertThat(r.subtotal().scale()).isEqualTo(2);
        assertThat(r.totalCompra()).isEqualByComparingTo("111099.89");
        assertThat(r.totalCompra().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("R-05: una cantidad que redondea a cero responde 400 CANTIDAD_INVALIDA")
    void agregarConCantidadQueRedondeaACeroResponde400() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(detalleCompraRepository.existeMateriaPrimaEnCompra(ID_COMPRA,
                ID_MATERIA_PRIMA)).thenReturn(false);

        assertThatThrownBy(() -> service.agregarDetalle(ID_COMPRA,
                new AgregarDetalleCompraRequest(ID_MATERIA_PRIMA, new BigDecimal("0.001"),
                        BigDecimal.ZERO)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CANTIDAD_INVALIDA");

        verify(detalleCompraRepository, never()).insertar(any(DetalleCompra.class));
    }

    @Test
    @DisplayName("CP-14: actualizar cambia cantidad y precio sin tocar la materia prima")
    void actualizarCambiaCantidadYPrecioYRecalculaElTotal() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(detalleCompraRepository.obtenerPorId(3)).thenReturn(Optional.of(
                linea(3, ID_COMPRA, ID_MATERIA_PRIMA, "100.00", "3200.00",
                        "Harina de trigo", "kg")));
        when(detalleCompraRepository.calcularTotal(ID_COMPRA))
                .thenReturn(new BigDecimal("480000.00"));

        var r = service.actualizarDetalle(ID_COMPRA, 3, new ActualizarDetalleCompraRequest(
                new BigDecimal("150.00"), new BigDecimal("3200.00")));

        var captor = ArgumentCaptor.forClass(DetalleCompra.class);
        verify(detalleCompraRepository).actualizar(captor.capture());
        var escrita = captor.getValue();
        assertThat(escrita.idDetalle()).isEqualTo(3);
        assertThat(escrita.cantidad()).isEqualByComparingTo("150.00");
        assertThat(escrita.precioUnitario()).isEqualByComparingTo("3200.00");
        // R-02: la materia prima de la línea se conserva; la edición no la reemplaza.
        assertThat(escrita.idMateriaPrima()).isEqualTo(ID_MATERIA_PRIMA);

        assertThat(r.idDetalleCompra()).isEqualTo(3);
        assertThat(r.materiaPrima().nombre()).isEqualTo("Harina de trigo");
        assertThat(r.cantidad()).isEqualByComparingTo("150.00");
        assertThat(r.subtotal()).isEqualByComparingTo("480000.00");
        verify(detalleCompraRepository).calcularTotal(ID_COMPRA);
        assertThat(r.totalCompra()).isEqualByComparingTo("480000.00");
        assertThat(r.totalCompra().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("CP-04/R-01: editar una línea de una compra RECIBIDA responde 409 sin escribir")
    void actualizarEnUnaCompraRecibidaResponde409() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.RECIBIDA);

        assertThatThrownBy(() -> service.actualizarDetalle(ID_COMPRA, 3,
                new ActualizarDetalleCompraRequest(new BigDecimal("150.00"),
                        new BigDecimal("3200.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "COMPRA_NO_EDITABLE");

        verifyNoInteractions(detalleCompraRepository, materiaPrimaRepository);
    }

    @Test
    @DisplayName("actualizar una línea inexistente responde 404 sin escribir")
    void actualizarUnaLineaInexistenteResponde404() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(detalleCompraRepository.obtenerPorId(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizarDetalle(ID_COMPRA, 99,
                new ActualizarDetalleCompraRequest(new BigDecimal("150.00"),
                        new BigDecimal("3200.00"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró la línea de detalle con id 99.");

        verify(detalleCompraRepository, never()).actualizar(any(DetalleCompra.class));
        verify(detalleCompraRepository, never()).calcularTotal(any());
    }

    @Test
    @DisplayName("actualizar una línea de otra compra responde 404 y no la modifica")
    void actualizarUnaLineaDeOtraCompraResponde404SinEscribir() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(detalleCompraRepository.obtenerPorId(3)).thenReturn(Optional.of(
                linea(3, 8, ID_MATERIA_PRIMA, "100.00", "3200.00", "Harina de trigo", "kg")));

        assertThatThrownBy(() -> service.actualizarDetalle(ID_COMPRA, 3,
                new ActualizarDetalleCompraRequest(new BigDecimal("150.00"),
                        new BigDecimal("3200.00"))))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");

        verify(detalleCompraRepository, never()).actualizar(any(DetalleCompra.class));
    }

    @Test
    @DisplayName("CP-15: eliminar quita la línea de la compra")
    void eliminarQuitaLaLineaDeLaCompra() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(detalleCompraRepository.obtenerPorId(3)).thenReturn(Optional.of(
                linea(3, ID_COMPRA, ID_MATERIA_PRIMA, "100.00", "3200.00",
                        "Harina de trigo", "kg")));

        service.eliminarDetalle(ID_COMPRA, 3);

        verify(detalleCompraRepository).eliminar(3);
    }

    @Test
    @DisplayName("CP-05/R-01: eliminar una línea de una compra RECIBIDA responde 409 sin borrar")
    void eliminarEnUnaCompraRecibidaResponde409() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.RECIBIDA);

        assertThatThrownBy(() -> service.eliminarDetalle(ID_COMPRA, 3))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "COMPRA_NO_EDITABLE");

        // El borrado es el punto donde R-01 se olvida más fácil: aquí tampoco se toca la línea.
        verifyNoInteractions(detalleCompraRepository, materiaPrimaRepository);
    }

    @Test
    @DisplayName("eliminar una línea inexistente responde 404 sin borrar nada")
    void eliminarUnaLineaInexistenteResponde404() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(detalleCompraRepository.obtenerPorId(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.eliminarDetalle(ID_COMPRA, 99))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró la línea de detalle con id 99.");

        verify(detalleCompraRepository, never()).eliminar(any());
    }

    @Test
    @DisplayName("eliminar una línea de otra compra responde 404 y no la borra")
    void eliminarUnaLineaDeOtraCompraResponde404() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(detalleCompraRepository.obtenerPorId(3)).thenReturn(Optional.of(
                linea(3, 8, ID_MATERIA_PRIMA, "100.00", "3200.00", "Harina de trigo", "kg")));

        assertThatThrownBy(() -> service.eliminarDetalle(ID_COMPRA, 3))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");

        verify(detalleCompraRepository, never()).eliminar(any());
    }

    @Test
    @DisplayName("R-04: el servicio nunca escribe materias_primas, solo consulta si está activa")
    void elServicioNoTocaElCostoDeReferencia() {
        dadoQueExisteCompra(ID_COMPRA, EstadoCompra.PENDIENTE);
        when(materiaPrimaRepository.existeActiva(ID_MATERIA_PRIMA)).thenReturn(true);
        when(detalleCompraRepository.existeMateriaPrimaEnCompra(ID_COMPRA,
                ID_MATERIA_PRIMA)).thenReturn(false);
        when(detalleCompraRepository.insertar(any(DetalleCompra.class))).thenReturn(28);
        when(detalleCompraRepository.obtenerPorId(28)).thenReturn(Optional.of(
                linea(28, ID_COMPRA, ID_MATERIA_PRIMA, "100.00", "3500.00",
                        "Harina de trigo", "kg")));
        when(detalleCompraRepository.calcularTotal(ID_COMPRA))
                .thenReturn(new BigDecimal("350000.00"));

        service.agregarDetalle(ID_COMPRA, new AgregarDetalleCompraRequest(
                ID_MATERIA_PRIMA, new BigDecimal("100.00"), new BigDecimal("3500.00")));

        // R-04: la única llamada a materias_primas es la comprobación de actividad; el costo de
        // referencia jamás se escribe desde este servicio, aunque el precio pagado sea otro.
        verify(materiaPrimaRepository).existeActiva(ID_MATERIA_PRIMA);
        verify(materiaPrimaRepository, never()).save(any());
        verify(detalleCompraRepository).insertar(any(DetalleCompra.class));
    }
}
