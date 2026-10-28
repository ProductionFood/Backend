package co.edu.corposucre.productionfood.compra;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.compra.dto.ActualizarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.AgregarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraListaResponse;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Líneas de detalle de una compra (HU-14).
 *
 * <p>Subrecurso anidado bajo {@code /compras/{idCompra}/detalles}: expone las líneas de una
 * compra —materia prima, cantidad, precio unitario y subtotal— y sus cuatro operaciones. El
 * controlador solo despacha al {@link DetalleCompraService}: que la compra exista y esté
 * {@code PENDIENTE}, que la materia prima no se repita en la compra y que el total lo calcule el
 * backend (R-01, R-02, R-03) son decisiones del servicio, no de aquí.
 *
 * <p>La consulta no exige estado: una compra {@code RECIBIDA} o {@code CANCELADA} se puede
 * listar, y su {@code editable=false} es lo que le dice al consumidor que no escriba. Las tres
 * escrituras sí exigen {@code PENDIENTE} —incluso el borrado (R-06)— y responden 409
 * {@code COMPRA_NO_EDITABLE} en cualquier otro estado: una compra ya recibida asentó su stock y
 * reescribir su detalle dejaría el documento en desacuerdo con el inventario.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, COMPRAS, PRODUCCION y
 * CONSULTA, y escritura a ADMIN y COMPRAS.
 */
@RestController
@RequestMapping("/api/v1/compras/{idCompra}/detalles")
@Tag(name = "Detalle de compras", description = "Líneas de detalle de una compra: consulta, "
        + "alta, edición y borrado del subrecurso (HU-14)")
public class DetalleCompraController {

    private final DetalleCompraService detalleCompraService;

    public DetalleCompraController(DetalleCompraService detalleCompraService) {
        this.detalleCompraService = detalleCompraService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar el detalle de una compra",
               description = "Devuelve las líneas de la compra con su total recalculado desde "
                        + "la base (SUM(cantidad * precio_unitario), R-03), su estado y si "
                        + "todavía es editable. No exige que la compra esté PENDIENTE: una "
                        + "compra RECIBIDA o CANCELADA se puede listar y su editable=false "
                        + "indica que no admite cambios. Responde 404 COMPRA_INEXISTENTE si el "
                        + "id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Detalle de la compra, con su total "
                + "y su estado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Compra inexistente "
                + "(COMPRA_INEXISTENTE)")
    })
    public DetalleCompraListaResponse listar(@PathVariable Integer idCompra) {
        return detalleCompraService.listarDetalles(idCompra);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Agregar un ítem a una compra",
               description = "Registra una línea con su materia prima, cantidad y precio "
                        + "unitario mientras la compra siga PENDIENTE (R-01); en cualquier otro "
                        + "estado responde 409 COMPRA_NO_EDITABLE. La materia prima debe existir "
                        + "y estar activa (409 MATERIA_PRIMA_INEXISTENTE) y no puede repetirse "
                        + "en la compra (409 MATERIA_PRIMA_DUPLICADA, R-02): para comprar otra "
                        + "cosa se edita la línea existente. La cantidad debe superar cero (400 "
                        + "CANTIDAD_INVALIDA); el precio puede ser cero porque hay muestras y "
                        + "bonificaciones (R-05). El subtotal y el total de la compra los "
                        + "calcula el backend con HALF_UP (R-03) y viajan en la respuesta. "
                        + "Devuelve 201 con la cabecera Location apuntando a la línea creada.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Línea agregada, con su subtotal y "
                + "el total de la compra"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad no "
                + "positiva (VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Compra inexistente "
                + "(COMPRA_INEXISTENTE)"),
        @ApiResponse(responseCode = "409", description = "Compra no editable o materia prima "
                + "inexistente o duplicada (COMPRA_NO_EDITABLE, MATERIA_PRIMA_INEXISTENTE, "
                + "MATERIA_PRIMA_DUPLICADA)")
    })
    public ResponseEntity<DetalleCompraResponse> agregar(
            @PathVariable Integer idCompra,
            @Valid @RequestBody AgregarDetalleCompraRequest req) {
        var detalle = detalleCompraService.agregarDetalle(idCompra, req);
        return ResponseEntity
                .created(URI.create("/api/v1/compras/" + idCompra + "/detalles/"
                        + detalle.idDetalleCompra()))
                .body(detalle);
    }

    @PutMapping("/{idDetalle}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Editar la cantidad y el precio de un ítem",
               description = "Cambia la cantidad y el precio unitario de una línea mientras su "
                        + "compra siga PENDIENTE (R-01); una compra RECIBIDA o CANCELADA "
                        + "responde 409 COMPRA_NO_EDITABLE. La materia prima de la línea no se "
                        + "reemplaza, porque el UNIQUE de la compra prohíbe repetirla (R-02): "
                        + "para comprar otra cosa se elimina la línea y se agrega otra. La "
                        + "cantidad nueva debe superar cero (400 CANTIDAD_INVALIDA); el precio "
                        + "puede ser cero (R-05). Devuelve la línea con su subtotal y el total "
                        + "de la compra recalculado (R-03). Responde 404 si la compra no existe "
                        + "o la línea no pertenece a ella.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Línea actualizada, con su subtotal "
                + "y el total de la compra"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad no "
                + "positiva (VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Compra inexistente "
                + "(COMPRA_INEXISTENTE), o línea inexistente o de otra compra "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Compra no editable "
                + "(COMPRA_NO_EDITABLE)")
    })
    public DetalleCompraResponse actualizar(@PathVariable Integer idCompra,
                                            @PathVariable Integer idDetalle,
                                            @Valid @RequestBody ActualizarDetalleCompraRequest req) {
        return detalleCompraService.actualizarDetalle(idCompra, idDetalle, req);
    }

    @DeleteMapping("/{idDetalle}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Quitar un ítem de una compra",
               description = "Elimina físicamente la línea de detalle mientras la compra siga "
                        + "PENDIENTE (R-06). Es la única eliminación del subrecurso: una línea "
                        + "quitada no tiene valor histórico propio mientras el documento no se "
                        + "haya ejecutado, y una compra ya RECIBIDA no admite perder líneas "
                        + "porque su total debe corresponder al stock que entró (R-01). La "
                        + "línea debe pertenecer a la compra de la ruta; si no, se responde "
                        + "404. Devuelve 204 sin cuerpo.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Línea eliminada"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Compra inexistente "
                + "(COMPRA_INEXISTENTE), o línea inexistente o de otra compra "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Compra no editable "
                + "(COMPRA_NO_EDITABLE)")
    })
    public ResponseEntity<Void> eliminar(@PathVariable Integer idCompra,
                                         @PathVariable Integer idDetalle) {
        detalleCompraService.eliminarDetalle(idCompra, idDetalle);
        return ResponseEntity.noContent().build();
    }
}
