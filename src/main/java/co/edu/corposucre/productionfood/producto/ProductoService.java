package co.edu.corposucre.productionfood.producto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.FiltroTexto;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.producto.dto.ActualizarProductoRequest;
import co.edu.corposucre.productionfood.producto.dto.CrearProductoRequest;
import co.edu.corposucre.productionfood.producto.dto.ProductoResponse;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedida;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedidaRepository;

/**
 * Reglas de negocio del catálogo de productos terminados (HU-11).
 *
 * <p>Es el gemelo de {@code MateriaPrimaService} con una asimetría central: <strong>el alta de un
 * producto no crea fila de inventario</strong>. El stock de producto terminado vive por lote en
 * {@code inventario_producto_terminado} (HU-19) y se agrega en la vista
 * {@code v_stock_producto_terminado}; no existe una fila de stock por producto que crear.
 */
@Service
public class ProductoService {

    private static final Map<String, String> ORDEN = Map.of(
        "nombre", "nombre", "precio", "precio", "id", "idProducto");

    private final ProductoRepository productoRepository;
    private final UnidadMedidaRepository unidadMedidaRepository;

    public ProductoService(ProductoRepository productoRepository,
                           UnidadMedidaRepository unidadMedidaRepository) {
        this.productoRepository = productoRepository;
        this.unidadMedidaRepository = unidadMedidaRepository;
    }

    @Transactional
    // En las expresiones de detalle los accesores de record se leen como propiedades
    // (#resultado.nombre) y no como llamadas: el contexto SpEL de solo lectura del aspecto no
    // resuelve la llamada y el detalle se perdería en silencio.
    @Auditable(accion = "CREAR", tabla = "productos",
            detalle = "'Producto creado: ' + #resultado.nombre")
    public ProductoResponse crear(CrearProductoRequest req) {
        // La unidad se valida con findById (y no con existsById) para reutilizarla en la
        // respuesta y no hacer una segunda consulta solo para el nombre y la abreviatura.
        validarUnidad(req.idUnidad());

        var producto = new Producto();
        producto.setNombre(req.nombre().trim());
        producto.setDescripcion(normalizarDescripcion(req.descripcion()));
        producto.setPrecio(aDosDecimales(req.precio()));
        producto.setIdUnidad(req.idUnidad());
        // La columna 'estado' es NOT NULL y con JPA un campo sin asignar se insertaría como
        // NULL: se fija aquí de forma explícita. El DEFAULT 1 de la base es la red de seguridad
        // para las inserciones hechas fuera de la aplicación, no el mecanismo.
        producto.setEstado(Boolean.TRUE);

        // A diferencia de la materia prima (HU-08 R-01) aquí NO se crea fila de inventario: el
        // stock de producto terminado entra con los lotes de HU-19, no con el alta del catálogo.
        var creado = productoRepository.saveAndFlush(producto);

        // Se relee de la base y no se construye desde la entidad: así la respuesta refleja el
        // precio ya redondeado por la columna decimal(10,2) y trae tieneReceta/stock calculados
        // (un producto nuevo tiene receta falsa y stock 0).
        return aResponse(buscarOFallar(creado.getIdProducto()), null);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductoResponse> listar(String nombre, Boolean activo,
                                                 int page, int size, String sort) {
        var orden = PageRequest.of(page, size, sort, ORDEN, "nombre,asc");
        var pagina = productoRepository.listarConDetalle(
                FiltroTexto.patronPrefijo(nombre), activo, orden.toPageable());
        return PageResponse.of(
                pagina.getContent().stream().map(fila -> aResponse(fila, null)).toList(),
                orden.page(), orden.size(), pagina.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ProductoResponse obtener(Integer id) {
        return aResponse(buscarOFallar(id), null);
    }

    @Transactional
    @Auditable(accion = "EDITAR", tabla = "productos",
            detalle = "'Producto editado: ' + #resultado.nombre")
    public ProductoResponse actualizar(Integer id, ActualizarProductoRequest req) {
        var producto = productoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el producto", id));
        // El PUT es un reemplazo completo y puede cambiar la unidad: se revalida siempre.
        validarUnidad(req.idUnidad());

        producto.setNombre(req.nombre().trim());
        producto.setDescripcion(normalizarDescripcion(req.descripcion()));
        // R-01: el precio del catálogo solo afecta a ventas futuras. Las líneas de pedido ya
        // registradas guardan su propio precio_unitario y no se tocan desde aquí.
        producto.setPrecio(aDosDecimales(req.precio()));
        producto.setIdUnidad(req.idUnidad());
        // El estado no se modifica por este endpoint y el stock no vive aquí.
        productoRepository.saveAndFlush(producto);

        return aResponse(buscarOFallar(id), null);
    }

    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO", tabla = "productos",
            detalle = "'Producto ' + #id + ' estado ' + #req.activo")
    public ProductoResponse cambiarEstado(Integer id, CambiarEstadoRequest req) {
        var producto = productoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el producto", id));
        producto.setEstado(req.activo());
        productoRepository.saveAndFlush(producto);

        // R-03: desactivar no retira el stock. Si el producto todavía tiene existencias la
        // operación procede igual (es una advertencia, no un conflicto) y la respuesta lo indica
        // con 'advertencia', que el frontend muestra sin bloquear.
        var fila = buscarOFallar(id);
        return aResponse(fila, req.activo() ? null : advertenciaStock(fila));
    }

    @Transactional(readOnly = true)
    public boolean existeActivo(Integer id) {
        return productoRepository.existeActivo(id);
    }

    private ProductoConDetalle buscarOFallar(Integer id) {
        return productoRepository.buscarConDetalle(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el producto", id));
    }

    private void validarUnidad(Integer idUnidad) {
        unidadMedidaRepository.findById(idUnidad)
                .orElseThrow(() -> new ConflictoNegocioException(
                        "UNIDAD_INEXISTENTE", "La unidad de medida indicada no existe."));
    }

    /**
     * Redondea el precio a los dos decimales de la columna con {@code HALF_UP}.
     *
     * <p>El contrato de HU-11 (CP-10) espera que tres decimales se redondeen, no que se rechacen.
     * Se hace en el servicio y no se delega en MySQL para que el valor de la respuesta no dependa
     * de que la base devuelva o no la fila ya redondeada.
     */
    private static BigDecimal aDosDecimales(BigDecimal precio) {
        return precio.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Normaliza la descripción opcional: recorta espacios y convierte el blanco en {@code null}.
     *
     * <p>Guardar {@code ""} y {@code null} para «sin descripción» obligaría al consumidor a tratar
     * dos casos; el esquema ya admite {@code NULL} para la ausencia de dato (CA-02).
     */
    private static String normalizarDescripcion(String descripcion) {
        if (descripcion == null) {
            return null;
        }
        var limpia = descripcion.trim();
        return limpia.isEmpty() ? null : limpia;
    }

    /**
     * Construye el aviso de stock para la desactivación (R-03) o {@code null} si no hay existencias.
     */
    private static String advertenciaStock(ProductoConDetalle fila) {
        var stock = fila.getStockDisponible();
        if (stock == null || stock.signum() <= 0) {
            return null;
        }
        return "El producto conserva " + stock.stripTrailingZeros().toPlainString() + " "
                + fila.getUnidadAbreviatura() + " de stock disponible; desactivarlo no lo retira "
                + "del inventario (R-03).";
    }

    private static ProductoResponse aResponse(ProductoConDetalle fila, String advertencia) {
        return new ProductoResponse(
                fila.getIdProducto(),
                fila.getNombre(),
                fila.getDescripcion(),
                fila.getPrecio(),
                new UnidadRefResponse(
                        fila.getIdUnidad(), fila.getUnidadNombre(), fila.getUnidadAbreviatura()),
                Boolean.TRUE.equals(fila.getEstado()),
                fila.getIdReceta() != null,
                fila.getStockDisponible(),
                advertencia);
    }
}
