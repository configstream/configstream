package io.github.configstream.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compiles small applications with the processor, laid out the way Maven and Gradle lay them out. */
class ConfigStreamManifestProcessorTest {

    private static final String MANIFEST = """
            properties:
              - key: feature.funds.enabled
                type: boolean
                initialValue: false
              - key: feature.funds.limit
                type: int
                initialValue: 3
              - key: fees.rate
                type: decimal
                initialValue: 0.25
            """;

    private static final String APPLICATION = """
            package com.example.orders;

            @io.github.configstream.api.ConfigStreamManifest
            public class OrdersApplication {
            }
            """;

    /** Code that reads the generated constants with their declared types. */
    private static final String USAGE = """
            package com.example.orders;

            class Usage {
                int limit = Feature.FUNDS_LIMIT.initialValue();
                boolean enabled = Feature.FUNDS_ENABLED.initialValue();
                java.math.BigDecimal rate = Fees.RATE.initialValue();
            }
            """;

    @TempDir
    Path project;

    @Test
    void generatesTypedConstantsFromTheManifestInTheClassOutput() throws IOException {
        // Maven copies resources into the class output before compiling
        write("target/classes/configstream.yml", MANIFEST);

        Result result = compile(List.of(APPLICATION, USAGE));

        assertThat(result.errors()).isEmpty();
        assertThat(result.generated("com/example/orders/Feature.java"))
                .contains("public static final Property<Integer> FUNDS_LIMIT")
                .contains("public static final Property<Boolean> FUNDS_ENABLED");
        assertThat(result.generated("com/example/orders/Fees.java")).contains("public static final Property<BigDecimal> RATE");
    }

    @Test
    void findsTheManifestInAResourcesFolderNextToTheSources() throws IOException {
        // Gradle keeps resources out of the class output
        write("src/main/resources/configstream.yml", MANIFEST);

        assertThat(compile(List.of(APPLICATION, USAGE)).errors()).isEmpty();
    }

    @Test
    void theResourcesFolderCanBeNamedExplicitly() throws IOException {
        write("config/configstream.yml", MANIFEST);

        Result result = compile(List.of(APPLICATION, USAGE), "-Aconfigstream.resources=" + project.resolve("config"));

        assertThat(result.errors()).isEmpty();
    }

    @Test
    void aMisspelledKeyDoesNotCompile() throws IOException {
        write("target/classes/configstream.yml", MANIFEST);

        Result result = compile(List.of(APPLICATION, """
                package com.example.orders;
                class Usage { Object limit = Feature.FUNDS_LIMT; }
                """));

        assertThat(result.errors()).singleElement().asString().contains("FUNDS_LIMT");
    }

    @Test
    void readingAPropertyAsTheWrongTypeDoesNotCompile() throws IOException {
        write("target/classes/configstream.yml", MANIFEST);

        Result result = compile(List.of(APPLICATION, """
                package com.example.orders;
                class Usage { boolean limit = Feature.FUNDS_LIMIT.initialValue(); }
                """));

        assertThat(result.errors()).singleElement().asString().containsIgnoringCase("incompatible types");
    }

    @Test
    void theGeneratedPackageCanBeChosen() throws IOException {
        write("target/classes/configstream.yml", MANIFEST);

        Result result = compile(List.of("""
                package com.example.orders;
                @io.github.configstream.api.ConfigStreamManifest(packageName = "com.example.orders.config")
                public class OrdersApplication { }
                """, """
                package com.example.orders;
                class Usage { int limit = com.example.orders.config.Feature.FUNDS_LIMIT.initialValue(); }
                """));

        assertThat(result.errors()).isEmpty();
        assertThat(result.generated("com/example/orders/config/Feature.java")).startsWith("package com.example.orders.config;");
    }

    @Test
    void aMissingManifestFailsTheBuild() {
        Result result = compile(List.of(APPLICATION));

        assertThat(result.errors()).singleElement().asString()
                .contains("Manifest 'configstream.yml' not found among the application's resources");
    }

    @Test
    void anInvalidManifestFailsTheBuildNamingTheFileAndProperty() throws IOException {
        write("target/classes/configstream.yml", """
                properties:
                  - key: feature.funds.limit
                    type: int
                    initialValue: lots
                """);

        Result result = compile(List.of(APPLICATION));

        assertThat(result.errors()).singleElement().asString()
                .contains("configstream.yml: property 'feature.funds.limit' is declared as int, but its value \"lots\"");
    }

    @Test
    void environmentFilesAreCheckedAtBuildTime() throws IOException {
        write("target/classes/configstream.yml", MANIFEST);
        write("target/classes/configstream-qa.yml", "properties:\n  feature.funds.limit: 5\n");
        write("target/classes/configstream-prod.yml", "properties:\n  feature.funds.limt: 10\n");

        Result result = compile(List.of(APPLICATION));

        assertThat(result.errors()).singleElement().asString()
                .contains("configstream-prod.yml: property 'feature.funds.limt' is not declared in the base manifest");
    }

    @Test
    void keysThatWouldClashFailTheBuild() throws IOException {
        write("target/classes/configstream.yml", """
                properties:
                  - key: feature.max-items
                    type: int
                    initialValue: 1
                  - key: feature.max_items
                    type: int
                    initialValue: 2
                """);

        assertThat(compile(List.of(APPLICATION)).errors()).singleElement().asString()
                .contains("would both generate Feature.MAX_ITEMS");
    }

    @Test
    void theAnnotationMayAppearOnlyOnce() throws IOException {
        write("target/classes/configstream.yml", MANIFEST);

        Result result = compile(List.of(APPLICATION, """
                package com.example.orders;
                @io.github.configstream.api.ConfigStreamManifest
                class Other { }
                """));

        assertThat(result.errors()).hasSize(2).allSatisfy(e -> assertThat(e).contains("may appear only once"));
    }

    private void write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Compiles the sources, placed under src/main/java, into target/classes with the processor. */
    private Result compile(List<String> sources, String... options) {
        try {
            Path sourceRoot = project.resolve("src/main/java");
            Path classes = project.resolve("target/classes");
            Path generated = project.resolve("target/generated-sources/annotations");
            Files.createDirectories(classes);
            Files.createDirectories(generated);
            List<File> files = new ArrayList<>();
            for (String source : sources) {
                String pkg = source.lines().filter(l -> l.startsWith("package ")).findFirst().orElseThrow()
                        .replace("package ", "").replace(";", "").trim();
                String name = source.replaceAll("(?s).*?\\b(?:class|interface)\\s+(\\w+).*", "$1");
                Path file = sourceRoot.resolve(pkg.replace('.', '/')).resolve(name + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, source);
                files.add(file.toFile());
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
                fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
                List<String> args = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path")));
                args.addAll(List.of(options));
                JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, args, null,
                        fileManager.getJavaFileObjectsFromFiles(files));
                task.setProcessors(List.of(new ConfigStreamManifestProcessor()));
                task.call();
            }
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(Locale.ROOT))
                    .toList();
            return new Result(errors, generated);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Result(List<String> errors, Path generatedRoot) {
        String generated(String relative) throws IOException {
            Path file = generatedRoot.resolve(relative);
            assertThat(file).as("generated " + relative + "; found " + list()).exists();
            return Files.readString(file);
        }

        private List<String> list() throws IOException {
            try (Stream<Path> files = Files.walk(generatedRoot)) {
                return files.filter(Files::isRegularFile).map(p -> generatedRoot.relativize(p).toString()).toList();
            }
        }
    }
}
