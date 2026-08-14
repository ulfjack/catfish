  simp only [inv_ret0, inv_ret1, inv_ret2, inv_ret3]
  simp [run, step, P, hpc, hstk, State.set, pLt, pGt, *] at hrun
  subst hrun
  simp only [State.set, Nat.reduceEqDiff, reduceIte]
  rw [hexValF_neg (by unfold digitVal; rw [if_neg (by omega), if_neg (by omega), if_neg (by omega)])]
