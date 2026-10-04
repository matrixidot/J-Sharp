package io.github.matrixidot.jsharp.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.execution.ParametersListUtil;
import java.util.ArrayList;
import java.util.List;
import org.jdom.Element;

/** Runs a J# program: {@code jsharp run <program files> -- <arguments>}. */
public final class JSharpRunConfiguration extends LocatableConfigurationBase<Object> {
  private String file = "";
  private String arguments = "";

  JSharpRunConfiguration(Project project, ConfigurationFactory factory, String name) {
    super(project, factory, name);
  }

  String getFile() {
    return file;
  }

  void setFile(String file) {
    this.file = file == null ? "" : file;
  }

  String getArguments() {
    return arguments;
  }

  void setArguments(String arguments) {
    this.arguments = arguments == null ? "" : arguments;
  }

  @Override
  public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new JSharpRunSettingsEditor(getProject());
  }

  @Override
  public void checkConfiguration() throws RuntimeConfigurationException {
    VirtualFile f = JSharpPrograms.find(file);
    if (f == null || !JSharpPlugin.isJSharp(f)) {
      throw new RuntimeConfigurationError("Choose the .jsharp file of the program to run.");
    }
  }

  @Override
  public RunProfileState getState(Executor executor, ExecutionEnvironment environment) {
    return new CommandLineState(environment) {
      @Override
      protected ProcessHandler startProcess() throws ExecutionException {
        VirtualFile f = JSharpPrograms.find(file);
        if (f == null) {
          throw new ExecutionException("J#: cannot find " + file);
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        List<String> args = new ArrayList<>();
        args.add("run");
        args.addAll(JSharpPrograms.programFiles(getProject(), f));
        args.add("--");
        args.addAll(ParametersListUtil.parse(arguments));
        GeneralCommandLine cl = JSharpPlugin.cli(args);
        if (f.getParent() != null) {
          cl = cl.withWorkDirectory(f.getParent().getPath());
        }
        KillableColoredProcessHandler handler = new KillableColoredProcessHandler(cl);
        ProcessTerminatedListener.attach(handler);
        return handler;
      }
    };
  }

  @Override
  public void readExternal(Element element) {
    super.readExternal(element);
    setFile(JDOMExternalizerUtil.readField(element, "file"));
    setArguments(JDOMExternalizerUtil.readField(element, "arguments"));
  }

  @Override
  public void writeExternal(Element element) {
    super.writeExternal(element);
    JDOMExternalizerUtil.writeField(element, "file", file);
    JDOMExternalizerUtil.writeField(element, "arguments", arguments);
  }

  @Override
  public String suggestedName() {
    VirtualFile f = JSharpPrograms.find(file);
    return f == null ? null : f.getName();
  }
}
