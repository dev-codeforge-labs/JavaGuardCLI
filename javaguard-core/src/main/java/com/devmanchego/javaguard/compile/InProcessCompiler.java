package com.devmanchego.javaguard.compile;

import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.google.errorprone.ErrorProneJavaCompiler;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compiles a handful of changed {@code .java} files in-process, without
 * starting Maven/Gradle (specification &sect;8.2). Uses
 * {@code javax.tools.JavaCompiler}: the plain system compiler, or - when
 * {@link CompileRequest#errorProneEnabled()} - Error Prone's
 * {@link ErrorProneJavaCompiler}, which wraps it and additionally reports
 * the curated check list ({@link ErrorProneCatalog}).
 *
 * <p><b>Deliberately does not pass {@code -Xlint:none}</b>, unlike the
 * option list spec &sect;8.2 step 2 lists verbatim: measured directly
 * against this Error Prone version, {@code -Xlint:none} silences every
 * WARNING-severity diagnostic through the same lint-category mechanism
 * Error Prone's own warnings use ({@code compiler.warn.error.prone}), which
 * would silently disable every enabled WARNING-level catalog check (all of
 * {@code BigDecimalEquals}, {@code EqualsHashCode}, ... - everything except
 * the ERROR-level checks). Instead, plain javac warnings are dropped by
 * {@link #isErrorProneDiagnostic}/kind filtering in {@link #mapDiagnostic},
 * which achieves the same "warnings from javac itself are dropped" outcome
 * (step 5) without the side effect.
 */
public final class InProcessCompiler {

    private static final Pattern ERROR_PRONE_MESSAGE = Pattern.compile("^\\[(\\w+)]\\s*(.*)$");
    private static final Pattern JAVAC_DETAIL_LINE = Pattern.compile("^\\s*(symbol|location|required|found|reason):\\s*(.*)$");
    private static final String ERROR_PRONE_ERROR_CODE = "compiler.err.error.prone";
    private static final String ERROR_PRONE_WARNING_CODE = "compiler.warn.error.prone";

    public CompileResult compile(CompileRequest request) {
        try {
            Files.createDirectories(request.outputDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create compiler output directory " + request.outputDir(), e);
        }

        JavaCompiler compiler = request.errorProneEnabled() ? new ErrorProneJavaCompiler() : ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(request.filesToCompile());
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options(request), null, units);
            ok = task.call();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not close the compiler's file manager", e);
        }

        List<Finding> findings = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            mapDiagnostic(d, request.projectRoot()).ifPresent(findings::add);
        }
        return new CompileResult(ok, findings);
    }

    private List<String> options(CompileRequest request) {
        List<String> options = new ArrayList<>();
        options.add("--release");
        options.add(String.valueOf(request.release()));
        // No -proc:<...> option at all: javac's own default already is "auto" -
        // discover annotation processors from -processorpath, or from -classpath
        // when no processor path is given (Lombok's own supported setup, and this
        // project's maven-spring-jpa-lombok fixture). There is no "-proc:auto"
        // value to pass explicitly; javac rejects it outright.
        options.add("-implicit:none");
        if (!request.classpath().isEmpty()) {
            options.add("-classpath");
            options.add(String.join(File.pathSeparator, request.classpath()));
        }
        if (!request.sourceRoots().isEmpty()) {
            options.add("-sourcepath");
            options.add(request.sourceRoots().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        options.add("-d");
        options.add(request.outputDir().toString());
        if (request.errorProneEnabled()) {
            options.addAll(ErrorProneCatalog.javacOptions());
        }
        return options;
    }

    private Optional<Finding> mapDiagnostic(Diagnostic<? extends JavaFileObject> d, Path projectRoot) {
        if (isErrorProneDiagnostic(d)) {
            return Optional.of(errorProneFinding(d, projectRoot));
        }
        if (d.getKind() == Diagnostic.Kind.ERROR) {
            return Optional.of(javacFinding(d, projectRoot));
        }
        return Optional.empty();
    }

    private boolean isErrorProneDiagnostic(Diagnostic<? extends JavaFileObject> d) {
        String code = d.getCode();
        return ERROR_PRONE_ERROR_CODE.equals(code) || ERROR_PRONE_WARNING_CODE.equals(code);
    }

    private Finding errorProneFinding(Diagnostic<? extends JavaFileObject> d, Path projectRoot) {
        String firstLine = firstLine(d.getMessage(null));
        Matcher m = ERROR_PRONE_MESSAGE.matcher(firstLine);
        String checkName = m.matches() ? m.group(1) : "Unknown";
        String summary = m.matches() ? m.group(2) : firstLine;

        String ruleId = "EP:" + checkName;
        Optional<Path> file = relativeFile(d, projectRoot);
        int line = safePosition(d.getLineNumber());
        int column = safePosition(d.getColumnNumber());
        return new Finding(ruleId, checkName, ErrorProneCatalog.categoryOf(checkName), ErrorProneCatalog.severityOf(checkName),
                "errorprone", file, line, column, line, summary,
                "See https://errorprone.info/bugpattern/" + checkName + " for details.", Optional.empty(), Map.of(),
                Fingerprints.of(ruleId, relativePathString(file), summary));
    }

    private Finding javacFinding(Diagnostic<? extends JavaFileObject> d, Path projectRoot) {
        String ruleId = "JAVAC";
        List<String> lines = d.getMessage(null).lines().toList();
        String summary = lines.isEmpty() ? "" : lines.get(0);
        Map<String, String> detail = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            Matcher m = JAVAC_DETAIL_LINE.matcher(lines.get(i));
            if (m.matches()) {
                detail.put(m.group(1), m.group(2).strip());
            }
        }
        Optional<Path> file = relativeFile(d, projectRoot);
        int line = safePosition(d.getLineNumber());
        int column = safePosition(d.getColumnNumber());
        return new Finding(ruleId, "Compilation error", Category.COMPILATION, Severity.CRITICAL, "javac", file,
                line, column, line, summary, JavacRemediation.forMessage(summary, JavacRemediation.FAST_DEFAULT),
                Optional.empty(), detail, Fingerprints.of(ruleId, relativePathString(file), summary));
    }

    private String firstLine(String message) {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private Optional<Path> relativeFile(Diagnostic<? extends JavaFileObject> d, Path projectRoot) {
        JavaFileObject source = d.getSource();
        if (source == null) {
            return Optional.empty();
        }
        try {
            Path absolute = Path.of(source.toUri()).toAbsolutePath().normalize();
            return Optional.of(projectRoot.relativize(absolute));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String relativePathString(Optional<Path> file) {
        return file.map(p -> p.toString().replace('\\', '/')).orElse("");
    }

    private int safePosition(long position) {
        if (position == Diagnostic.NOPOS || position < 0) {
            return 0;
        }
        return (int) Math.min(position, Integer.MAX_VALUE);
    }
}
