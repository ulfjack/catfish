import Jvm.Semantics

/-!
  Smoke test for the `long` opcodes added to the JVM subset.

  Exercises `i2l`, `lmul`, `ladd`, `lsub`, and `lcmp` through the `step`/`run`
  relation, the way `OnePlusOne` does for the int core. No spec pipeline, no
  generated obligations -- just the machine.
-/

namespace Jvm.Examples
open Jvm

/-- Entry state: empty operand stack; locals and the array are unused. -/
def start : State :=
  { pc := 0, stk := [], loc := fun _ => 0, arr := { get := fun _ => 0, len := 0, len_le := by omega } }

/-- `(long) Integer.MAX_VALUE * 16L`, compared against `Integer.MAX_VALUE`.

    The product `34359738352` overflows a 32-bit int but is well within `long`,
    so `lmul` does not wrap and `lcmp` reports the product is greater (pushes 1).
    This is exactly the shape of `advance`'s overflow guard:
    `currentChunkSize * 16 + … > Integer.MAX_VALUE`. -/
def longOverflowGuard : Nat → Option Instr
  | 0 => some (.push 2147483647)   -- ldc Integer.MAX_VALUE
  | 1 => some .i2l                 -- (long) MAX_VALUE
  | 2 => some (.push 16)           -- ldc2_w 16L
  | 3 => some .lmul                -- MAX_VALUE * 16  = 34359738352, no 32-bit wrap
  | 4 => some (.push 2147483647)   -- Integer.MAX_VALUE
  | 5 => some .i2l                 -- widen for the long comparison
  | 6 => some .lcmp                -- product > MAX_VALUE  ⇒  1
  | 7 => some .ireturn
  | _ => none

theorem longOverflowGuard_gt :
    (run longOverflowGuard 8 start).map State.stk = some [1] := by
  rfl

/-- `5L + 3L - 2L`, to cover `ladd`/`lsub`. -/
def longAddSub : Nat → Option Instr
  | 0 => some (.push 5)
  | 1 => some (.push 3)
  | 2 => some .ladd
  | 3 => some (.push 2)
  | 4 => some .lsub
  | 5 => some .ireturn
  | _ => none

theorem longAddSub_eq_six :
    (run longAddSub 6 start).map State.stk = some [6] := by
  rfl

#print axioms longOverflowGuard_gt
#print axioms longAddSub_eq_six

end Jvm.Examples
