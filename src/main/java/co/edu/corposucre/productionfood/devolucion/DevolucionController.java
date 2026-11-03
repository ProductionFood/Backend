package co.edu.corposucre.productionfood.devolucion;

import java.net.URI;
import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.devolucion.dto.AprobacionResponse;
import co.edu.corposucre.productionfood.devolucion.dto.CambiarEstadoDevolucionRequest;
import co.edu.corposucre.productionfood.devolucion.dto.CrearDevolucionRequest;
import co.edu.corposucre.productionfood.devolucion.dto.DevolucionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints del ciclo de vida de una devolución (HU-18).
 *
 * <p>Esta colección expone el alta de una devolución sobre una línea de un pedido, la consulta
 * paginada con filtros —incluida la lista de devoluciones de un pedido, como subrecurso
 * {@code /pedidos/{id}/devoluciones}— y las dos transiciones de la máquina R-04: aprobar, que
 * repone el stock en los lotes de los que salió la línea (R-01, R-02), y rechazar, que no toca
 * inventario. El efecto no se decide aquí: lo garantiza {@link DevolucionService} —control de
 * cantidad contra el acumulado de la línea, pedido devolible y máquina de transiciones— y el
 * controlador solo lo expone.
 *
 * <p>Registrar una devolución solo reporta la mercancía: la fila nace {@code PENDIENTE} y no
 * mueve inventario (R-01), así que el alta no tiene estado ni fecha en su contrato: el primero
 * lo fija el servicio y la segunda la base con su {@code DEFAULT (curdate())} (R-06).
 *
 * <p>No existe un DELETE y no debe añadirse: una devolución es el registro de mercancía
 * devuelta contra un pedido y su estado es la trazabilidad de la reposición; borrarla
 * rompería el histórico de la bitácora y dejaría el kardex con entradas sin origen.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md, fila Devoluciones) da lectura a ADMIN,
 * VENTAS y CONSULTA, y escritura (alta y transiciones de estado) a ADMIN y VENTAS. PRODUCCION
 * no accede a este módulo ni siquiera en lectura, a diferencia de los pedidos: es la decisión
 * ya acordada para esta historia y manda sobre la columna de roles de la especificación §2.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Devoluciones", description = "Registro, consulta y ciclo de estado de las "
        + "devoluciones de un pedido (HU-18)")
public class DevolucionController {

    private final DevolucionService devolucionService;

    public DevolucionController(DevolucionService devolucionService) {
        this.devolucionService = devolucionService;
    }

    @GetMapping("/devoluciones")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','CONSULTA')")
    @Operation(summary = "Listar devoluciones",
               description = "Listado paginado de devoluciones con su pedido, su cliente, el "
                        + "producto devuelto y el compromiso de la línea. Los cuatro filtros "
                        + "son opcionales y se pueden combinar: estado, como valor canónico "
                        + "del enum, deja solo las devoluciones en ese estado (un literal no "
                        + "canónico responde 400 PARAMETRO_INVALIDO); fechaInicio y fechaFin "
                        + "delimitan un rango sobre la fecha de devolución, un rango invertido "
                        + "responde 400 RANGO_FECHAS_INVALIDO y una fecha mal formada responde "
                        + "400 PARAMETRO_INVALIDO; idPedido acota a un "
                        + "pedido. Se puede ordenar por fechaDevolucion, estado o id (por "
                        + "defecto fechaDevolucion descendente) y un campo fuera de esa lista "
                        + "blanca responde 400 CAMPO_ORDEN_INVALIDO. La validación de page, "
                        + "size y sort la hace el servicio (1 ≤ size ≤ 100).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden "
                + "inválidos, rango de fechas invertido, o estado o fecha no canónicos "
                + "(PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO, RANGO_FECHAS_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<DevolucionResponse> listar(
            @RequestParam(required = false) EstadoDevolucion estado,
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin,
            @RequestParam(required = false) Integer idPedido,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return devolucionService.listar(estado, fechaInicio, fechaFin, idPedido, page, size, sort);
    }

    @GetMapping("/devoluciones/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','CONSULTA')")
    @Operation(summary = "Obtener una devolución por id",
               description = "Devuelve la devolución con su pedido y cliente, el producto "
                        + "devuelto, lo pedido en la línea, lo devuelto hasta ahora y lo que "
                        + "aún queda por devolver (R-03). Una lectura no mueve stock ni cambia "
                        + "estados. Responde 404 si el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Devolución encontrada"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Devolución inexistente "
                + "(RECURSO_NO_ENCONTRADO)")
    })
    public DevolucionResponse obtener(@PathVariable Integer id) {
        return devolucionService.obtener(id);
    }

    @GetMapping("/pedidos/{idPedido}/devoluciones")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','CONSULTA')")
    @Operation(summary = "Listar las devoluciones de un pedido",
               description = "Listado paginado de las devoluciones de un pedido concreto, con "
                        + "los mismos campos y las mismas reglas de orden que el listado "
                        + "general; estado y rango de fechas no se aceptan aquí porque el "
                        + "propio pedido ya acota el conjunto. Un pedido sin devoluciones "
                        + "—o inexistente— responde una página vacía con total 0, no un 404: "
                        + "comprobar la existencia del pedido no añadiría información y sí un "
                        + "caso de error que el endpoint no define.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Página de devoluciones del pedido, "
                + "vacía si no tiene ninguna"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden "
                + "inválidos, o id de pedido no numérico (PARAMETRO_INVALIDO, "
                + "CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<DevolucionResponse> listarPorPedido(
            @PathVariable Integer idPedido,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return devolucionService.listarPorPedido(idPedido, page, size, sort);
    }

    @PostMapping("/devoluciones")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Registrar una devolución",
               description = "Registra la devolución de una línea de un pedido, siempre en "
                        + "estado PENDIENTE y sin mover inventario (R-01): quien reporta la "
                        + "mercancía no decide si vuelve a la venta. El detalle de pedido es "
                        + "obligatorio y debe existir (409 DETALLE_PEDIDO_INEXISTENTE, CA-01) "
                        + "y pertenecer a un pedido ENTREGADO o EN_PREPARACION (409 "
                        + "PEDIDO_NO_DEVOLVIBLE, R-05). La cantidad debe ser mayor que cero "
                        + "(400 CANTIDAD_INVALIDA, CA-04) y no superar lo pedido contando las "
                        + "devoluciones previas pendientes o aprobadas de la línea (422 "
                        + "CANTIDAD_EXCEDE_PEDIDO, R-03). El motivo es obligatorio y no puede "
                        + "superar 50 caracteres ni la descripción 255 (400 "
                        + "VALIDACION_FALLIDA). El estado no se acepta en el cuerpo y la "
                        + "fecha la pone la base (R-06). Devuelve 201 con la cabecera "
                        + "Location apuntando a la devolución creada.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Devolución registrada en estado "
                + "PENDIENTE"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o cantidad no "
                + "positiva (VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "409", description = "Detalle de pedido inexistente o "
                + "pedido no devolible (DETALLE_PEDIDO_INEXISTENTE, PEDIDO_NO_DEVOLVIBLE)"),
        @ApiResponse(responseCode = "422", description = "La devolución excede lo pedido "
                + "contando las previas (CANTIDAD_EXCEDE_PEDIDO)")
    })
    public ResponseEntity<DevolucionResponse> crear(
            @Valid @RequestBody CrearDevolucionRequest req) {
        var creado = devolucionService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/devoluciones/" + creado.idDevolucion()))
                .body(creado);
    }

    @PatchMapping("/devoluciones/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Aprobar o rechazar una devolución",
               description = "Transición de estado según la máquina R-04: desde PENDIENTE se "
                        + "puede pasar a APROBADA o RECHAZADA, y una devolución APROBADA o "
                        + "RECHAZADA no admite ningún cambio más, porque ambos son estados "
                        + "terminales. Aprobar devuelve la mercancía a los lotes de los que "
                        + "salió la línea, en orden inverso al consumo (R-01, R-02), y "
                        + "devuelve en lotesRepuestos cada entrada con su saldo antes y "
                        + "después; rechazar no toca inventario y responde la lista vacía. Un "
                        + "destino prohibido responde 409 TRANSICION_INVALIDA con el estado "
                        + "actual y los permitidos (contrato §7). Devuelve 200 con la "
                        + "devolución releída de la base en su estado nuevo; responde 404 si "
                        + "la devolución no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado; al aprobar, "
                + "con los lotes que repusieron la mercancía"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido, estado obligatorio "
                + "o no canónico (VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Devolución inexistente "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Transición prohibida "
                + "(TRANSICION_INVALIDA)")
    })
    public AprobacionResponse cambiarEstado(@PathVariable Integer id,
                                            @Valid @RequestBody CambiarEstadoDevolucionRequest req) {
        return devolucionService.cambiarEstado(id, req);
    }
}
