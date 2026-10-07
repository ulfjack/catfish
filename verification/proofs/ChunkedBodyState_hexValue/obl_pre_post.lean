-- Merged obligation: one proof for all 7 return paths. From the precondition
-- (digitVal c ≠ none) derive that c is in one of the three HEXDIG ranges, then
-- fix the four phi guards with by_cases so `run` reduces with no internal fork;
-- `wrap` is identity on the small results (omega discharges the emod).
simp only [inv_pre] at hinv
have hr : (48 ≤ s.loc 0 ∧ s.loc 0 ≤ 57) ∨ (97 ≤ s.loc 0 ∧ s.loc 0 ≤ 102)
        ∨ (65 ≤ s.loc 0 ∧ s.loc 0 ≤ 70) := by
  unfold digitVal at hinv
  by_cases ha : 48 ≤ s.loc 0 ∧ s.loc 0 ≤ 57
  · exact Or.inl ha
  · by_cases hb : 97 ≤ s.loc 0 ∧ s.loc 0 ≤ 102
    · exact Or.inr (Or.inl hb)
    · by_cases hc : 65 ≤ s.loc 0 ∧ s.loc 0 ≤ 70
      · exact Or.inr (Or.inr hc)
      · rw [if_neg ha, if_neg hb, if_neg hc] at hinv; exact absurd rfl hinv
simp only [inv_post]
by_cases h0 : s.loc 0 < 48 <;> by_cases h1 : s.loc 0 > 57 <;>
by_cases h2 : s.loc 0 < 97 <;> by_cases h3 : s.loc 0 > 102 <;>
  simp only [run, step, P, State.set, pLt, pGt, hpc, hstk,
    decide_eq_true_eq, decide_eq_false_iff_not, Nat.reduceAdd, Option.some.injEq, *] at hrun <;>
  subst hrun <;>
  simp only [List.cons.injEq, and_true, hexValF_eq, wrap] <;>
  (repeat' split) <;> omega
