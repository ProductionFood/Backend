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
import co.edu.corposucre.productionfood.lote.dto.CrearLoteMateriaPrimaRequest;
import co.edu.corposucre.productionfood.lote.dto.LoteMateriaPrimaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints de lotes de materia prima (HU-09).
 *
 * <p>La historia expone solo la colección, el recurso por id y el alta: listar con filtros,
 * obtener uno y registrar un lote. No existe un DELETE y no debe añadirse: la tabla {@code lotes}
 * es compartida con los lotes de producto terminado (HU-19) y los movimientos del kardex la
 * referencian como origen, así que borrar un lote rompería la trazabilidad del inventario.
 *
 * <p>El alta es el punto de entrada de stock de esta historia (R-01), pero la bifurcación crítica
 * es la protección contra la doble suma (R-02): un lote que llega con {@code idDetalleCompra} nace
 * de una compra ya recibida y no vuelve a sumar, mientras que un alta manual sí suma. Esa decisión
 * vive en {@link LoteService#crear}, no aquí; el controlador se limita a exponerla y a devolver
 * los saldos anterior y posterior para que el efecto sea visible en la respuesta.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, PRODUCCION, COMPRAS y
 * CONSULTA, y escritura a ADMIN, PRODUCCION y COMPRAS. VENTAS no participa en el módulo.
 */
@RestController
@RequestMapping("/api/v1/lotes-materia-prima")
@Tag(name = "Lotes de materia prima",
     description = "Registro y consulta de lotes de materia prima (HU-09)")
public class LoteController {

    private final LoteService loteService;

    public LoteController(LoteService loteService) {
        this.loteService = loteService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','CONSULTA')")
    @Operation(summary = "Listar lotes de materia prima",
               description = "Listado paginado de lotes de materia prima con el nombre de su "
                        + "materia prima y la abreviatura de su unidad. Los filtros son "
                        + "opcionales y se pueden combinar: idMateriaPrima acota a una materia "
                        + "prima; soloVencidos deja únicamente los lotes cuya fecha de "
                        + "vencimiento ya pasó; proximosAVencer, en días y mayor que cero, deja "
                        + "los que vencen dentro de esa ventana. Se puede ordenar por "
                        + "codigoLote, fechaProduccion, fechaVencimiento, cantidad o id, por "
                        + "defecto fechaVencimiento ascendente. La validación de page, size y "
                        + "sort la hace el servicio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación, orden o "
                + "ventana de vencimiento inválidos (PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<LoteMateriaPrimaResponse> listar(
            @RequestParam(required = false) Integer idMateriaPrima,
            @RequestParam(required = false) Boolean soloVencidos,
            @RequestParam(required = false) Integer proximosAVencer,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return loteService.listar(idMateriaPrima, soloVencidos, proximosAVencer, page, size, sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','CONSULTA')")
    @Operation(summary = "Obtener un lote de materia prima por id",
               description = "Devuelve el lote con su materia prima, las fechas de producción y "
                        + "vencimiento, la cantidad, los días que faltan para vencer y si ya "
                        + "está vencido. Una lectura no mueve stock, así que la respuesta no "
                        + "incluye inventarioActualizado. Responde 404 si el id no existe o si "
                        + "corresponde a un lote de producto terminado, que no es de esta "
                        + "historia.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lote encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Lote inexistente")
    })
    public LoteMateriaPrimaResponse obtener(@PathVariable Integer id) {
        return loteService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS')")
    @Operation(summary = "Registrar un lote de materia prima",
               description = "Crea un lote de materia prima y lo suma al inventario cuando es un "
                        + "alta manual. Según la regla R-02, si el cuerpo trae idDetalleCompra el "
                        + "lote nace de una compra ya recibida y NO vuelve a sumar: el stock ya "
                        + "entró con esa recepción, y hacerlo marcaría el doble de existencias "
                        + "sin ningún error visible. Solo el alta sin idDetalleCompra suma, y en "
                        + "ese caso la respuesta incluye inventarioActualizado con los saldos "
                        + "anterior y posterior. El código se recorta y se guarda en mayúsculas "
                        + "(R-06) y debe ser único. El vencimiento no puede ser anterior a la "
                        + "producción, aunque sí puede ser igual (R-04). Un lote ya vencido se "
                        + "registra igualmente y sí suma al inventario, marcado con vencido=true "
                        + "(R-05). Devuelve 201 con la cabecera Location.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Lote registrado"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o regla de negocio "
                + "incumplida (VALIDACION_FALLIDA, FECHAS_INVALIDAS, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "El lote no pudo releerse tras el alta"),
        @ApiResponse(responseCode = "409", description = "Código de lote duplicado o materia "
                + "prima inexistente (CODIGO_LOTE_DUPLICADO, MATERIA_PRIMA_INEXISTENTE)")
    })
    public ResponseEntity<LoteMateriaPrimaResponse> crear(
            @Valid @RequestBody CrearLoteMateriaPrimaRequest req) {
        var creado = loteService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/lotes-materia-prima/" + creado.idLote()))
                .body(creado);
    }
}
