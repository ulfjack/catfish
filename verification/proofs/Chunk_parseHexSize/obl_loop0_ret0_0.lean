  simp only [inv_loop0] at hinv
  obtain ⟨hoff, hlen, hfits, hamax, hi0, hile, hacc0, haccm, haccv⟩ := hinv
  unfold MAXI at hamax haccm
  have hidx : wrap (s.loc 1 + s.loc 4) = s.loc 1 + s.loc 4 := by apply wrap_id' <;> omega
  have hbnd : 0 ≤ s.loc 1 + s.loc 4 ∧ s.loc 1 + s.loc 4 < (s.alen : Int) := by omega
  have htn : (s.loc 1 + s.loc 4).toNat = (s.loc 1).toNat + (s.loc 4).toNat := Int.toNat_add hoff hi0
  have e2 : ((s.loc 2).toNat : Int) = s.loc 2 := Int.toNat_of_nonneg (by omega)
  have e4 : ((s.loc 4).toNat : Int) = s.loc 4 := Int.toNat_of_nonneg hi0
  have hbound : (s.loc 4).toNat + 1 ≤ (s.loc 2).toNat := by omega
  rw [hidx, htn] at c1 c2
  cases hd : digitVal (s.arr ((s.loc 1).toNat + (s.loc 4).toNat)) with
  | none => rw [hexValF_neg hd] at c1; exact absurd c1 (by omega)
  | some d =>
    obtain ⟨hd0, hd15⟩ := digitVal_range hd
    rw [hexValF_of hd] at c1 c2
    have hsub : wrap (2147483647 - d) = 2147483647 - d := by apply wrap_id' <;> omega
    have hdv16 : wrap (Int.div (2147483647 - d) 16) = Int.div (2147483647 - d) 16 := by
      rw [Int.div_eq_ediv (by omega) (by omega)]; apply wrap_id' <;> omega
    rw [hsub, hdv16] at c2
    simp [run, step, P, hpc, hstk, State.set, hbnd, hidx, htn, hexValF_of hd, hsub, hdv16, hd0, c0, c2, zGe, pLt, pLe] at hrun
    subst hrun
    simp only [State.set, Nat.reduceEqDiff, reduceIte, inv_ret0]
    have hstep : valOf s.arr (s.loc 1).toNat ((s.loc 4).toNat + 1) = some (s.loc 3 * 16 + d) := by simp [valOf, haccv, hd]
    have hover : MAXI < s.loc 3 * 16 + d := by
      rw [Int.div_eq_ediv (by omega) (by omega)] at c2; unfold MAXI; omega
    rw [parseSpec_overflow hbound hstep hover]
