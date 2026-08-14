/-
  A subset JVM semantics, sufficient for straight-line + loop integer/byte-array
  code as produced by javac.  Trusted component: everything in this file is part
  of the trust base and should be read by a human.
-/

namespace Jvm

def MAXI : Int := 2147483647
def MINI : Int := -2147483648

/-- 32-bit two's-complement wraparound. -/
def wrap (x : Int) : Int := (x + 2147483648) % 4294967296 - 2147483648

theorem wrap_id {x : Int} (h1 : MINI ≤ x) (h2 : x ≤ MAXI) : wrap x = x := by
  unfold wrap MAXI MINI at *
  have : (x + 2147483648) % 4294967296 = x + 2147483648 := by
    apply Int.emod_eq_of_lt <;> omega
  omega

/-- Literal-bound form, so callers can discharge side conditions with `omega`
    without unfolding MAXI/MINI in every goal. -/
theorem wrap_id' {x : Int} (h1 : -2147483648 ≤ x) (h2 : x ≤ 2147483647) : wrap x = x :=
  wrap_id (by unfold MINI; omega) (by unfold MAXI; omega)

/--
  Machine state.

  ASSUMPTION (documented in TRUST.md): this subset admits exactly one array,
  passed as a parameter, so the heap is modelled as (arr, alen) rather than a
  general reference map.  `arr` holds already-sign-extended byte values, which
  is what `baload` delivers.
-/
structure State where
  pc   : Nat
  stk  : List Int
  loc  : Nat → Int
  arr  : Nat → Int
  alen : Nat

def State.set (s : State) (n : Nat) (v : Int) : Nat → Int :=
  fun m => if m = n then v else s.loc m

inductive Instr where
  | nop
  | mark                        -- invokestatic Verify.*: pops the pushed spec ref
  | push     (v : Int)          -- iconst_*, bipush, sipush, ldc <int>
  | pushRef                     -- ldc <String>, aload_*: opaque reference
  | iload    (n : Nat)
  | istore   (n : Nat)
  | iinc     (n : Nat) (k : Int)
  | baload
  | alen
  | iadd | isub | imul | idiv | irem
  | iand | ior | ixor | ishl | ishr
  | call     (f : Int → Int)    -- verified-elsewhere pure static, one int arg
  | callSpec                    -- spec-only static: never executed, never reached
  | ifcmp    (p : Int → Int → Bool) (t : Nat)
  | ifz      (p : Int → Bool) (t : Nat)
  | goto     (t : Nat)
  | ireturn
  | vreturn

/-- Bitwise ops on 32-bit two's-complement, via UInt32. -/
def bitOp (f : UInt32 → UInt32 → UInt32) (a b : Int) : Int :=
  let w := f (UInt32.ofNat (a % 4294967296).toNat) (UInt32.ofNat (b % 4294967296).toNat)
  wrap (w.toNat : Int)

/-- Shift/mask semantics per JVMS: only the low 5 bits of the shift count. -/
def shiftCount (b : Int) : Nat := (b % 32).toNat

def step (prog : Nat → Option Instr) (s : State) : Option State :=
  match prog s.pc with
  | none => none
  | some i =>
    match i, s.stk with
    | .nop,        st           => some { s with pc := s.pc + 1, stk := st }
    | .mark,       _ :: st      => some { s with pc := s.pc + 1, stk := st }
    | .push v,     st           => some { s with pc := s.pc + 1, stk := v :: st }
    | .pushRef,    st           => some { s with pc := s.pc + 1, stk := 0 :: st }
    | .iload n,    st           => some { s with pc := s.pc + 1, stk := s.loc n :: st }
    | .istore n,   v :: st      =>
        some { s with pc := s.pc + 1, stk := st, loc := s.set n v }
    | .iinc n k,   st           =>
        some { s with pc := s.pc + 1, stk := st, loc := s.set n (wrap (s.loc n + k)) }
    | .baload,     j :: _ :: st =>
        if 0 ≤ j ∧ j < (s.alen : Int)
        then some { s with pc := s.pc + 1, stk := s.arr j.toNat :: st }
        else none                                    -- ArrayIndexOutOfBoundsException
    | .alen,       _ :: st      => some { s with pc := s.pc + 1, stk := (s.alen : Int) :: st }
    | .iadd,       b :: a :: st => some { s with pc := s.pc + 1, stk := wrap (a + b) :: st }
    | .isub,       b :: a :: st => some { s with pc := s.pc + 1, stk := wrap (a - b) :: st }
    | .imul,       b :: a :: st => some { s with pc := s.pc + 1, stk := wrap (a * b) :: st }
    | .idiv,       b :: a :: st =>
        if b = 0 then none                           -- ArithmeticException
        else some { s with pc := s.pc + 1, stk := wrap (Int.div a b) :: st }
    | .irem,       b :: a :: st =>
        if b = 0 then none
        else some { s with pc := s.pc + 1, stk := Int.emod a b :: st }
    -- bitwise ops go via 32-bit words; `bitOp` is defined below
    | .iand,       b :: a :: st => some { s with pc := s.pc + 1, stk := bitOp (· &&& ·) a b :: st }
    | .ior,        b :: a :: st => some { s with pc := s.pc + 1, stk := bitOp (· ||| ·) a b :: st }
    | .ixor,       b :: a :: st => some { s with pc := s.pc + 1, stk := bitOp (· ^^^ ·) a b :: st }
    | .ishl,       b :: a :: st =>
        some { s with pc := s.pc + 1, stk := wrap (a * 2 ^ shiftCount b) :: st }
    | .ishr,       b :: a :: st =>
        some { s with pc := s.pc + 1, stk := Int.div a (2 ^ shiftCount b) :: st }
    | .call f,     c :: st      => some { s with pc := s.pc + 1, stk := f c :: st }
    | .callSpec,   _            => none              -- must not be reachable
    | .ifcmp p t,  b :: a :: st =>
        some { s with pc := if p a b then t else s.pc + 1, stk := st }
    | .ifz p t,    a :: st      =>
        some { s with pc := if p a then t else s.pc + 1, stk := st }
    | .goto t,     st           => some { s with pc := t, stk := st }
    | .ireturn,    st           => some { s with stk := st }   -- halt: pc unchanged
    | .vreturn,    st           => some { s with stk := st }
    | _, _ => none                                   -- stack underflow / type error

def run (prog : Nat → Option Instr) : Nat → State → Option State
  | 0,     s => some s
  | n + 1, s => match step prog s with
                | some s' => run prog n s'
                | none    => none

theorem run_succ (prog : Nat → Option Instr) (n : Nat) (s t : State)
    (h : step prog s = some t) : run prog (n + 1) s = run prog n t := by
  simp [run, h]

/-- Predicates used by emitted `ifcmp` / `ifz` instructions. -/
def pEq  (a b : Int) : Bool := a = b
def pNe  (a b : Int) : Bool := a ≠ b
def pLt  (a b : Int) : Bool := a < b
def pLe  (a b : Int) : Bool := a ≤ b
def pGt  (a b : Int) : Bool := a > b
def pGe  (a b : Int) : Bool := a ≥ b
def zEq  (a : Int) : Bool := a = 0
def zNe  (a : Int) : Bool := a ≠ 0
def zLt  (a : Int) : Bool := a < 0
def zLe  (a : Int) : Bool := a ≤ 0
def zGt  (a : Int) : Bool := a > 0
def zGe  (a : Int) : Bool := a ≥ 0

end Jvm
