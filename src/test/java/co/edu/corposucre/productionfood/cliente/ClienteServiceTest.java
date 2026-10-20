package co.edu.corposucre.productionfood.cliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;

import co.edu.corposucre.productionfood.cliente.dto.ActualizarClienteRequest;
import co.edu.corposucre.productionfood.cliente.dto.ClienteResponse;
import co.edu.corposucre.productionfood.cliente.dto.CrearClienteRequest;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;

class ClienteServiceTest {

    private ClienteRepository clienteRepository;
    private ClienteService clienteService;

    @BeforeEach
    void setUp() {
        clienteRepository = mock(ClienteRepository.class);
        clienteService = new ClienteService(clienteRepository, true);
        when(clienteRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void limpiarSeguridad() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Crear un cliente devuelve activo=true y persiste estado TRUE")
    void crearClienteValidoDevuelveActivoYPersisteEstado() {
        var r = clienteService.crear(
                new CrearClienteRequest("Ana Pérez", "ana@pf.local", "3001234567"));

        assertThat(r.nombre()).isEqualTo("Ana Pérez");
        assertThat(r.contacto()).isEqualTo("ana@pf.local");
        assertThat(r.telefono()).isEqualTo("3001234567");
        assertThat(r.activo()).isTrue();

        var captor = ArgumentCaptor.forClass(Cliente.class);
        verify(clienteRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEstado()).isTrue();
    }

    @Test
    @DisplayName("Crear recorta el nombre y deja en null el contacto o teléfono en blanco")
    void crearRecortaElNombreYAnulaContactoOTelefonoEnBlanco() {
        clienteService.crear(new CrearClienteRequest("  Ana Pérez  ", "   ", "  "));

        var captor = ArgumentCaptor.forClass(Cliente.class);
        verify(clienteRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getNombre()).isEqualTo("Ana Pérez");
        assertThat(captor.getValue().getContacto()).isNull();
        assertThat(captor.getValue().getTelefono()).isNull();
    }

    @Test
    @DisplayName("Crear guarda el nombre tal como se escribió, sin pasarlo a minúsculas")
    void crearNoPasaElNombreAMinusculas() {
        clienteService.crear(
                new CrearClienteRequest("  Ana PÉREZ  ", "ana@pf.local", "3001234567"));

        var captor = ArgumentCaptor.forClass(Cliente.class);
        verify(clienteRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getNombre()).isEqualTo("Ana PÉREZ");
    }

    @Test
    @DisplayName("Obtener un cliente existente lo convierte a ClienteResponse con activo=true")
    void obtenerClienteExistente() {
        when(clienteRepository.findById(7)).thenReturn(Optional.of(
                clienteCon(7, "Ana Pérez", "ana@pf.local", "3001234567", true)));

        var r = clienteService.obtener(7);

        assertThat(r.idCliente()).isEqualTo(7);
        assertThat(r.nombre()).isEqualTo("Ana Pérez");
        assertThat(r.contacto()).isEqualTo("ana@pf.local");
        assertThat(r.telefono()).isEqualTo("3001234567");
        assertThat(r.activo()).isTrue();
    }

    @Test
    @DisplayName("Obtener un cliente inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerClienteInexistenteResponde404() {
        when(clienteRepository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clienteService.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró el cliente con id 9999.");
    }

    @Test
    @DisplayName("Listar pagina por defecto, ordena por nombre ascendente y convierte a ClienteResponse")
    void listarPaginaPorDefectoOrdenaPorNombreYConvierte() {
        var c1 = clienteCon(1, "Ana Pérez", "ana@pf.local", "3001234567", true);
        var c2 = clienteCon(2, "Bruno Díaz", null, null, false);
        when(clienteRepository.buscar(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c1, c2),
                        org.springframework.data.domain.PageRequest.of(0, 20), 45));

        var r = clienteService.listar(null, null, 0, 20, null);

        assertThat(r.content()).extracting(ClienteResponse::nombre)
                .containsExactly("Ana Pérez", "Bruno Díaz");
        assertThat(r.content().get(0).activo()).isTrue();
        assertThat(r.content().get(1).activo()).isFalse();
        assertThat(r.content().get(1).contacto()).isNull();
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(45);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(clienteRepository).buscar(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("nombre").isAscending()).isTrue();
    }

    @Test
    @DisplayName("Listar con filtros envía el prefijo escapado y el orden pedido")
    void listarConFiltrosEnviaPrefijoYOrden() {
        when(clienteRepository.buscar(any(), eq(true), any(Pageable.class)))
                .thenReturn(Page.empty());

        clienteService.listar("  Ana  ", true, 0, 20, "nombre,desc");

        var prefijo = ArgumentCaptor.forClass(String.class);
        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(clienteRepository).buscar(prefijo.capture(), eq(true), captor.capture());
        assertThat(prefijo.getValue()).isEqualTo("ana%");
        assertThat(captor.getValue().getSort().getOrderFor("nombre").isDescending()).isTrue();
    }

    @Test
    @DisplayName("patronPrefijo devuelve null sin filtro y escapa los comodines de SQL")
    void patronPrefijoSinFiltroYConComodines() {
        assertThat(ClienteService.patronPrefijo(null)).isNull();
        assertThat(ClienteService.patronPrefijo("   ")).isNull();
        assertThat(ClienteService.patronPrefijo("100%")).isEqualTo("100\\%%");
        assertThat(ClienteService.patronPrefijo("a_b")).isEqualTo("a\\_b%");
        assertThat(ClienteService.patronPrefijo("c\\d")).isEqualTo("c\\\\d%");
    }

    @Test
    @DisplayName("Listar con size fuera de rango responde 400 PARAMETRO_INVALIDO sin consultar el repositorio")
    void listarConSizeFueraDeRangoResponde400() {
        assertThatThrownBy(() -> clienteService.listar(null, null, 0, 1000, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        assertThatThrownBy(() -> clienteService.listar(null, null, 0, 0, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        verify(clienteRepository, never()).buscar(any(), any(), any());
    }

    @Test
    @DisplayName("Listar con un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listarConCampoDeOrdenNoPermitidoResponde400() {
        assertThatThrownBy(() -> clienteService.listar(null, null, 0, 20, "estado,asc"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verify(clienteRepository, never()).buscar(any(), any(), any());
    }

    @Test
    @DisplayName("Editar reemplaza todos los campos y un contacto o teléfono nulo borra el valor anterior")
    void editarReemplazaCamposYBorraContactoOTelefonoNulos() {
        when(clienteRepository.findById(3)).thenReturn(Optional.of(
                clienteCon(3, "Nombre Viejo", "viejo@pf.local", "3000000000", true)));

        var r = clienteService.actualizar(3, new ActualizarClienteRequest(
                "Nombre Nuevo", null, null));

        assertThat(r.nombre()).isEqualTo("Nombre Nuevo");
        assertThat(r.contacto()).isNull();
        assertThat(r.telefono()).isNull();

        var captor = ArgumentCaptor.forClass(Cliente.class);
        verify(clienteRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getNombre()).isEqualTo("Nombre Nuevo");
        assertThat(captor.getValue().getContacto()).isNull();
        assertThat(captor.getValue().getTelefono()).isNull();
    }

    @Test
    @DisplayName("Editar no toca el estado: el cliente sigue activo tras la edición")
    void editarNoTocaElEstado() {
        when(clienteRepository.findById(3)).thenReturn(Optional.of(
                clienteCon(3, "Nombre Viejo", "viejo@pf.local", "3000000000", true)));

        var r = clienteService.actualizar(3, new ActualizarClienteRequest(
                "Nombre Nuevo", "nuevo@pf.local", "3001111111"));

        assertThat(r.activo()).isTrue();

        var captor = ArgumentCaptor.forClass(Cliente.class);
        verify(clienteRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEstado()).isTrue();
    }

    @Test
    @DisplayName("Editar un cliente inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void editarClienteInexistenteResponde404() {
        when(clienteRepository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clienteService.actualizar(9999,
                new ActualizarClienteRequest("Nombre Nuevo", null, null)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("Desactivar guarda activo=false")
    void desactivarGuardaActivoFalse() {
        when(clienteRepository.findById(9)).thenReturn(Optional.of(
                clienteCon(9, "Ana Pérez", "ana@pf.local", "3001234567", true)));

        var r = clienteService.cambiarEstado(9, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();

        var captor = ArgumentCaptor.forClass(Cliente.class);
        verify(clienteRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEstado()).isFalse();
    }

    @Test
    @DisplayName("Desactivar un cliente ya inactivo es idempotente")
    void desactivarClienteYaInactivoEsIdempotente() {
        when(clienteRepository.findById(9)).thenReturn(Optional.of(
                clienteCon(9, "Ana Pérez", "ana@pf.local", "3001234567", false)));

        var r = clienteService.cambiarEstado(9, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        verify(clienteRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("Reactivar un cliente no consulta los pedidos en curso")
    void reactivarClienteNoConsultaPedidosEnCurso() {
        when(clienteRepository.findById(9)).thenReturn(Optional.of(
                clienteCon(9, "Ana Pérez", "ana@pf.local", "3001234567", false)));

        var r = clienteService.cambiarEstado(9, new CambiarEstadoRequest(true));

        assertThat(r.activo()).isTrue();
        verify(clienteRepository, never()).contarPedidosEnCurso(any());
    }

    @Test
    @DisplayName("Desactivar un cliente con pedidos en curso responde 409 CLIENTE_CON_PEDIDOS_ACTIVOS sin guardar")
    void desactivarClienteConPedidosEnCursoResponde409() {
        when(clienteRepository.findById(5)).thenReturn(Optional.of(
                clienteCon(5, "Ana Pérez", "ana@pf.local", "3001234567", true)));
        when(clienteRepository.contarPedidosEnCurso(5)).thenReturn(2L);

        assertThatThrownBy(() -> clienteService.cambiarEstado(5, new CambiarEstadoRequest(false)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CLIENTE_CON_PEDIDOS_ACTIVOS");
        verify(clienteRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Con la regla desactivada, desactivar un cliente con pedidos en curso se permite y no consulta los pedidos")
    void desactivarConReglaDesactivadaIgnoraLosPedidosEnCurso() {
        var servicioSinRegla = new ClienteService(clienteRepository, false);
        when(clienteRepository.findById(5)).thenReturn(Optional.of(
                clienteCon(5, "Ana Pérez", "ana@pf.local", "3001234567", true)));
        when(clienteRepository.contarPedidosEnCurso(5)).thenReturn(2L);

        var r = servicioSinRegla.cambiarEstado(5, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        verify(clienteRepository, never()).contarPedidosEnCurso(any());
    }

    private Cliente clienteCon(int id, String nombre, String contacto,
                               String telefono, boolean activo) {
        var c = new Cliente();
        c.setIdCliente(id);
        c.setNombre(nombre);
        c.setContacto(contacto);
        c.setTelefono(telefono);
        c.setEstado(activo);
        return c;
    }
}
