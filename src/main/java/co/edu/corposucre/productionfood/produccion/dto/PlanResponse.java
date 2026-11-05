package co.edu.corposucre.productionfood.produccion.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta con un plan de producción y su responsable (HU-20).
 *
 * <p>El responsable se embebe como {@link ResponsableRef} para mostrarlo sin una llamada
 * extra, igual que las demás respuestas del proyecto; el contrato lo define como
 * {@code responsable: {idUsuario, nombre}}.
 *
 * <p>{@code estado} viaja como {@code String} (el nombre canónico, por ejemplo
 * {@code "PLANIFICADO"}): es lo que el contrato expone al consumidor y evita que un cambio de
 * empaquetado del enum se filtre al JSON.
 *
 * <p>{@code cantidadProductos} es derivado de {@code detalle_plan_produccion}, no columna de
 * la cabecera: un plan recién registrado responde {@code 0} hasta que HU-21 agrega sus líneas.
 *
 * <p>{@code observaciones} lleva {@code @JsonInclude(ALWAYS)} porque la inclusión global de
 * Jackson es {@code non_null}: sin esa anotación, un plan sin observaciones perdería la clave
 * del JSON y el contrato de HU-20 exige verla como {@code null} (CP-08).
 *
 * <p>{@code editable} lo calcula el servicio a partir de la máquina de estados de
 * {@code EstadoPlan} y de R-05: solo un plan {@code PLANIFICADO} admite edición de cabecera, y
 * el booleano le ahorra al consumidor reimplementar esa regla.
 *
 * @param idPlan              identificador del plan
 * @param fechaPlanificacion  fecha en que se planificó (CA-01)
 * @param fechaProduccion     fecha programada de producción (CA-01, R-03)
 * @param estado              estado actual (CA-02)
 * @param observaciones       detalle opcional del plan (CA-03)
 * @param responsable         usuario responsable del plan (CA-04, R-04)
 * @param cantidadProductos   líneas de {@code detalle_plan_produccion} del plan
 * @param editable            {@code true} si la cabecera admite edición (R-05)
 */
public record PlanResponse(
        Integer idPlan,
        LocalDate fechaPlanificacion,
        LocalDate fechaProduccion,
        String estado,
        @JsonInclude(JsonInclude.Include.ALWAYS) String observaciones,
        ResponsableRef responsable,
        Integer cantidadProductos,
        Boolean editable) {

    /**
     * Referencia embebida al usuario responsable del plan.
     *
     * @param idUsuario identificador del usuario
     * @param nombre    nombre del usuario, del {@code JOIN} con {@code usuarios}
     */
    public record ResponsableRef(
            Integer idUsuario,
            String nombre) {
    }
}
