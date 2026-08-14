simp only [inv_5] at hinv
obtain ⟨hoff, hlen, hfits, harr, hi0, hile, hacc0, haccm, haccv⟩ := hinv
have hieq : s.loc 2 = s.loc 4 := by omega
simp only [inv_89]
simp [run, step, P, hpc, hstk, State.set, c0, pLt] at hrun
subst hrun
simp only [State.set, hieq]
simpa using haccv
