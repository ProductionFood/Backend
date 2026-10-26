package co.edu.corposucre.productionfood.compra.dto;

import java.time.LocalDate;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Deserializador de {@code fecha} que distingue la omisión de un {@code null} explícito (HU-13).
 *
 * <p>CA-02 pide la fecha obligatoria «por defecto la fecha actual»: omitir el campo debe crear la
 * compra con la de hoy (CP-04), pero un {@code null} escrito a mano es un error del cliente y
 * debe responder {@code 400 VALIDACION_FALLIDA} con {@code fieldErrors[0].field == "fecha"}.
 * Bean Validation no sirve para eso porque una omisión y un null llegan igual a {@code @NotNull};
 * la distinción solo existe aquí, en la deserialización, y se apoya en que Jackson 3 no trata igual
 * los dos casos: un null explícito cae en {@link #getNullValue} (o llega como token
 * {@code VALUE_NULL} a {@link #deserialize}), mientras que un campo ausente cae en
 * {@link #getAbsentValue}, que sigue devolviendo {@code null} para que mande el default defensivo
 * del servicio (fecha de hoy).
 *
 * <p>En Jackson 3 la clase base es {@link ValueDeserializer}, no {@code JsonDeserializer}.
 */
public class LocalDateObligatoriaDeserializer extends ValueDeserializer<LocalDate> {

    @Override
    public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return rechazarNullExplicito(ctxt);
        }
        return LocalDate.parse(p.getString());
    }

    @Override
    public LocalDate getNullValue(DeserializationContext ctxt) {
        return rechazarNullExplicito(ctxt);
    }

    /**
     * Un campo ausente devuelve {@code null} y no el rechazo de {@link #getNullValue}: en
     * Jackson 3 la implementación por defecto delega en {@code getNullValue}, así que sin este
     * override la omisión de {@code fecha} (CP-04) se confundiría con un null explícito.
     */
    @Override
    public Object getAbsentValue(DeserializationContext ctxt) {
        return null;
    }

    private static LocalDate rechazarNullExplicito(DeserializationContext ctxt) {
        return ctxt.reportInputMismatch(LocalDate.class,
                "El campo 'fecha' no admite null explícito; omítelo para usar la fecha de hoy.");
    }
}
