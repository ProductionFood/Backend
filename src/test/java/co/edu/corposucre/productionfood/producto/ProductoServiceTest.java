package co.edu.corposucre.productionfood.producto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import co.edu.corposucre.productionfood.producto.dto.ActualizarProductoRequest;
import co.edu.corposucre.productionfood.producto.dto.CrearProductoRequest;
import co.edu.corposucre.productionfood.producto.dto.ProductoResponse;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedida;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedidaRepository;

class ProductoServiceTest {

    private ProductoRepository productoRepository;
    private UnidadMedidaRepository unidadMedidaRepository;
    private ProductoService servicio;

    @BeforeEach
    void setUp() {
        productoRepository = mock(ProductoRepository.class);
        unidadMedidaRepository = mock(UnidadMedidaRepository.class);
        servicio = new ProductoService(productoRepository, unidadMedidaRepository);
        // El id solo existe tras el flush: el mock simula la asignación del IDENTITY para poder
        // releer la fila con el id del producto creado.
        when(productoRepository.saveAndFlush(any())).thenAnswer(inv -> {
            var producto = inv.getArgument(0, Producto.class);
            producto.setIdProducto(11);
            return producto;
        });
    }

    @Test
    @DisplayName("Crear fija estado TRUE, redondea el precio a dos decimales y no crea inventario")
    void crearFijaEstadoRedondeaYNoCreaInventario() {
        when(unidadMedidaRepository.findById(1))
                .thenReturn(Optional.of(unidad(1, "Unidad", "und")));
        when(productoRepository.buscarConDetalle(11)).thenReturn(Optional.of(
                fila(11, "Pan francés", "Pan de corteza crujiente", new BigDecimal("1200.56"), 1,
                        "Unidad", "und", true, false, BigDecimal.ZERO)));

        var r = servicio.crear(new CrearProductoRequest(
                "  Pan francés  ", "  Pan de corteza crujiente  ",
                new BigDecimal("1200.555"), 1));

        assertThat(r.idProducto()).isEqualTo(11);
        assertThat(r.nombre()).isEqualTo("Pan francés");
        assertThat(r.descripcion()).isEqualTo("Pan de corteza crujiente");
        assertThat(r.precio()).isEqualByComparingTo("1200.56");
        assertThat(r.activo()).isTrue();
        assertThat(r.tieneReceta()).isFalse();
        assertThat(r.stockDisponible()).isZero();
        assertThat(r.advertencia()).isNull();

        var captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepository).saveAndFlush(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getNombre()).isEqualTo("Pan francés");
        assertThat(guardado.getEstado()).isTrue();
        // CP-10: el redondeo se hace antes de guardar, no se delega en MySQL.
        assertThat(guardado.getPrecio()).isEqualByComparingTo("1200.56");
        // A diferencia de HU-08, el alta de un producto no tiene ninguna fila de inventario que
        // crear (HU-19): el servicio ni siquiera depende de InventarioRepository.
        assertThat(ProductoService.class.getDeclaredConstructors()[0].getParameterCount())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Crear sin descripción la deja en null")
    void crearSinDescripcionLaDejaNull() {
        when(unidadMedidaRepository.findById(1))
                .thenReturn(Optional.of(unidad(1, "Unidad", "und")));
        when(productoRepository.buscarConDetalle(11)).thenReturn(Optional.of(
                fila(11, "Pan francés", null, new BigDecimal("1200.00"), 1, "Unidad", "und",
                        true, false, BigDecimal.ZERO)));

        var r = servicio.crear(new CrearProductoRequest(
                "Pan francés", null, new BigDecimal("1200.00"), 1));

        assertThat(r.descripcion()).isNull();
    }

    @Test
    @DisplayName("Crear con descripción en blanco la normaliza a null")
    void crearConDescripcionEnBlancoLaNormaliza() {
        when(unidadMedidaRepository.findById(1))
                .thenReturn(Optional.of(unidad(1, "Unidad", "und")));
        when(productoRepository.buscarConDetalle(11)).thenReturn(Optional.of(
                fila(11, "Pan francés", null, new BigDecimal("1200.00"), 1, "Unidad", "und",
                        true, false, BigDecimal.ZERO)));

        servicio.crear(new CrearProductoRequest("Pan francés", "   ", new BigDecimal("1200.00"), 1));

        var captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getDescripcion()).isNull();
    }

    @Test
    @DisplayName("Crear con unidad inexistente responde 409 UNIDAD_INEXISTENTE sin guardar nada")
    void crearConUnidadInexistenteNoGuardaNada() {
        when(unidadMedidaRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.crear(new CrearProductoRequest(
                "Pan francés", null, new BigDecimal("1200.00"), 99)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "UNIDAD_INEXISTENTE");

        verify(productoRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Listar pagina por defecto, mapea la proyección con unidad, receta y stock")
    void listarPaginaMapeaProyeccionConUnidadRecetaYStock() {
        var f1 = fila(1, "Croissant", "Hojaldre", new BigDecimal("2500.00"), 5, "Unidad", "und",
                true, true, new BigDecimal("24.00"));
        var f2 = fila(2, "Pan integral", null, new BigDecimal("4800.00"), 5, "Unidad", "und",
                true, false, BigDecimal.ZERO);
        when(productoRepository.listarConDetalle(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(f1, f2),
                        org.springframework.data.domain.PageRequest.of(0, 20), 45));

        var r = servicio.listar(null, null, 0, 20, null);

        assertThat(r.content()).extracting(ProductoResponse::nombre)
                .containsExactly("Croissant", "Pan integral");
        assertThat(r.content().get(0).unidad().nombre()).isEqualTo("Unidad");
        assertThat(r.content().get(0).tieneReceta()).isTrue();
        assertThat(r.content().get(0).stockDisponible()).isEqualByComparingTo("24.00");
        assertThat(r.content().get(1).tieneReceta()).isFalse();
        assertThat(r.content().get(1).descripcion()).isNull();
        assertThat(r.content().get(1).advertencia()).isNull();
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(20);
        assertThat(r.totalElements()).isEqualTo(45);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productoRepository).listarConDetalle(isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort().getOrderFor("nombre").isAscending()).isTrue();
    }

    @Test
    @DisplayName("Listar con filtros envía el prefijo escapado vía FiltroTexto y el orden pedido")
    void listarConFiltrosEnviaPrefijoYOrden() {
        when(productoRepository.listarConDetalle(any(), eq(true), any(Pageable.class)))
                .thenReturn(Page.empty());

        servicio.listar("  Pan  ", true, 0, 20, "nombre,desc");

        var prefijo = ArgumentCaptor.forClass(String.class);
        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productoRepository).listarConDetalle(prefijo.capture(), eq(true), captor.capture());
        assertThat(prefijo.getValue()).isEqualTo("pan%");
        assertThat(captor.getValue().getSort().getOrderFor("nombre").isDescending()).isTrue();
    }

    @Test
    @DisplayName("La lista blanca de orden admite precio e id y mapea id a idProducto")
    void listarAceptaLosCamposDeLaListaBlanca() {
        when(productoRepository.listarConDetalle(any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        servicio.listar(null, null, 0, 20, "precio,desc");
        servicio.listar(null, null, 0, 20, "id,asc");

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productoRepository, times(2)).listarConDetalle(any(), any(), captor.capture());
        var pageables = captor.getAllValues();
        assertThat(pageables.get(0).getSort().getOrderFor("precio").isDescending()).isTrue();
        assertThat(pageables.get(1).getSort().getOrderFor("idProducto").isAscending()).isTrue();
    }

    @Test
    @DisplayName("Listar con un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listarConCampoDeOrdenNoPermitidoResponde400() {
        assertThatThrownBy(() -> servicio.listar(null, null, 0, 20, "estado,asc"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verify(productoRepository, never()).listarConDetalle(any(), any(), any());
    }

    @Test
    @DisplayName("Obtener un producto existente devuelve su unidad, su receta y su stock")
    void obtenerProductoExistente() {
        when(productoRepository.buscarConDetalle(7)).thenReturn(Optional.of(
                fila(7, "Pan francés", "Corteza crujiente", new BigDecimal("1200.00"), 5,
                        "Unidad", "und", true, true, new BigDecimal("120.00"))));

        var r = servicio.obtener(7);

        assertThat(r.idProducto()).isEqualTo(7);
        assertThat(r.descripcion()).isEqualTo("Corteza crujiente");
        assertThat(r.unidad().abreviatura()).isEqualTo("und");
        assertThat(r.tieneReceta()).isTrue();
        assertThat(r.stockDisponible()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("Obtener un producto inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerProductoInexistenteResponde404() {
        when(productoRepository.buscarConDetalle(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO")
                .hasMessage("No se encontró el producto con id 9999.");
    }

    @Test
    @DisplayName("Editar reemplaza los campos, no toca el estado y devuelve la fila releída")
    void editarReemplazaCamposSinTocarElEstado() {
        when(productoRepository.findById(3)).thenReturn(Optional.of(
                productoCon(3, "Nombre Viejo", "Vieja", new BigDecimal("1.00"), 1, false)));
        when(unidadMedidaRepository.findById(2)).thenReturn(Optional.of(unidad(2, "Docena", "doc")));
        when(productoRepository.buscarConDetalle(3)).thenReturn(Optional.of(
                fila(3, "Nombre Nuevo", "Nueva", new BigDecimal("1500.00"), 2, "Docena", "doc",
                        false, true, new BigDecimal("7.00"))));

        var r = servicio.actualizar(3, new ActualizarProductoRequest(
                "  Nombre Nuevo  ", "  Nueva  ", new BigDecimal("1499.999"), 2));

        assertThat(r.nombre()).isEqualTo("Nombre Nuevo");
        assertThat(r.descripcion()).isEqualTo("Nueva");
        assertThat(r.precio()).isEqualByComparingTo("1500.00");
        assertThat(r.unidad().nombre()).isEqualTo("Docena");
        // El estado no se toca por el PUT: sigue inactivo como estaba en la entidad.
        assertThat(r.activo()).isFalse();

        var captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepository).saveAndFlush(captor.capture());
        var guardado = captor.getValue();
        assertThat(guardado.getNombre()).isEqualTo("Nombre Nuevo");
        assertThat(guardado.getDescripcion()).isEqualTo("Nueva");
        assertThat(guardado.getPrecio()).isEqualByComparingTo("1500.00");
        assertThat(guardado.getIdUnidad()).isEqualTo(2);
        assertThat(guardado.getEstado()).isFalse();
    }

    @Test
    @DisplayName("Editar con unidad inexistente responde 409 UNIDAD_INEXISTENTE sin guardar")
    void editarConUnidadInexistenteResponde409() {
        when(productoRepository.findById(3)).thenReturn(Optional.of(
                productoCon(3, "Nombre Viejo", null, new BigDecimal("1200.00"), 1, true)));
        when(unidadMedidaRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizar(3, new ActualizarProductoRequest(
                "Nombre Nuevo", null, new BigDecimal("1200.00"), 99)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "UNIDAD_INEXISTENTE");

        verify(productoRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Editar un producto inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void editarProductoInexistenteResponde404() {
        when(productoRepository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.actualizar(9999, new ActualizarProductoRequest(
                "Nombre Nuevo", null, BigDecimal.ONE, 1)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("Desactivar un producto con stock responde 200 con advertencia y conserva el stock (R-03)")
    void desactivarConStockRespondeAdvertenciaYConservaStock() {
        when(productoRepository.findById(5)).thenReturn(Optional.of(
                productoCon(5, "Croissant", "Hojaldre", new BigDecimal("2500.00"), 5, true)));
        when(productoRepository.buscarConDetalle(5)).thenReturn(Optional.of(
                fila(5, "Croissant", "Hojaldre", new BigDecimal("2500.00"), 5, "Unidad", "und",
                        false, true, new BigDecimal("24.00"))));

        var r = servicio.cambiarEstado(5, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        assertThat(r.stockDisponible()).isEqualByComparingTo("24.00");
        assertThat(r.advertencia()).contains("24").contains("und").contains("R-03");

        var captor = ArgumentCaptor.forClass(Producto.class);
        verify(productoRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEstado()).isFalse();
    }

    @Test
    @DisplayName("Desactivar un producto sin stock no añade advertencia")
    void desactivarSinStockNoAñadeAdvertencia() {
        when(productoRepository.findById(5)).thenReturn(Optional.of(
                productoCon(5, "Croissant", null, new BigDecimal("2500.00"), 5, true)));
        when(productoRepository.buscarConDetalle(5)).thenReturn(Optional.of(
                fila(5, "Croissant", null, new BigDecimal("2500.00"), 5, "Unidad", "und",
                        false, true, BigDecimal.ZERO)));

        var r = servicio.cambiarEstado(5, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        assertThat(r.advertencia()).isNull();
    }

    @Test
    @DisplayName("Reactivar un producto nunca añade advertencia aunque tenga stock")
    void reactivarNoAñadeAdvertencia() {
        when(productoRepository.findById(5)).thenReturn(Optional.of(
                productoCon(5, "Croissant", null, new BigDecimal("2500.00"), 5, false)));
        when(productoRepository.buscarConDetalle(5)).thenReturn(Optional.of(
                fila(5, "Croissant", null, new BigDecimal("2500.00"), 5, "Unidad", "und",
                        true, true, new BigDecimal("24.00"))));

        var r = servicio.cambiarEstado(5, new CambiarEstadoRequest(true));

        assertThat(r.activo()).isTrue();
        assertThat(r.advertencia()).isNull();
    }

    @Test
    @DisplayName("Desactivar un producto ya inactivo es idempotente")
    void desactivarYaInactivoEsIdempotente() {
        when(productoRepository.findById(9)).thenReturn(Optional.of(
                productoCon(9, "Torta", null, new BigDecimal("32000.00"), 5, false)));
        when(productoRepository.buscarConDetalle(9)).thenReturn(Optional.of(
                fila(9, "Torta", null, new BigDecimal("32000.00"), 5, "Unidad", "und",
                        false, false, BigDecimal.ZERO)));

        var r = servicio.cambiarEstado(9, new CambiarEstadoRequest(false));

        assertThat(r.activo()).isFalse();
        verify(productoRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("Cambiar el estado de un producto inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void cambiarEstadoInexistenteResponde404() {
        when(productoRepository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.cambiarEstado(9999, new CambiarEstadoRequest(false)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RECURSO_NO_ENCONTRADO");
    }

    @Test
    @DisplayName("existeActivo delega en el repositorio")
    void existeActivoDelega() {
        when(productoRepository.existeActivo(1)).thenReturn(true);
        when(productoRepository.existeActivo(2)).thenReturn(false);

        assertThat(servicio.existeActivo(1)).isTrue();
        assertThat(servicio.existeActivo(2)).isFalse();
        verify(productoRepository).existeActivo(1);
        verify(productoRepository).existeActivo(2);
    }

    private UnidadMedida unidad(int id, String nombre, String abreviatura) {
        var unidad = new UnidadMedida();
        unidad.setIdUnidad(id);
        unidad.setNombre(nombre);
        unidad.setAbreviatura(abreviatura);
        return unidad;
    }

    private Producto productoCon(int id, String nombre, String descripcion, BigDecimal precio,
                                 int idUnidad, boolean activo) {
        var producto = new Producto();
        producto.setIdProducto(id);
        producto.setNombre(nombre);
        producto.setDescripcion(descripcion);
        producto.setPrecio(precio);
        producto.setIdUnidad(idUnidad);
        producto.setEstado(activo);
        return producto;
    }

    /**
     * Fila de la proyección {@link ProductoConDetalle}: como es una interfaz de Spring Data,
     * este record la implementa para los tests sin tener que stubear sus diez getters.
     */
    private record FilaDetalleCon(Integer idProducto, String nombre, String descripcion,
                                  BigDecimal precio, Integer idUnidad, String unidadNombre,
                                  String unidadAbreviatura, Boolean estado, Integer idReceta,
                                  BigDecimal stockDisponible) implements ProductoConDetalle {

        @Override
        public Integer getIdProducto() {
            return idProducto;
        }

        @Override
        public String getNombre() {
            return nombre;
        }

        @Override
        public String getDescripcion() {
            return descripcion;
        }

        @Override
        public BigDecimal getPrecio() {
            return precio;
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
        public Boolean getEstado() {
            return estado;
        }

        @Override
        public Integer getIdReceta() {
            return idReceta;
        }

        @Override
        public BigDecimal getStockDisponible() {
            return stockDisponible;
        }
    }

    private FilaDetalleCon fila(int id, String nombre, String descripcion, BigDecimal precio,
                                int idUnidad, String unidadNombre, String unidadAbreviatura,
                                boolean activo, boolean tieneReceta, BigDecimal stockDisponible) {
        return new FilaDetalleCon(id, nombre, descripcion, precio, idUnidad, unidadNombre,
                unidadAbreviatura, activo, tieneReceta ? 1 : null, stockDisponible);
    }
}
