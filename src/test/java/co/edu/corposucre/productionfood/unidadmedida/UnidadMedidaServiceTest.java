package co.edu.corposucre.productionfood.unidadmedida;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.unidadmedida.dto.ActualizarUnidadRequest;
import co.edu.corposucre.productionfood.unidadmedida.dto.CrearUnidadRequest;

class UnidadMedidaServiceTest {

    private UnidadMedidaRepository repository;
    private UnidadMedidaService service;

    @BeforeEach
    void setUp() {
        repository = mock(UnidadMedidaRepository.class);
        service = new UnidadMedidaService(repository);
        when(repository.saveAndFlush(any(UnidadMedida.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Construye una fila de proyección con los conteos de uso de sus tres tablas.
     */
    private UnidadMedidaConUsos fila(Integer id, String nombre, String abreviatura,
            long enMaterias, long enProductos, long enRecetas) {
        var f = mock(UnidadMedidaConUsos.class);
        when(f.getIdUnidad()).thenReturn(id);
        when(f.getNombre()).thenReturn(nombre);
        when(f.getAbreviatura()).thenReturn(abreviatura);
        when(f.getEnMateriasPrimas()).thenReturn(Long.valueOf(enMaterias));
        when(f.getEnProductos()).thenReturn(Long.valueOf(enProductos));
        when(f.getEnRecetas()).thenReturn(Long.valueOf(enRecetas));
        return f;
    }

    private UnidadMedida capturarGuardado() {
        var captor = ArgumentCaptor.forClass(UnidadMedida.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private UnidadMedida entidad(Integer id, String nombre, String abreviatura) {
        var unidad = new UnidadMedida();
        unidad.setIdUnidad(id);
        unidad.setNombre(nombre);
        unidad.setAbreviatura(abreviatura);
        return unidad;
    }

    @Test
    @DisplayName("crear recorta el nombre y la abreviatura antes de guardar")
    void crearRecortaNombreYAbreviatura() {
        var resp = service.crear(new CrearUnidadRequest("  Kilogramo  ", "  kg  "));

        var guardado = capturarGuardado();
        assertThat(guardado.getNombre()).isEqualTo("Kilogramo");
        assertThat(guardado.getAbreviatura()).isEqualTo("kg");
        assertThat(resp.nombre()).isEqualTo("Kilogramo");
        assertThat(resp.abreviatura()).isEqualTo("kg");
    }

    @Test
    @DisplayName("crear conserva las mayúsculas de la abreviatura")
    void crearConservaMayusculasDeAbreviatura() {
        service.crear(new CrearUnidadRequest("Kilogramo", "KG"));

        assertThat(capturarGuardado().getAbreviatura()).isEqualTo("KG");
    }

    @Test
    @DisplayName("crear rechaza una abreviatura duplicada sin guardar la unidad")
    void crearRechazaAbreviaturaDuplicada() {
        when(repository.existsByAbreviatura("kg")).thenReturn(true);

        assertThatThrownBy(() -> service.crear(new CrearUnidadRequest("Kilo", "kg")))
            .isInstanceOf(ConflictoNegocioException.class)
            .extracting(e -> ((ConflictoNegocioException) e).getCodigo())
            .isEqualTo("ABREVIATURA_DUPLICADA");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("crear traduce la DuplicateKeyException de la base a conflicto de negocio")
    void crearTraduceDuplicateKeyException() {
        when(repository.saveAndFlush(any(UnidadMedida.class)))
            .thenThrow(new DuplicateKeyException("abreviatura"));

        assertThatThrownBy(() -> service.crear(new CrearUnidadRequest("Kilogramo", "kg")))
            .isInstanceOf(ConflictoNegocioException.class)
            .extracting(e -> ((ConflictoNegocioException) e).getCodigo())
            .isEqualTo("ABREVIATURA_DUPLICADA");
    }

    @Test
    @DisplayName("crear devuelve los conteos de uso en cero")
    void crearDevuelveUsosEnCero() {
        var resp = service.crear(new CrearUnidadRequest("Kilogramo", "kg"));

        assertThat(resp.usos().enMateriasPrimas()).isZero();
        assertThat(resp.usos().enProductos()).isZero();
        assertThat(resp.usos().enRecetas()).isZero();
        assertThat(resp.usos().total()).isZero();
    }

    @Test
    @DisplayName("listar devuelve el catálogo completo con sus conteos de uso")
    void listarDevuelveCatalogoConConteos() {
        var catalogo = fila(1, "Kilogramo", "kg", 12, 5, 0);
        when(repository.listarConUsos()).thenReturn(List.of(catalogo));

        var resp = service.listar();

        assertThat(resp).hasSize(1);
        var unidad = resp.get(0);
        assertThat(unidad.idUnidad()).isEqualTo(1);
        assertThat(unidad.nombre()).isEqualTo("Kilogramo");
        assertThat(unidad.abreviatura()).isEqualTo("kg");
        assertThat(unidad.usos().enMateriasPrimas()).isEqualTo(12);
        assertThat(unidad.usos().enProductos()).isEqualTo(5);
        assertThat(unidad.usos().enRecetas()).isZero();
        assertThat(unidad.usos().total()).isEqualTo(17);
    }

    @Test
    @DisplayName("listar devuelve una lista vacía cuando el catálogo no tiene unidades")
    void listarDevuelveListaVacia() {
        when(repository.listarConUsos()).thenReturn(List.of());

        assertThat(service.listar()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("obtener devuelve la unidad con el total de sus usos")
    void obtenerDevuelveUnidadConUsos() {
        var unidad = fila(1, "Kilogramo", "kg", 3, 1, 2);
        when(repository.buscarConUsos(1)).thenReturn(Optional.of(unidad));

        var resp = service.obtener(1);

        assertThat(resp.idUnidad()).isEqualTo(1);
        assertThat(resp.nombre()).isEqualTo("Kilogramo");
        assertThat(resp.abreviatura()).isEqualTo("kg");
        assertThat(resp.usos().enMateriasPrimas()).isEqualTo(3);
        assertThat(resp.usos().enProductos()).isEqualTo(1);
        assertThat(resp.usos().enRecetas()).isEqualTo(2);
        assertThat(resp.usos().total()).isEqualTo(6);
    }

    @Test
    @DisplayName("obtener una unidad inexistente lanza RecursoNoEncontradoException")
    void obtenerInexistenteLanzaNoEncontrado() {
        when(repository.buscarConUsos(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(9999))
            .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("actualizar una unidad inexistente lanza RecursoNoEncontradoException")
    void actualizarInexistenteLanzaNoEncontrado() {
        when(repository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(9999,
                new ActualizarUnidadRequest("Kilogramo", "kg"), false))
            .isInstanceOf(RecursoNoEncontradoException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("actualizar el nombre de una unidad en uso sin cambiar la abreviatura")
    void actualizarNombreSinCambiarAbreviaturaDeUnidadEnUso() {
        when(repository.findById(1)).thenReturn(Optional.of(entidad(1, "Kilo", "kg")));
        var usos = fila(1, "Kilo", "kg", 4, 2, 1);
        when(repository.buscarConUsos(1)).thenReturn(Optional.of(usos));

        service.actualizar(1, new ActualizarUnidadRequest("Kilogramo", "kg"), false);

        var guardado = capturarGuardado();
        assertThat(guardado.getNombre()).isEqualTo("Kilogramo");
        assertThat(guardado.getAbreviatura()).isEqualTo("kg");
        verify(repository, never()).existsByAbreviaturaAndIdUnidadNot(any(), any());
    }

    @Test
    @DisplayName("actualizar la abreviatura de una unidad no usada se guarda sin conflicto")
    void actualizarAbreviaturaDeUnidadNoUsada() {
        when(repository.findById(1)).thenReturn(Optional.of(entidad(1, "Kilogramo", "kg")));
        when(repository.existsByAbreviaturaAndIdUnidadNot("kgs", 1)).thenReturn(false);
        var sinUsos = fila(1, "Kilogramo", "kg", 0, 0, 0);
        when(repository.buscarConUsos(1)).thenReturn(Optional.of(sinUsos));

        service.actualizar(1, new ActualizarUnidadRequest("Kilogramo", "kgs"), false);

        assertThat(capturarGuardado().getAbreviatura()).isEqualTo("kgs");
    }

    @Test
    @DisplayName("actualizar la abreviatura de una unidad en uso exige confirmación explícita")
    void actualizarAbreviaturaDeUnidadEnUsoExigeConfirmacion() {
        when(repository.findById(1)).thenReturn(Optional.of(entidad(1, "Kilogramo", "kg")));
        when(repository.existsByAbreviaturaAndIdUnidadNot("kgs", 1)).thenReturn(false);
        var enUso = fila(1, "Kilogramo", "kg", 12, 5, 0);
        when(repository.buscarConUsos(1)).thenReturn(Optional.of(enUso));

        assertThatThrownBy(() -> service.actualizar(1,
                new ActualizarUnidadRequest("Kilogramo", "kgs"), false))
            .isInstanceOf(ConflictoNegocioException.class)
            .hasMessageContaining("12")
            .hasMessageContaining("5")
            .hasMessageContaining("confirmar=true")
            .extracting(e -> ((ConflictoNegocioException) e).getCodigo())
            .isEqualTo("UNIDAD_EN_USO");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("actualizar la abreviatura de una unidad en uso con confirmar=true "
            + "guarda el cambio")
    void actualizarAbreviaturaDeUnidadEnUsoConConfirmacion() {
        when(repository.findById(1)).thenReturn(Optional.of(entidad(1, "Kilogramo", "kg")));
        when(repository.existsByAbreviaturaAndIdUnidadNot("kgs", 1)).thenReturn(false);
        var enUso = fila(1, "Kilogramo", "kgs", 12, 5, 0);
        when(repository.buscarConUsos(1)).thenReturn(Optional.of(enUso));

        service.actualizar(1, new ActualizarUnidadRequest("Kilogramo", "kgs"), true);

        assertThat(capturarGuardado().getAbreviatura()).isEqualTo("kgs");
    }

    @Test
    @DisplayName("actualizar a una abreviatura que ya existe en otra unidad se rechaza")
    void actualizarAbreviaturaDuplicadaDeOtraUnidad() {
        when(repository.findById(1)).thenReturn(Optional.of(entidad(1, "Kilogramo", "kgs")));
        when(repository.existsByAbreviaturaAndIdUnidadNot("kg", 1)).thenReturn(true);

        assertThatThrownBy(() -> service.actualizar(1,
                new ActualizarUnidadRequest("Kilogramo", "kg"), false))
            .isInstanceOf(ConflictoNegocioException.class)
            .extracting(e -> ((ConflictoNegocioException) e).getCodigo())
            .isEqualTo("ABREVIATURA_DUPLICADA");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("actualizar traduce la DuplicateKeyException de la base a conflicto de negocio")
    void actualizarTraduceDuplicateKeyException() {
        when(repository.findById(1)).thenReturn(Optional.of(entidad(1, "Kilogramo", "kgs")));
        when(repository.existsByAbreviaturaAndIdUnidadNot("kg", 1)).thenReturn(false);
        var sinUsos = fila(1, "Kilogramo", "kg", 0, 0, 0);
        when(repository.buscarConUsos(1)).thenReturn(Optional.of(sinUsos));
        when(repository.saveAndFlush(any(UnidadMedida.class)))
            .thenThrow(new DuplicateKeyException("abreviatura"));

        assertThatThrownBy(() -> service.actualizar(1,
                new ActualizarUnidadRequest("Kilogramo", "kg"), false))
            .isInstanceOf(ConflictoNegocioException.class)
            .extracting(e -> ((ConflictoNegocioException) e).getCodigo())
            .isEqualTo("ABREVIATURA_DUPLICADA");
    }
}
