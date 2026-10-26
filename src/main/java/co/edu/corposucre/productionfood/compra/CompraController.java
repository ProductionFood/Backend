package co.edu.corposucre.productionfood.compra;

import java.net.URI;
import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.compra.dto.ActualizarCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CambiarEstadoCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CompraResponse;
import co.edu.corposucre.productionfood.compra.dto.CrearCompraRequest;
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
                        + "proveedor; estado, como valor canónico del enum, deja solo las "
                        + "compras en ese estado (un literal no canónico como ENVIADA responde "
                        + "400 PARAMETRO_INVALIDO); fechaInicio y fechaFin delimitan un rango "
                        + "inclusivo y un rango invertido responde 400 RANGO_FECHAS_INVALIDO. "
                        + "Se puede ordenar por fecha, estado o id, por defecto fecha "
                        + "descendente. La validación de page, size y sort la hace el "
                        + "servicio (1 ≤ size ≤ 100).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación, orden o "
                + "rango de fechas inválidos, o estado no canónico (PARAMETRO_INVALIDO, "
                + "CAMPO_ORDEN_INVALIDO, RANGO_FECHAS_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<CompraResponse> listar(
            @RequestParam(required = false) Integer idProveedor,
            @RequestParam(required = false) EstadoCompra estado,
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return compraService.listar(idProveedor, estado, fechaInicio, fechaFin, page, size, sort);
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
}
