simp only [inv_0] at hinv
obtain ⟨hoff, hlen, hfits, harr⟩ := hinv
simp only [inv_10]
simp [run, step, P, hpc, hstk, State.set] at hrun
subst hrun
refine ⟨hoff, hlen, hfits, harr, ?_, ?_, ?_, ?_, ?_⟩
· simp [State.set] <;> omega
· simp [State.set] <;> omega
· simp [State.set] <;> omega
· simp [State.set] <;> (unfold MAXI; omega)
· simp [State.set, valOf]
