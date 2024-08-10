package edu.columbia.cs.psl.phosphor.driver;

import edu.columbia.cs.psl.phosphor.agent.InstrumentUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class JLinkInvoker {
    private JLinkInvoker() {
        throw new AssertionError();
    }

    public static void invoke(
            File javaHome, File outputDirectory, Instrumentation instrumentation, Properties options, String modules)
            throws InterruptedException, IOException {
        String jlinkAgentJar =
                InstrumentUtil.getClassPathElement(JLinkRegistrationAgent.class).getAbsolutePath();
        List<String> command = new ArrayList<>();
        String classPath = buildClassPath(instrumentation);
        command.add(InstrumentUtil.javaHomeToJLinkExec(javaHome).getAbsolutePath());
        command.add("-J-javaagent:" + jlinkAgentJar);
        command.add("-J--class-path=" + classPath);
        command.add("-J--add-reads=" + JLinkRegistrationAgent.MODULE_NAME + "=ALL-UNNAMED");
        command.add("-J--module-path=" + jlinkAgentJar);
        command.add("-J--add-modules=" + JLinkRegistrationAgent.MODULE_NAME);
        String pluginOption = getPluginOption(javaHome, instrumentation, options);
        command.add(pluginOption);
        command.add(pluginOption.replace("-instrument=", "-pre-gen-instrument="));
        command.add("--output=" + outputDirectory.getAbsolutePath());
        command.add("--add-modules");
        command.add(processModules(modules));
        ProcessBuilder builder = new ProcessBuilder(command);
        System.out.println(String.join(" ", builder.command()));
        Process process = builder.inheritIO().start();
        if (process.waitFor() != 0) {
            throw new RuntimeException("Failed to create instrumented runtime image");
        }
    }

    private static File storeOptions(Properties options) throws IOException {
        // Write the options to a temporary file
        File file = InstrumentUtil.createTemporaryFile("phosphor-", ".properties");
        try (FileWriter writer = new FileWriter(file)) {
            options.store(writer, null);
        }
        return file;
    }

    private static String buildClassPath(Instrumentation instrumentation) {
        return instrumentation.getClassPathElements().stream()
                .map(File::getAbsolutePath)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static String getPluginOption(File javaHome, Instrumentation instrumentation, Properties options)
            throws IOException {
        return String.format(
                "--phosphor-instrument=x:type=%s:source=%s:options=%s",
                instrumentation.getClass().getName(),
                javaHome.getAbsolutePath(),
                storeOptions(options).getAbsolutePath());
    }

    private static String processModules(String moduleString) {
        // Ensure that all required modules are included
        Set<String> modules =
                new LinkedHashSet<>(Arrays.asList("java.base", "jdk.jdwp.agent", "java.instrument", "jdk.unsupported"));
        if (!moduleString.isEmpty()) {
            modules.addAll(Arrays.asList(moduleString.split(",")));
        }
        return String.join(",", modules);
    }
}