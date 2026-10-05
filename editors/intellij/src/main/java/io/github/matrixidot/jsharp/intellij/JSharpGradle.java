package io.github.matrixidot.jsharp.intellij;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;

/**
 * Loads a new project's Gradle build, in IDEs with Gradle support (IntelliJ IDEA). Elsewhere the
 * project stays a folder of files, which J# editing does not need.
 */
@Service(Service.Level.PROJECT)
final class JSharpGradle {
  private final CoroutineScope scope;

  JSharpGradle(Project project, CoroutineScope scope) {
    this.scope = scope;
  }

  static void link(Project project, Path dir) {
    ExternalSystemUnlinkedProjectAware gradle =
        ExternalSystemUnlinkedProjectAware.getInstance(new ProjectSystemId("GRADLE"));
    if (gradle == null || gradle.isLinkedProject(project, dir.toString())) {
      return;
    }
    BuildersKt.launch(
        project.getService(JSharpGradle.class).scope,
        EmptyCoroutineContext.INSTANCE,
        CoroutineStart.DEFAULT,
        (s, continuation) -> gradle.linkAndLoadProjectAsync(project, dir.toString(), continuation));
  }
}
