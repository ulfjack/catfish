/-
  Domain specification for RFC 9112 §7.1 chunked transfer-coding: the HEXDIG
  primitives (`digitVal`, `hexValF`, `isHexDigitF`) named by the @Returns /
  @Precondition strings on `ChunkedBodyState`, and the framing state machine
  (`St`, `chunkStep`, `decode`).

  This is NOT part of the JVM trust base: a bug here is a wrong specification of
  what chunked encoding *is*, not a wrong model of the machine.  It also does not
  depend on the JVM semantics -- the size cap is a `maxLen` parameter, so the
  caller (the obligation, or `ChunkedRefinement`) supplies `Integer.MAX_VALUE`.
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

/-!
  ## The chunked framing as a state machine

  A reference decoder for RFC 9112 §7.1 chunked transfer-coding, one byte at a
  time.  Chunk-ext and the trailer section are elided for now.

  Per the RFC, `chunk-size = 1*HEXDIG` -- one or more hex digits, no upper bound
  on the digit count -- so there is no digit cap here; `sizeStart` vs `size`
  enforces the `1*` without a boolean.  The `value ≤ maxLen` rejection is the
  recipient's policy (a chunk larger than it can address), not RFC grammar, so it
  is a parameter; Catfish supplies `Integer.MAX_VALUE`.
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

/-- One input byte drives one transition.  `maxLen` is the recipient size cap;
    it is checked after every digit, mirroring Catfish's per-digit guard. -/
def chunkStep (maxLen : Int) : St → Int → St
  | .sizeStart, b =>
      match digitVal b with
      | some d => if (d.toNat : Int) ≤ maxLen then .size d.toNat else .error
      | none => .error                                    -- 1*HEXDIG: need a leading digit
  | .size v, b =>
      match digitVal b with
      | some d =>
          let v' := v * 16 + d.toNat
          if (v' : Int) ≤ maxLen then .size v' else .error -- reject > recipient cap
      | none => if b = CR then .sizeLF v else .error       -- (chunk-ext elided)
  | .sizeLF v, b => if b = LF then (if v = 0 then .done else .data v) else .error
  | .data left, _ => if left ≤ 1 then .dataCR else .data (left - 1)
  | .dataCR, b => if b = CR then .dataLF else .error
  | .dataLF, b => if b = LF then .sizeStart else .error
  | .done, _ => .done                                      -- absorbing
  | .error, _ => .error                                    -- absorbing

/-- Fold the machine over the whole input. -/
def decode (maxLen : Int) (bs : List Int) : St := bs.foldl (chunkStep maxLen) .sizeStart

/-- The framing is well formed iff decoding completes. -/
def accepts (maxLen : Int) (bs : List Int) : Prop := decode maxLen bs = .done

theorem chunkStep_error (maxLen : Int) (b : Int) : chunkStep maxLen .error b = .error := rfl
theorem chunkStep_done (maxLen : Int) (b : Int) : chunkStep maxLen .done b = .done := rfl

-- "1" CRLF "A" CRLF "0" CRLF : a one-byte chunk then the last-chunk.
example : decode 255 [49, 13, 10, 65, 13, 10, 48, 13, 10] = St.done := by decide
-- a bare LF in the size line is rejected (line endings must be exactly CRLF)
example : decode 255 [49, 10] = St.error := by decide
-- an empty size field (CR with no digit) is rejected (1*HEXDIG)
example : decode 255 [13] = St.error := by decide

end ChunkedEncoding
