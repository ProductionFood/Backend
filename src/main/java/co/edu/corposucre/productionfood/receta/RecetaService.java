package co.edu.corposucre.productionfood.receta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.FiltroTexto;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.producto.ProductoRepository;
import co.edu.corposucre.productionfood.receta.dto.ActualizarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.ActualizarRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.AgregarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.CrearRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.DetalleRecetaResponse;
import co.edu.corposucre.productionfood.receta.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.receta.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.receta.dto.RecetaResponse;
import co.edu.corposucre.productionfood.unidadmedida.UnidadMedidaRepository;

/**
 * Reglas de negocio de las recetas (HU-12).
 *
 * <p>{@code cantidadProducir} es el rendimiento de <strong>una tanda completa</strong>, no de una
 * unidad (R-02): es el divisor con el que HU-22 calcula el consumo teórico. Las cantidades del
 * detalle llevan escala 3 porque la columna es {@code decimal(10,3)} y el tercer decimal es
 * significativo (R-06); {@code cantidadProducir} y los costos llevan escala 2.
 *
 * <p>El alta de la cabecera y de su detalle ocurre en una sola transacción: si un ingrediente falla,
 * no queda una receta a medias.
 */
@Service
public class RecetaService {

    /**
     * Lista blanca de ordenamiento del listado: traduce el campo que pide el cliente al nombre de
     * columna SQL. El orden va cualificado con {@code r.} porque el {@code SELECT} del repositorio
     * hace {@code JOIN} a {@code productos} y {@code unidades_medida}, que también tienen columna
     * {@code nombre}; sin el prefijo el motor no sabría cuál ordenar.
     */
    private static final Map<String, String> ORDEN = Map.of(
        "id", "r.id_receta",
        "nombre", "r.nombre",
        "cantidadProducir", "r.cantidad_producir");

    private static final String ORDEN_POR_DEFECTO = "nombre,asc";

    /**
     * Cota superior exclusiva de la cantidad de un ingrediente: {@code decimal(10,3)} admite hasta
     * siete dígitos enteros, es decir, valores menores que diez millones.
     */
    private static final BigDecimal MAXIMO_CANTIDAD_DETALLE = new BigDecimal("10000000");

    private final RecetaRepository recetaRepository;
    private final DetalleRecetaRepository detalleRecetaRepository;
    private final ProductoRepository productoRepository;
    private final MateriaPrimaRepository materiaPrimaRepository;
    private final UnidadMedidaRepository unidadMedidaRepository;

    public RecetaService(RecetaRepository recetaRepository,
                         DetalleRecetaRepository detalleRecetaRepository,
                         ProductoRepository productoRepository,
                         MateriaPrimaRepository materiaPrimaRepository,
                         UnidadMedidaRepository unidadMedidaRepository) {
        this.recetaRepository = recetaRepository;
        this.detalleRecetaRepository = detalleRecetaRepository;
        this.productoRepository = productoRepository;
        this.materiaPrimaRepository = materiaPrimaRepository;
        this.unidadMedidaRepository = unidadMedidaRepository;
    }

    /**
     * Lista las recetas con paginación, filtro por nombre y estado.
     *
     * <p>El detalle de todas las recetas de la página se trae con una sola consulta
     * ({@link DetalleRecetaRepository#listarPorRecetas(java.util.List)}) y se agrupa por
     * {@code idReceta}: consultar el detalle receta a receta repetiría la ida a la base por cada
     * fila de la página.
     *
     * @param nombre filtro de prefijo por nombre; puede ser {@code null}
     * @param activo estado buscado; {@code null} para no filtrar
     * @param page   página solicitada, desde cero
     * @param size   tamaño de página, entre 1 y 100
     * @param sort   orden en formato {@code campo,asc|desc}; {@code null} usa {@code nombre,asc}
     * @return la página de recetas, cada una con su detalle y sus costos
     * @throws ParametroInvalidoException si la paginación o el campo de orden no son válidos
     */
    @Transactional(readOnly = true)
    public PageResponse<RecetaResponse> listar(String nombre, Boolean activo,
                                               int page, int size, String sort) {
        var orden = PageRequest.of(page, size, sort, ORDEN, ORDEN_POR_DEFECTO);
        var filtro = FiltroTexto.patronPrefijo(nombre);
        var recetas = recetaRepository.listar(filtro, activo, orden.toPageable());
        var detalles = detallesPorReceta(
                recetas.stream().map(Receta::getIdReceta).toList());

        var contenido = recetas.stream()
                .map(receta -> aResponse(receta,
                        detalles.getOrDefault(receta.getIdReceta(), List.of())))
                .toList();
        return PageResponse.of(contenido, orden.page(), orden.size(),
                recetaRepository.contar(filtro, activo));
    }

    /**
     * Devuelve una receta con su detalle completo y sus costos recalculados.
     *
     * @param id identificador de la receta
     * @return la receta con su detalle
     * @throws RecursoNoEncontradoException si la receta no existe
     */
    @Transactional(readOnly = true)
    public RecetaResponse obtener(Integer id) {
        var receta = buscarOFallar(id);
        return aResponse(receta, detalleRecetaRepository.listarPorReceta(id));
    }

    /**
     * Devuelve la receta de un producto (CA-01).
     *
     * @param idProducto identificador del producto
     * @return la receta del producto con su detalle
     * @throws RecursoNoEncontradoException si el producto no tiene receta
     */
    @Transactional(readOnly = true)
    public RecetaResponse porProducto(Integer idProducto) {
        var receta = recetaRepository.buscarPorProducto(idProducto)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "la receta del producto", idProducto));
        return aResponse(receta,
                detalleRecetaRepository.listarPorReceta(receta.getIdReceta()));
    }

    /**
     * Crea una receta con su detalle inicial.
     *
     * @param req datos de la cabecera y de los ingredientes
     * @return la receta releída de la base, con sus costos estimados
     * @throws ConflictoNegocioException si el producto, la unidad o una materia prima no existen o
     *                                   están inactivos, si el producto ya tiene receta o si hay
     *                                   materias primas repetidas
     * @throws ParametroInvalidoException si {@code cantidadProducir} no es mayor que cero, o si la
     *                                    cantidad de un ingrediente no cabe en {@code decimal(10,3)}
     */
    @Transactional
    @Auditable(accion = "CREAR", tabla = "recetas",
            detalle = "'Receta creada: ' + #resultado.nombre")
    public RecetaResponse crear(CrearRecetaRequest req) {
        if (!productoRepository.existeActivo(req.idProducto())) {
            throw new ConflictoNegocioException("PRODUCTO_INEXISTENTE",
                    "El producto indicado no existe o está inactivo.");
        }
        if (req.cantidadProducir() == null || req.cantidadProducir().signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_PRODUCIR_INVALIDA",
                    "La cantidad a producir debe ser un número mayor que cero.");
        }
        validarUnidad(req.idUnidad());
        if (recetaRepository.existePorProducto(req.idProducto())) {
            throw new ConflictoNegocioException("PRODUCTO_YA_TIENE_RECETA",
                    "El producto ya tiene una receta asignada.");
        }

        var detalles = req.detalles() == null
                ? List.<AgregarDetalleRequest>of()
                : req.detalles();
        for (var linea : detalles) {
            if (!materiaPrimaRepository.existeActiva(linea.idMateriaPrima())) {
                throw new ConflictoNegocioException("MATERIA_PRIMA_INEXISTENTE",
                        "La materia prima indicada no existe o está inactiva.");
            }
        }
        var ids = new HashSet<Integer>();
        for (var linea : detalles) {
            if (!ids.add(linea.idMateriaPrima())) {
                throw new ConflictoNegocioException("MATERIA_PRIMA_DUPLICADA",
                        "La materia prima indicada ya está en la receta.");
            }
        }

        var lineas = new ArrayList<DetalleReceta>();
        for (var linea : detalles) {
            var detalle = new DetalleReceta();
            detalle.setIdMateriaPrima(linea.idMateriaPrima());
            detalle.setCantidad(cantidadTresDecimales(linea.cantidad(), "cantidad"));
            lineas.add(detalle);
        }

        var receta = new Receta();
        receta.setIdProducto(req.idProducto());
        receta.setNombre(req.nombre().trim());
        receta.setCantidadProducir(req.cantidadProducir().setScale(2, RoundingMode.HALF_UP));
        receta.setIdUnidad(req.idUnidad());
        receta.setActivo(Boolean.TRUE);

        Integer idReceta;
        try {
            idReceta = recetaRepository.insertar(receta);
        } catch (DuplicateKeyException ex) {
            throw new ConflictoNegocioException("PRODUCTO_YA_TIENE_RECETA",
                    "El producto ya tiene una receta asignada.");
        }

        for (var detalle : lineas) {
            detalle.setIdReceta(idReceta);
            detalleRecetaRepository.insertar(detalle);
        }

        var cabecera = recetaRepository.buscarPorId(idReceta)
                .orElseThrow(() -> new RecursoNoEncontradoException("la receta", idReceta));
        return aResponse(cabecera, detalleRecetaRepository.listarPorReceta(idReceta));
    }

    /**
     * Agrega un ingrediente a una receta existente.
     *
     * <p>Comprueba el duplicado antes de insertar para dar un 409 legible, pero la garantía ante dos
     * peticiones simultáneas es {@code uk_detalle_receta}: si la base lo rechaza, la
     * {@link DuplicateKeyException} se traduce al mismo código.
     *
     * @param idReceta identificador de la receta
     * @param req      materia prima y cantidad a agregar
     * @return la receta completa con los costos recalculados
     * @throws RecursoNoEncontradoException si la receta no existe
     * @throws ConflictoNegocioException    si la materia prima no existe o está inactiva, o si ya
     *                                      está en la receta
     * @throws ParametroInvalidoException   si la cantidad no cabe en {@code decimal(10,3)}
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "recetas",
            detalle = "'Receta ' + #idReceta + ' con detalle agregado'")
    public RecetaResponse agregarDetalle(Integer idReceta, AgregarDetalleRequest req) {
        var receta = recetaRepository.buscarPorId(idReceta)
                .orElseThrow(() -> new RecursoNoEncontradoException("la receta", idReceta));
        if (!materiaPrimaRepository.existeActiva(req.idMateriaPrima())) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_INEXISTENTE",
                    "La materia prima indicada no existe o está inactiva.");
        }
        if (detalleRecetaRepository.existeMateriaPrima(idReceta, req.idMateriaPrima())) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_DUPLICADA",
                    "La materia prima indicada ya está en la receta.");
        }

        var detalle = new DetalleReceta();
        detalle.setIdReceta(idReceta);
        detalle.setIdMateriaPrima(req.idMateriaPrima());
        detalle.setCantidad(cantidadTresDecimales(req.cantidad(), "cantidad"));
        try {
            detalleRecetaRepository.insertar(detalle);
        } catch (DuplicateKeyException ex) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_DUPLICADA",
                    "La materia prima indicada ya está en la receta.");
        }

        return aResponse(receta, detalleRecetaRepository.listarPorReceta(idReceta));
    }

    /**
     * Actualiza los datos editables de la cabecera.
     *
     * <p>El PUT es un reemplazo completo de nombre, rendimiento y unidad de producción: la unidad
     * se revalida siempre. No cambia el producto (CA-01) ni el estado, que tiene su propio
     * endpoint. Los costos se recalculan al devolver la receta releída.
     *
     * @param id  identificador de la receta
     * @param req valores nuevos de la cabecera
     * @return la receta completa con el detalle y los costos recalculados
     * @throws RecursoNoEncontradoException si la receta no existe
     * @throws ParametroInvalidoException   si {@code cantidadProducir} no es mayor que cero
     * @throws ConflictoNegocioException    si la unidad de medida no existe
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "recetas",
            detalle = "'Receta editada: ' + #resultado.nombre")
    public RecetaResponse actualizar(Integer id, ActualizarRecetaRequest req) {
        var receta = buscarOFallar(id);
        if (req.cantidadProducir() == null || req.cantidadProducir().signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_PRODUCIR_INVALIDA",
                    "La cantidad a producir debe ser un número mayor que cero.");
        }
        validarUnidad(req.idUnidad());

        receta.setNombre(req.nombre().trim());
        receta.setCantidadProducir(req.cantidadProducir().setScale(2, RoundingMode.HALF_UP));
        receta.setIdUnidad(req.idUnidad());
        recetaRepository.actualizar(receta);

        return aResponse(buscarOFallar(id), detalleRecetaRepository.listarPorReceta(id));
    }

    /**
     * Activa o desactiva una receta.
     *
     * <p>Es idempotente: enviar el estado que ya tiene deja la receta igual y responde con la
     * receta completa. La cabecera nunca se elimina; solo se desactiva (R-07).
     *
     * @param id  identificador de la receta
     * @param req estado nuevo
     * @return la receta completa con el estado aplicado
     * @throws RecursoNoEncontradoException si la receta no existe
     */
    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO", tabla = "recetas",
            detalle = "'Receta ' + #id + ' estado ' + #req.activo")
    public RecetaResponse cambiarEstado(Integer id, CambiarEstadoRequest req) {
        var receta = buscarOFallar(id);
        recetaRepository.cambiarEstado(receta.getIdReceta(), req.activo());

        return aResponse(buscarOFallar(id), detalleRecetaRepository.listarPorReceta(id));
    }

    /**
     * Edita la cantidad de un ingrediente de una receta.
     *
     * <p>El detalle debe pertenecer a la receta indicada en la ruta: una línea de otra receta se
     * trata como inexistente (404), no como prohibida, porque el subrecurso anidado solo conoce
     * las líneas de su receta.
     *
     * @param idReceta  identificador de la receta
     * @param idDetalle identificador de la línea de detalle
     * @param req       cantidad nueva, con escala 3 (R-06)
     * @return la receta completa con los costos recalculados
     * @throws RecursoNoEncontradoException si la receta no existe, o si la línea no existe o no
     *                                      pertenece a la receta
     * @throws ParametroInvalidoException   si la cantidad no cabe en {@code decimal(10,3)}
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "recetas",
            detalle = "'Ingrediente ' + #idDetalle + ' de la receta ' + #idReceta + ' editado'")
    public RecetaResponse actualizarDetalle(Integer idReceta, Integer idDetalle,
                                            ActualizarDetalleRequest req) {
        var receta = buscarOFallar(idReceta);
        var detalle = detalleDeLaReceta(idReceta, idDetalle);
        detalleRecetaRepository.actualizarCantidad(detalle.getIdDetalleReceta(),
                cantidadTresDecimales(req.cantidad(), "cantidad"));

        return aResponse(receta, detalleRecetaRepository.listarPorReceta(idReceta));
    }

    /**
     * Elimina un ingrediente de una receta (R-07).
     *
     * <p>Es la única eliminación física del proyecto: un ingrediente quitado no tiene valor
     * histórico y {@code detalle_receta} no está referenciada por otra tabla. Las mismas reglas de
     * propiedad que en {@link #actualizarDetalle(Integer, Integer, ActualizarDetalleRequest)}
     * aplican: una línea de otra receta responde 404.
     *
     * @param idReceta  identificador de la receta
     * @param idDetalle identificador de la línea de detalle
     * @throws RecursoNoEncontradoException si la receta no existe, o si la línea no existe o no
     *                                      pertenece a la receta
     */
    @Transactional
    @Auditable(accion = "ELIMINAR", tabla = "detalle_receta",
            detalle = "'Ingrediente ' + #idDetalle + ' eliminado de la receta ' + #idReceta")
    public void eliminarDetalle(Integer idReceta, Integer idDetalle) {
        buscarOFallar(idReceta);
        var detalle = detalleDeLaReceta(idReceta, idDetalle);
        detalleRecetaRepository.eliminar(detalle.getIdDetalleReceta());
    }

    private Receta buscarOFallar(Integer id) {
        return recetaRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la receta", id));
    }

    private DetalleReceta detalleDeLaReceta(Integer idReceta, Integer idDetalle) {
        return detalleRecetaRepository.buscarPorId(idDetalle)
                .filter(detalle -> idReceta.equals(detalle.getIdReceta()))
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "la línea de detalle", idDetalle));
    }

    private Map<Integer, List<DetalleReceta>> detallesPorReceta(List<Integer> idsRecetas) {
        return detalleRecetaRepository.listarPorRecetas(idsRecetas).stream()
                .collect(Collectors.groupingBy(DetalleReceta::getIdReceta));
    }

    private void validarUnidad(Integer idUnidad) {
        unidadMedidaRepository.findById(idUnidad)
                .orElseThrow(() -> new ConflictoNegocioException(
                        "UNIDAD_INEXISTENTE", "La unidad de medida indicada no existe."));
    }

    /**
     * Normaliza una cantidad de detalle a escala 3 y comprueba que el valor resultante sea
     * almacenable en {@code decimal(10,3)}.
     *
     * <p>Es una segunda barrera tras la validación del DTO: un valor como {@code 0.0004} pasa
     * {@link jakarta.validation.constraints.Positive} pero al redondearse a tres decimales queda en
     * cero, y uno como {@code 99999999.999} excede la columna. Ambos deben responderse como 400
     * antes de tocar la base, no como un 500 o un 409 de integridad.
     */
    private static BigDecimal cantidadTresDecimales(BigDecimal cantidad, String campo) {
        var normalizada = cantidad.setScale(3, RoundingMode.HALF_UP);
        if (normalizada.signum() <= 0 || normalizada.compareTo(MAXIMO_CANTIDAD_DETALLE) >= 0) {
            throw new ParametroInvalidoException("VALIDACION_FALLIDA",
                    campo + " debe ser mayor que cero y menor que 10000000, con hasta 3 decimales.");
        }
        return normalizada;
    }

    private static RecetaResponse aResponse(Receta receta, List<DetalleReceta> detalles) {
        var lineas = detalles.stream().map(RecetaService::aDetalleResponse).toList();
        var total = lineas.stream()
                .map(DetalleRecetaResponse::costoEstimado)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        var porUnidad = total.divide(receta.getCantidadProducir(), 2, RoundingMode.HALF_UP);

        return new RecetaResponse(
                receta.getIdReceta(),
                new ProductoRefResponse(receta.getIdProducto(), receta.getNombreProducto()),
                receta.getNombre(),
                receta.getCantidadProducir().setScale(2, RoundingMode.HALF_UP),
                new UnidadRefResponse(
                        receta.getIdUnidad(), receta.getNombreUnidad(),
                        receta.getAbreviaturaUnidad()),
                Boolean.TRUE.equals(receta.getActivo()),
                !lineas.isEmpty(),
                lineas,
                total,
                porUnidad);
    }

    private static DetalleRecetaResponse aDetalleResponse(DetalleReceta detalle) {
        var cantidad = detalle.getCantidad().setScale(3, RoundingMode.HALF_UP);
        var costo = cantidad.multiply(detalle.getCostoUnitario()).setScale(2, RoundingMode.HALF_UP);
        return new DetalleRecetaResponse(
                detalle.getIdDetalleReceta(),
                new MateriaPrimaRefResponse(
                        detalle.getIdMateriaPrima(), detalle.getNombreMateriaPrima(),
                        detalle.getAbreviaturaUnidad()),
                cantidad,
                costo);
    }
}
