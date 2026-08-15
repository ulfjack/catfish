import Jvm.Semantics

/-!
  A minimal end-to-end sanity check of the JVM subset semantics.

  It executes the exact bytecode `javac` emits for `1 + 1` —
  `iconst_1 ; iconst_1 ; iadd ; ireturn` — through the `step`/`run` relation
  and proves the machine halts with `2` on top of the operand stack.

  This is a smoke test for the semantics, not a use of the spec pipeline: no
  `Verify.*` markers, no generated obligations. If the definitions in
  `Jvm/Semantics.lean` stop reducing (e.g. a bad `wrap` or `iadd`), this breaks.
-/

namespace Jvm.Examples
open Jvm

/-- `iconst_1 ; iconst_1 ; iadd ; ireturn`: the bytecode for `1 + 1`.
    Index = position in the instruction stream, as in generated programs. -/
def onePlusOne : Nat → Option Instr
  | 0 => some (.push 1)
  | 1 => some (.push 1)
  | 2 => some .iadd
  | 3 => some .ireturn
  | _ => none

/-- Entry state: empty operand stack; locals and the array are unused here. -/
def start : State :=
  { pc := 0, stk := [], loc := fun _ => 0, arr := { get := fun _ => 0, len := 0, len_le := by omega } }

/-- After the four instructions, the top (and only) stack value is `2`.
    `run` re-executes the semantics from the definition, so this is a claim
    about the machine, not about the program table alone. -/
theorem onePlusOne_eq_two :
    (run onePlusOne 4 start).map State.stk = some [2] := by
  rfl

-- Clean-axiom audit, in the spirit of the pipeline's `make audit`. Reducing
-- through `run`/`step` and the `State`/`Option`/`List` matches pulls in
-- `propext` (from match/structure-update compilation) — part of Lean's
-- standard base, and strictly cleaner than the generated obligations, which
-- also use `Classical.choice`/`Quot.sound`. Expected output:
--   'onePlusOne_eq_two' depends on axioms: [propext]
#print axioms onePlusOne_eq_two

end Jvm.Examples
