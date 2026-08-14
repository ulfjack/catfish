/-
  Domain specification for RFC 9112 §7.1 chunk-size parsing.  Referenced by name
  from the Verify.* strings in Chunk.java and from the hand-written proofs.

  This is NOT part of the JVM trust base: a bug here is a wrong specification of
  what a chunk size *is*, not a wrong model of the machine.  Pure arithmetic over
  `Int`/`Option`; it does not depend on the JVM semantics.
-/

namespace ChunkedEncoding

/-- RFC 9112 HEXDIG, case-insensitive, as a partial function on byte values. -/
def digitVal (c : Int) : Option Int :=
  if 48 ≤ c ∧ c ≤ 57 then some (c - 48)
  else if 97 ≤ c ∧ c ≤ 102 then some (c - 87)
  else if 65 ≤ c ∧ c ≤ 70 then some (c - 55)
  else none

theorem digitVal_range {c d : Int} (h : digitVal c = some d) : 0 ≤ d ∧ d ≤ 15 := by
  unfold digitVal at h
  repeat' split at h
  all_goals simp_all
  all_goals omega

/-- Contract for the separately verified `Chunk.hexVal`. -/
def hexValF (c : Int) : Int :=
  match digitVal c with | some d => d | none => -1

theorem hexValF_of {c d : Int} (h : digitVal c = some d) : hexValF c = d := by
  unfold hexValF; rw [h]

theorem hexValF_neg {c : Int} (h : digitVal c = none) : hexValF c = -1 := by
  unfold hexValF; rw [h]

/-- Value of the hex digit string `arr[off .. off+n)`, `none` if any byte is not HEXDIG. -/
def valOf (arr : Nat → Int) (off : Nat) : Nat → Option Int
  | 0     => some 0
  | n + 1 => match valOf arr off n, digitVal (arr (off + n)) with
             | some v, some d => some (v * 16 + d)
             | _, _ => none

end ChunkedEncoding
