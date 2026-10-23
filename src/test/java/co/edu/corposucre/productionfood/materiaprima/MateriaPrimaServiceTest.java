package co.edu.corposucre.productionfood.materiaprima;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioRepository;
import co.edu.corposucre.productionfood.materiaprima.dto.ActualizarMateriaPrimaRequest;
import co.edu.corposucre.productionfood.materiaprima.dto.CrearMateriaPrimaRequest;
import co.edu.corposucre.productionfood.materiaprima.dto.MateriaPrimaResponse;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedida;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedidaRepository;

class MateriaPrimaServiceTest {

    private MateriaPrimaRepository materiaPrimaRepository;
    private InventarioRepository inventarioRepository;
    private UnidadMedidaRepository unidadMedidaRepository;
    private MateriaPrimaService servicio;

    @BeforeEach
    void setUp() {
        materiaPrimaRepository = mock(MateriaPrimaRepository.class);
        inventarioRepository = mock(InventarioRepository.class);
        unidadMedidaRepository = mock(UnidadMedidaRepository.class);
        servicio = new MateriaPrimaService(materiaPrimaRepository, inventarioRepository,
                unidadMedidaRepository, true);
        // El id solo existe tras el flush: el mock simula la asignación del IDENTITY para
        // poder verificar que crearFilaInicial recibe el id de la materia prima creada.
        when(materiaPrimaRepository.saveAndFlush(any())).thenAnswer(inv -> {
            var materiaPrima = inv.getArgument(0, MateriaPrima.class);
            materiaPrima.setIdMateriaPrima(11);
            return materiaPrima;
        });
    }

    @Test
    @DisplayName("Crear fija estado TRUE, crea la fila de inventario con el id nuevo y responde con stock 0")
    void crearFijaEstadoCreaInventarioYRespondeConStockCero() {
        when(unidadMedidaRepository.findById(1))
                .thenReturn(Optional.of(unidad(1, "Kilogramo", "kg")));

        var r = servicio.crear(new CrearMateriaPrimaRequest(
                "  Harina de trigo  ", 1, new BigDecimal("2.50"), new BigDecimal("3500.00")));

        assertThat(r.idMateriaPrima()).isEqualTo(11);
        assertThat(r.nombre()).isEqualTo("Harina de trigo");
        assertThat(r.activo()).isTrue();
        assertThat(r.cantidadDisponible()).isZero();
        assertThat(r.unidad().idUnidad()).isEqualTo(1);
        assertThat(r.unidad().nombre()).isEqualTo("Kilogramo");
        assertThat(r.unidad().abreviatura()).isEqualTo("kg");

        var captor = ArgumentCaptor.forClass(MateriaPrima.class);
        verify(materiaPrimaRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getNombre()).isEqualTo("Harina de trigo");
        assertThat(captor.getValue().getEstado()).isTrue();
        // R-01: sin fila de inventario, HU-10/23/27 fallarían después.
        verify(inventarioRepository).crearFilaInicial(11);
    }

    @Test
    @DisplayName("Crear con unidad inexistente responde 409 UNIDAD_INEXISTENTE sin guardar nada")
    void crearConUnidadInexistenteNoGuardaNada() {
        when(unidadMedidaRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.crear(new CrearMateriaPrimaRequest(
                "Harina de trigo", 99, new BigDecimal("2.50"), new BigDecimal("3500.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "UNIDAD_INEXISTENTE");

        verify(materiaPrimaRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventarioRepository);
    }

    @Test
    @DisplayName("Listar pagina por defecto, mapea la proyección con su unidad y reporta el total")
    void listarPaginaMapeaProyeccionConUnidad() {
        var f1 = fila(1, "Harina de trigo", 1, "Kilogramo", "kg",
                new BigDecimal("5.00"), new BigDecimal("3500.00"), true, new BigDecimal("12.50"));
        var f2 = fila(2, "Azúcar", 2, "Gramo", "g",
                new BigDecimal("1.00"), new BigDecimal("4000.00"), false, BigDecimal.ZERO);
        when(materiaPrimaRepository.listarConDetalle(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(f1, f2),
                        org.springframework.data.domain.PageRequest.of(0, 20), 45));

        var r = servicio.listar(null, null, 0, 20, null);

        assertThat(r.content()).extracting(MateriaPrimaResponse::nombre)
                .containsExactly("Harina de trigo", "Azúcar");
        assertThat(r.content().get(0).unidad().nombre()).isEqualTo("Kilogramo");
        assertThat(r.content().get(0).unidad().abreviatura()).isEqualTo("kg");
        assertThat(r.content().get(0).activo()).isTrue();
        assertThat(r.content().get(0).cantidadDisponible()).isEqualByComparingTo("12.50");
        assertThat(r.content().get(1).activo()).isFalse();
        assertThat(r.content().get(1).cantidadDisponible()).isZero();
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(45);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(materiaPrimaRepository).listarConDetalle(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("nombre").isAscending()).isTrue();
    }

    @Test
    @DisplayName("Listar con filtros envía el prefijo escapado vía FiltroTexto y el orden pedido")
    void listarConFiltrosEnviaPrefijoYOrden() {
        when(materiaPrimaRepository.listarConDetalle(any(), eq(true), any(Pageable.class)))
                .thenReturn(Page.empty());

        servicio.listar("  Ana  ", true, 0, 20, "nombre,desc");

        var prefijo = ArgumentCaptor.forClass(String.class);
        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(materiaPrimaRepository)
                .listarConDetalle(prefijo.capture(), eq(true), captor.capture());
        assertThat(prefijo.getValue()).isEqualTo("ana%");
        assertThat(captor.getValue().getSort().getOrderFor("nombre").isDescending()).isTrue();
    }

    @Test
    @DisplayName("La lista blanca de orden admite stockMinimo, costoUnitario e id")
    void listarAceptaLosCamposDeLaListaBlanca() {
        when(materiaPrimaRepository.listarConDetalle(any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        servicio.listar(null, null, 0, 20, "stockMinimo,desc");
        servicio.listar(null, null, 0, 20, "costoUnitario,asc");
        servicio.listar(null, null, 0, 20, "id,asc");

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(materiaPrimaRepository, times(3))
                .listarConDetalle(any(), any(), captor.capture());
        var pageables = captor.getAllValues();
        assertThat(pageables.get(0).getSort().getOrderFor("stockMinimo").isDescending()).isTrue();
        assertThat(pageables.get(1).getSort().getOrderFor("costoUnitario").isAscending()).isTrue();
        assertThat(pageables.get(2).getSort().getOrderFor("idMateriaPrima").isAscending()).isTrue();
    }

    @Test
    @DisplayName("Listar con un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listarConCampoDeOrdenNoPermitidoResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, 0, 20, "estado,asc"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verify(materiaPrimaRepository, never()).listarConDetalle(any(), any(), any());
    }

    @Test
    @DisplayName("Obtener una materia prima existente devuelve su unidad y su cantidad disponible")
    void obtenerMateriaPrimaExistente() {
        when(materiaPrimaRepository.buscarConDetalle(7)).thenReturn(Optional.of(
                fila(7, "Harina de trigo", 1, "Kilogramo", "kg",
                        new BigDecimal("5.00"), new BigDecimal("3500.00"), true,
                        new BigDecimal("12.50"))));

        var r = servicio.obtener(7);

        assertThat(r.idMateriaPrima()).isEqualTo(7);
        assertThat(r.nombre()).isEqualTo("Harina de trigo");
        assertThat(r.unidad().nombre()).isEqualTo("Kilogramo");
        assertThat(r.unidad().abreviatura()).isEqualTo("kg");
        assertThat(r.activo()).isTrue();
        assertThat(r.cantidadDisponible()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("Obtener una materia prima inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerMateriaPrimaInexistenteResponde404() {
        when(materiaPrimaRepository.buscarConDetalle(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró la materia prima con id 9999.");
    }

    @Test
    @DisplayName("Editar reemplaza los campos, no toca el estado ni el inventario y devuelve la fila releída")
    void editarReemplazaCamposSinTocarElEstado() {
        when(materiaPrimaRepository.findById(3)).thenReturn(Optional.of(
                materiaPrimaCon(3, "Nombre Viejo", 1, new BigDecimal("1.00"),
                        new BigDecimal("2000.00"), false)));
        when(unidadMedidaRepository.findById(2)).thenReturn(Optional.of(unidad(2, "Litro", "L")));
        when(materiaPrimaRepository.buscarConDetalle(3)).thenReturn(Optional.of(
                fila(3, "Nombre Nuevo", 2, "Litro", "L", new BigDecimal("8.00"),
                        new BigDecimal("4200.00"), false, new BigDecimal("7.500"))));

        var r = servicio.actualizar(3, new ActualizarMateriaPrimaRequest(
                "  Nombre Nuevo  ", 2, new BigDecimal("8.00"), new BigDecimal("4200.00")));

        assertThat(r.nombre()).isEqualTo("Nombre Nuevo");
        assertThat(r.unidad().nombre()).isEqualTo("Litro");
        assertThat(r.activo()).isFalse();
        assertThat(r.cantidadDisponible()).isEqualByComparingTo("7.500");

        var captor = ArgumentCaptor.forClass(MateriaPrima.class);
        verify(materiaPrimaRepository).saveAndFlush(captor.capture());
        var guardada = captor.getValue();
        assertThat(guardada.getNombre()).isEqualTo("Nombre Nuevo");
        assertThat(guardada.getIdUnidad()).isEqualTo(2);
        assertThat(guardada.getStockMinimo()).isEqualByComparingTo("8.00");
        assertThat(guardada.getCostoUnitario()).isEqualByComparingTo("4200.00");
        assertThat(guardada.getEstado()).isFalse();
        verifyNoInteractions(inventarioRepository);
    }

    @Test
    @DisplayName("Editar con unidad inexistente responde 409 UNIDAD_INEXISTENTE sin guardar")
    void editarConUnidadInexistenteResponde409() {
        when(materiaPrimaRepository.findById(3)).thenReturn(Optional.of(
                materiaPrimaCon(3, "Nombre Viejo", 1, new BigDecimal("1.00"),
                        new BigDecimal("2000.00"), true)));
        when(unidadMedidaRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizar(3, new ActualizarMateriaPrimaRequest(
                "Nombre Nuevo", 99, new BigDecimal("1.00"), new BigDecimal("2000.00"))))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "UNIDAD_INEXISTENTE");

        verify(materiaPrimaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Editar una materia prima inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void editarMateriaPrimaInexistenteResponde404() {
        when(materiaPrimaRepository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizar(9999, new ActualizarMateriaPrimaRequest(
                "Nombre Nuevo", 1, BigDecimal.ONE, BigDecimal.ONE)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("Desactivar una materia prima con recetas activas responde 409 MATERIA_PRIMA_EN_USO sin guardar")
    void desactivarConRecetasActivasResponde409() {
        when(materiaPrimaRepository.findById(5)).thenReturn(Optional.of(
                materiaPrimaCon(5, "Harina de trigo", 1, new BigDecimal("5.00"),
                        new BigDecimal("3500.00"), true)));
        when(materiaPrimaRepository.contarRecetasActivas(5)).thenReturn(2L);

        assertThatThrownBy(() -> servicio.cambiarEstado(5, new CambiarEstadoRequest(false)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "MATERIA_PRIMA_EN_USO")
                .hasMessageContaining("2 receta(s)");

        verify(materiaPrimaRepository, never()).saveAndFlush(any());
        verifyNoInteractions(inventarioRepository);
    }

    @Test
    @DisplayName("Con la regla desactivada, desactivar con recetas activas se permite sin consultarlas")
    void desactivarConReglaDesactivadaIgnoraLasRecetas() {
        var servicioSinRegla = new MateriaPrimaService(materiaPrimaRepository,
                inventarioRepository, unidadMedidaRepository, false);
        when(materiaPrimaRepository.findById(5)).thenReturn(Optional.of(
                materiaPrimaCon(5, "Harina de trigo", 1, new BigDecimal("5.00"),
                        new BigDecimal("3500.00"), true)));
        when(materiaPrimaRepository.buscarConDetalle(5)).thenReturn(Optional.of(
                fila(5, "Harina de trigo", 1, "Kilogramo", "kg", new BigDecimal("5.00"),
                        new BigDecimal("3500.00"), false, BigDecimal.ZERO)));

        var r = servicioSinRegla.cambiarEstado(5, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        verify(materiaPrimaRepository, never()).contarRecetasActivas(any());
        verifyNoInteractions(inventarioRepository);
    }

    @Test
    @DisplayName("Desactivar sin recetas activas guarda activo=false y NO toca el inventario")
    void desactivarSinRecetasGuardaActivoFalseSinTocarInventario() {
        when(materiaPrimaRepository.findById(9)).thenReturn(Optional.of(
                materiaPrimaCon(9, "Azúcar", 2, new BigDecimal("1.00"),
                        new BigDecimal("4000.00"), true)));
        when(materiaPrimaRepository.contarRecetasActivas(9)).thenReturn(0L);
        when(materiaPrimaRepository.buscarConDetalle(9)).thenReturn(Optional.of(
                fila(9, "Azúcar", 2, "Gramo", "g", new BigDecimal("1.00"),
                        new BigDecimal("4000.00"), false, new BigDecimal("3.00"))));

        var r = servicio.cambiarEstado(9, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        // R-04: la cantidad disponible sobrevive a la desactivación para conservar el kardex.
        assertThat(r.cantidadDisponible()).isEqualByComparingTo("3.00");

        var captor = ArgumentCaptor.forClass(MateriaPrima.class);
        verify(materiaPrimaRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEstado()).isFalse();
        verifyNoInteractions(inventarioRepository);
    }

    @Test
    @DisplayName("Desactivar una materia prima ya inactiva es idempotente")
    void desactivarYaInactivaEsIdempotente() {
        when(materiaPrimaRepository.findById(9)).thenReturn(Optional.of(
                materiaPrimaCon(9, "Azúcar", 2, new BigDecimal("1.00"),
                        new BigDecimal("4000.00"), false)));
        when(materiaPrimaRepository.contarRecetasActivas(9)).thenReturn(0L);
        when(materiaPrimaRepository.buscarConDetalle(9)).thenReturn(Optional.of(
                fila(9, "Azúcar", 2, "Gramo", "g", new BigDecimal("1.00"),
                        new BigDecimal("4000.00"), false, BigDecimal.ZERO)));

        var r = servicio.cambiarEstado(9, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        verify(materiaPrimaRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("Reactivar una materia prima no consulta las recetas activas")
    void reactivarNoConsultaRecetasActivas() {
        when(materiaPrimaRepository.findById(9)).thenReturn(Optional.of(
                materiaPrimaCon(9, "Azúcar", 2, new BigDecimal("1.00"),
                        new BigDecimal("4000.00"), false)));
        when(materiaPrimaRepository.buscarConDetalle(9)).thenReturn(Optional.of(
                fila(9, "Azúcar", 2, "Gramo", "g", new BigDecimal("1.00"),
                        new BigDecimal("4000.00"), true, BigDecimal.ZERO)));

        var r = servicio.cambiarEstado(9, new CambiarEstadoRequest(true));

        assertThat(r.activo()).isTrue();
        verify(materiaPrimaRepository, never()).contarRecetasActivas(any());
    }

    @Test
    @DisplayName("Cambiar el estado de una materia prima inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void cambiarEstadoInexistenteResponde404() {
        when(materiaPrimaRepository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.cambiarEstado(9999, new CambiarEstadoRequest(false)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("existeActiva delega en el repositorio")
    void existeActivaDelega() {
        when(materiaPrimaRepository.existeActiva(1)).thenReturn(true);
        when(materiaPrimaRepository.existeActiva(2)).thenReturn(false);

        assertThat(servicio.existeActiva(1)).isTrue();
        assertThat(servicio.existeActiva(2)).isFalse();
        verify(materiaPrimaRepository).existeActiva(1);
        verify(materiaPrimaRepository).existeActiva(2);
    }

    private UnidadMedida unidad(int id, String nombre, String abreviatura) {
        var unidad = new UnidadMedida();
        unidad.setIdUnidad(id);
        unidad.setNombre(nombre);
        unidad.setAbreviatura(abreviatura);
        return unidad;
    }

    private MateriaPrima materiaPrimaCon(int id, String nombre, int idUnidad,
                                         BigDecimal stockMinimo, BigDecimal costoUnitario,
                                         boolean activo) {
        var materiaPrima = new MateriaPrima();
        materiaPrima.setIdMateriaPrima(id);
        materiaPrima.setNombre(nombre);
        materiaPrima.setIdUnidad(idUnidad);
        materiaPrima.setStockMinimo(stockMinimo);
        materiaPrima.setCostoUnitario(costoUnitario);
        materiaPrima.setEstado(activo);
        return materiaPrima;
    }

    /**
     * Fila de la proyección {@link MateriaPrimaConDetalle}: como es una interfaz de Spring
     * Data, este record la implementa para los tests sin tener que stubear sus nueve getters.
     */
    private record FilaDetalleCon(Integer idMateriaPrima, String nombre, Integer idUnidad,
                                  String unidadNombre, String unidadAbreviatura,
                                  BigDecimal stockMinimo, BigDecimal costoUnitario,
                                  Boolean estado, BigDecimal cantidadDisponible)
            implements MateriaPrimaConDetalle {

        @Override
        public Integer getIdMateriaPrima() {
            return idMateriaPrima;
        }

        @Override
        public String getNombre() {
            return nombre;
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
        public BigDecimal getStockMinimo() {
            return stockMinimo;
        }

        @Override
        public BigDecimal getCostoUnitario() {
            return costoUnitario;
        }

        @Override
        public Boolean getEstado() {
            return estado;
        }

        @Override
        public BigDecimal getCantidadDisponible() {
            return cantidadDisponible;
        }
    }

    private FilaDetalleCon fila(int id, String nombre, int idUnidad, String unidadNombre,
                                String unidadAbreviatura, BigDecimal stockMinimo,
                                BigDecimal costoUnitario, boolean activo,
                                BigDecimal cantidadDisponible) {
        return new FilaDetalleCon(id, nombre, idUnidad, unidadNombre, unidadAbreviatura,
                stockMinimo, costoUnitario, activo, cantidadDisponible);
    }
}
