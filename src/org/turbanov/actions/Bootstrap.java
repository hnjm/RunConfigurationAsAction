package org.turbanov.actions;

import javax.swing.Icon;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.intellij.execution.DefaultExecutionTarget;
import com.intellij.execution.ExecutionTarget;
import com.intellij.execution.ExecutionTargetManager;
import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.RunManagerAdapter;
import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.ide.ui.customization.CustomActionsSchema;
import com.intellij.ide.ui.customization.CustomisedActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.util.IconUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static java.util.stream.Collectors.toMap;

/**
 * @author Andrey Turbanov
 * @since 27.01.2017
 */
public class Bootstrap implements ProjectComponent {
    private static final Logger log = Logger.getInstance(RunConfigurationAsAction.class);
    private static final String ACTION_ID_PREFIX = "RunConfigurationAsAction";
    private static final PluginId PLUGIN_ID = PluginId.getId("org.turbanov.run.configuration.as.action");

    private final Project myProject;

    public Bootstrap(@NotNull Project project) {
        this.myProject = project;
    }

    private void registerAction(@NotNull RunnerAndConfigurationSettings runConfig,
                                @NotNull Executor executor,
                                @Nullable ExecutionTarget target) {
        String actionId = makeActionId(executor, runConfig, target);
        ActionManager actionManager = ActionManagerEx.getInstance();
        AnAction action = actionManager.getAction(actionId);
        if (action == null) {
            Icon icon = makeIcon(runConfig, executor);
            String targetAdd;
            if (target == null || DefaultExecutionTarget.INSTANCE.equals(target)) {
                targetAdd = "";
            } else {
                targetAdd = " " + target.getDisplayName();
            }
            String text = executor.getActionName() + " '" + runConfig.getName() + "'" + targetAdd;
            String executionTargetId = target == null ? null : target.getId();
            action = new RunConfigurationAsAction(runConfig.getName(), executor.getId(), icon, text, executionTargetId);
            actionManager.registerAction(actionId, action, PLUGIN_ID);
        } else if (action instanceof RunConfigurationAsAction) {
            ((RunConfigurationAsAction) action).register();
        } else {
            log.warn("Someone uses our action id = " + actionId + ": " + action.getClass() + " " + action);
        }
    }

    private Icon makeIcon(@NotNull RunnerAndConfigurationSettings runConfig, @NotNull Executor executor) {
        Icon icon = executor.getIcon();
        Icon result = IconUtil.addText(icon, runConfig.getName());
        return result;
    }

    private static String makeActionId(@NotNull Executor executor,
                                       @NotNull RunnerAndConfigurationSettings runConfig,
                                       @Nullable ExecutionTarget target) {
        String targetAdditional = target == null ? "" : "_" + target.getId();
        return ACTION_ID_PREFIX + "_" + executor.getId() + "_" + runConfig.getName() + targetAdditional;
    }

    public void removeForAllExecutors(@NotNull RunnerAndConfigurationSettings runConfig) {
        Executor[] executors = ExecutorRegistry.getInstance().getRegisteredExecutors();
        ActionManager actionManager = ActionManager.getInstance();
        List<ExecutionTarget> targets = getTargets(runConfig);
        for (Executor executor : executors) {
            for (ExecutionTarget target : targets) {
                String actionId = makeActionId(executor, runConfig, target);
                AnAction action = actionManager.getAction(actionId);
                if (!(action instanceof RunConfigurationAsAction)) {
                    continue;
                }
                int count = ((RunConfigurationAsAction) action).unregister();
                if (count <= 0) {
                    if (count < 0) {
                        log.warn("Someone remove more action than register " + action);
                    }
                    actionManager.unregisterAction(actionId);
                }
            }
        }
    }

    private void registerForAllExecutors(@NotNull RunnerAndConfigurationSettings settings) {
        Executor[] executors = ExecutorRegistry.getInstance().getRegisteredExecutors();
        List<ExecutionTarget> targets = getTargets(settings);
        for (Executor executor : executors) {
            for (ExecutionTarget target : targets) {
                registerAction(settings, executor, target);
            }
        }
    }

    @NotNull
    private List<ExecutionTarget> getTargets(@NotNull RunnerAndConfigurationSettings runConfig) {
        List<ExecutionTarget> targets = ExecutionTargetManager.getInstance(myProject).getTargetsFor(runConfig);
        if (targets.size() == 1 && DefaultExecutionTarget.INSTANCE.equals(targets.get(0))) {
            return targets;
        }
        targets = new ArrayList<>(targets);
        targets.add(null);
        return targets;
    }

    @NotNull
    @Override
    public String getComponentName() {
        return ACTION_ID_PREFIX;
    }

    @Override
    public void projectOpened() {
        RunManagerEx runManager = RunManagerEx.getInstanceEx(myProject);
        runManager.addRunManagerListener(new RunManagerAdapter() {
            @Override
            public void runConfigurationAdded(@NotNull RunnerAndConfigurationSettings settings) {
                registerForAllExecutors(settings);
            }

            @Override
            public void runConfigurationRemoved(@NotNull RunnerAndConfigurationSettings settings) {
                removeForAllExecutors(settings);
            }
        });

        List<RunnerAndConfigurationSettings> allSettings = runManager.getAllSettings();
        for (RunnerAndConfigurationSettings setting : allSettings) {
            registerForAllExecutors(setting);
        }

        int baseVersion = ApplicationInfo.getInstance().getBuild().getBaselineVersion();
        if (baseVersion >= 182) {
            //no hacks required in 2018.2+
            CustomActionsSchema.setCustomizationSchemaForCurrentProjects();
        } else {
            reloadCustomizedToolbar("NavBarToolBar");
            reloadCustomizedToolbar("MainToolBar");
        }
    }

    private void reloadCustomizedToolbar(String toolbarName) {
        AnAction action = CustomActionsSchema.getInstance().getCorrectedAction(toolbarName);
        if (!(action instanceof CustomisedActionGroup)) {
            return;
        }
        Map<String, Field> booleanFields = Arrays.stream(action.getClass().getDeclaredFields())
                .filter(f -> f.getType() == boolean.class)
                .collect(toMap(Field::getName, Function.identity()));
        Field forceUpdate = booleanFields.get("myForceUpdate");
        if (forceUpdate == null) {
            if (booleanFields.size() != 1) {
                log.warn("IDEA version isn't compatible. Plugin can work unstable. CustomisedActionGroup fields: " + booleanFields.values());
                return;
            }
            forceUpdate = booleanFields.values().iterator().next();
        }
        try {
            forceUpdate.setAccessible(true);
            forceUpdate.set(action, true);
        } catch (IllegalAccessException e) {
            log.warn("Access denied to field " + forceUpdate.getName());
        }
    }

    @Override
    public void projectClosed() {
        RunManagerEx runManager = RunManagerEx.getInstanceEx(myProject);
        List<RunnerAndConfigurationSettings> allSettings = runManager.getAllSettings();
        for (RunnerAndConfigurationSettings setting : allSettings) {
            removeForAllExecutors(setting);
        }
    }
}


