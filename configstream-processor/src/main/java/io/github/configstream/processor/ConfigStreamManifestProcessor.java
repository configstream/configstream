package io.github.configstream.processor;

import io.github.configstream.api.ConfigStreamManifest;
import io.github.configstream.api.Manifest;
import io.github.configstream.api.ManifestException;
import io.github.configstream.api.ManifestParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Generates typed {@code Property} constants from the manifest named by {@link ConfigStreamManifest}, and checks the
 * manifest and its environment files ({@code configstream-<environment>.yml}) while compiling, so mistakes fail the
 * build rather than the deployment.
 *
 * <p>The manifest is looked up among the application's resources: in the class output (where Maven copies resources
 * before compiling), then in a {@code resources} folder next to the sources (Gradle, IDEs). The
 * {@code -Aconfigstream.resources=<dir>} option names the folder explicitly for other layouts.
 */
@SupportedAnnotationTypes("io.github.configstream.api.ConfigStreamManifest")
@SupportedOptions(ConfigStreamManifestProcessor.RESOURCES_OPTION)
public class ConfigStreamManifestProcessor extends AbstractProcessor {

    static final String RESOURCES_OPTION = "configstream.resources";

    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private boolean generated;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Set<? extends Element> annotated = round.getElementsAnnotatedWith(ConfigStreamManifest.class);
        if (annotated.isEmpty()) {
            return false;
        }
        if (generated || annotated.size() > 1) {
            for (Element element : annotated) {
                error("@ConfigStreamManifest may appear only once per application.", element);
            }
            return true;
        }
        generated = true;
        Element element = annotated.iterator().next();
        ConfigStreamManifest annotation = element.getAnnotation(ConfigStreamManifest.class);
        try {
            generate(element, annotation);
        } catch (ManifestException | IllegalArgumentException e) {
            error(e.getMessage(), element);
        } catch (IOException e) {
            error("Could not generate the property constants: " + e, element);
        }
        return true;
    }

    private void generate(Element element, ConfigStreamManifest annotation) throws IOException {
        String location = annotation.value();
        Located manifestFile = locate(location, element);
        if (manifestFile == null) {
            error("Manifest '" + location + "' not found among the application's resources (e.g. src/main/resources/"
                    + location + "). Create it, or point -A" + RESOURCES_OPTION + " at your resources folder.", element);
            return;
        }
        String fileName = fileName(location);
        Manifest manifest;
        try (InputStream in = manifestFile.open()) {
            manifest = ManifestParser.parse(in, fileName);
        }
        for (Path environmentFile : environmentFiles(manifestFile, fileName)) {
            try (InputStream in = Files.newInputStream(environmentFile)) {
                ManifestParser.applyEnvironment(manifest, in, environmentFile.getFileName().toString());
            }
        }
        String packageName = annotation.packageName().isEmpty() ? packageOf(element) : annotation.packageName();
        for (ConstantsWriter.GeneratedClass generatedClass : ConstantsWriter.write(manifest, packageName, fileName)) {
            String qualified = packageName.isEmpty() ? generatedClass.simpleName()
                    : packageName + "." + generatedClass.simpleName();
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualified, element);
            try (Writer writer = file.openWriter()) {
                writer.write(generatedClass.source());
            }
        }
    }

    /** The manifest file, found by the option, in the class output, or in a resources folder near the sources. */
    private Located locate(String location, Element element) {
        String resourcesDir = processingEnv.getOptions().get(RESOURCES_OPTION);
        if (resourcesDir != null) {
            Path path = Path.of(resourcesDir).resolve(location);
            return Files.isRegularFile(path) ? Located.of(path) : null;
        }
        try {
            FileObject resource = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", location);
            try (InputStream probe = resource.openInputStream()) {
                return new Located(resource, pathOf(resource.toUri()));
            }
        } catch (IOException | IllegalArgumentException notThere) {
            // Not copied to the class output (e.g. Gradle keeps resources separately); look next to the sources
        }
        Path source = sourceFileOf(element);
        for (Path dir = source == null ? null : source.getParent(); dir != null; dir = dir.getParent()) {
            for (Path candidate : List.of(dir.resolve("resources").resolve(location),
                    dir.resolve("src").resolve("main").resolve("resources").resolve(location))) {
                if (Files.isRegularFile(candidate)) {
                    return Located.of(candidate);
                }
            }
        }
        return null;
    }

    /** {@code configstream-<environment>.yml} files next to the manifest, when it is a file on disk. */
    private static List<Path> environmentFiles(Located manifest, String fileName) throws IOException {
        List<Path> files = new ArrayList<>();
        if (manifest.path() == null || manifest.path().getParent() == null) {
            return files;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        String extension = dot < 0 ? "" : fileName.substring(dot);
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(manifest.path().getParent(), stem + "-*" + extension)) {
            for (Path sibling : siblings) {
                String name = sibling.getFileName().toString();
                String environment = name.substring(stem.length() + 1, name.length() - extension.length());
                if (ENVIRONMENT.matcher(environment).matches() && Files.isRegularFile(sibling)) {
                    files.add(sibling);
                }
            }
        }
        files.sort(null);
        return files;
    }

    private Path sourceFileOf(Element element) {
        try {
            com.sun.source.util.Trees trees = com.sun.source.util.Trees.instance(processingEnv);
            var path = trees.getPath(element);
            return path == null ? null : pathOf(path.getCompilationUnit().getSourceFile().toUri());
        } catch (RuntimeException | LinkageError notJavac) {
            return null; // another compiler; the class output or the option must be used
        }
    }

    private String packageOf(Element element) {
        Elements elements = processingEnv.getElementUtils();
        PackageElement pkg = element instanceof PackageElement p ? p : elements.getPackageOf(element);
        return pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString();
    }

    private static String fileName(String location) {
        int slash = location.lastIndexOf('/');
        return slash < 0 ? location : location.substring(slash + 1);
    }

    private static Path pathOf(URI uri) {
        try {
            return "file".equals(uri.getScheme()) ? Path.of(uri) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void error(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    /** A manifest found either as a compiler resource or as a plain file; {@code path} is null if not on disk. */
    private record Located(FileObject resource, Path path) {

        static Located of(Path path) {
            return new Located(null, path);
        }

        InputStream open() throws IOException {
            return resource != null ? resource.openInputStream() : Files.newInputStream(path);
        }
    }
}
