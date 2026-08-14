simp only [inv_5] at hinv
obtain ⟨hoff, hlen, hfits, harr, hi0, hile, hacc0, haccm, haccv⟩ := hinv
unfold MAXI at harr haccm
have hidx : wrap (s.loc 1 + s.loc 4) = s.loc 1 + s.loc 4 := by apply wrap_id' <;> omega
rw [hidx] at c1 c2
have hbnd : 0 ≤ s.loc 1 + s.loc 4 ∧ s.loc 1 + s.loc 4 < (s.alen : Int) := by omega
simp only [inv_61]
simp [run, step, P, hpc, hstk, State.set, hidx, hbnd, c0, c1, c2, pLt, pLe, zGe] at hrun
subst hrun
simp [State.set]
