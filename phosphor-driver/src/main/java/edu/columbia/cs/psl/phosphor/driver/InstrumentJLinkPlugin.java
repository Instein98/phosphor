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
import java.util.Set;
import java.util.HashSet;

public class InstrumentJLinkPlugin implements Plugin {
    public static final Set<String> seenClasses = new HashSet<>();
    private Instrumentation instrumentation;
    private ResourcePoolPacker packer;

    @Override
    public String getName() {
        return "phosphor-instrument";
    }

    @Override
    public Category getType() {
        return Category.FILTER;
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
        packer = new ResourcePoolPacker(instrumentation, pool, out);
        pool.transformAndCopy(this::transform, out);
        return out.build();
    }

    private ResourcePoolEntry transform(ResourcePoolEntry entry) {
        if (entry.type().equals(ResourcePoolEntry.Type.CLASS_OR_RESOURCE)
                && entry.path().endsWith(".class")) {
            seenClasses.add(entry.path());
            if (entry.path().endsWith("module-info.class")) {
                if (entry.path().startsWith("/java.base")) {
                    // Transform java.base's module-info.class file and pack core classes into java.base
                    return packer.pack(entry);
                }
            } else {
                byte[] instrumented = instrumentation.apply(entry.contentBytes());
                return instrumented == null ? entry : entry.copyWithContent(instrumented);
            }
        }
        return entry;
    }
}