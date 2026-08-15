  simp only [inv_pre] at hinv
  unfold digitVal at hinv
  rw [if_neg (by omega), if_neg (by omega)] at hinv
  have hr : 65 ≤ s.loc 0 ∧ s.loc 0 ≤ 70 := by
    by_cases h : 65 ≤ s.loc 0 ∧ s.loc 0 ≤ 70
    · exact h
    · rw [if_neg h] at hinv; exact absurd rfl hinv
  simp only [inv_ret2]
  simp [run, step, P, hpc, hstk, State.set, pLt, pGt, *] at hrun
  subst hrun
  simp only [State.set, List.cons.injEq, and_true]
  have h1 : wrap (s.loc 0 - 65) = s.loc 0 - 65 := by apply wrap_id' <;> omega
  rw [h1, wrap_id' (by omega) (by omega)]
  rw [hexValF_of (show digitVal (s.loc 0) = some (s.loc 0 - 55) by unfold digitVal; rw [if_neg (by omega), if_neg (by omega), if_pos ⟨by omega, by omega⟩])]
  omega
