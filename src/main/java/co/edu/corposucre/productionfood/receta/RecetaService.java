package co.edu.corposucre.productionfood.receta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.producto.ProductoRepository;
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
     * Crea una receta con su detalle inicial.
     *
     * @param req datos de la cabecera y de los ingredientes
     * @return la receta releída de la base, con sus costos estimados
     * @throws ConflictoNegocioException si el producto, la unidad o una materia prima no existen o
     *                                   están inactivos, si el producto ya tiene receta o si hay
     *                                   materias primas repetidas
     * @throws ParametroInvalidoException si {@code cantidadProducir} no es mayor que cero
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

        var receta = new Receta();
        receta.setIdProducto(req.idProducto());
        receta.setNombre(req.nombre().trim());
        receta.setCantidadProducir(req.cantidadProducir().setScale(2, RoundingMode.HALF_UP));
        receta.setIdUnidad(req.idUnidad());
        receta.setActivo(Boolean.TRUE);

        var idReceta = recetaRepository.insertar(receta);

        for (var linea : detalles) {
            var detalle = new DetalleReceta();
            detalle.setIdReceta(idReceta);
            detalle.setIdMateriaPrima(linea.idMateriaPrima());
            detalle.setCantidad(linea.cantidad().setScale(3, RoundingMode.HALF_UP));
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
        detalle.setCantidad(req.cantidad().setScale(3, RoundingMode.HALF_UP));
        try {
            detalleRecetaRepository.insertar(detalle);
        } catch (DuplicateKeyException ex) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_DUPLICADA",
                    "La materia prima indicada ya está en la receta.");
        }

        return aResponse(receta, detalleRecetaRepository.listarPorReceta(idReceta));
    }

    private void validarUnidad(Integer idUnidad) {
        unidadMedidaRepository.findById(idUnidad)
                .orElseThrow(() -> new ConflictoNegocioException(
                        "UNIDAD_INEXISTENTE", "La unidad de medida indicada no existe."));
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
