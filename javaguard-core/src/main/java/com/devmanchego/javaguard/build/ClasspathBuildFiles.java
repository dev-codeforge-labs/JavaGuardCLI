package com.devmanchego.javaguard.build;

import com.devmanchego.javaguard.build.gradle.GradleClasspathExtractor;
import com.devmanchego.javaguard.build.maven.MavenClasspathExtractor;
import com.devmanchego.javaguard.project.BuildTool;
import com.devmanchego.javaguard.project.ModuleInfo;

import java.nio.file.Path;
import java.util.List;

/** The build files whose hashes decide whether a module's classpath cache is stale, whichever build tool made it. */
public final class ClasspathBuildFiles {

    private ClasspathBuildFiles() {
    }

    public static List<Path> of(ModuleInfo module) {
        return module.buildTool() == BuildTool.GRADLE
                ? GradleClasspathExtractor.buildFileChain(module)
                : MavenClasspathExtractor.buildFileChain(module.moduleRoot());
    }
}
