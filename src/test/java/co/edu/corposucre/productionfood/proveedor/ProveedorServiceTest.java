package co.edu.corposucre.productionfood.proveedor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
}
