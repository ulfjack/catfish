import ChunkedEncoding

/-
  Refinement: the real `ChunkedBodyState` control flow refines the `St` state
  machine, one input byte at a time.

  `advance(buf, off, len)` is `foldl advanceStep` over the input slice starting
  from the current state, so proving the class refines the reference decoder
  reduces to a one-byte simulation `α (advanceStep cs b) = chunkStep (α cs) b`.
  `advanceStep` transcribes `advance`'s core control flow; the abstraction `α`
  maps the class fields (enum state + currentChunkSize/chunkSizeDigits/
  chunkDataLeft) onto `St`.  `maxLen` is the recipient size cap, threaded through
  both sides (Catfish's `Integer.MAX_VALUE`).

  This is NOT the JVM-bytecode proof of `advance` (that needs object fields,
  `long`, `switch`, and the `Sink` in the semantics).  It verifies the *design*:
  the field layout and per-byte transitions implement the spec.  Three things
  `advance` does are elided here, matching the `St` spec: chunk-ext (`SIZE_EXT`),
  the trailer section (`TRAILER*`, so a zero-size chunk goes straight to `done`),
  and the 15-digit cap (`St` follows RFC `1*HEXDIG` with no digit cap).  The DATA
  phase is modelled one byte at a time; `advance` bulk-forwards the same span.
-/

namespace ChunkedEncoding

inductive CState | SIZE | SIZE_CR | DATA | DATA_CR | DATA_LF | DONE | ERROR
  deriving DecidableEq

structure ClassState where
  state : CState
  size : Nat
  digits : Nat
  dataLeft : Nat

def α (cs : ClassState) : St :=
  match cs.state with
  | .SIZE => if cs.digits = 0 then .sizeStart else .size cs.size
  | .SIZE_CR => .sizeLF cs.size
  | .DATA => .data cs.dataLeft
  | .DATA_CR => .dataCR
  | .DATA_LF => .dataLF
  | .DONE => .done
  | .ERROR => .error

def advanceStep (maxLen : Int) (cs : ClassState) (b : Int) : ClassState :=
  match cs.state with
  | .SIZE =>
      match digitVal b with
      | some d =>
          let sz := cs.size * 16 + d.toNat
          if (sz : Int) ≤ maxLen then { cs with size := sz, digits := cs.digits + 1 }
          else { cs with state := .ERROR }
      | none =>
          if b = CR then
            (if cs.digits = 0 then { cs with state := .ERROR } else { cs with state := .SIZE_CR })
          else { cs with state := .ERROR }
  | .SIZE_CR =>
      if b = LF then
        (if cs.size = 0 then { state := .DONE, size := 0, digits := 0, dataLeft := 0 }
         else { state := .DATA, size := 0, digits := 0, dataLeft := cs.size })
      else { cs with state := .ERROR }
  | .DATA =>
      if cs.dataLeft ≤ 1 then { cs with state := .DATA_CR, dataLeft := 0 }
      else { cs with dataLeft := cs.dataLeft - 1 }
  | .DATA_CR => if b = CR then { cs with state := .DATA_LF } else { cs with state := .ERROR }
  | .DATA_LF => if b = LF then { state := .SIZE, size := 0, digits := 0, dataLeft := 0 } else { cs with state := .ERROR }
  | .DONE => cs
  | .ERROR => cs

/-- Field consistency: before any digit the size is 0. -/
def Wf (cs : ClassState) : Prop := cs.state = .SIZE → cs.digits = 0 → cs.size = 0

theorem advanceStep_wf (maxLen : Int) (cs : ClassState) (b : Int) (h : Wf cs) :
    Wf (advanceStep maxLen cs b) := by
  obtain ⟨st, sz, dg, dl⟩ := cs
  cases st <;>
    simp only [Wf, advanceStep] at * <;>
    (repeat' split) <;>
    simp_all

set_option maxHeartbeats 1000000 in
theorem advanceStep_refines (maxLen : Int) (cs : ClassState) (b : Int) (h : Wf cs) :
    α (advanceStep maxLen cs b) = chunkStep maxLen (α cs) b := by
  obtain ⟨st, sz, dg, dl⟩ := cs
  simp only [Wf] at h
  cases st
  case SIZE =>
    by_cases hdg : dg = 0
    · subst hdg
      have hsz : sz = 0 := h rfl rfl
      subst hsz
      rw [show α ⟨CState.SIZE, 0, 0, dl⟩ = St.sizeStart by simp [α]]
      cases hd : digitVal b with
      | some d =>
        simp only [advanceStep, chunkStep, hd, Nat.zero_mul, Nat.zero_add]
        split <;> simp [α]
      | none =>
        have hadv : advanceStep maxLen ⟨CState.SIZE, 0, 0, dl⟩ b = ⟨CState.ERROR, 0, 0, dl⟩ := by
          simp only [advanceStep, hd]; by_cases hb : b = CR <;> simp [hb]
        rw [hadv]; simp [α, chunkStep, hd]
    · rw [show α ⟨CState.SIZE, sz, dg, dl⟩ = St.size sz by simp [α, hdg]]
      cases hd : digitVal b with
      | some d =>
        simp only [advanceStep, chunkStep, hd]
        split <;> simp [α, hdg]
      | none =>
        by_cases hb : b = CR
        · have hadv : advanceStep maxLen ⟨CState.SIZE, sz, dg, dl⟩ b = ⟨CState.SIZE_CR, sz, dg, dl⟩ := by
            simp only [advanceStep, hd]; simp [hb, hdg]
          rw [hadv, show α ⟨CState.SIZE_CR, sz, dg, dl⟩ = St.sizeLF sz by simp [α]]
          simp only [chunkStep, hd]; simp [hb]
        · have hadv : advanceStep maxLen ⟨CState.SIZE, sz, dg, dl⟩ b = ⟨CState.ERROR, sz, dg, dl⟩ := by
            simp only [advanceStep, hd]; simp [hb]
          rw [hadv, show α ⟨CState.ERROR, sz, dg, dl⟩ = St.error by simp [α]]
          simp only [chunkStep, hd]; simp [hb]
  case SIZE_CR =>
    rw [show α ⟨CState.SIZE_CR, sz, dg, dl⟩ = St.sizeLF sz by simp [α]]
    by_cases hb : b = LF
    · by_cases hz : sz = 0 <;> simp [advanceStep, chunkStep, α, hb, hz]
    · simp [advanceStep, chunkStep, α, hb]
  case DATA =>
    rw [show α ⟨CState.DATA, sz, dg, dl⟩ = St.data dl by simp [α]]
    by_cases hl : dl ≤ 1 <;> simp [advanceStep, chunkStep, α, hl]
  case DATA_CR =>
    rw [show α ⟨CState.DATA_CR, sz, dg, dl⟩ = St.dataCR by simp [α]]
    by_cases hb : b = CR <;> simp [advanceStep, chunkStep, α, hb]
  case DATA_LF =>
    rw [show α ⟨CState.DATA_LF, sz, dg, dl⟩ = St.dataLF by simp [α]]
    by_cases hb : b = LF <;> simp [advanceStep, chunkStep, α, hb]
  case DONE => simp [advanceStep, chunkStep, α]
  case ERROR => simp [advanceStep, chunkStep, α]

/-- The class's incremental `advance` over a buffer is a fold of the one-byte
    step -- so `advance` refines a fold of `chunkStep`, one abstraction step per
    input byte, provided the starting fields are well formed. -/
theorem foldl_advanceStep_refines (maxLen : Int) (cs : ClassState) (h : Wf cs) (bs : List Int) :
    α (bs.foldl (advanceStep maxLen) cs) = bs.foldl (chunkStep maxLen) (α cs) := by
  induction bs generalizing cs with
  | nil => rfl
  | cons b bs ih =>
    simp only [List.foldl_cons]
    rw [ih (advanceStep maxLen cs b) (advanceStep_wf maxLen cs b h),
        advanceStep_refines maxLen cs b h]

/-- The initial class state (all zero, `SIZE`). -/
def initial : ClassState := ⟨.SIZE, 0, 0, 0⟩

theorem initial_wf : Wf initial := fun _ _ => rfl

/-- End to end: driving `advanceStep` from the initial state over the whole input
    computes exactly the reference decoder `decode`. -/
theorem advance_refines_decode (maxLen : Int) (bs : List Int) :
    α (bs.foldl (advanceStep maxLen) initial) = decode maxLen bs := by
  rw [foldl_advanceStep_refines maxLen initial initial_wf bs]; rfl

-- "1" CRLF "A" CRLF "0" CRLF, driven through the class step from the initial
-- state, reaches `done` -- the same acceptance the reference `decode` gives.
example : α ([49, 13, 10, 65, 13, 10, 48, 13, 10].foldl (advanceStep 255) initial) = St.done := by
  rw [advance_refines_decode]; decide

end ChunkedEncoding
