package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.compra.dto.ActualizarCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CompraResponse;
import co.edu.corposucre.productionfood.compra.dto.CrearCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.ProveedorRefResponse;
import co.edu.corposucre.productionfood.proveedor.ProveedorRepository;

/**
 * Alta y edición de la cabecera de una compra (HU-13).
 *
 * <p>Este componente cubre solo la cabecera: el alta ({@link #crear(CrearCompraRequest)}) y la
 * edición de sus dos campos editables ({@link #actualizar(Integer, ActualizarCompraRequest)}).
 * El listado, la consulta, el cambio de estado y las líneas de detalle viven en los demás
 * componentes de la HU-13 y de HU-14.
 *
 * <p>Los códigos de error son los de la especificación §5 y no el genérico
 * {@code VALIDACION_FALLIDA}: una regla de negocio violada responde 409
 * ({@link ConflictoNegocioException}) y un dato de entrada fuera de rango, 400
 * ({@link ParametroInvalidoException}).
 */
@Service
public class CompraService {

    private final CompraRepository compraRepository;

    private final ProveedorRepository proveedorRepository;

    public CompraService(CompraRepository compraRepository,
                         ProveedorRepository proveedorRepository) {
        this.compraRepository = compraRepository;
        this.proveedorRepository = proveedorRepository;
    }

    /**
     * Registra una compra nueva, siempre en estado {@code PENDIENTE} (HU-13 §5).
     *
     * <p>Las validaciones siguen el orden de la especificación —proveedor, fecha y estado— para
     * que un error de referencia no se enmascare detrás de uno de formato:
     *
     * <ol>
     *   <li><strong>Proveedor</strong>: debe existir ({@code PROVEEDOR_INEXISTENTE}, 409, CP-02) y
     *       estar activo ({@code PROVEEDOR_INACTIVO}, 409, CP-03/R-06). La comprobación de
     *       actividad es exclusiva del alta: una compra ya registrada sigue su curso aunque su
     *       proveedor se desactive después, por eso R-06 no se repite en la edición.</li>
     *   <li><strong>Fecha</strong>: si se omite se registra la de hoy (CP-04); si es posterior a
     *       hoy se rechaza ({@code FECHA_FUTURA}, 400, R-07). Las fechas pasadas siempre se
     *       admiten, para poder registrar compras atrasadas (CP-06).</li>
     *   <li><strong>Estado</strong>: si se omite nace {@code PENDIENTE}; si se envía y no lo es se
     *       rechaza ({@code VALIDACION_FALLIDA}, 400). Una compra nace {@code PENDIENTE} y
     *       {@code RECIBIDA}/{@code CANCELADA} solo se alcanzan por transición de estado (diseño
     *       §4 «Al crear, el estado es siempre PENDIENTE»): dejar que alguien cree una
     *       {@code RECIBIDA} directamente saltaría la recepción y esa compra no sumaría stock a
     *       existencias (R-01).</li>
     * </ol>
     *
     * <p>La compra se inserta sin líneas de detalle: el total y la cantidad de ítems responden
     * {@code 0.00} y {@code 0} hasta que HU-14 agrega el detalle, que es el estado válido de una
     * compra recién registrada.
     *
     * @param req proveedor obligatorio, fecha y estado opcionales de la compra
     * @return la compra insertada, con total {@code 0.00}, cero ítems y {@code editable} a
     *         {@code true}
     * @throws ConflictoNegocioException si el proveedor no existe o está inactivo
     * @throws ParametroInvalidoException si la fecha es futura o el estado enviado no es
     *                                   {@code PENDIENTE}
     */
    @Transactional
    // En las expresiones de detalle los accesores de record se leen como propiedades
    // (#resultado.idCompra) y no como llamadas: el aspecto no resuelve llamadas de record.
    @Auditable(accion = "CREAR", tabla = "compras",
            detalle = "'Compra creada: ' + #resultado.idCompra"
                    + " + ' del proveedor ' + #req.idProveedor")
    public CompraResponse crear(CrearCompraRequest req) {
        var proveedor = proveedorRepository.findById(req.idProveedor())
                .orElseThrow(() -> new ConflictoNegocioException("PROVEEDOR_INEXISTENTE",
                        String.format("El proveedor con id %s no existe.", req.idProveedor())));
        if (Boolean.FALSE.equals(proveedor.getEstado())) {
            throw new ConflictoNegocioException("PROVEEDOR_INACTIVO",
                    String.format("El proveedor con id %s está inactivo; no puede recibir "
                            + "compras nuevas.", req.idProveedor()));
        }

        var hoy = LocalDate.now();
        var fecha = req.fecha() == null ? hoy : req.fecha();
        if (fecha.isAfter(hoy)) {
            throw new ParametroInvalidoException("FECHA_FUTURA",
                    "La fecha de la compra no puede ser posterior a hoy.");
        }

        var estado = req.estado() == null ? EstadoCompra.PENDIENTE : req.estado();
        if (estado != EstadoCompra.PENDIENTE) {
            throw new ParametroInvalidoException("VALIDACION_FALLIDA",
                    "Una compra solo puede registrarse en estado PENDIENTE; RECIBIDA y CANCELADA "
                            + "se alcanzan por transición de estado.");
        }

        var compra = new Compra();
        compra.setIdProveedor(proveedor.getIdProveedor());
        compra.setFecha(fecha);
        compra.setEstado(estado);
        var idCompra = compraRepository.insertar(compra);

        return new CompraResponse(idCompra,
                new ProveedorRefResponse(proveedor.getIdProveedor(), proveedor.getNombre()),
                fecha, estado.name(), new BigDecimal("0.00"), 0, true);
    }

    /**
     * Edita el proveedor y la fecha de una compra mientras siga {@code PENDIENTE} (HU-13 R-04).
     *
     * <p>El estado no se toca por aquí: tiene su endpoint propio, con la máquina de transiciones
     * de {@link EstadoCompra}. Las validaciones, en el orden de la especificación:
     *
     * <ol>
     *   <li>La compra debe existir; si no, 404 (patrón del resto del proyecto).</li>
     *   <li>Debe estar {@code PENDIENTE} ({@code COMPRA_NO_EDITABLE}, 409, R-04): una
     *       {@code RECIBIDA} ya asentó su recepción y su stock, y una {@code CANCELADA} cerró el
     *       ciclo; ninguna de las dos admite reescribir su cabecera.</li>
     *   <li>El proveedor debe existir ({@code PROVEEDOR_INEXISTENTE}, 409, CP-02), pero aquí
     *       <strong>no</strong> se exige que esté activo: R-06 reserva esa comprobación solo para
     *       el alta, de modo que reasignar una compra ya registrada no depende del estado actual
     *       del proveedor.</li>
     *   <li>La fecha es obligatoria —la revalida el servicio aunque el {@code @NotNull} del DTO ya
     *       la rechaza en la capa HTTP— y no puede ser posterior a hoy ({@code FECHA_FUTURA}, 400,
     *       R-07).</li>
     * </ol>
     *
     * <p>La respuesta se relee de la base tras la escritura para que {@code totalCompra} y
     * {@code cantidadItems}, derivados de {@code detalle_compra}, sean los de la fila y no una
     * copia en memoria.
     *
     * @param id   identificador de la compra a editar
     * @param req  proveedor y fecha nuevos; ninguno puede ser {@code null}
     * @return la compra releída de la base, con sus totales derivados
     * @throws RecursoNoEncontradoException si la compra no existe (también si desaparece entre la
     *                                      escritura y la relectura)
     * @throws ConflictoNegocioException si la compra no está {@code PENDIENTE} o el proveedor no
     *                                   existe
     * @throws ParametroInvalidoException si la fecha es futura
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "compras",
            detalle = "'Compra editada: ' + #resultado.idCompra")
    public CompraResponse actualizar(Integer id, ActualizarCompraRequest req) {
        var compra = compraRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));

        if (compra.getEstado() != EstadoCompra.PENDIENTE) {
            throw new ConflictoNegocioException("COMPRA_NO_EDITABLE",
                    String.format("La compra con id %s no es editable: solo las compras PENDIENTE "
                            + "admiten cambios en su cabecera.", id));
        }

        var proveedor = proveedorRepository.findById(req.idProveedor())
                .orElseThrow(() -> new ConflictoNegocioException("PROVEEDOR_INEXISTENTE",
                        String.format("El proveedor con id %s no existe.", req.idProveedor())));
        // R-06 deliberadamente no comprueba aquí que el proveedor esté activo: esa regla es solo
        // del alta (CP-03), y una compra PENDIENTE ya registrada no se bloquea por desactivarla.

        if (req.fecha() == null) {
            // Defensivo: el @NotNull del DTO ya devuelve 400 VALIDACION_FALLIDA en la capa HTTP.
            throw new ParametroInvalidoException("VALIDACION_FALLIDA",
                    "La fecha de la compra es obligatoria.");
        }
        if (req.fecha().isAfter(LocalDate.now())) {
            throw new ParametroInvalidoException("FECHA_FUTURA",
                    "La fecha de la compra no puede ser posterior a hoy.");
        }

        compraRepository.actualizar(id, proveedor.getIdProveedor(), req.fecha());

        return compraRepository.buscarPorId(id)
                .map(CompraService::aResponse)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));
    }

    /**
     * Convierte una fila de {@link CompraRepository} en la respuesta del contrato.
     *
     * <p>{@code totalCompra} y {@code cantidadItems} llegan ya calculados de la base y
     * {@code editable} se deriva de la misma máquina de estados que valida la edición: solo una
     * compra {@code PENDIENTE} admite cambios.
     */
    private static CompraResponse aResponse(Compra compra) {
        return new CompraResponse(
                compra.getIdCompra(),
                new ProveedorRefResponse(compra.getIdProveedor(), compra.getNombreProveedor()),
                compra.getFecha(),
                compra.getEstado().name(),
                compra.getTotalCompra(),
                compra.getCantidadItems(),
                compra.getEstado() == EstadoCompra.PENDIENTE);
    }
}
