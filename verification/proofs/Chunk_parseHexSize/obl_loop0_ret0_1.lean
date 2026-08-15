  simp only [inv_loop0] at hinv
  obtain ⟨hoff, hlen, hfits, hi0, hile, hacc0, haccm, haccv⟩ := hinv
  have hamax : s.arr.length ≤ MAXI := s.arr.length_le
  simp only [Jvm.Arr.length] at hfits hamax
  unfold MAXI at hamax haccm
  have hidx : wrap (s.loc 1 + s.loc 4) = s.loc 1 + s.loc 4 := by apply wrap_id' <;> omega
  have hbnd : 0 ≤ s.loc 1 + s.loc 4 ∧ s.loc 1 + s.loc 4 < (s.arr.len : Int) := by omega
  have htn : (s.loc 1 + s.loc 4).toNat = (s.loc 1).toNat + (s.loc 4).toNat := Int.toNat_add hoff hi0
  rw [hidx, htn] at c1
  have hd : digitVal (s.arr ((s.loc 1).toNat + (s.loc 4).toNat)) = none := by
    cases hh : digitVal (s.arr ((s.loc 1).toNat + (s.loc 4).toNat)) with
    | none => rfl
    | some d => rw [hexValF_of hh] at c1; exact absurd (digitVal_range hh).1 (by omega)
  have e2 : ((s.loc 2).toNat : Int) = s.loc 2 := Int.toNat_of_nonneg (by omega)
  have e4 : ((s.loc 4).toNat : Int) = s.loc 4 := Int.toNat_of_nonneg hi0
  have hbound : (s.loc 4).toNat + 1 ≤ (s.loc 2).toNat := by omega
  simp [run, step, P, hpc, hstk, State.set, hbnd, hidx, htn, hexValF_neg hd, c0, zGe, pLt] at hrun
  subst hrun
  simp only [State.set, Nat.reduceEqDiff, reduceIte, inv_ret0]
  have hstep : valOf s.arr (s.loc 1).toNat ((s.loc 4).toNat + 1) = none := by simp [valOf, haccv, hd]
  rw [parseSpec_none hbound hstep]
