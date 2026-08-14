simp only [inv_loop0] at hinv
obtain ⟨hoff, hlen, hfits, harr, hi0, hile, hacc0, haccm, haccv⟩ := hinv
unfold MAXI at harr haccm
have hidx : wrap (s.loc 1 + s.loc 4) = s.loc 1 + s.loc 4 := by
  apply wrap_id' <;> omega
have htn : (s.loc 1 + s.loc 4).toNat = (s.loc 1).toNat + (s.loc 4).toNat :=
  Int.toNat_add hoff hi0
rw [hidx, htn] at c1 c2
cases hdv : digitVal (s.arr ((s.loc 1).toNat + (s.loc 4).toNat)) with
| none => rw [hexValF_neg hdv] at c1; omega
| some d =>
  obtain ⟨hd0, hd15⟩ := digitVal_range hdv
  rw [hexValF_of hdv] at c1 c2
  have hsub : wrap (2147483647 - d) = 2147483647 - d := by
    apply wrap_id' <;> omega
  rw [hsub] at c2
  have hdv16 : wrap (Int.div (2147483647 - d) 16) = Int.div (2147483647 - d) 16 := by
    rw [Int.div_eq_ediv (by omega) (by omega)]
    apply wrap_id' <;> omega
  rw [hdv16] at c2
  -- the overflow guard is exact: acc ≤ (MAX-d)/16  ↔  acc*16+d ≤ MAX
  have c2e : s.loc 3 ≤ (2147483647 - d) / 16 := by
    rwa [Int.div_eq_ediv (by omega) (by omega)] at c2
  have hnoovf : s.loc 3 * 16 + d ≤ 2147483647 := by omega
  have hmul : wrap (s.loc 3 * 16) = s.loc 3 * 16 := by
    apply wrap_id' <;> omega
  have hadd : wrap (s.loc 3 * 16 + d) = s.loc 3 * 16 + d := by
    apply wrap_id' <;> omega
  have hinc : wrap (s.loc 4 + 1) = s.loc 4 + 1 := by
    apply wrap_id' <;> omega
  have hbnd : 0 ≤ s.loc 1 + s.loc 4 ∧ s.loc 1 + s.loc 4 < (s.alen : Int) := by omega
  have htn1 : (s.loc 4 + 1).toNat = (s.loc 4).toNat + 1 := by
    rw [Int.toNat_add hi0 (by omega)]; rfl
  simp only [inv_loop0]
  simp [run, step, P, hpc, hstk, State.set, hidx, htn, hexValF_of hdv, hsub, hdv16,
        hmul, hadd, hinc, hbnd, c0, hd0, c2, pLt, pLe, zGe] at hrun
  subst hrun
  refine ⟨hoff, hlen, hfits, harr, ?_, ?_, ?_, ?_, ?_⟩
  · simp only [State.set]; omega
  · simp only [State.set]; omega
  · simp only [State.set]; omega
  · simp only [State.set]; unfold MAXI; omega
  · simp [State.set, htn1, valOf, haccv, hdv]
