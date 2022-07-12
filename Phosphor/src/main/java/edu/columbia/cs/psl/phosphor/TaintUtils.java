package edu.columbia.cs.psl.phosphor;

import edu.columbia.cs.psl.phosphor.instrumenter.InvokedViaInstrumentation;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.SignatureReWriter;
import edu.columbia.cs.psl.phosphor.runtime.ArrayHelper;
import edu.columbia.cs.psl.phosphor.runtime.PhosphorStackFrame;
import edu.columbia.cs.psl.phosphor.runtime.Taint;
import edu.columbia.cs.psl.phosphor.struct.*;
import edu.columbia.cs.psl.phosphor.struct.harmony.util.List;
import edu.columbia.cs.psl.phosphor.struct.multid.MultiDTaintedArray;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

import static edu.columbia.cs.psl.phosphor.instrumenter.TaintMethodRecord.*;

public class TaintUtils {

    public static final int RAW_INSN = 201;
    public static final int NO_TAINT_STORE_INSN = 202;
    public static final int IGNORE_EVERYTHING = 203;
    public static final int IS_TMP_STORE = 213;
    public static final int FOLLOWED_BY_FRAME = 217;
    public static final String TAINT_FIELD = "PHOSPHOR_TAG";
    public static final String METHOD_SUFFIX = "$$PHOSPHORTAGGED";
    public static final String PHOSPHOR_ADDED_FIELD_PREFIX = "$$PHOSPHOR_";
    public static final String MARK_FIELD = PHOSPHOR_ADDED_FIELD_PREFIX + "MARK";
    public static final String ADDED_SVUID_SENTINEL = PHOSPHOR_ADDED_FIELD_PREFIX + "REMOVE_SVUID";
    public static final String CLASS_OFFSET_CACHE_ADDED_FIELD = PHOSPHOR_ADDED_FIELD_PREFIX + "OFFSET_CACHE";
    public static final String TAINT_WRAPPER_FIELD = "PHOSPHOR_WRAPPER";
    public static boolean VERIFY_CLASS_GENERATION = false;

    private TaintUtils() {
        // Prevents this class from being instantiated
    }

    public static boolean isPreAllocReturnType(String methodDescriptor) {
        Type retType = Type.getReturnType(methodDescriptor);
        return retType.getSort() != Type.VOID;
    }

    @InvokedViaInstrumentation(record = GET_TAINT_OBJECT)
    public static Taint getTaintObj(Object obj) {
        if (obj == null || Taint.IGNORE_TAINTING) {
            return null;
        }
        if (obj instanceof TaintedWithObjTag) {
            return (Taint) ((TaintedWithObjTag) obj).getPHOSPHOR_TAG();
        } else if (ArrayHelper.engaged == 1) {
            return ArrayHelper.getTag(obj);
        } else if (obj instanceof Taint[]) {
            return Taint.combineTaintArray((Taint[]) obj);
        } else if (obj instanceof LazyArrayObjTags) {
            return ((LazyArrayObjTags) obj).lengthTaint;
        }
        return null;
    }

    @SuppressWarnings("unused")
    @InvokedViaInstrumentation(record = GET_TAINT_COPY_SIMPLE)
    public static Taint<?> getTaintCopySimple(Object obj) {
        if (obj == null || Taint.IGNORE_TAINTING) {
            return null;
        } else if (obj instanceof TaintedWithObjTag) {
            Taint<?> t = ((Taint<?>) ((TaintedWithObjTag) obj).getPHOSPHOR_TAG());
            return t;
        } else {
            return null;
        }
    }

    public static int[][] create2DTaintArray(Object in, int[][] ar) {
        for (int i = 0; i < Array.getLength(in); i++) {
            Object entry = Array.get(in, i);
            if (entry != null) {
                ar[i] = new int[Array.getLength(entry)];
            }
        }
        return ar;
    }

    public static int[][][] create3DTaintArray(Object in, int[][][] ar) {
        for (int i = 0; i < Array.getLength(in); i++) {
            Object entry = Array.get(in, i);
            if (entry != null) {
                ar[i] = new int[Array.getLength(entry)][];
                for (int j = 0; j < Array.getLength(entry); j++) {
                    Object e = Array.get(entry, j);
                    if (e != null) {
                        ar[i][j] = new int[Array.getLength(e)];
                    }
                }
            }
        }
        return ar;
    }

    public static void generateMultiDTaintArray(Object in, Object taintRef) {
        // Precondition is that taintArrayRef is an array with the same number of
        // dimensions as obj, with each allocated.
        for (int i = 0; i < Array.getLength(in); i++) {
            Object entry = Array.get(in, i);
            Class<?> clazz = entry.getClass();
            if (clazz.isArray()) {
                // Multi-D array
                int innerDims = Array.getLength(entry);
                Array.set(taintRef, i, Array.newInstance(Integer.TYPE, innerDims));
            }
        }
    }

    public static void arraycopy(Object src, int srcPos,
            Object dest, int destPos,
            int length) {
        Object srcArray = src instanceof LazyArrayObjTags ? ((LazyArrayObjTags) src).getVal() : src;
        Object destArray = dest instanceof LazyArrayObjTags ? ((LazyArrayObjTags) dest).getVal() : dest;
        System.arraycopy(srcArray, srcPos, destArray, destPos, length);
        if (src instanceof LazyArrayObjTags && dest instanceof LazyArrayObjTags) {
            LazyArrayObjTags destArr = (LazyArrayObjTags) dest;
            LazyArrayObjTags srcArr = (LazyArrayObjTags) src;
            if (srcArr.taints != null) {
                if (destArr.taints == null) {
                    destArr.taints = new Taint[destArr.getLength()];
                }
                System.arraycopy(srcArr.taints, srcPos, destArr.taints, destPos, length);
            }
        }
    }

    public static String getShadowTaintType(String typeDesc) {
        Type t = Type.getType(typeDesc);
        if (isShadowedType(t)) {
            return Configuration.TAINT_TAG_DESC;
        }
        return null;
    }

    public static Type getContainerReturnType(String originalReturnType) {
        return getContainerReturnType(Type.getType(originalReturnType));
    }

    public static Type getContainerReturnType(Type originalReturnType) {
        switch (originalReturnType.getSort()) {
            case Type.BYTE:
                return Type.getType(TaintedByteWithObjTag.class);
            case Type.BOOLEAN:
                return Type.getType(TaintedBooleanWithObjTag.class);
            case Type.CHAR:
                return Type.getType(TaintedCharWithObjTag.class);
            case Type.DOUBLE:
                return Type.getType(TaintedDoubleWithObjTag.class);
            case Type.FLOAT:
                return Type.getType(TaintedFloatWithObjTag.class);
            case Type.INT:
                return Type.getType(TaintedIntWithObjTag.class);
            case Type.LONG:
                return Type.getType(TaintedLongWithObjTag.class);
            case Type.SHORT:
                return Type.getType(TaintedShortWithObjTag.class);
            case Type.OBJECT:
            case Type.ARRAY:
                return Type.getType(TaintedReferenceWithObjTag.class);
            default:
                return originalReturnType;
        }
    }

    public static boolean isShadowedType(Type t) {
        return (Configuration.REFERENCE_TAINTING || (t.getSort() != Type.ARRAY && t.getSort() != Type.OBJECT))
                && t.getSort() != Type.VOID;
    }

    public static boolean isWrappedType(Type t) {
        return t.getSort() == Type.ARRAY;
    }

    public static boolean isErasedReturnType(Type t) {
        return isWrappedTypeWithErasedType(t) || (t.getSort() == Type.OBJECT
                && !t.getDescriptor().equals("Ledu/columbia/cs/psl/phosphor/struct/TaintedReferenceWithObjTag;"));
    }

    public static boolean isWrappedTypeWithErasedType(Type t) {
        return t.getSort() == Type.ARRAY && (t.getDimensions() > 1 || t.getElementType().getSort() == Type.OBJECT);
    }

    public static boolean isWrapperType(Type t) {
        return t.getDescriptor().startsWith("Ledu/columbia/cs/psl/phosphor/struct/Lazy");
    }

    public static boolean isWrappedTypeWithSeparateField(Type t) {
        return t.getSort() == Type.ARRAY;
    }

    public static Type getWrapperType(Type t) {
        return MultiDTaintedArray.getTypeForType(t);
    }

    public static Type getUnwrappedType(Type wrappedType) {
        if (wrappedType.getSort() != Type.OBJECT) {
            return wrappedType;
        }
        switch (wrappedType.getDescriptor()) {
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyBooleanArrayObjTags;":
                return Type.getType("[Z");
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyByteArrayObjTags;":
                return Type.getType("[B");
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyCharArrayObjTags;":
                return Type.getType("[C");
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyDoubleArrayObjTags;":
                return Type.getType("[D");
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyFloatArrayObjTags;":
                return Type.getType("[F");
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyIntArrayObjTags;":
                return Type.getType("[I");
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyLongArrayObjTags;":
                return Type.getType("[J");
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyShortArrayObjTags;":
                return Type.getType("[S");
            case "Ledu/columbia/cs/psl/phosphor/struct/LazyReferenceArrayObjTags;":
                return Type.getType("[Ljava/lang/Object;");
            default:
                return wrappedType;
        }
    }

    public static Object getStackTypeForType(Type t) {
        if (t == null) {
            return Opcodes.NULL;
        }
        switch (t.getSort()) {
            case Type.ARRAY:
            case Type.OBJECT:
                return t.getInternalName();
            case Type.BYTE:
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                return Opcodes.INTEGER;
            case Type.DOUBLE:
                return Opcodes.DOUBLE;
            case Type.FLOAT:
                return Opcodes.FLOAT;
            case Type.LONG:
                return Opcodes.LONG;

            default:
                throw new IllegalArgumentException("Got: " + t);
        }
    }

    public static Object[] newTaintArray(int len) {
        return (Object[]) Array.newInstance(Configuration.TAINT_TAG_OBJ_CLASS, len);
    }

    private static <T> T shallowClone(T obj) {
        try {
            Method m = obj.getClass().getDeclaredMethod("clone");
            m.setAccessible(true);
            return (T) m.invoke(obj);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    public static <T extends Enum<T>> T enumValueOf(Class<T> enumType, String name) {
        T ret = Enum.valueOf(enumType, name);
        if (((Object) name) instanceof TaintedWithObjTag) {
            Object tag = ((TaintedWithObjTag) ((Object) name)).getPHOSPHOR_TAG();
            if (tag != null) {
                ret = shallowClone(ret);
                ((TaintedWithObjTag) ret).setPHOSPHOR_TAG(tag);
            }
        }
        return ret;
    }

    public static <T extends Enum<T>> T enumValueOf(Class<T> enumType, String name, PhosphorStackFrame ctrl) {
        T ret = Enum.valueOf(enumType, name);
        Taint tag = (Taint) ((TaintedWithObjTag) ((Object) name)).getPHOSPHOR_TAG();
        tag = Taint.combineTags(tag, ctrl);
        if (tag != null && !tag.isEmpty()) {
            ret = shallowClone(ret);
            ((TaintedWithObjTag) ret).setPHOSPHOR_TAG(tag);
        }
        return ret;
    }

    @SuppressWarnings("unused")
    @InvokedViaInstrumentation(record = ENSURE_UNBOXED)
    public static Object ensureUnboxed(Object o) {
        if (o instanceof LazyArrayObjTags) {
            return ((LazyArrayObjTags) o).getVal();
        } else if (Configuration.WITH_ENUM_BY_VAL && o instanceof Enum<?>) {
            return Enum.valueOf(((Enum) o).getDeclaringClass(), ((Enum) o).name());
        }
        return o;
    }

    public static boolean isPrimitiveArrayType(Type t) {
        return t != null && t.getSort() == Type.ARRAY && t.getDimensions() == 1
                && t.getElementType().getSort() != Type.OBJECT;
    }

    public static boolean isPrimitiveType(Type t) {
        return t != null && t.getSort() != Type.ARRAY && t.getSort() != Type.OBJECT && t.getSort() != Type.VOID;
    }

    public static boolean isPrimitiveOrPrimitiveArrayType(Type t) {
        return isPrimitiveArrayType(t) || isPrimitiveType(t);
    }

    public static String remapSignature(String sig, final List<String> extraArgs) {
        if (sig == null) {
            return null;
        }
        SignatureReWriter sw = new SignatureReWriter() {
            int isInArray = 0;
            boolean isInReturnType;
            boolean isInParam;

            @Override
            public SignatureVisitor visitArrayType() {
                isInArray++;
                return super.visitArrayType();
            }

            @Override
            public void visitBaseType(char descriptor) {
                if (descriptor == 'V') {
                    super.visitBaseType(descriptor);
                    return;
                }
                if (isInParam) {
                    if (isInArray == 0) {
                        super.visitClassType(Configuration.TAINT_TAG_INTERNAL_NAME);
                        super.visitEnd();
                        super.visitParameterType();
                        super.visitBaseType(descriptor);
                    } else if (isInArray == 1) {
                        super.pop();
                        super.visitClassType(
                                MultiDTaintedArray.getTypeForType(Type.getType("[" + descriptor)).getInternalName());
                        super.visitEnd();
                        super.visitArrayType();
                        super.visitParameterType();
                        super.visitBaseType(descriptor);
                    } else {
                        super.pop();
                        super.visitClassType(
                                MultiDTaintedArray.getTypeForType(Type.getType("[" + descriptor)).getInternalName());
                        super.visitEnd();
                    }
                } else {
                    if (isInArray > 0) {
                        super.pop();// reduce dimensions by 1
                        super.visitClassType(TaintUtils.getContainerReturnType("[" + descriptor).getInternalName());
                        super.visitEnd();
                    } else {
                        super.visitClassType(TaintUtils.getContainerReturnType("" + descriptor).getInternalName());
                        super.visitEnd();
                    }
                }
                isInParam = false;
                isInArray = 0;
            }

            @Override
            public SignatureVisitor visitReturnType() {
                // Add in extra stuff as needed.
                for (String s : extraArgs) {
                    super.visitParameterType();
                    super.visitClassType(s);
                    super.visitEnd();
                }
                isInReturnType = true;
                return super.visitReturnType();
            }

            @Override
            public void visitTypeVariable(String name) {
                isInParam = false;
                isInArray = 0;
                super.visitTypeVariable(name);
            }

            @Override
            public void visitClassType(String name) {
                isInArray = 0;
                isInParam = false;
                super.visitClassType(name);
            }

            @Override
            public SignatureVisitor visitParameterType() {
                isInParam = true;
                return super.visitParameterType();
            }
        };
        SignatureReader sr = new SignatureReader(sig);
        sr.accept(sw);
        sig = sw.toString();
        return sig;
    }

    /*
     * Returns the class instance resulting from removing any phosphor taint
     * wrapping from the specified class.
     */
    public static Class<?> getUnwrappedClass(Class<?> wrappedClass) {
        if (wrappedClass.equals(LazyBooleanArrayObjTags.class)) {
            return boolean[].class;
        } else if (wrappedClass.equals(LazyByteArrayObjTags.class)) {
            return byte[].class;
        } else if (wrappedClass.equals(LazyCharArrayObjTags.class)) {
            return char[].class;
        } else if (wrappedClass.equals(LazyDoubleArrayObjTags.class)) {
            return double[].class;
        } else if (wrappedClass.equals(LazyFloatArrayObjTags.class)) {
            return float[].class;
        } else if (wrappedClass.equals(LazyIntArrayObjTags.class)) {
            return int[].class;
        } else if (wrappedClass.equals(LazyLongArrayObjTags.class)) {
            return long[].class;
        } else if (wrappedClass.equals(LazyReferenceArrayObjTags.class)) {
            return Object[].class;
        } else if (wrappedClass.equals(LazyShortArrayObjTags.class)) {
            return short[].class;
        } else if (wrappedClass.equals(TaintedBooleanWithObjTag.class)) {
            return boolean.class;
        } else if (wrappedClass.equals(TaintedByteWithObjTag.class)) {
            return byte.class;
        } else if (wrappedClass.equals(TaintedCharWithObjTag.class)) {
            return char.class;
        } else if (wrappedClass.equals(TaintedDoubleWithObjTag.class)) {
            return double.class;
        } else if (wrappedClass.equals(TaintedFloatWithObjTag.class)) {
            return float.class;
        } else if (wrappedClass.equals(TaintedIntWithObjTag.class)) {
            return int.class;
        } else if (wrappedClass.equals(TaintedLongWithObjTag.class)) {
            return long.class;
        } else if (wrappedClass.equals(TaintedReferenceWithObjTag.class)) {
            return Object.class;
        } else if (wrappedClass.equals(TaintedShortWithObjTag.class)) {
            return short.class;
        } else {
            return wrappedClass;
        }
    }

    /* Returns whether the specified type is a primitive wrapper type. */
    public static boolean isTaintedPrimitiveType(Type type) {
        if (type == null) {
            return false;
        } else {
            return type.equals(Type.getType(TaintedByteWithObjTag.class))
                    || type.equals(Type.getType(TaintedBooleanWithObjTag.class))
                    || type.equals(Type.getType(TaintedCharWithObjTag.class))
                    || type.equals(Type.getType(TaintedDoubleWithObjTag.class))
                    || type.equals(Type.getType(TaintedFloatWithObjTag.class))
                    || type.equals(Type.getType(TaintedIntWithObjTag.class))
                    || type.equals(Type.getType(TaintedLongWithObjTag.class))
                    || type.equals(Type.getType(TaintedShortWithObjTag.class));
        }
    }

    public static boolean containsTaint(String desc) {
        return desc.contains(Configuration.TAINT_TAG_DESC);
    }
}
