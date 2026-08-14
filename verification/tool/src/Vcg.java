import java.nio.file.*;
import java.util.*;

/**
 * Reads a class file, finds Verify.* cut points, and emits Lean: - the program table P
 * (dense-indexed transcription of the bytecode) - one `inv_<offset>` definition per cut point,
 * spliced from the spec string - one `sorry`-ed theorem per acyclic path between cut points - a
 * source map so Lean diagnostics can be mapped back into the .java file
 *
 * <p>UNTRUSTED. A bug here makes proofs fail to close, or emits a Prop that does not elaborate; it
 * cannot make a false theorem provable. The one exception is the spec string itself, which is
 * spliced verbatim -- see TRUST.md.
 */
final class Vcg {

  // ---------- decoded instruction ----------
  static final class Ins {
    int off; // bytecode offset
    int idx; // dense index in P
    String lean; // Lean Instr constructor, targets patched later
    int target = -1; // branch target offset, or -1
    boolean fallsThrough = true;
    boolean isReturn = false;
    boolean isMarker = false; // the invokestatic Verify.*
    boolean isMarkerLdc = false; // the ldc that feeds it
    String specString; // on the ldc of a marker
    String markerKind; // requires | invariant | ensure | assume
    int retSlot = -1; // on an `ensure` cut point: slot holding the return value
    // symbolic effect, applied by the explorer
    String kind; // opcode class for the explorer
    int slot; // local slot for loads/stores/iinc
    long imm; // immediate
    String callee; // for .call
  }

  final ClassFile cf;
  final ClassFile.Method m;
  final List<Ins> ins = new ArrayList<>();
  final Map<Integer, Integer> offToIdx = new HashMap<>();
  final NavigableMap<Integer, Ins> byOff = new TreeMap<>();
  final Map<String, String> callMap; // "Owner.name:desc" -> Lean function name
  final List<String> errors = new ArrayList<>();
  final String javaFile;

  Vcg(ClassFile cf, String methodName, Map<String, String> callMap, String javaFile) {
    this.cf = cf;
    this.m = cf.method(methodName);
    this.callMap = callMap;
    this.javaFile = javaFile;
    decode();
    markCutPoints();
    synthPreconditionCutPoint();
    synthReturnCutPoints();
    checkBackEdges();
  }

  /**
   * The entry (requires) cut point. If the method opens with Verify.requires, keep it; otherwise
   * make the first instruction a requires cut point carrying the @Precondition, defaulting to True.
   */
  private void synthPreconditionCutPoint() {
    if (ins.isEmpty()) return;
    Ins first = ins.get(0);
    if (first.isMarkerLdc && "requires".equals(first.markerKind)) return;
    first.isMarkerLdc = true;
    first.markerKind = "requires";
    first.specString = m.precondition != null ? m.precondition : "True";
  }

  /**
   * With @Returns("expr"), every `return <local>;` carries the postcondition `ret = expr` without
   * an explicit Verify.ensure. Turn each `iload r; ireturn` into an ensure cut point, exactly as a
   * hand-written Verify.ensure("ret = expr") would have produced.
   */
  private void synthReturnCutPoints() {
    if (m.returnsSpec == null) return;
    for (int k = 0; k + 1 < ins.size(); k++) {
      Ins a = ins.get(k), b = ins.get(k + 1);
      if (a.kind.equals("iload") && b.isReturn) {
        a.isMarkerLdc = true; // isMarkerLdc == "is a cut point" downstream
        a.markerKind = "ensure";
        a.retSlot = a.slot;
        a.specString = "ret = " + m.returnsSpec;
      }
    }
  }

  // ---------- decoding ----------
  private int u1(int p) {
    return m.code[p] & 0xff;
  }

  private int s1(int p) {
    return m.code[p];
  }

  private int u2(int p) {
    return ((m.code[p] & 0xff) << 8) | (m.code[p + 1] & 0xff);
  }

  private int s2(int p) {
    return (short) u2(p);
  }

  private void decode() {
    int p = 0;
    while (p < m.code.length) {
      Ins i = new Ins();
      i.off = p;
      int op = u1(p);
      int len = 1;
      switch (op) {
        case 0x00 -> {
          i.kind = "nop";
          i.lean = ".nop";
        }
        case 0x01 -> {
          i.kind = "pushRef";
          i.lean = ".pushRef";
        } // aconst_null
        case 0x02 -> {
          i.kind = "push";
          i.imm = -1;
          i.lean = ".push (-1)";
        }
        case 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 -> {
          i.kind = "push";
          i.imm = op - 0x03;
          i.lean = ".push " + i.imm;
        }
        case 0x10 -> {
          i.kind = "push";
          i.imm = s1(p + 1);
          i.lean = ".push (" + i.imm + ")";
          len = 2;
        }
        case 0x11 -> {
          i.kind = "push";
          i.imm = s2(p + 1);
          i.lean = ".push (" + i.imm + ")";
          len = 3;
        }
        case 0x12, 0x13 -> { // ldc, ldc_w
          int cpi = (op == 0x12) ? u1(p + 1) : u2(p + 1);
          len = (op == 0x12) ? 2 : 3;
          int tag = cf.pool[cpi].tag;
          if (tag == ClassFile.STRING) {
            i.kind = "pushRef";
            i.lean = ".pushRef";
            i.isMarkerLdc = true; // provisional; confirmed below
            i.specString = cf.stringConst(cpi);
          } else if (tag == ClassFile.INT) {
            i.kind = "push";
            i.imm = cf.intConst(cpi);
            i.lean = ".push (" + i.imm + ")";
          } else {
            errors.add("unsupported ldc constant tag " + tag + " at " + p);
            i.kind = "nop";
            i.lean = ".nop";
          }
        }
        case 0x15 -> {
          i.kind = "iload";
          i.slot = u1(p + 1);
          len = 2;
        }
        case 0x1a, 0x1b, 0x1c, 0x1d -> {
          i.kind = "iload";
          i.slot = op - 0x1a;
        }
        case 0x19 -> {
          i.kind = "pushRef";
          i.lean = ".pushRef";
          len = 2;
        } // aload
        case 0x2a, 0x2b, 0x2c, 0x2d -> {
          i.kind = "pushRef";
          i.lean = ".pushRef";
        }
        case 0x36 -> {
          i.kind = "istore";
          i.slot = u1(p + 1);
          len = 2;
        }
        case 0x3b, 0x3c, 0x3d, 0x3e -> {
          i.kind = "istore";
          i.slot = op - 0x3b;
        }
        case 0x33 -> {
          i.kind = "baload";
          i.lean = ".baload";
        }
        case 0xbe -> {
          i.kind = "alen";
          i.lean = ".alen";
        }
        case 0x60 -> {
          i.kind = "bin";
          i.lean = ".iadd";
        }
        case 0x64 -> {
          i.kind = "bin";
          i.lean = ".isub";
        }
        case 0x68 -> {
          i.kind = "bin";
          i.lean = ".imul";
        }
        case 0x6c -> {
          i.kind = "bin";
          i.lean = ".idiv";
        }
        case 0x70 -> {
          i.kind = "bin";
          i.lean = ".irem";
        }
        case 0x7e -> {
          i.kind = "bin";
          i.lean = ".iand";
        }
        case 0x80 -> {
          i.kind = "bin";
          i.lean = ".ior";
        }
        case 0x82 -> {
          i.kind = "bin";
          i.lean = ".ixor";
        }
        case 0x78 -> {
          i.kind = "bin";
          i.lean = ".ishl";
        }
        case 0x7a -> {
          i.kind = "bin";
          i.lean = ".ishr";
        }
        case 0x84 -> {
          i.kind = "iinc";
          i.slot = u1(p + 1);
          i.imm = s1(p + 2);
          len = 3;
          i.lean = ".iinc " + i.slot + " (" + i.imm + ")";
        }
        case 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e -> {
          String[] z = {"zEq", "zNe", "zLt", "zGe", "zGt", "zLe"};
          i.kind = "ifz";
          i.callee = z[op - 0x99];
          i.target = p + s2(p + 1);
          len = 3;
        }
        case 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4 -> {
          String[] c = {"pEq", "pNe", "pLt", "pGe", "pGt", "pLe"};
          i.kind = "ifcmp";
          i.callee = c[op - 0x9f];
          i.target = p + s2(p + 1);
          len = 3;
        }
        case 0xa7 -> {
          i.kind = "goto";
          i.target = p + s2(p + 1);
          len = 3;
          i.fallsThrough = false;
        }
        case 0xac -> {
          i.kind = "return";
          i.lean = ".ireturn";
          i.isReturn = true;
          i.fallsThrough = false;
        }
        case 0xb1 -> {
          i.kind = "return";
          i.lean = ".vreturn";
          i.isReturn = true;
          i.fallsThrough = false;
        }
        case 0x91 -> {
          i.kind = "nop";
          i.lean = ".nop";
        } // i2b: modelled as identity
        case 0xb8 -> { // invokestatic
          int cpi = u2(p + 1);
          len = 3;
          String ref = cf.methodRef(cpi);
          i.callee = ref;
          if (ref.startsWith("Verify.")) {
            i.kind = "marker";
            i.isMarker = true;
            i.lean = ".mark";
            i.markerKind = ref.substring("Verify.".length(), ref.indexOf(':'));
          } else if (callMap.containsKey(ref)) {
            i.kind = "call";
            i.lean = ".call " + callMap.get(ref);
          } else {
            errors.add(
                "unmapped invokestatic "
                    + ref
                    + " at offset "
                    + p
                    + " (add it to calls.map or make it a Verify.* marker)");
            i.kind = "nop";
            i.lean = ".nop";
          }
        }
        default -> {
          errors.add(String.format("unsupported opcode 0x%02x at offset %d", op, p));
          i.kind = "nop";
          i.lean = ".nop";
        }
      }
      if (i.kind.equals("iload")) i.lean = ".iload " + i.slot;
      if (i.kind.equals("istore")) i.lean = ".istore " + i.slot;
      ins.add(i);
      byOff.put(p, i);
      p += len;
    }
    for (int k = 0; k < ins.size(); k++) {
      ins.get(k).idx = k;
      offToIdx.put(ins.get(k).off, k);
    }
  }

  /** A cut point is the `ldc <String>` immediately feeding an invokestatic Verify.*. */
  private void markCutPoints() {
    for (int k = 0; k < ins.size(); k++) {
      Ins i = ins.get(k);
      if (!i.isMarkerLdc) continue;
      Ins nxt = (k + 1 < ins.size()) ? ins.get(k + 1) : null;
      if (nxt == null || !nxt.isMarker) {
        i.isMarkerLdc = false; // an ordinary string constant, not a marker
        i.specString = null;
        continue;
      }
      i.markerKind = nxt.markerKind;
      if (i.markerKind.equals("ensure")) {
        Ins ld = (k + 2 < ins.size()) ? ins.get(k + 2) : null;
        Ins rt = (k + 3 < ins.size()) ? ins.get(k + 3) : null;
        if (ld == null || !ld.kind.equals("iload") || rt == null || !rt.isReturn) {
          errors.add(
              "Verify.ensure at offset "
                  + i.off
                  + " must be immediately followed by"
                  + " `return <local>;` so the return value can be named");
        } else {
          i.retSlot = ld.slot;
        }
      }
    }
  }

  boolean isCut(int idx) {
    return idx >= 0 && idx < ins.size() && ins.get(idx).isMarkerLdc;
  }

  /** Every back edge must land on a cut point, or the loop has no invariant. */
  private void checkBackEdges() {
    if (ins.isEmpty() || !ins.get(0).isMarkerLdc || !"requires".equals(ins.get(0).markerKind)) {
      errors.add("method must have an entry precondition (@Precondition or Verify.requires)");
    }
    for (Ins i : ins) {
      if (i.target >= 0 && i.target <= i.off) {
        Integer t = offToIdx.get(i.target);
        if (t == null) {
          errors.add("branch to non-instruction offset " + i.target);
          continue;
        }
        if (!isCut(t)) {
          Integer line =
              m.lines.floorEntry(i.target) == null ? null : m.lines.floorEntry(i.target).getValue();
          errors.add(
              "back edge "
                  + i.off
                  + " -> "
                  + i.target
                  + " (source line "
                  + line
                  + ") does not land on a Verify.invariant(...) cut point");
        }
      }
    }
  }

  // ---------- symbolic path exploration ----------

  static final class Sym {
    ArrayDeque<String> stack = new ArrayDeque<>(); // top at head
    Map<Integer, String> locals = new HashMap<>();
    List<String> conds = new ArrayList<>();
    List<String> side = new ArrayList<>(); // side conditions, emitted as comments
    int steps = 0;

    Sym copy() {
      Sym t = new Sym();
      t.stack = new ArrayDeque<>(stack);
      t.locals = new HashMap<>(locals);
      t.conds = new ArrayList<>(conds);
      t.side = new ArrayList<>(side);
      t.steps = steps;
      return t;
    }

    String loc(int n) {
      return locals.getOrDefault(n, "s.loc " + n);
    }
  }

  static final class Path {
    int fromIdx, toIdx;
    boolean endsInReturn;
    Sym sym;
  }

  final List<Path> paths = new ArrayList<>();

  void explore() {
    for (Ins c : ins) {
      if (!c.isMarkerLdc) continue;
      walk(c.idx, c.idx, new Sym(), 0);
    }
  }

  private void walk(int from, int at, Sym st, int depth) {
    if (depth > 4000) {
      errors.add("path explosion from index " + from);
      return;
    }
    Ins i = ins.get(at);

    // reached a cut point (other than the origin at step 0): emit the path
    if (i.isMarkerLdc && st.steps > 0) {
      Path pa = new Path();
      pa.fromIdx = from;
      pa.toIdx = at;
      pa.sym = st;
      paths.add(pa);
      return;
    }

    switch (i.kind) {
      case "nop" -> {
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "pushRef" -> {
        st.stack.push("0");
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "marker" -> {
        st.stack.pop();
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "push" -> {
        st.stack.push("(" + i.imm + " : Int)");
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "iload" -> {
        st.stack.push(par(st.loc(i.slot)));
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "istore" -> {
        String v = st.stack.pop();
        st.locals.put(i.slot, v);
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "iinc" -> {
        st.locals.put(i.slot, "wrap (" + st.loc(i.slot) + " + " + i.imm + ")");
        st.side.add("no wrap: " + st.loc(i.slot) + " + " + i.imm);
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "alen" -> {
        st.stack.pop();
        st.stack.push("(s.alen : Int)");
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "baload" -> {
        String j = st.stack.pop();
        st.stack.pop();
        st.side.add("in bounds: 0 ≤ " + j + " ∧ " + j + " < (s.alen : Int)");
        st.stack.push("(s.arr (" + j + ").toNat)");
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "bin" -> {
        String b = st.stack.pop(), a = st.stack.pop();
        String opn = i.lean.substring(1); // iadd, isub, ...
        String e =
            switch (opn) {
              case "iadd" -> "wrap (" + a + " + " + b + ")";
              case "isub" -> "wrap (" + a + " - " + b + ")";
              case "imul" -> "wrap (" + a + " * " + b + ")";
              case "idiv" -> "wrap (Int.div " + a + " " + b + ")";
              case "irem" -> "Int.emod " + a + " " + b;
              default -> "(opaque_" + opn + " " + a + " " + b + ")";
            };
        if (opn.equals("iadd") || opn.equals("isub") || opn.equals("imul"))
          st.side.add("no wrap: " + e.substring(6, e.length() - 1));
        if (opn.equals("idiv") || opn.equals("irem")) st.side.add("nonzero divisor: " + b);
        st.stack.push(par(e));
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "call" -> {
        String a = st.stack.pop();
        st.stack.push("(" + i.lean.substring(".call ".length()) + " " + a + ")");
        st.steps++;
        walk(from, at + 1, st, depth + 1);
      }
      case "goto" -> {
        st.steps++;
        walk(from, offToIdx.get(i.target), st, depth + 1);
      }
      case "ifcmp", "ifz" -> {
        String cond;
        if (i.kind.equals("ifcmp")) {
          String b = st.stack.pop(), a = st.stack.pop();
          cond = rel(i.callee, a, b);
        } else {
          String a = st.stack.pop();
          cond = rel(i.callee, a, "(0 : Int)");
        }
        st.steps++;
        Sym taken = st.copy();
        taken.conds.add(cond);
        Sym notTaken = st.copy();
        notTaken.conds.add("¬ (" + cond + ")");
        walk(from, offToIdx.get(i.target), taken, depth + 1);
        walk(from, at + 1, notTaken, depth + 1);
      }
      case "return" -> {
        st.steps++;
        Path pa = new Path();
        pa.fromIdx = from;
        pa.toIdx = at;
        pa.endsInReturn = true;
        pa.sym = st;
        paths.add(pa);
        if (!ins.get(from).markerKind.equals("ensure")) {
          Integer line =
              m.lines.floorEntry(i.off) == null ? null : m.lines.floorEntry(i.off).getValue();
          errors.add(
              "return at offset "
                  + i.off
                  + " (source line "
                  + line
                  + ") is not preceded by Verify.ensure(...) in its own block");
        }
      }
      default -> errors.add("explorer: unhandled kind " + i.kind + " at offset " + i.off);
    }
  }

  private static String par(String e) {
    return e.startsWith("(") && e.endsWith(")") ? e : "(" + e + ")";
  }

  private static String rel(String p, String a, String b) {
    return switch (p) {
      case "pEq", "zEq" -> a + " = " + b;
      case "pNe", "zNe" -> a + " ≠ " + b;
      case "pLt", "zLt" -> a + " < " + b;
      case "pLe", "zLe" -> a + " ≤ " + b;
      case "pGt", "zGt" -> a + " > " + b;
      case "pGe", "zGe" -> a + " ≥ " + b;
      default -> throw new IllegalStateException(p);
    };
  }

  String predName(Ins i) {
    return i.callee;
  }
}
