package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.compra.dto.ActualizarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.AgregarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraListaResponse;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraResponse;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;
import co.edu.corposucre.productionfood.receta.dto.MateriaPrimaRefResponse;

/**
 * Líneas de detalle de una compra (HU-14): consulta, alta, edición y borrado del subrecurso.
 *
 * <p>Toda escritura exige primero que la compra exista (404 {@code COMPRA_INEXISTENTE}) y que
 * siga {@code PENDIENTE} (409 {@code COMPRA_NO_EDITABLE}, R-01) —incluido el borrado—: una
 * compra {@code RECIBIDA} ya asentó su stock y reescribir su detalle dejaría el documento en
 * desacuerdo con el inventario. La consulta ({@link #listarDetalles(Integer)}) no exige estado:
 * una compra recibida o cancelada se puede seguir leyendo, y su {@code editable} a {@code false}
 * es lo que le dice al consumidor que no escriba.
 *
 * <p>Reglas de negocio que este servicio carga y el DTO no puede expresar:
 *
 * <ul>
 *   <li><strong>R-02</strong>: la materia prima debe existir y estar activa
 *       ({@code MATERIA_PRIMA_INEXISTENTE}, 409) y no repetirse en la compra
 *       ({@code MATERIA_PRIMA_DUPLICADA}, 409), anticipando con un mensaje útil lo que el
 *       {@code UNIQUE uk_detalle_compra} detiene ante dos peticiones simultáneas, caso en el que
 *       el {@link DuplicateKeyException} del {@code INSERT} se traduce al mismo 409.</li>
 *   <li><strong>R-03</strong>: el total es del backend: siempre
 *       {@link DetalleCompraRepository#calcularTotal(Integer)} redondeado a escala 2 con
 *       {@code HALF_UP}, nunca una suma en memoria y nunca un {@code double}. Cada respuesta lo
 *       trae recalculado desde la base.</li>
 *   <li><strong>R-04</strong>: registrar o editar una línea <em>no</em> toca
 *       {@code materias_primas.costo_unitario}: el precio pagado es de esta compra y el costo de
 *       referencia es de HU-08. Ese costo solo se <em>lee</em> para exponer en cada respuesta de
 *       línea su {@code costoReferencia} y la {@code diferenciaCosto} contra el precio pagado.</li>
 *   <li><strong>R-05/R-07</strong>: la cantidad debe ser mayor que cero una vez redondeada a
 *       escala 2 (400 {@code CANTIDAD_INVALIDA}, segunda barrera tras el {@code @Positive} del
 *       DTO, que deja pasar valores como {@code 0.001}); el precio puede ser cero.</li>
 * </ul>
 *
 * <p>La edición de línea no admite cambiar la materia prima (R-02): para comprar otra cosa se
 * elimina la línea y se agrega otra. Los códigos de error son los de HU-14 §5, no el genérico
 * {@code VALIDACION_FALLIDA}.
 */
@Service
public class DetalleCompraService {

    private final CompraRepository compraRepository;

    private final DetalleCompraRepository detalleCompraRepository;

    private final MateriaPrimaRepository materiaPrimaRepository;

    public DetalleCompraService(CompraRepository compraRepository,
                                DetalleCompraRepository detalleCompraRepository,
                                MateriaPrimaRepository materiaPrimaRepository) {
        this.compraRepository = compraRepository;
        this.detalleCompraRepository = detalleCompraRepository;
        this.materiaPrimaRepository = materiaPrimaRepository;
    }

    /**
     * Devuelve las líneas de una compra con su total, su estado y si todavía es editable.
     *
     * <p>El total y los ítems salen de la base en cada llamada: {@code totalCompra} es el
     * {@code SUM(cantidad * precio_unitario)} redondeado a escala 2 con {@code HALF_UP} (R-03) y
     * {@code cantidadItems} es el tamaño de la lista devuelta, de modo que el número de ítems y
     * el array nunca pueden discrepar. Una compra sin líneas responde lista vacía y total
     * {@code 0.00}, que es el estado válido de una compra recién registrada (HU-13).
     *
     * <p>El comparativo de R-04 ({@code costoReferencia}/{@code diferenciaCosto}) se resuelve con
     * una sola consulta de costos para todas las materias primas del listado, nunca una por línea.
     *
     * @param idCompra identificador de la compra
     * @return sus líneas, el total recalculado, el estado y la bandera de edición
     * @throws CompraInexistenteException si la compra no existe (404)
     */
    @Transactional(readOnly = true)
    public DetalleCompraListaResponse listarDetalles(Integer idCompra) {
        var compra = buscarCompra(idCompra);

        var lineas = detalleCompraRepository.listarPorCompra(idCompra);
        var costos = detalleCompraRepository.costosReferenciaMateriaPrima(
                lineas.stream().map(DetalleCompra::idMateriaPrima).distinct().toList());
        var detalles = lineas.stream()
                .map(linea -> aResponse(linea, null, costos.get(linea.idMateriaPrima())))
                .toList();

        return new DetalleCompraListaResponse(
                idCompra,
                compra.getEstado().name(),
                compra.getEstado() == EstadoCompra.PENDIENTE,
                detalles,
                totalCompra(idCompra),
                detalles.size());
    }

    /**
     * Agrega una línea a una compra {@code PENDIENTE} (HU-14 R-01/R-02).
     *
     * <p>Orden de las validaciones, de la especificación §5:
     *
     * <ol>
     *   <li>La compra existe (404 {@code COMPRA_INEXISTENTE}) y está {@code PENDIENTE}
     *       (409 {@code COMPRA_NO_EDITABLE}).</li>
     *   <li>La materia prima existe y está activa (409 {@code MATERIA_PRIMA_INEXISTENTE}).</li>
     *   <li>La materia prima no es ya un ítem de la compra (409
     *       {@code MATERIA_PRIMA_DUPLICADA}, R-02); una carrera que supera esta comprobación y
     *       revienta el {@code UNIQUE} se responde con el mismo 409, nunca con un 409 genérico de
     *       integridad.</li>
     *   <li>La cantidad, redondeada a escala 2, sigue siendo positiva (400
     *       {@code CANTIDAD_INVALIDA}).</li>
     * </ol>
     *
     * <p>La respuesta se relee de la base tras el {@code INSERT} para traer nombre y unidad de la
     * materia prima, y el total de la compra vuelve a consultarse con
     * {@link DetalleCompraRepository#calcularTotal(Integer)}: la cifra que ve el cliente es
     * siempre la del servidor. El costo de referencia de la materia prima se lee una sola vez
     * para exponer el comparativo de R-04, sin escribir nunca esa columna.
     *
     * @param idCompra compra a la que se agrega la línea
     * @param req      materia prima, cantidad positiva y precio no negativo (R-05)
     * @return la línea insertada, con su subtotal, el total de la compra y el comparativo de costo
     * @throws CompraInexistenteException si la compra no existe (404)
     * @throws ConflictoNegocioException si la compra no es editable o la materia prima no existe
     *                                   o ya está en la compra
     * @throws ParametroInvalidoException si la cantidad no es mayor que cero
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "compras",
            detalle = "'Compra ' + #idCompra + ' con detalle agregado'")
    public DetalleCompraResponse agregarDetalle(Integer idCompra,
                                                AgregarDetalleCompraRequest req) {
        var compra = buscarCompra(idCompra);
        exigirPendiente(compra);

        if (!materiaPrimaRepository.existeActiva(req.idMateriaPrima())) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_INEXISTENTE",
                    "La materia prima indicada no existe o está inactiva.");
        }
        if (detalleCompraRepository.existeMateriaPrimaEnCompra(idCompra, req.idMateriaPrima())) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_DUPLICADA",
                    "La materia prima indicada ya está en la compra; edite la línea existente.");
        }

        var linea = new DetalleCompra(null, idCompra, req.idMateriaPrima(),
                validarCantidad(req.cantidad()), req.precioUnitario());
        Integer idDetalle;
        try {
            idDetalle = detalleCompraRepository.insertar(linea);
        } catch (DuplicateKeyException ex) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_DUPLICADA",
                    "La materia prima indicada ya está en la compra; edite la línea existente.");
        }

        var guardada = detalleCompraRepository.obtenerPorId(idDetalle)
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle));
        var costo = detalleCompraRepository
                .costoReferenciaDeMateriaPrima(guardada.idMateriaPrima()).orElse(null);
        return aResponse(guardada, totalCompra(idCompra), costo);
    }

    /**
     * Cambia la cantidad y el precio de una línea mientras su compra siga {@code PENDIENTE}
     * (HU-14 R-01).
     *
     * <p>La materia prima de la línea no se toca: el DTO no la trae y el servicio conserva la
     * original, porque el {@code UNIQUE} de la compra prohíbe repetirla (R-02); para comprar
     * otra cosa se elimina la línea y se agrega otra. La línea debe existir <strong>y</strong>
     * pertenecer a la compra de la ruta: una línea de otra compra se trata como inexistente
     * (404), no como prohibida, porque el subrecurso anidado solo conoce las líneas de su
     * compra.
     *
     * @param idCompra  compra de la que es la línea
     * @param idDetalle identificador de la línea
     * @param req       cantidad nueva positiva y precio no negativo, ambos a escala 2
     * @return la línea actualizada, con su subtotal, el total de la compra recalculado y el
     *         comparativo de costo
     * @throws CompraInexistenteException si la compra no existe (404)
     * @throws ConflictoNegocioException si la compra no está {@code PENDIENTE}
     * @throws RecursoNoEncontradoException si la línea no existe o no pertenece a la compra (404)
     * @throws ParametroInvalidoException si la cantidad no es mayor que cero
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "compras",
            detalle = "'Detalle ' + #idDetalle + ' de la compra ' + #idCompra + ' editado'")
    public DetalleCompraResponse actualizarDetalle(Integer idCompra, Integer idDetalle,
                                                   ActualizarDetalleCompraRequest req) {
        var compra = buscarCompra(idCompra);
        exigirPendiente(compra);
        var linea = lineaDeLaCompra(idCompra, idDetalle);

        var actualizada = new DetalleCompra(linea.idDetalle(), idCompra, linea.idMateriaPrima(),
                validarCantidad(req.cantidad()), req.precioUnitario(),
                linea.nombreMateriaPrima(), linea.abreviaturaUnidad());
        detalleCompraRepository.actualizar(actualizada);

        var costo = detalleCompraRepository
                .costoReferenciaDeMateriaPrima(actualizada.idMateriaPrima()).orElse(null);
        return aResponse(actualizada, totalCompra(idCompra), costo);
    }

    /**
     * Elimina una línea de una compra {@code PENDIENTE} (HU-14 R-06).
     *
     * <p>La verificación de estado <strong>también</strong> aplica aquí, que es donde más fácil
     * es olvidarla: una línea borrada de una compra ya {@code RECIBIDA} dejaría su total sin
     * correspondencia con el stock que entró, sin ninguna señal del desajuste. Es la única
     * eliminación física del subrecurso —una línea no tiene valor histórico mientras el documento
     * no se haya ejecutado— y las reglas de propiedad son las mismas que en la edición.
     *
     * @param idCompra  compra de la que es la línea
     * @param idDetalle identificador de la línea
     * @throws CompraInexistenteException si la compra no existe (404)
     * @throws ConflictoNegocioException si la compra no está {@code PENDIENTE}
     * @throws RecursoNoEncontradoException si la línea no existe o no pertenece a la compra (404)
     */
    @Transactional
    @Auditable(accion = "ELIMINAR", tabla = "detalle_compra",
            detalle = "'Detalle ' + #idDetalle + ' eliminado de la compra ' + #idCompra")
    public void eliminarDetalle(Integer idCompra, Integer idDetalle) {
        var compra = buscarCompra(idCompra);
        exigirPendiente(compra);
        var linea = lineaDeLaCompra(idCompra, idDetalle);
        detalleCompraRepository.eliminar(linea.idDetalle());
    }

    /**
     * Carga la compra o responde 404 con el código propio de HU-14.
     *
     * <p>No es {@link RecursoNoEncontradoException}: ese responde con el código transversal
     * {@code RECURSO_NO_ENCONTRADO} y CP-02 exige {@code COMPRA_INEXISTENTE} para distinguir que
     * el que falta es el documento padre.
     */
    private Compra buscarCompra(Integer idCompra) {
        return compraRepository.buscarPorId(idCompra)
                .orElseThrow(() -> new CompraInexistenteException(idCompra));
    }

    /**
     * Exige que la compra siga {@code PENDIENTE} antes de cualquier escritura (HU-14 R-01,
     * idéntica a la R-04 de HU-13 que valida la cabecera).
     *
     * <p>Una compra {@code RECIBIDA} ya asentó su recepción y su stock, y una {@code CANCELADA}
     * cerró el ciclo: modificar sus líneas desincronizaría el documento del inventario.
     */
    private static void exigirPendiente(Compra compra) {
        if (compra.getEstado() != EstadoCompra.PENDIENTE) {
            throw new ConflictoNegocioException("COMPRA_NO_EDITABLE",
                    String.format("La compra con id %s no es editable: solo las compras PENDIENTE "
                            + "admiten cambios en su detalle.", compra.getIdCompra()));
        }
    }

    /**
     * Localiza la línea dentro de la compra de la ruta.
     *
     * <p>Una línea que existe pero pertenece a otra compra responde 404 con el código transversal,
     * igual que en HU-12: el subrecurso anidado solo conoce sus propias líneas.
     */
    private DetalleCompra lineaDeLaCompra(Integer idCompra, Integer idDetalle) {
        return detalleCompraRepository.obtenerPorId(idDetalle)
                .filter(linea -> idCompra.equals(linea.idCompra()))
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle));
    }

    /**
     * Total de la compra recalculado desde la base, a escala 2 con {@code HALF_UP} (R-03).
     *
     * <p>El repositorio devuelve el {@code SUM} con la escala que trae MySQL —potencialmente
     * cuatro decimales, porque el producto de dos {@code decimal(10,2)} los tiene—; el redondeo
     * aquí es el que evita que un total llegue como {@code 111099.8889} o, peor, como un
     * {@code double} con centavos fantasma (CP-13).
     */
    private BigDecimal totalCompra(Integer idCompra) {
        return detalleCompraRepository.calcularTotal(idCompra).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Segunda barrera tras el {@code @Positive} del DTO (HU-14 R-05, §5 {@code CANTIDAD_INVALIDA}).
     *
     * <p>Un valor como {@code 0.001} pasa Bean Validation pero, redondeado a la escala 2 de la
     * columna (R-07), queda en cero: una línea de cero unidades no es una línea, y sin esta
     * comprobación llegaría al {@code CHECK (cantidad > 0)} como un error de integridad en lugar
     * de un 400 con mensaje.
     *
     * @return la cantidad normalizada a escala 2
     * @throws ParametroInvalidoException si la cantidad es nula o no mayor que cero tras redondear
     */
    private static BigDecimal validarCantidad(BigDecimal cantidad) {
        var normalizada = cantidad == null ? null : cantidad.setScale(2, RoundingMode.HALF_UP);
        if (normalizada == null || normalizada.signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad debe ser un número mayor que cero.");
        }
        return normalizada;
    }

    /**
     * Convierte una línea de {@link DetalleCompraRepository} en la respuesta del contrato.
     *
     * <p>{@code totalCompra} va en {@code null} cuando se construye desde el listado: ahí el
     * total agregado viaja una sola vez en {@link DetalleCompraListaResponse}, y en las
     * respuestas de línea se rellena con el total recién consultado.
     *
     * <p>{@code costoReferencia} llega ya consultado —una línea suelta con su materia prima, el
     * listado con el mapa resuelto en una sola llamada— y con él se calcula la
     * {@code diferenciaCosto} del comparativo R-04.
     */
    private static DetalleCompraResponse aResponse(DetalleCompra linea, BigDecimal totalCompra,
                                                   BigDecimal costoReferencia) {
        return new DetalleCompraResponse(
                linea.idDetalle(),
                new MateriaPrimaRefResponse(linea.idMateriaPrima(), linea.nombreMateriaPrima(),
                        linea.abreviaturaUnidad()),
                linea.cantidad(),
                linea.precioUnitario(),
                linea.subtotal(),
                totalCompra,
                costoReferencia,
                diferenciaDeCosto(linea.precioUnitario(), costoReferencia));
    }

    /**
     * Comparativo de R-04: lo pagado menos el costo de referencia, a escala 2 con {@code HALF_UP}.
     *
     * <p>El resultado puede ser positivo (la línea salió más cara de lo previsto), negativo (más
     * barata) o cero. Sin costo de referencia no hay comparativo posible: se devuelve {@code
     * null} y, como la aplicación solo serializa propiedades no nulas, el campo ni siquiera
     * aparece en la respuesta.
     *
     * @param precioUnitario  precio pagado en esta línea
     * @param costoReferencia {@code materias_primas.costo_unitario} vigente, o {@code null}
     * @return la diferencia a escala 2, o {@code null} si no hay costo de referencia
     */
    private static BigDecimal diferenciaDeCosto(BigDecimal precioUnitario,
                                                BigDecimal costoReferencia) {
        if (costoReferencia == null) {
            return null;
        }
        return precioUnitario.subtract(costoReferencia).setScale(2, RoundingMode.HALF_UP);
    }
}
