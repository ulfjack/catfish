import java.nio.file.*;
import java.util.*;

/** jvmlean: emit Lean proof obligations from a compiled Java method. */
public final class Main {

  public static void main(String[] args) throws Exception {
    Map<String, String> opt = new LinkedHashMap<>();
    for (String a : args) {
      int eq = a.indexOf('=');
      if (!a.startsWith("--") || eq < 0) {
        usage();
        return;
      }
      opt.put(a.substring(2, eq), a.substring(eq + 1));
    }
    Path cls = Path.of(req(opt, "class"));
    String method = req(opt, "method");
    Path out = Path.of(req(opt, "out"));
    String javaFile = opt.getOrDefault("source", cls.getFileName().toString());

    ClassFile cf = new ClassFile(cls);
    Map<String, String> callMap = buildCallMap(cf);
    Vcg v = new Vcg(cf, method, callMap, javaFile);
    if (v.errors.isEmpty()) v.explore(); // structural errors first: an
    // uninstrumented loop makes the
    // path walk diverge
    if (!v.errors.isEmpty()) {
      for (String e : v.errors) System.err.println("error: " + e);
      System.err.println(v.errors.size() + " error(s); no output written");
      System.exit(1);
    }

    Emit e = new Emit(v, cf, method, javaFile);
    if (opt.containsKey("proofs")) e.proofDir = Path.of(opt.get("proofs"));
    // --specs=Module=Namespace[,Module2=Namespace2]: domain specs to import and
    // open, so the generator carries no knowledge of any particular domain.
    String specs = opt.get("specs");
    if (specs != null && !specs.isBlank())
      for (String entry : specs.split(",")) e.specs.add(entry.split("=", 2));
    Files.createDirectories(out.getParent());
    Files.writeString(out, e.lean());
    Files.writeString(Path.of(out + ".map.json"), e.sourceMap());
    System.err.printf(
        "wrote %s: %d cut points, %d obligations (%d proved, %d open)%n",
        out, e.cutCount(), e.proved + e.unproved, e.proved, e.unproved);
    for (String o : e.open) System.err.println("  open: " + o);
  }

  private static String req(Map<String, String> o, String k) {
    String s = o.get(k);
    if (s == null) {
      usage();
      System.exit(2);
    }
    return s;
  }

  /**
   * The call map, built from @Returns annotations: each annotated static method becomes a contract
   * substituted at its call sites. `@Returns("hexValF c")` on `int hexVal(byte c)` yields
   * `Chunk.hexVal:(B)I -> hexValF` (eta-reduced from `fun c => hexValF c`).
   */
  private static Map<String, String> buildCallMap(ClassFile cf) {
    Map<String, String> m = new LinkedHashMap<>();
    for (ClassFile.Method mm : cf.methods) {
      if (mm.returnsSpec == null) continue;
      String param = paramName(mm);
      m.put(cf.thisClass + "." + mm.name + ":" + mm.desc, contractFn(mm.returnsSpec, param));
    }
    return m;
  }

  /** Name of the single value parameter (slot 0 for a static method). */
  private static String paramName(ClassFile.Method mm) {
    for (ClassFile.LocalVar lv : mm.locals) if (lv.slot == 0) return lv.name;
    return "x";
  }

  /** `fun param => expr`, eta-reduced to `f` when expr is exactly `f param`. */
  private static String contractFn(String expr, String param) {
    String suffix = " " + param;
    if (expr.endsWith(suffix)) {
      String head = expr.substring(0, expr.length() - suffix.length()).trim();
      if (!head.isEmpty() && !head.contains(" ")) return head;
    }
    return "(fun " + param + " => " + expr + ")";
  }

  private static void usage() {
    System.err.println(
        """
        usage: jvmlean --class=Foo.class --method=bar --out=lean/Generated/Foo_bar.lean
                       [--source=Foo.java] [--proofs=dir] [--specs=Module=Namespace,...]
        """);
  }
}
