  simp only [inv_ret1]
  simp [run, step, P, hpc, hstk, State.set, pLt, pGt, *] at hrun
  subst hrun
  simp only [State.set, List.cons.injEq, and_true]
  have h1 : wrap (s.loc 0 - 97) = s.loc 0 - 97 := by apply wrap_id' <;> omega
  rw [h1, wrap_id' (by omega) (by omega)]
  rw [hexValF_of (show digitVal (s.loc 0) = some (s.loc 0 - 87) by unfold digitVal; rw [if_neg (by omega), if_pos ⟨by omega, by omega⟩])]
  omega
