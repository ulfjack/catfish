import Jvm.Semantics

/-
  Domain specification for RFC 9112 §7.1 chunk-size parsing.  Referenced by name
  from the Verify.* / @Returns strings in Chunk.java and from the hand-written
  proofs.

  This is NOT part of the JVM trust base: a bug here is a wrong specification of
  what a chunk size *is*, not a wrong model of the machine.
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

/-- HEXDIG membership as the 0/1 an `isHexDigit`-style boolean method returns. -/
def isHexDigitF (c : Int) : Int := if digitVal c = none then 0 else 1

/-- `isHexDigitF` in range-union form: the shape the branch conditions match. -/
theorem isHexDigitF_eq (c : Int) :
    isHexDigitF c =
      if (48 ≤ c ∧ c ≤ 57) ∨ (97 ≤ c ∧ c ≤ 102) ∨ (65 ≤ c ∧ c ≤ 70) then 1 else 0 := by
  unfold isHexDigitF digitVal
  repeat' split
  all_goals simp_all
  all_goals omega

/-- Contract for the separately verified `Chunk.hexVal`. -/
def hexValF (c : Int) : Int :=
  match digitVal c with | some d => d | none => -1

theorem hexValF_of {c d : Int} (h : digitVal c = some d) : hexValF c = d := by
  unfold hexValF; rw [h]

theorem hexValF_neg {c : Int} (h : digitVal c = none) : hexValF c = -1 := by
  unfold hexValF; rw [h]

/-- Value of the hex digit string `b[off .. off+n)`, `none` if any byte is not HEXDIG. -/
def valOf (b : Jvm.Arr) (off : Nat) : Nat → Option Int
  | 0     => some 0
  | n + 1 => match valOf b off n, digitVal (b (off + n)) with
             | some v, some d => some (v * 16 + d)
             | _, _ => none

/-- A parsed hex value is non-negative. -/
theorem valOf_nonneg {b : Jvm.Arr} {off : Nat} :
    ∀ {n : Nat} {v : Int}, valOf b off n = some v → 0 ≤ v := by
  intro n
  induction n with
  | zero => intro v h; simp only [valOf, Option.some.injEq] at h; omega
  | succ k ih =>
    intro v h
    simp only [valOf] at h
    cases hw : valOf b off k with
    | none => rw [hw] at h; simp at h
    | some w =>
      cases hd : digitVal (b (off + k)) with
      | none => rw [hw, hd] at h; simp at h
      | some d =>
        rw [hw, hd] at h
        simp only [Option.some.injEq] at h
        have := ih hw
        have := (digitVal_range hd).1
        omega

/-- Once `valOf` is `none` at one length it is `none` at every greater length. -/
theorem valOf_none_le {b : Jvm.Arr} {off n : Nat}
    (h : valOf b off n = none) : ∀ {m : Nat}, n ≤ m → valOf b off m = none := by
  intro m hnm
  induction hnm with
  | refl => exact h
  | step _ ih => simp [valOf, ih]

/-- Once the value reaches `w`, every longer prefix is `none` or at least `w`. -/
theorem valOf_ge {b : Jvm.Arr} {off k : Nat} {w : Int} (hk : valOf b off k = some w) :
    ∀ {m : Nat}, k ≤ m → valOf b off m = none ∨ ∃ u, valOf b off m = some u ∧ w ≤ u := by
  intro m hkm
  induction hkm with
  | refl => exact Or.inr ⟨w, hk, by omega⟩
  | @step j _ ih =>
    rcases ih with hnone | ⟨u, hu, hwu⟩
    · exact Or.inl (by simp [valOf, hnone])
    · cases hd : digitVal (b (off + j)) with
      | none => exact Or.inl (by simp [valOf, hu, hd])
      | some d =>
        refine Or.inr ⟨u * 16 + d, by simp [valOf, hu, hd], ?_⟩
        have := (digitVal_range hd).1
        have := valOf_nonneg hu
        omega

/--
  The parse result: the hex value of the whole field when it is a non-empty run of
  HEXDIG that fits in a 32-bit int, otherwise -1 (empty, non-hex byte, or overflow).
  RFC 9112 requires 1*HEXDIG, so the empty field is rejected.
-/
def parseSpec (b : Jvm.Arr) (off n : Nat) : Int :=
  if n = 0 then -1
  else match valOf b off n with
       | some v => if v ≤ Jvm.MAXI then v else -1
       | none   => -1

/-- A non-hex byte anywhere in `[k, m)` (via `valOf … k = none`) forces -1. -/
theorem parseSpec_none {b : Jvm.Arr} {off k m : Nat}
    (hkm : k ≤ m) (hk : valOf b off k = none) : parseSpec b off m = -1 := by
  have hm : valOf b off m = none := valOf_none_le hk hkm
  unfold parseSpec
  simp only [hm]
  split <;> rfl

/-- A prefix value exceeding MAXI forces -1. -/
theorem parseSpec_overflow {b : Jvm.Arr} {off k m : Nat} {w : Int}
    (hkm : k ≤ m) (hk : valOf b off k = some w) (hover : Jvm.MAXI < w) :
    parseSpec b off m = -1 := by
  have hk0 : k ≠ 0 := by
    rintro rfl
    simp only [valOf, Option.some.injEq] at hk
    unfold Jvm.MAXI at hover; omega
  have hm0 : m ≠ 0 := by omega
  unfold parseSpec
  rw [if_neg hm0]
  rcases valOf_ge hk hkm with hnone | ⟨u, hu, hwu⟩
  · simp only [hnone]
  · simp only [hu]; rw [if_neg (by omega : ¬ u ≤ Jvm.MAXI)]

/-!
  ## The chunked framing as a state machine

  A reference decoder for RFC 9112 §7.1 chunked transfer-coding, one byte at a
  time.  Chunk-ext and the trailer section are elided for now.

  Per the RFC, `chunk-size = 1*HEXDIG` -- one or more hex digits, no upper bound
  on the digit count -- so there is no digit cap here; `sizeStart` vs `size`
  enforces the `1*` without a boolean.  The `value ≤ MAXI` rejection is Catfish's
  recipient policy (a chunk larger than an int can address), not RFC grammar.
-/

def CR : Int := 13
def LF : Int := 10

/-- Decoder state.  The counter lives in the constructors that use it: the
    chunk-size value being parsed, or the chunk-data bytes still to consume.
    "Decoded byte count" is not stored -- it is the number of bytes consumed
    while in `data`, recoverable from the run. -/
inductive St where
  | sizeStart          -- chunk-size field, before the first HEXDIG
  | size (v : Nat)     -- chunk-size, ≥1 HEXDIG, value so far
  | sizeLF (v : Nat)   -- consumed the size's CR, expecting LF (v = the size)
  | data (left : Nat)  -- consuming chunk-data, `left` bytes remain
  | dataCR             -- consumed all chunk-data, expecting CR
  | dataLF             -- consumed the data's CR, expecting LF
  | done               -- last-chunk (size 0) consumed
  | error
  deriving Repr, DecidableEq

/-- One input byte drives one transition. -/
def chunkStep : St → Int → St
  | .sizeStart, b =>
      match digitVal b with
      | some d => .size d.toNat
      | none => .error                                    -- 1*HEXDIG: need a leading digit
  | .size v, b =>
      match digitVal b with
      | some d =>
          let v' := v * 16 + d.toNat
          if (v' : Int) ≤ Jvm.MAXI then .size v' else .error -- reject > Integer.MAX_VALUE
      | none => if b = CR then .sizeLF v else .error       -- (chunk-ext elided)
  | .sizeLF v, b => if b = LF then (if v = 0 then .done else .data v) else .error
  | .data left, _ => if left ≤ 1 then .dataCR else .data (left - 1)
  | .dataCR, b => if b = CR then .dataLF else .error
  | .dataLF, b => if b = LF then .sizeStart else .error
  | .done, _ => .done                                      -- absorbing
  | .error, _ => .error                                    -- absorbing

/-- Fold the machine over the whole input. -/
def decode (bs : List Int) : St := bs.foldl chunkStep .sizeStart

/-- The framing is well formed iff decoding completes. -/
def accepts (bs : List Int) : Prop := decode bs = .done

theorem chunkStep_error (b : Int) : chunkStep .error b = .error := rfl
theorem chunkStep_done (b : Int) : chunkStep .done b = .done := rfl

-- "1" CRLF "A" CRLF "0" CRLF : a one-byte chunk then the last-chunk.
example : decode [49, 13, 10, 65, 13, 10, 48, 13, 10] = St.done := by decide
-- a bare LF in the size line is rejected (line endings must be exactly CRLF)
example : decode [49, 10] = St.error := by decide
-- an empty size field (CR with no digit) is rejected (1*HEXDIG)
example : decode [13] = St.error := by decide

end ChunkedEncoding
