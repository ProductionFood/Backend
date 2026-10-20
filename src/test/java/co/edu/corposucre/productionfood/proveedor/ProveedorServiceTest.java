package co.edu.corposucre.productionfood.proveedor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.proveedor.dto.CrearProveedorRequest;

class ProveedorServiceTest {

    private ProveedorRepository repository;
    private ProveedorService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProveedorRepository.class);
        service = new ProveedorService(repository);
        when(repository.saveAndFlush(any(Proveedor.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private Proveedor capturarGuardado() {
        var captor = ArgumentCaptor.forClass(Proveedor.class);
        verify(repository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("crear guarda con nombre recortado, estado activo y datos opcionales")
    void crearGuardaProveedorActivo() {
        var resp = service.crear(new CrearProveedorRequest(
            "  Harinas SA ", "Ana", "3001234567", "ventas@harinas.co"));

        var guardado = capturarGuardado();
        assertThat(guardado.getNombre()).isEqualTo("Harinas SA");
        assertThat(guardado.getEstado()).isTrue();
        assertThat(guardado.getContacto()).isEqualTo("Ana");
        assertThat(guardado.getTelefono()).isEqualTo("3001234567");
        assertThat(guardado.getCorreo()).isEqualTo("ventas@harinas.co");
        assertThat(resp.activo()).isTrue();
        assertThat(resp.nombre()).isEqualTo("Harinas SA");
    }

    @Test
    @DisplayName("crear guarda como null el correo, contacto y teléfono vacíos o en blanco")
    void crearNormalizaOpcionalesEnBlancoANull() {
        service.crear(new CrearProveedorRequest("Lácteos", "  ", "", ""));

        var guardado = capturarGuardado();
        assertThat(guardado.getContacto()).isNull();
        assertThat(guardado.getTelefono()).isNull();
        assertThat(guardado.getCorreo()).isNull();
    }

    @Test
    @DisplayName("crear recorta los espacios del correo")
    void crearRecortaCorreo() {
        service.crear(new CrearProveedorRequest("Lácteos", null, null, "  a@b.co  "));

        assertThat(capturarGuardado().getCorreo()).isEqualTo("a@b.co");
    }

    @Test
    @DisplayName("patronPrefijo normaliza, escapa comodines y devuelve null sin texto")
    void patronPrefijoNormaliza() {
        assertThat(ProveedorService.patronPrefijo(null)).isNull();
        assertThat(ProveedorService.patronPrefijo("  ")).isNull();
        assertThat(ProveedorService.patronPrefijo("Hari")).isEqualTo("hari%");
        assertThat(ProveedorService.patronPrefijo("%")).isEqualTo("\\%%");
        assertThat(ProveedorService.patronPrefijo("a_b")).isEqualTo("a\\_b%");
    }

    @Test
    @DisplayName("listar rechaza campo de orden no permitido y paginación fuera de rango")
    void listarRechazaParametrosInvalidos() {
        assertThatThrownBy(() -> service.listar(null, null, 0, 10, "correo,asc"))
            .isInstanceOfSatisfying(ParametroInvalidoException.class,
                e -> assertThat(e.getCodigo()).isEqualTo("CAMPO_ORDEN_INVALIDO"));
        assertThatThrownBy(() -> service.listar(null, null, 0, 101, null))
            .isInstanceOfSatisfying(ParametroInvalidoException.class,
                e -> assertThat(e.getCodigo()).isEqualTo("PARAMETRO_INVALIDO"));
        assertThatThrownBy(() -> service.listar(null, null, -1, 10, null))
            .isInstanceOfSatisfying(ParametroInvalidoException.class,
                e -> assertThat(e.getCodigo()).isEqualTo("PARAMETRO_INVALIDO"));
    }

    @Test
    @DisplayName("listar mapea el contenido, conserva el total y ordena por nombre ascendente")
    void listarMapeaYOrdenaPorDefecto() {
        var p = new Proveedor();
        p.setIdProveedor(7);
        p.setNombre("Harinas SA");
        p.setEstado(true);
        when(repository.buscar(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(p)));

        var resp = service.listar(null, null, 0, 10, null);

        assertThat(resp.content()).hasSize(1);
        assertThat(resp.content().get(0).idProveedor()).isEqualTo(7);
        assertThat(resp.totalElements()).isEqualTo(1);
        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).buscar(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "nombre"));
    }
}
