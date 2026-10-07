import Jvm.Semantics
open Jvm
set_option maxHeartbeats 4000000

/-!
  Merged-path obligation, worked by hand as the target shape for the VCG.

  `public static int sign(int x) { return x > 0 ? 1 : -1; }`

  javac emits a diamond: `iload_0; ifle L; iconst_1; goto E; L: iconst_m1; E: ireturn`.
  The two arms rejoin at `ireturn`, where the returned value is a phi node.  The
  path-enumerating VCG would emit *two* obligations (one per arm); merging at the
  join instead yields *one*, whose postcondition is the phi expanded by its branch
  condition:

      s'.stk = [if x > 0 then 1 else -1]

  The proof is the uniform recipe: `by_cases` on each branch predicate (here the
  one comparison) fixes the guards, so `run` reduces with no internal fork; then
  the guarded result is discharged.  This is exactly what the generator emits for
  a merged obligation -- no per-arm hand proof.
-/

namespace Jvm.Examples.Sign
open Jvm

/-- Bytecode for `x > 0 ? 1 : -1` (x in local 0), dense-indexed. -/
def P : Nat → Option Instr
  | 0 => some (.iload 0)
  | 1 => some (.ifz zLe 4)   -- ifle: if x ≤ 0, jump past the `then`
  | 2 => some (.push 1)
  | 3 => some (.goto 5)
  | 4 => some (.push (-1))
  | 5 => some (.ireturn)
  | _ => none

/-- One obligation for both arms: the result is the phi `if x > 0 then 1 else -1`. -/
theorem sign_merged (s s' : State)
    (hpc : s.pc = 0) (hstk : s.stk = [])
    (hrun : run P 5 s = some s') :
    s'.stk = [if s.loc 0 > 0 then 1 else -1] := by
  by_cases h : s.loc 0 ≤ 0 <;>
    simp only [run, step, P, hpc, hstk, State.set, zLe, decide_eq_true_eq,
      decide_eq_false_iff_not, Nat.reduceAdd, Option.some.injEq, h] at hrun <;>
    subst hrun <;>
    simp only [List.cons.injEq, and_true] <;>
    split <;> omega

#print axioms sign_merged

end Jvm.Examples.Sign
