package edu.columbia.cs.psl.phosphor;

import edu.columbia.cs.psl.phosphor.runtime.NonModifiableClassException;
import edu.columbia.cs.psl.phosphor.struct.SinglyLinkedList;
import edu.columbia.cs.psl.phosphor.struct.harmony.util.StringBuilder;
import edu.columbia.cs.psl.phosphor.struct.harmony.util.*;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Scanner;

public class BasicSourceSinkManager extends SourceSinkManager {

    private static final Map<String, Object> sourceLabels = new HashMap<>();
    // Maps class names to a set of all the methods listed as sources for the class
    private static final Map<String, Set<String>> sources = new HashMap<>();
    // Maps class names to a set of all the methods listed as sinks for the class
    private static final Map<String, Set<String>> sinks = new HashMap<>();
    // Maps class names to a set of all the methods listed as taintThrough methods for the class
    private static final Map<String, Set<String>> taintThrough = new HashMap<>();
    // Maps class names to sets of class instances
    private static final Map<String, Set<Class<?>>> classMap = new HashMap<>();
    public static InputStream sourcesFile;
    public static InputStream sinksFile;
    public static InputStream taintThroughFile;
    // Maps class names to a set of all methods listed as sources for the class or one of its supertypes or superinterfaces
    private static Map<String, Set<String>> inheritedSources = new HashMap<>();
    // Maps class names to a set of all methods listed as sinks for the class or one of its supertypes or superinterfaces
    private static Map<String, Set<String>> inheritedSinks = new HashMap<>();
    // Maps class names to a set of all methods listed as taintThrough methods for the class or one of its supertypes or superinterfaces
    private static Map<String, Set<String>> inheritedTaintThrough = new HashMap<>();

    /* Private constructor ensures that only one instance of BasicSourceSinkManager is ever created. */
    private BasicSourceSinkManager() {
    }

    @Override
    public boolean isSourceOrSinkOrTaintThrough(Class<?> clazz) {
        synchronized (BasicSourceSinkManager.class) {
            String className = clazz.getName().replace(".", "/");
            // This class has a sink, source or taintThrough method
            return !getAutoTaintMethods(className, sinks, inheritedSinks).isEmpty()
                    || !getAutoTaintMethods(className, sources, inheritedSources).isEmpty()
                    || !getAutoTaintMethods(className, taintThrough, inheritedTaintThrough).isEmpty();
        }
    }

    @Override
    public Object getLabel(String str) {
        synchronized (BasicSourceSinkManager.class) {
            return sourceLabels.get(str);
        }
    }

    @Override
    public boolean isTaintThrough(String str) {
        synchronized (BasicSourceSinkManager.class) {
            if (str.startsWith("[")) {
                return false;
            } else {
                String[] parsed = str.split("\\.");
                // Check if the set of taintThrough methods for the class name contains the method name
                return getAutoTaintMethods(parsed[0], taintThrough, inheritedTaintThrough).contains(parsed[1]);
            }
        }
    }

    @Override
    public boolean isSource(String str) {
        synchronized (BasicSourceSinkManager.class) {
            if (str.startsWith("[")) {
                return false;
            } else {
                String[] parsed = str.split("\\.");
                // Check if the set of source methods for the class name contains the method name
                if (getAutoTaintMethods(parsed[0], sources, inheritedSources).contains(parsed[1])) {
                    String baseSource = findSuperTypeAutoTaintProvider(parsed[0], parsed[1], sources, inheritedSources);
                    if (!sourceLabels.containsKey(str)) {
                        sourceLabels.put(str, sourceLabels.get(String.format("%s.%s", baseSource, parsed[1])));
                    }
                    return true;
                } else {
                    return false;
                }
            }
        }
    }

    @Override
    public boolean isSink(String str) {
        synchronized (BasicSourceSinkManager.class) {
            if (str.startsWith("[")) {
                return false;
            } else {
                String[] parsed = str.split("\\.");
                // Check if the set of sink methods for the class name contains the method name
                return getAutoTaintMethods(parsed[0], sinks, inheritedSinks).contains(parsed[1]);
            }
        }
    }

    /* Returns the name of sink method from which the specified method inherited its sink property or null if the specified
     * method is not a sink. */
    public String getBaseSink(String str) {
        synchronized (BasicSourceSinkManager.class) {
            String[] parsed = str.split("\\.");
            String baseSink = findSuperTypeAutoTaintProvider(parsed[0], parsed[1], sinks, inheritedSinks);
            return baseSink == null ? null : String.format("%s.%s", baseSink, parsed[1]);
        }
    }

    /* Updates the FileInputStream of souces/sinks/taintThrough files based on the values of
     * corresponding system properties and initializes anything needed to call
     * isSourceOrSinkOrTaintThrough. */
    public static synchronized void init(){
        // Update the taint methods from system properties if they are not already specified in the java agent options.
        updateInputStreamsBySysProperties();
        // Ensure that BasicSourceSinkManager and anything needed to call isSourceOrSinkOrTaintThrough gets initialized
        loadTaintMethods();
        getInstance().isSourceOrSinkOrTaintThrough(Object.class);
    }

    /* Updates the sourcesFile, sinksFile and taintThroughFile based on the values of the system
     * properties if they are not already specified in the java agent options. This method is added
     * to provide more flexibility in specifying the auto-taint methods, allowing applications to
     * specify such methods in additional jvm options, as the java_runtime_11_phosphor config have
     * hardcoded the '-javaagent' jvm option. For example, `-Dphosphor.taintSourcesFile=/path/to/file`.*/
    public static synchronized void updateInputStreamsBySysProperties() {
        if (sourcesFile == null && System.getProperty("phosphor.taintSourcesFile") != null){
            try {
                sourcesFile = new FileInputStream(System.getProperty("phosphor.taintSourcesFile"));
            } catch(FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        if (sinksFile == null && System.getProperty("phosphor.taintSinksFile") != null){
            try {
                sinksFile = new FileInputStream(System.getProperty("phosphor.taintSinksFile"));
            } catch(FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        if (taintThroughFile == null && System.getProperty("phosphor.taintThroughFile") != null){
            try {
                taintThroughFile = new FileInputStream(System.getProperty("phosphor.taintThroughFile"));
            } catch(FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    /* Reads source, sink and taintThrough methods from their files into their respective maps. */
    public static synchronized void loadTaintMethods() {
        readTaintMethods(sourcesFile, AutoTaint.SOURCE);
        readTaintMethods(sinksFile, AutoTaint.SINK);
        readTaintMethods(taintThroughFile, AutoTaint.TAINT_THROUGH);
    }

    /* Provides access to the single instance of BasicSourceSinkManager */
    public static BasicSourceSinkManager getInstance() {
        return BasicSourceSinkManagerSingleton.INSTANCE;
    }

    /* Adds method names from the specified input stream into the map of base autoTaint methods of the specified type.
     * If reading in source methods then sourceLabels are also created for each method name added. */
    private static synchronized void readTaintMethods(InputStream src, AutoTaint type) {
        Scanner s = null;
        String lastLine = null;
        Map<String, Set<String>> baseMethods;
        switch (type) {
            case SOURCE:
                baseMethods = sources;
                break;
            case SINK:
                baseMethods = sinks;
                break;
            default:
                baseMethods = taintThrough;
        }
        try {
            if (src != null) {
                s = new Scanner(src);
                while (s.hasNextLine()) {
                    String line = s.nextLine();
                    lastLine = line;
                    if (!line.startsWith("#") && !line.isEmpty()) {
                        String[] parsed = line.split("\\.");
                        if (!baseMethods.containsKey(parsed[0])) {
                            baseMethods.put(parsed[0], new HashSet<String>());
                        }
                        baseMethods.get(parsed[0]).add(parsed[1]);
                        if (type.equals(AutoTaint.SOURCE)) {
                            sourceLabels.put(line, line);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            System.err.printf("Unable to parse %s file: %s\n", type.name, src);
            if (lastLine != null) {
                System.err.printf("Last line read: '%s'\n", lastLine);
            }
            throw new RuntimeException(e);
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    /* Stores the specified class instance so that it can last be used to retransform the class if it's autoTaint methods
     * change as a result of a call to replaceAutoTaintMethods. */
    public static synchronized void recordClass(Class<?> clazz) {
        String key = clazz.getName().replace(".", "/");
        if (!classMap.containsKey(key)) {
            classMap.put(key, new HashSet<>());
        }
        classMap.get(key).add(clazz);
    }

    public static synchronized java.util.LinkedList<String> replaceAutoTaintMethods(Iterable<String> src, AutoTaint type) {
        StringBuilder builder = new StringBuilder();
        for (String s : src) {
            builder.append(s).append("\n");
        }
        return replaceAutoTaintMethods(new ByteArrayInputStream(builder.toString().getBytes()), type);
    }

    /* Replaces the set of base autoTaint methods of the specified type with methods read from the specified stream. Retransforms
     * any class with a method whose status as an autoTaint methods of the specified type has changed. Returns a list of
     * the replaced base autoTaint methods of the specified type. */
    public static synchronized java.util.LinkedList<String> replaceAutoTaintMethods(InputStream src, AutoTaint type) {
        Map<String, Set<String>> baseMethods;
        Map<String, Set<String>> inheritedMethods;
        Map<String, Set<String>> prevInheritedMethods;
        switch (type) {
            case SOURCE:
                baseMethods = sources;
                prevInheritedMethods = inheritedSources;
                // Clear the map of inherited or derived autoTaint methods of the specified type
                inheritedMethods = new HashMap<>();
                inheritedSources = inheritedMethods;
                break;
            case SINK:
                baseMethods = sinks;
                prevInheritedMethods = inheritedSinks;
                // Clear the map of inherited or derived autoTaint methods of the specified type
                inheritedMethods = new HashMap<>();
                inheritedSinks = inheritedMethods;
                break;
            default:
                baseMethods = taintThrough;
                prevInheritedMethods = inheritedTaintThrough;
                // Clear the map of inherited or derived autoTaint methods of the specified type
                inheritedMethods = new HashMap<>();
                inheritedTaintThrough = inheritedMethods;
        }
        // Reconstruct the original set of base methods
        java.util.LinkedList<String> prevBaseMethods = new java.util.LinkedList<>();
        for (String className : baseMethods.keySet()) {
            for (String methodName : baseMethods.get(className)) {
                prevBaseMethods.add(className + "." + methodName);
            }
        }
        // Update the set of base autoTaint methods of the specified type
        baseMethods.clear();
        readTaintMethods(src, type);
        // Retransform any class that has a method that changed from being a autoTaint methods of the specified type
        // to a not being an autoTaint methods of the specified type or vice versa
        for (String className : prevInheritedMethods.keySet()) {
            Set<String> autoTaintMethods = getAutoTaintMethods(className, baseMethods, inheritedMethods);
            if (!autoTaintMethods.equals(prevInheritedMethods.get(className))) {
                // Set of autoTaint methods for this class changed
                try {
                    if (classMap.containsKey(className)) {
                        for (Class<?> clazz : classMap.get(className)) {
                            Phosphor.getInstrumentation().retransformClasses(clazz);
                        }
                    }
                } catch (NonModifiableClassException e) {
                    //
                } catch (Throwable t) {
                    // Make sure that any other type of exception is printed
                    t.printStackTrace();
                    throw t;
                }
            }
        }
        return prevBaseMethods;
    }

    /* Returns the set of methods that are a particular type of auto taint method (i.e. source, sink or taintThrough) for
     * the class or interface with the specified slash-separated string class name. A method is considered to be an auto
     * taint method if the method is present in the set of base auto taint methods for either the specified class or a supertype of the
     * specified class. Previously determined auto taint methods are stored in inheritedMethods. */
    private static synchronized Set<String> getAutoTaintMethods(String className, Map<String, Set<String>> baseMethods,
                                                                Map<String, Set<String>> inheritedMethods) {
        if (inheritedMethods.containsKey(className)) {
            // The auto taint methods for this class have already been determined.
            return inheritedMethods.get(className);
        } else {
            // Recursively build the set of auto taint methods for this class
            Set<String> set = new HashSet<>();
            if (baseMethods.containsKey(className)) {
                // Add any methods from this class that are directly listed as auto taint methods
                set.addAll(baseMethods.get(className));
            }
            ClassNode cn = ClassNodeCache.getClassNode(className);
            if (cn != null) {
                if (cn.interfaces != null) {
                    // Add all auto taint methods from interfaces implemented by this class
                    for (String inter : cn.interfaces) {
                        set.addAll(getAutoTaintMethods(inter, baseMethods, inheritedMethods));
                    }
                }
                if (cn.superName != null && !cn.superName.equals("java/lang/Object")) {
                    // Add all auto taint methods from the superclass of this class
                    set.addAll(getAutoTaintMethods(cn.superName, baseMethods, inheritedMethods));
                }
            }
            inheritedMethods.put(className, set);
            return set;
        }
    }

    /* Returns the string class name of the supertype of the class or interface with specified string class name from which
     * its method with the specified method name derived its status as an auto taint (i.e. source, sink or taintThrough)
     * method. */
    private static synchronized String findSuperTypeAutoTaintProvider(String className, String methodName, Map<String,
            Set<String>> baseMethods, Map<String, Set<String>> inheritedMethods) {
        SinglyLinkedList<String> queue = new SinglyLinkedList<>();
        queue.enqueue(className);
        while (!queue.isEmpty()) {
            String curClassName = queue.pop();
            // Check that the current class actually has an inherited auto taint method with the target method name
            if (inheritedMethods.containsKey(curClassName) && inheritedMethods.get(curClassName).contains(methodName)) {
                if (baseMethods.containsKey(curClassName) && baseMethods.get(curClassName).contains(methodName)) {
                    return curClassName;
                }
                ClassNode cn = ClassNodeCache.getClassNode(curClassName);
                if (cn != null) {
                    if (cn.interfaces != null) {
                        // Enqueue interfaces implemented by the current class
                        for (String inter : cn.interfaces) {
                            queue.enqueue(inter);
                        }
                    }
                    if (cn.superName != null && !cn.superName.equals("java/lang/Object")) {
                        // Enqueue the superclass of the current class
                        queue.enqueue(cn.superName);
                    }
                }
            }
        }
        // The specified method for the specified class is not the particular type of auto taint method corresponding to
        // the specified maps
        return null;
    }

    /* Represents the different types of auto-taint methods: sources, sinks and taintThroughs. */
    public enum AutoTaint {
        SOURCE("sources"),
        SINK("sinks"),
        TAINT_THROUGH("taintThrough");

        public final String name;

        AutoTaint(String name) {
            this.name = name;
        }
    }

    /* Inner class used to provide access to single instance of class and ensure that only a single instance of
     * BasicSourceSinkManager is ever created. */
    private static class BasicSourceSinkManagerSingleton {
        private static final BasicSourceSinkManager INSTANCE = new BasicSourceSinkManager();
    }
}
