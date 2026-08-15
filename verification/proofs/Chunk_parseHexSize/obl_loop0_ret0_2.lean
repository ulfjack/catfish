  simp only [inv_loop0] at hinv
  obtain ⟨hoff, hlen, hfits, hi0, hile, hacc0, haccm, haccv⟩ := hinv
  unfold MAXI at haccm
  have hieq : s.loc 4 = s.loc 2 := by omega
  have e2 : ((s.loc 2).toNat : Int) = s.loc 2 := Int.toNat_of_nonneg (by omega)
  have hln : (s.loc 2).toNat ≠ 0 := by omega
  rw [hieq] at haccv
  simp [run, step, P, hpc, hstk, State.set, pLt, pGe, pLe, zNe, zGe, c0, c1] at hrun
  subst hrun
  simp only [State.set, Nat.reduceEqDiff, reduceIte, inv_ret0]
  simp only [parseSpec, hln, if_false, haccv]
  rw [if_pos (show s.loc 3 ≤ Jvm.MAXI by unfold Jvm.MAXI; omega)]
