package io.github.configstream.spring;

import io.github.configstream.api.Manifest;
import io.github.configstream.api.ManifestException;
import io.github.configstream.api.ManifestParser;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/** Reads {@code configstream.yml} and, when an environment is set, {@code configstream-<environment>.yml} next to it. */
final class ManifestLoader {

    private static final Logger log = LoggerFactory.getLogger(ManifestLoader.class);

    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private ManifestLoader() {
    }

    static Manifest load(ResourceLoader resources, String location, String environment) {
        if (environment != null && !ENVIRONMENT.matcher(environment).matches()) {
            throw new IllegalStateException("configstream.environment '" + environment
                    + "' may only contain letters, digits, '-' and '_'.");
        }
        Resource base = resources.getResource(location);
        if (!base.exists()) {
            log.warn("No manifest at {}: this application declares no properties, so none are created on startup. "
                    + "Add configstream.yml to declare them.", location);
            return Manifest.empty();
        }
        String baseName = base.getFilename() != null ? base.getFilename() : location;
        Manifest manifest = read(base, baseName, null);
        if (environment != null) {
            String envName = environmentFileName(baseName, environment);
            Resource envFile = relative(base, envName);
            if (envFile != null && envFile.exists()) {
                manifest = read(envFile, envName, manifest);
                log.info("Loaded {} properties from {}, with initial values from {}",
                        manifest.properties().size(), baseName, envName);
                return manifest;
            }
            log.info("No {} found; properties created in environment '{}' use the initial values from {}",
                    envName, environment, baseName);
        }
        log.info("Loaded {} properties from {}", manifest.properties().size(), baseName);
        return manifest;
    }

    /** {@code configstream.yml} and {@code prod} give {@code configstream-prod.yml}. */
    static String environmentFileName(String baseName, String environment) {
        int dot = baseName.lastIndexOf('.');
        return dot < 0 ? baseName + "-" + environment : baseName.substring(0, dot) + "-" + environment + baseName.substring(dot);
    }

    private static Manifest read(Resource resource, String name, Manifest base) {
        try (InputStream in = resource.getInputStream()) {
            return base == null ? ManifestParser.parse(in, name) : ManifestParser.applyEnvironment(base, in, name);
        } catch (IOException e) {
            throw new ManifestException(name, "could not be read: " + e.getMessage(), e);
        }
    }

    private static Resource relative(Resource base, String name) {
        try {
            return base.createRelative(name);
        } catch (IOException e) {
            return null;
        }
    }
}
