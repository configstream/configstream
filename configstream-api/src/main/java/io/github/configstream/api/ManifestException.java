package io.github.configstream.api;

/** A manifest or environment file that can't be used, with the file and the problem in the message. */
public class ManifestException extends RuntimeException {

    public ManifestException(String source, String problem) {
        super(source + ": " + problem);
    }

    public ManifestException(String source, String problem, Throwable cause) {
        super(source + ": " + problem, cause);
    }
}
