  simp only [inv_22, inv_46, inv_70, inv_79]
  simp [run, step, P, hpc, hstk, State.set, pLt, pGt, *] at hrun
  subst hrun
  simp only [State.set, Nat.reduceEqDiff, reduceIte]
  rw [wrap_id' (by omega) (by omega)]
  rw [hexValF_of (by unfold digitVal; rw [if_neg (by omega), if_pos (And.intro (by omega) (by omega))])]
