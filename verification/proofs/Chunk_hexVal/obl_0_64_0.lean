  simp only [inv_22, inv_41, inv_60, inv_64]
  simp [run, step, P, hpc, hstk, State.set, pLt, pGt, *] at hrun
  subst hrun
  simp only [State.set, Nat.reduceEqDiff, reduceIte]
  rw [hexValF_neg (by unfold digitVal; rw [if_neg (by omega), if_neg (by omega), if_neg (by omega)])]
