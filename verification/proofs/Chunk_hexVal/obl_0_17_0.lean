  simp only [inv_17, inv_36, inv_55, inv_59]
  simp [run, step, P, hpc, hstk, State.set, pLt, pGt, *] at hrun
  subst hrun
  simp only [State.set, Nat.reduceEqDiff, reduceIte]
  rw [wrap_id' (by omega) (by omega)]
  rw [hexValF_of (by unfold digitVal; rw [ if_pos (And.intro (by omega) (by omega))])]
