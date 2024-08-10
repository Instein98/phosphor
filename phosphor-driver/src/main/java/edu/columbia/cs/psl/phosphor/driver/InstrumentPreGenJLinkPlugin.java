package edu.columbia.cs.psl.phosphor.driver;

import jdk.tools.jlink.plugin.Plugin;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

/**
 * JLink plugin that instruments "pre-generated" classes such as `BoundMethodHandle$Species_LL`
 * See https://bugs.openjdk.org/browse/JDK-8247536 for the "pre-generation" mechanism.
 * The InstrumentJLinkPlugin is of type "FILTER", which seems to be a phase before pre-generation.
 * Consequently, the InstrumentJLinkPlugin cannot see or instrument those pre-generated classes.
 */
public class InstrumentPreGenJLinkPlugin implements Plugin {
    private Instrumentation instrumentation;

    @Override
    public String getName() {
        return "phosphor-pre-gen-instrument";
    }

    @Override
    public Category getType() {
        // Pre-generated classes are only be visible to plugins of certain types.
        return Category.PACKAGER;
    }

    @Override
    public String getDescription() {
        return "Applies instrumentation to the runtime image and packs classes into the java.base module";
    }

    @Override
    public boolean hasArguments() {
        return true;
    }

    @Override
    public void configure(Map<String, String> config) {
        try (FileReader reader = new FileReader(config.get("options"))) {
            Properties options = new Properties();
            options.load(reader);
            instrumentation = Instrumentation.create(config.get("type"), new File(config.get("source")), options);
        } catch (IOException | ReflectiveOperationException e) {
            throw new RuntimeException("Failed to process configuration", e);
        }
    }

    @Override
    public ResourcePool transform(ResourcePool pool, ResourcePoolBuilder out) {
        pool.transformAndCopy(this::transform, out);
        return out.build();
    }

    private ResourcePoolEntry transform(ResourcePoolEntry entry) {
        if (entry.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE)
                && entry.path().endsWith(".class")) {
            // Only instrument classes that were not visible to the InstrumentJLinkPlugin and are not phosphor classes.
            if (!InstrumentJLinkPlugin.seenClasses.contains(entry.path())
                        && !entry.path().startsWith("/java.base/edu/columbia/cs/psl/phosphor/")) {
                byte[] instrumented = instrumentation.apply(entry.contentBytes());
                return instrumented == null ? entry : entry.copyWithContent(instrumented);
            }
        }
        return entry;
    }
}