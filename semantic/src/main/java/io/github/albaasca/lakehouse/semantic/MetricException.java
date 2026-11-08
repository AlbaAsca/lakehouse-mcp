package io.github.albaasca.lakehouse.semantic;

/** A metric definition is wrong, or a call does not fit it. The message says which file or parameter. */
public class MetricException extends RuntimeException {

    public MetricException(String message) {
        super(message);
    }

    public MetricException(String message, Throwable cause) {
        super(message, cause);
    }
}
