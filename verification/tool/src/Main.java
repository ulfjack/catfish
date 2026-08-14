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
    Map<String, String> callMap = readCallMap(opt.get("calls"));

    ClassFile cf = new ClassFile(cls);
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

  private static Map<String, String> readCallMap(String p) throws Exception {
    Map<String, String> m = new LinkedHashMap<>();
    if (p == null) return m;
    for (String line : Files.readAllLines(Path.of(p))) {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int i = line.indexOf('=');
      m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
    }
    return m;
  }

  private static void usage() {
    System.err.println(
        """
        usage: jvmlean --class=Foo.class --method=bar --out=lean/Generated/Foo_bar.lean
                       [--calls=calls.map] [--source=Foo.java]
        """);
  }
}
