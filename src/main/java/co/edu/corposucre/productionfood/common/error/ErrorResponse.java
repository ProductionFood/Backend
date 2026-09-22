package co.edu.corposucre.productionfood.common.error;

import java.util.List;

public record ErrorResponse(
    String timestamp,
    int status,
    String error,
    String code,
    String message,
    String path,
    List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {}
}
