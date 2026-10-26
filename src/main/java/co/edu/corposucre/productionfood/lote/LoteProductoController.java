package co.edu.corposucre.productionfood.lote;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;
import co.edu.corposucre.productionfood.lote.dto.CrearLoteProductoRequest;
import co.edu.corposucre.productionfood.lote.dto.LoteProductoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints de lotes de producto terminado (HU-19).
 *
 * <p>Esta colección es la puerta de entrada del stock vendible: el alta de un lote crea, en una
 * sola transacción, el registro en {@code lotes}, su saldo en
 * {@code inventario_producto_terminado} y el movimiento {@code ENTRADA_PRODUCCION} del kardex
 * (R-02). Sin esa fila de inventario HU-17 no tiene nada que despachar y cualquier venta respondería
 * {@code STOCK_INSUFICIENTE} (R-01). El efecto no se decide aquí: lo garantiza
 * {@link LoteProductoService#crearLoteProducto} y el controlador solo lo expone.
 *
 * <p>La tabla {@code lotes} es compartida con los lotes de materia prima (HU-09), pero cada
 * historia ve solo su mitad: todas las consultas de esta ruta exigen {@code idProducto} informado,
 * así que un lote de materia prima responde 404 (R-06). La respuesta publica la cantidad de ingreso
 * y la disponible por separado porque {@code lotes.cantidad} nunca cambia y no sirve para saber qué
 * queda (R-03).
 *
 * <p>No existe un DELETE y no debe añadirse: los movimientos del kardex referencian el lote como
 * origen y borrarlo rompería la trazabilidad que exige un retiro sanitario.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, VENTAS, PRODUCCION y
 * CONSULTA, escritura a ADMIN y PRODUCCION, y el kardex a ADMIN, PRODUCCION y VENTAS. CONSULTA
 * queda fuera del kardex porque expone el detalle operativo de quién movió el stock y con qué
 * documento.
 */
@RestController
@RequestMapping("/api/v1/lotes-producto")
@Tag(name = "Lotes de producto terminado",
     description = "Registro y consulta de lotes de producto terminado (HU-19)")
public class LoteProductoController {

    private final LoteProductoService loteProductoService;

    public LoteProductoController(LoteProductoService loteProductoService) {
        this.loteProductoService = loteProductoService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar lotes de producto terminado",
               description = "Listado paginado de lotes de producto terminado con el nombre del "
                        + "producto y la abreviatura de su unidad. Cada fila trae la cantidad "
                        + "ingresada y la disponible por separado (R-03), los días para vencer, "
                        + "si el lote está vencido y el stock total del producto. Los filtros son "
                        + "opcionales y se pueden combinar: idProducto acota a un producto; "
                        + "soloVencidos deja únicamente los lotes cuya fecha de vencimiento ya "
                        + "pasó; proximosAVencer, en días y mayor que cero, deja los que vencen "
                        + "dentro de esa ventana; conStock deja solo los lotes con saldo. Se "
                        + "puede ordenar por codigoLote, fechaProduccion, fechaVencimiento o id, "
                        + "por defecto fechaVencimiento ascendente. La validación de page, size y "
                        + "sort la hace el servicio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación, orden o "
                + "ventana de vencimiento inválidos (PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<LoteProductoResponse> listar(
            @RequestParam(required = false) Integer idProducto,
            @RequestParam(required = false) Boolean soloVencidos,
            @RequestParam(required = false) Integer proximosAVencer,
            @RequestParam(required = false) Boolean conStock,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return loteProductoService.listar(idProducto, soloVencidos, proximosAVencer, conStock,
                page, size, sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Obtener un lote de producto terminado por id",
               description = "Devuelve el lote con su producto, las fechas de producción y "
                        + "vencimiento, la cantidad ingresada, la cantidad disponible en el "
                        + "estante, los días que faltan para vencer, si ya está vencido y el "
                        + "stock total del producto. Una lectura no mueve stock. Responde 404 si "
                        + "el id no existe o si corresponde a un lote de materia prima, que no es "
                        + "de esta historia (R-06).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lote encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Lote inexistente o de materia prima")
    })
    public LoteProductoResponse obtener(@PathVariable Integer id) {
        return loteProductoService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Registrar un lote de producto terminado",
               description = "Crea el lote y, en la misma transacción, su saldo inicial en "
                        + "inventario_producto_terminado y el movimiento ENTRADA_PRODUCCION del "
                        + "kardex (R-02): las tres filas o ninguna. Ese saldo es lo que convierte "
                        + "el lote en stock vendible para HU-17 (R-01) y nace igual a la cantidad "
                        + "ingresada. El código se recorta y se guarda en mayúsculas (R-08) y debe "
                        + "ser único incluso frente a lotes de materia prima (CA-01). El producto "
                        + "debe existir (CA-02). El vencimiento no puede ser anterior a la "
                        + "producción (CA-03) y, a diferencia de HU-09, tampoco puede estar en el "
                        + "pasado (R-05). La cantidad debe ser mayor que cero (CA-04). Devuelve "
                        + "201 con la cabecera Location.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Lote registrado con su stock inicial"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o regla de negocio "
                + "incumplida (VALIDACION_FALLIDA, FECHAS_INVALIDAS, LOTE_YA_VENCIDO, "
                + "CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "El lote no pudo releerse tras el alta"),
        @ApiResponse(responseCode = "409", description = "Código de lote duplicado o producto "
                + "inexistente (CODIGO_LOTE_DUPLICADO, PRODUCTO_INEXISTENTE)")
    })
    public ResponseEntity<LoteProductoResponse> crear(
            @Valid @RequestBody CrearLoteProductoRequest req) {
        var creado = loteProductoService.crearLoteProducto(req);
        return ResponseEntity
                .created(URI.create("/api/v1/lotes-producto/" + creado.idLote()))
                .body(creado);
    }

    @GetMapping("/{id}/movimientos")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','VENTAS')")
    @Operation(summary = "Consultar el kardex de un lote de producto terminado",
               description = "Historial paginado de movimientos del lote (R-02): qué entró o "
                        + "salió, cuándo, quién y por qué documento, con los saldos anterior y "
                        + "posterior. Explica cómo se llegó a la cantidad disponible actual y es "
                        + "la base de la trazabilidad ante un retiro sanitario. Se ordena siempre "
                        + "del movimiento más reciente al más antiguo y no acepta sort. Un lote "
                        + "existente sin movimientos devuelve una página vacía; si el lote no "
                        + "existe o es de materia prima, responde 404.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Kardex paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación inválidos "
                + "(PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Lote inexistente o de materia prima")
    })
    public PageResponse<MovimientoResponse> movimientos(
            @PathVariable Integer id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return loteProductoService.movimientos(id, page, size);
    }
}
