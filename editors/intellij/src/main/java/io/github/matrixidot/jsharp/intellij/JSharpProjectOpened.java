package io.github.matrixidot.jsharp.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Key;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * Runs the New Project wizard's last steps (load the Gradle build, open the main file) once the new
 * project has opened: right away if it already has, else from this post-startup activity.
 */
public final class JSharpProjectOpened implements ProjectActivity {
  private static final Key<AtomicReference<Runnable>> PENDING = Key.create("jsharp.whenOpened");

  static void whenOpened(Project project, Runnable action) {
    var pending = new AtomicReference<>(action);
    project.putUserData(PENDING, pending);
    // Whichever of this and the activity takes the action runs it, exactly once.
    if (StartupManager.getInstance(project).postStartupActivityPassed()) {
      run(pending);
    }
  }

  @Override
  public Object execute(Project project, Continuation<? super Unit> continuation) {
    var pending = project.getUserData(PENDING);
    if (pending != null) {
      run(pending);
    }
    return Unit.INSTANCE;
  }

  private static void run(AtomicReference<Runnable> pending) {
    Runnable action = pending.getAndSet(null);
    if (action != null) {
      action.run();
    }
  }
}
