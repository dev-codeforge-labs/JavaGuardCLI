package com.devmanchego.javaguard.analysis.spotbugs;

import edu.umd.cs.findbugs.BugCollectionBugReporter;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.Plugin;
import edu.umd.cs.findbugs.PluginException;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.config.UserPreferences;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SpotBugs with the FindSecBugs plugin, in process (specification &sect;10.3 step 5, catalog &sect;5): analyzes the given
 * class files against an auxiliary classpath and returns plain {@link Bug}s, leaving every JavaGuard decision (rank to
 * severity, which categories count, merging) to {@link SpotBugsAnalyzer}. The FindSecBugs plugin is found on the class
 * path as the jar that carries its own {@code findsecbugs} descriptor, and loaded as a custom plugin.
 */
public class SpotBugsRunner {

    /**
     * One reported bug.
     *
     * @param pattern    the bug pattern, e.g. {@code SQL_INJECTION_JDBC}
     * @param category   SpotBugs' category, e.g. {@code SECURITY}
     * @param rank       1 (scariest) to 20
     * @param className  the class the bug is in ({@code a.b.C} or {@code a.b.C$Inner})
     * @param line       1-based line, 0 when unknown
     * @param message    SpotBugs' one-line description of this occurrence
     */
    public record Bug(String pattern, String category, int rank, String className, int line, String message) {
    }

    private static final Logger LOG = Logger.getLogger(SpotBugsRunner.class.getName());

    private static boolean pluginsLoaded;

    /** Runs the analysis; throws {@link IOException} when SpotBugs cannot be set up or fails. */
    public List<Bug> run(List<Path> classFiles, List<String> auxiliaryClasspath) throws IOException, InterruptedException {
        if (classFiles.isEmpty()) {
            return List.of();
        }
        Project project = new Project();
        project.setProjectName("javaguard");
        classFiles.forEach(file -> project.addFile(file.toAbsolutePath().toString()));
        auxiliaryClasspath.forEach(project::addAuxClasspathEntry);
        loadPlugins(project);

        BugCollectionBugReporter reporter = new BugCollectionBugReporter(project);
        reporter.setPriorityThreshold(Priorities.NORMAL_PRIORITY);
        reporter.setRankThreshold(14);
        FindBugs2 engine = new FindBugs2();
        engine.setProject(project);
        engine.setBugReporter(reporter);
        engine.setDetectorFactoryCollection(DetectorFactoryCollection.instance());
        UserPreferences preferences = UserPreferences.createDefaultUserPreferences();
        preferences.setEffort(UserPreferences.EFFORT_DEFAULT);
        engine.setUserPreferences(preferences);
        engine.setNoClassOk(true);
        engine.execute();
        reporter.finish();

        List<Bug> bugs = new ArrayList<>();
        for (BugInstance bug : reporter.getBugCollection().getCollection()) {
            SourceLineAnnotation where = bug.getPrimarySourceLineAnnotation();
            bugs.add(new Bug(bug.getType(), bug.getBugPattern().getCategory(), bug.getBugRank(),
                    bug.getPrimaryClass().getClassName(), where == null ? 0 : Math.max(0, where.getStartLine()),
                    bug.getMessageWithoutPrefix()));
        }
        return bugs;
    }

    private static synchronized void loadPlugins(Project project) throws IOException {
        if (pluginsLoaded) {
            return;
        }
        Optional<File> plugin = findSecBugsJar();
        if (plugin.isEmpty()) {
            LOG.warning("The FindSecBugs plugin jar was not found on the class path; only SpotBugs' own detectors run.");
        } else {
            try {
                Plugin.loadCustomPlugin(plugin.get(), project);
            } catch (PluginException e) {
                throw new IOException("Could not load the FindSecBugs plugin " + plugin.get() + ": " + e.getMessage(), e);
            }
        }
        pluginsLoaded = true;
    }

    /**
     * The FindSecBugs plugin jar. In a development or test run it is on the class path: the jar whose descriptor says
     * {@code findsecbugs}. In the packaged distribution it is a separate file, because shading merges the descriptors of
     * SpotBugs and FindSecBugs: {@code -Djavaguard.plugins.dir=<dir>}, else {@code plugins/} next to the jar that holds this
     * class; the file is the one named {@code findsecbugs*.jar}.
     */
    public static Optional<File> findSecBugsJar() {
        try {
            Enumeration<URL> descriptors = SpotBugsRunner.class.getClassLoader().getResources("findbugs.xml");
            for (URL url : Collections.list(descriptors)) {
                String text = url.toString();
                if (text.contains("findsecbugs") && text.startsWith("jar:")) {
                    String jar = text.substring("jar:".length(), text.indexOf("!/"));
                    return Optional.of(new File(new URL(jar).toURI()));
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not locate the FindSecBugs plugin on the class path.", e);
        }
        return pluginsDirectories().stream().map(SpotBugsRunner::findsecbugsIn).flatMap(Optional::stream).findFirst();
    }

    private static List<File> pluginsDirectories() {
        List<File> directories = new ArrayList<>();
        String configured = System.getProperty("javaguard.plugins.dir");
        if (configured != null && !configured.isBlank()) {
            directories.add(new File(configured));
        }
        try {
            var source = SpotBugsRunner.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                File location = new File(source.getLocation().toURI());
                File parent = location.getParentFile();
                if (parent != null) {
                    directories.add(new File(parent, "plugins"));
                }
            }
        } catch (URISyntaxException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not locate the plugins directory.", e);
        }
        return directories;
    }

    private static Optional<File> findsecbugsIn(File directory) {
        File[] jars = directory.listFiles((dir, name) -> name.startsWith("findsecbugs") && name.endsWith(".jar"));
        return jars == null || jars.length == 0 ? Optional.empty() : Optional.of(jars[0]);
    }
}
