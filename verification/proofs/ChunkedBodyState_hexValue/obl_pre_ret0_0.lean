  simp only [inv_ret0]
  simp [run, step, P, hpc, hstk, State.set, pLt, pGt, *] at hrun
  subst hrun
  simp only [State.set, List.cons.injEq, and_true]
  rw [wrap_id' (by omega) (by omega)]
  rw [hexValF_of (show digitVal (s.loc 0) = some (s.loc 0 - 48) by unfold digitVal; rw [if_pos ⟨by omega, by omega⟩])]
