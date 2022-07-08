package edu.columbia.cs.psl.phosphor.struct;

import edu.columbia.cs.psl.phosphor.Configuration;
import edu.columbia.cs.psl.phosphor.instrumenter.InvokedViaInstrumentation;
import edu.columbia.cs.psl.phosphor.runtime.PhosphorStackFrame;
import edu.columbia.cs.psl.phosphor.runtime.Taint;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static edu.columbia.cs.psl.phosphor.instrumenter.TaintMethodRecord.TAINTED_FLOAT_ARRAY_GET;
import static edu.columbia.cs.psl.phosphor.instrumenter.TaintMethodRecord.TAINTED_FLOAT_ARRAY_SET;

public final class LazyFloatArrayObjTags extends LazyArrayObjTags {

    private static final long serialVersionUID = 6150577887427835055L;

    public float[] val;

    public LazyFloatArrayObjTags(int len) {
        val = new float[len];
    }

    public LazyFloatArrayObjTags(float[] array, Taint[] taints) {
        this.taints = taints;
        this.val = array;
    }

    public LazyFloatArrayObjTags(float[] array) {
        this.val = array;
    }

    public LazyFloatArrayObjTags(Taint lenTaint, float[] array) {
        this.val = array;
        this.lengthTaint = lenTaint;
    }

    @InvokedViaInstrumentation(record = TAINTED_FLOAT_ARRAY_SET)
    public void set(int idx, float val, Taint idxTaint, Taint valTaint, PhosphorStackFrame stackFrame) {
        set(idx, val, Configuration.derivedTaintListener.arraySet(this, idx, val, idxTaint, valTaint, stackFrame));
    }

    @Override
    public Object clone() {
        return new LazyFloatArrayObjTags(val.clone(), (taints != null) ? taints.clone() : null);
    }

    public void set(int idx, float val, Taint tag) {
        this.val[idx] = val;
        if(taints == null && tag != null && !tag.isEmpty()) {
            taints = new Taint[this.val.length];
        }
        if(taints != null) {
            taints[idx] = tag;
        }
    }

    @InvokedViaInstrumentation(record = TAINTED_FLOAT_ARRAY_GET)
    public float get(int idx, Taint idxTaint, PhosphorStackFrame ret) {
        return Configuration.derivedTaintListener.arrayGet(this, idx, idxTaint, ret);
    }

    public static LazyFloatArrayObjTags factory(float[] array) {
        if(array == null) {
            return null;
        }
        return new LazyFloatArrayObjTags(array);
    }

    public static LazyFloatArrayObjTags factory(float[] array, Taint lengthTaint) {
        if (array == null) {
            return null;
        }
        return new LazyFloatArrayObjTags(lengthTaint, array);
    }

    public int getLength() {
        return val.length;
    }

    @Override
    public Object getVal() {
        return val;
    }

    public void ensureVal(float[] v) {
        if (v != val) {
            val = v;
        }
    }

    public static float[] unwrap(LazyFloatArrayObjTags obj) {
        if (obj != null) {
            return obj.val;
        }
        return null;
    }

    private void writeObject(ObjectOutputStream stream) throws IOException {
        if (val == null) {
            stream.writeInt(-1);
        } else {
            stream.writeInt(val.length);
            for (float el : val) {
                stream.writeFloat(el);
            }
        }
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        int len = stream.readInt();
        if (len == -1) {
            val = null;
        } else {
            val = new float[len];
            for (int i = 0; i < len; i++) {
                val[i] = stream.readFloat();
            }
        }
    }
}


