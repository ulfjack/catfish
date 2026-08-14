import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Just enough class-file parsing for the VC generator. No dependencies. */
final class ClassFile {

  // ---- constant pool ----
  static final int UTF8 = 1,
      INT = 3,
      FLOAT = 4,
      LONG = 5,
      DOUBLE = 6,
      CLASS = 7,
      STRING = 8,
      FIELDREF = 9,
      METHODREF = 10,
      IFACEREF = 11,
      NAMEANDTYPE = 12,
      MHANDLE = 15,
      MTYPE = 16,
      DYNAMIC = 17,
      INVOKEDYNAMIC = 18,
      MODULE = 19,
      PACKAGE = 20;

  static final class Const {
    int tag;
    String str;
    int i1, i2;
    int intVal;
    long longVal;
  }

  final Const[] pool;
  final String thisClass;
  final List<Method> methods = new ArrayList<>();

  static final class Method {
    String name, desc;
    byte[] code;
    int maxLocals;

    /** LocalVariableTable entries, with scopes: javac reuses slots. */
    List<LocalVar> locals = new ArrayList<>();

    /** bytecode offset -> source line */
    NavigableMap<Integer, Integer> lines = new TreeMap<>();
  }

  static final class LocalVar {
    int start, length, slot;
    String name;

    boolean covers(int off) {
      return off >= start && off < start + length;
    }
  }

  private final DataInputStream in;

  ClassFile(Path p) throws IOException {
    byte[] all = Files.readAllBytes(p);
    in = new DataInputStream(new ByteArrayInputStream(all));
    int magic = in.readInt();
    if (magic != 0xCAFEBABE) throw new IOException("not a class file: " + p);
    in.readUnsignedShort(); // minor
    in.readUnsignedShort(); // major
    int n = in.readUnsignedShort();
    pool = new Const[n];
    for (int i = 1; i < n; i++) {
      Const c = new Const();
      c.tag = in.readUnsignedByte();
      switch (c.tag) {
        case UTF8 -> c.str = in.readUTF();
        case INT -> c.intVal = in.readInt();
        case FLOAT -> in.readInt();
        case LONG -> {
          c.longVal = in.readLong();
        }
        case DOUBLE -> {
          in.readLong();
        }
        case CLASS, STRING, MTYPE, MODULE, PACKAGE -> c.i1 = in.readUnsignedShort();
        case FIELDREF, METHODREF, IFACEREF, NAMEANDTYPE, DYNAMIC, INVOKEDYNAMIC -> {
          c.i1 = in.readUnsignedShort();
          c.i2 = in.readUnsignedShort();
        }
        case MHANDLE -> {
          in.readUnsignedByte();
          c.i1 = in.readUnsignedShort();
        }
        default -> throw new IOException("bad constant tag " + c.tag + " at " + i);
      }
      pool[i] = c;
      if (c.tag == LONG || c.tag == DOUBLE) i++; // 8-byte constants take two slots
    }
    in.readUnsignedShort(); // access
    thisClass = className(in.readUnsignedShort());
    in.readUnsignedShort(); // super
    int ifaces = in.readUnsignedShort();
    for (int i = 0; i < ifaces; i++) in.readUnsignedShort();
    skipFields();
    int mcount = in.readUnsignedShort();
    for (int i = 0; i < mcount; i++) methods.add(readMethod());
    // class attributes: ignore
  }

  String utf8(int idx) {
    return pool[idx].str;
  }

  String className(int idx) {
    return utf8(pool[idx].i1).replace('/', '.');
  }

  String stringConst(int idx) {
    return utf8(pool[idx].i1);
  }

  int intConst(int idx) {
    return pool[idx].intVal;
  }

  /** owner.name:desc for a Methodref */
  String methodRef(int idx) {
    Const mr = pool[idx];
    Const nt = pool[mr.i2];
    return className(mr.i1) + "." + utf8(nt.i1) + ":" + utf8(nt.i2);
  }

  private void skipFields() throws IOException {
    int n = in.readUnsignedShort();
    for (int i = 0; i < n; i++) {
      in.readUnsignedShort();
      in.readUnsignedShort();
      in.readUnsignedShort();
      skipAttributes();
    }
  }

  private void skipAttributes() throws IOException {
    int n = in.readUnsignedShort();
    for (int i = 0; i < n; i++) {
      in.readUnsignedShort();
      int len = in.readInt();
      in.skipNBytes(len);
    }
  }

  private Method readMethod() throws IOException {
    Method m = new Method();
    in.readUnsignedShort(); // access
    m.name = utf8(in.readUnsignedShort());
    m.desc = utf8(in.readUnsignedShort());
    int nattr = in.readUnsignedShort();
    for (int i = 0; i < nattr; i++) {
      String an = utf8(in.readUnsignedShort());
      int len = in.readInt();
      if (an.equals("Code")) readCode(m);
      else in.skipNBytes(len);
    }
    return m;
  }

  private void readCode(Method m) throws IOException {
    in.readUnsignedShort(); // max_stack
    m.maxLocals = in.readUnsignedShort();
    int clen = in.readInt();
    m.code = new byte[clen];
    in.readFully(m.code);
    int exc = in.readUnsignedShort();
    for (int i = 0; i < exc; i++) in.skipNBytes(8);
    int nattr = in.readUnsignedShort();
    for (int i = 0; i < nattr; i++) {
      String an = utf8(in.readUnsignedShort());
      int len = in.readInt();
      switch (an) {
        case "LocalVariableTable" -> {
          int n = in.readUnsignedShort();
          for (int j = 0; j < n; j++) {
            int st = in.readUnsignedShort(), ln = in.readUnsignedShort();
            LocalVar lv = new LocalVar();
            lv.start = st;
            lv.length = ln;
            lv.name = utf8(in.readUnsignedShort());
            in.readUnsignedShort(); // descriptor
            lv.slot = in.readUnsignedShort();
            m.locals.add(lv);
          }
        }
        case "LineNumberTable" -> {
          int n = in.readUnsignedShort();
          for (int j = 0; j < n; j++) {
            int start = in.readUnsignedShort();
            int line = in.readUnsignedShort();
            m.lines.put(start, line);
          }
        }
        default -> in.skipNBytes(len);
      }
    }
  }

  Method method(String name) {
    for (Method m : methods) if (m.name.equals(name)) return m;
    throw new NoSuchElementException(name);
  }
}
