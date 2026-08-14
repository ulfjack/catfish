  simp only [inv_loop0] at hinv
  obtain ⟨hoff, hlen, hfits, hamax, hi0, hile, hacc0, haccm, haccv⟩ := hinv
  simp only [inv_ret0]
  simp [run, step, P, hpc, hstk, State.set, pLt, pGe, pLe, zNe, zGe, c0, c1, *] at hrun
  subst hrun
  simp only [State.set, Nat.reduceEqDiff, reduceIte]
  have hlen0 : s.loc 2 = 0 := by omega
  rw [hlen0]; simp [parseSpec]
