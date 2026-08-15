  simp only [inv_ret0]
  simp [run, step, P, hpc, hstk, State.set, pLt, pLe, pGt, pGe, *] at hrun
  subst hrun
  simp only [State.set, List.cons.injEq, and_true, isHexDigitF_eq]
  by_cases hh : (48 ≤ s.loc 0 ∧ s.loc 0 ≤ 57) ∨ (97 ≤ s.loc 0 ∧ s.loc 0 ≤ 102) ∨ (65 ≤ s.loc 0 ∧ s.loc 0 ≤ 70)
  · rw [if_pos hh] <;> omega
  · rw [if_neg hh] <;> omega
