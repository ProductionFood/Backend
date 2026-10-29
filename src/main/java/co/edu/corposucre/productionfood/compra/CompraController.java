package co.edu.corposucre.productionfood.compra;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.compra.dto.ActualizarCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CambiarEstadoCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CompraResponse;
import co.edu.corposucre.productionfood.compra.dto.CrearCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.GastoProveedorResponse;
import co.edu.corposucre.productionfood.compra.dto.ResumenComprasResponse;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints del ciclo de vida de una compra (HU-13).
 *
 * <p>Esta colección expone el alta de compras a proveedor, su edición de cabecera mientras
 * siguen {@code PENDIENTE}, la consulta paginada con filtros y las dos transiciones de la
 * máquina de estados R-02: recibir, que es el punto principal de entrada de stock del sistema
 * (R-01), y cancelar. El efecto no se decide aquí: lo garantiza {@link CompraService} y el
 * controlador solo lo expone.
 *
 * <p>El PATCH de estado no es un único {@code cambiarEstado}: cada destino tiene su propio
 * método de servicio y su propia acción de bitácora (HU-04 R-06: {@code RECEPCION_COMPRA} para
 * la recepción, que es la que hace entrar stock, y {@code CAMBIAR_ESTADO} para el resto), y el
 * aspecto de auditoría toma la acción de la anotación del método interceptado. Por eso el
 * controlador despacha por el estado pedido; un destino que no tiene transición propia
 * ({@code PENDIENTE} no es destino de nadie, y las compras ya recibidas o canceladas no admiten
 * saltos) responde 409 {@code TRANSICION_INVALIDA} con la excepción que construye el servicio
 * (contrato §7).
 *
 * <p>No existe un DELETE y no debe añadirse: las compras referencian al proveedor y sus líneas
 * alimentan el kardex de materias primas; borrarlas rompería el historial de compras de HU-15 y
 * la trazabilidad del inventario.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, COMPRAS, PRODUCCION y
 * CONSULTA, y escritura (alta, edición de cabecera y transiciones de estado) a ADMIN y COMPRAS.
 */
@RestController
@RequestMapping("/api/v1/compras")
@Tag(name = "Compras", description = "Registro, consulta y ciclo de estado de las compras a "
        + "proveedor (HU-13)")
public class CompraController {

    private final CompraService compraService;

    public CompraController(CompraService compraService) {
        this.compraService = compraService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar compras",
               description = "Listado paginado de compras con su proveedor embebido, su estado, "
                        + "el total derivado del detalle y el número de líneas. Los cuatro "
                        + "filtros son opcionales y se pueden combinar: idProveedor acota a un "
                        + "proveedor (un id que no existe responde 404 PROVEEDOR_INEXISTENTE); "
                        + "estado, como valor canónico del enum, deja solo las "
                        + "compras en ese estado (un literal no canónico como ENVIADA responde "
                        + "400 PARAMETRO_INVALIDO); fechaInicio y fechaFin delimitan un rango "
                        + "inclusivo y un rango invertido responde 400 RANGO_FECHAS_INVALIDO. "
                        + "Se puede ordenar por fecha, estado, total o id, por defecto fecha "
                        + "descendente. La validación de page, size y sort la hace el "
                        + "servicio (1 ≤ size ≤ 100). Con incluirDetalle=true cada compra del "
                        + "contenido añade su lista de líneas de detalle en la forma de HU-14 "
                        + "(una sola consulta, sin consulta por compra) y el tamaño de página "
                        + "se recorta a 20 como máximo si lo excede, reflejándose en la "
                        + "respuesta; sin incluirDetalle la respuesta no lleva el campo detalle "
                        + "y la cota de size sigue en 100 (CP-12).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación, orden o "
                + "rango de fechas inválidos, o estado no canónico (PARAMETRO_INVALIDO, "
                + "CAMPO_ORDEN_INVALIDO, RANGO_FECHAS_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "El filtro idProveedor apunta a un "
                + "proveedor inexistente (PROVEEDOR_INEXISTENTE)")
    })
    public PageResponse<CompraResponse> listar(
            @RequestParam(required = false) Integer idProveedor,
            @RequestParam(required = false) EstadoCompra estado,
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean incluirDetalle) {
        return compraService.listar(idProveedor, estado, fechaInicio, fechaFin, page, size, sort,
                incluirDetalle);
    }

    @GetMapping("/resumen")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Resumen de compras por período",
               description = "Resume las compras del período agrupadas por estado: cantidad y "
                        + "total de las recibidas, pendientes y canceladas, más el gasto "
                        + "ejecutado —solo el total de las RECIBIDA— y el comprometido —solo el "
                        + "total de las PENDIENTE—; las canceladas quedan fuera de ambos "
                        + "(R-04) y los totales se recalculan siempre del detalle (R-05). "
                        + "fechaInicio y fechaFin son opcionales, inclusivos y en formato ISO "
                        + "(yyyy-MM-dd); un rango invertido responde 400 RANGO_FECHAS_INVALIDO. "
                        + "Un período sin compras responde 200 con todas las cantidades en 0 y "
                        + "los totales en 0.00 (CP-17). A diferencia del listado, el resumen "
                        + "no admite PRODUCCION ni CONSULTA: la matriz de roles de HU-15 lo "
                        + "reserva a ADMIN y COMPRAS.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Resumen del período, con compras o "
                + "con todo en cero"),
        @ApiResponse(responseCode = "400", description = "Rango de fechas invertido "
                + "(RANGO_FECHAS_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public ResumenComprasResponse resumen(
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin) {
        return compraService.resumen(fechaInicio, fechaFin);
    }

    @GetMapping("/por-proveedor")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Gasto de compras por proveedor",
               description = "Resume las compras RECIBIDA del período agrupadas por proveedor: "
                        + "cantidad de compras distintas, total de sus líneas —recalculado "
                        + "siempre del detalle (R-05)— y el porcentaje del total del proveedor "
                        + "sobre la suma del período, a escala 2. Solo cuenta el gasto "
                        + "ejecutado: las PENDIENTE y las CANCELADA quedan fuera, y un "
                        + "proveedor sin recibidas en el rango no aparece. El listado viene "
                        + "ordenado por total descendente (CP-15) y los porcentajes suman "
                        + "100.00 con el residuo de redondeo permitido (CP-16). fechaInicio y "
                        + "fechaFin son opcionales, inclusivos y en formato ISO (yyyy-MM-dd); "
                        + "un rango invertido responde 400 RANGO_FECHAS_INVALIDO. Un período "
                        + "sin compras recibidas responde 200 con la lista vacía. Igual que "
                        + "el resumen, la matriz de roles de HU-15 lo reserva a ADMIN y "
                        + "COMPRAS.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de gastos por proveedor, con "
                + "compras o vacía"),
        @ApiResponse(responseCode = "400", description = "Rango de fechas invertido "
                + "(RANGO_FECHAS_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public List<GastoProveedorResponse> gastoPorProveedor(
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin) {
        return compraService.gastoPorProveedor(fechaInicio, fechaFin);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Obtener una compra por id",
               description = "Devuelve la compra con su proveedor, su estado, el total derivado "
                        + "del detalle, el número de líneas y si todavía es editable. Una "
                        + "lectura no mueve stock ni cambia estados. Responde 404 si el id no "
                        + "existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Compra encontrada"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Compra inexistente "
                + "(RECURSO_NO_ENCONTRADO)")
    })
    public CompraResponse obtener(@PathVariable Integer id) {
        return compraService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Registrar una compra",
               description = "El estado es obligatorio y solo admite PENDIENTE: la compra nace "
                        + "en ese estado y RECIBIDA y CANCELADA se alcanzan solo por transición "
                        + "(R-02), así que un estado canónico distinto de PENDIENTE responde 400 "
                        + "VALIDACION_FALLIDA. El proveedor debe existir (CA-01) y estar activo "
                        + "(CA-02); una compra con un proveedor inactivo responde 409 "
                        + "PROVEEDOR_INACTIVO. La fecha es opcional (hoy si se omite; un null "
                        + "explícito responde 400 VALIDACION_FALLIDA en el campo fecha) y no "
                        + "puede ser futura (CA-03, FECHA_FUTURA). Los totales nacen en cero: se "
                        + "derivan del detalle, que agrega HU-14. Devuelve 201 con la cabecera "
                        + "Location apuntando a la compra creada.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Compra registrada en estado "
                + "PENDIENTE"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos (VALIDACION_FALLIDA, "
                + "FECHA_FUTURA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "409", description = "Proveedor inexistente o inactivo "
                + "(PROVEEDOR_INEXISTENTE, PROVEEDOR_INACTIVO)")
    })
    public ResponseEntity<CompraResponse> crear(
            @Valid @RequestBody CrearCompraRequest req) {
        var creado = compraService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/compras/" + creado.idCompra()))
                .body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Editar la cabecera de una compra",
               description = "Cambia el proveedor y la fecha de una compra. Solo una compra "
                        + "PENDIENTE es editable (R-04): en cualquier otro estado responde 409 "
                        + "COMPRA_NO_EDITABLE. El proveedor nuevo debe existir (CA-01); a "
                        + "diferencia del alta no se exige que esté activo, porque una compra "
                        + "ya registrada no se bloquea por desactivar a su proveedor. La fecha "
                        + "no puede ser futura (CA-03, FECHA_FUTURA). El estado no se modifica "
                        + "por aquí: tiene su propio endpoint. Responde 404 si la compra no "
                        + "existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Compra actualizada"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación "
                + "fallida (VALIDACION_FALLIDA, FECHA_FUTURA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Compra inexistente "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Proveedor inexistente o compra no "
                + "editable (PROVEEDOR_INEXISTENTE, COMPRA_NO_EDITABLE)")
    })
    public CompraResponse actualizar(@PathVariable Integer id,
                                     @Valid @RequestBody ActualizarCompraRequest req) {
        return compraService.actualizar(id, req);
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Recibir o cancelar una compra",
               description = "Transición de estado según la máquina R-02: desde PENDIENTE solo "
                        + "se puede pasar a RECIBIDA o a CANCELADA, y una compra RECIBIDA o "
                        + "CANCELADA no admite ningún cambio más. El endpoint despacha por el "
                        + "estado pedido —y no expone un único cambiarEstado— porque cada "
                        + "transición tiene su propia acción de bitácora (HU-04 R-06): RECIBIDA "
                        + "llama a la recepción, que suma la mercancía al inventario y al "
                        + "kardex (R-01) y exige que la compra tenga líneas de detalle "
                        + "(COMPRA_SIN_DETALLE); CANCELADA llama a la cancelación. Un destino "
                        + "sin transición propia —PENDIENTE, que no es destino de nadie— "
                        + "responde 409 TRANSICION_INVALIDA con el estado actual y los "
                        + "permitidos (contrato §7). Responde 404 si la compra no existe. 200 "
                        + "devuelve RecepcionResponse cuando el destino es RECIBIDA y "
                        + "CompraResponse cuando es CANCELADA.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado: "
                + "RecepcionResponse si el destino es RECIBIDA, CompraResponse si es CANCELADA"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido, estado obligatorio "
                + "o no canónico (VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Compra inexistente "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Transición prohibida o compra sin "
                + "detalle (TRANSICION_INVALIDA, COMPRA_SIN_DETALLE)")
    })
    public ResponseEntity<?> cambiarEstado(@PathVariable Integer id,
                                           @Valid @RequestBody CambiarEstadoCompraRequest req) {
        return switch (req.estado()) {
            case RECIBIDA -> ResponseEntity.ok(compraService.recibir(id));
            case CANCELADA -> ResponseEntity.ok(compraService.cancelar(id));
            default -> throw compraService.transicionInvalida(id, req.estado());
        };
    }

    /**
     * Reserva los paths de solo lectura de HU-15 frente a cualquier intento de escritura.
     *
     * <p>{@code /resumen} y {@code /por-proveedor} no tienen endpoint de escritura (HU-15
     * R-01). Sin este mapping explícito, un PUT —por ejemplo— encajaría en el
     * {@code @PutMapping("/{id}")} de HU-13 y fallaría al convertir «resumen» a entero,
     * respondiendo 400 PARAMETRO_INVALIDO en lugar del 405 que exige el contrato (CP-21).
     * El path literal es más específico que {@code /{id}} y solo declara los métodos de
     * escritura, de modo que los GET de {@code /resumen} y {@code /por-proveedor} siguen
     * resolviéndose sin ambigüedad a sus handlers.
     *
     * <p>El método no hace nada: lanza {@link HttpRequestMethodNotSupportedException}, que
     * {@code GlobalExceptionHandler} ya serializa como 405 METODO_NO_PERMITIDO con el cuerpo
     * estándar. No lleva {@code @PreAuthorize} porque no toca datos ni pasa por el servicio;
     * la autenticación la exige la cadena de seguridad global.
     *
     * @param request petición entrante, solo para leer el método HTTP que se debe rechazar
     * @throws HttpRequestMethodNotSupportedException siempre; es el mecanismo de respuesta 405
     */
    @RequestMapping(value = {"/resumen", "/por-proveedor"},
            method = {RequestMethod.POST, RequestMethod.PUT,
                      RequestMethod.PATCH, RequestMethod.DELETE})
    public void escriturasNoPermitidas(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod());
    }
}
