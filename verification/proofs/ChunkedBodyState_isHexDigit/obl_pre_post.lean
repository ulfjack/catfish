-- Merged obligation: one proof for all 15 return paths. Fix the six phi guards
-- with by_cases so `run` reduces with no internal fork, then relate the computed
-- 0/1 to isHexDigitF via its range-union form.
simp only [inv_post]
by_cases h0 : s.loc 0 < 48 <;> by_cases h1 : s.loc 0 ≤ 57 <;>
by_cases h2 : s.loc 0 < 97 <;> by_cases h3 : s.loc 0 ≤ 102 <;>
by_cases h4 : s.loc 0 < 65 <;> by_cases h5 : s.loc 0 > 70 <;>
  simp only [run, step, P, State.set, pLt, pLe, pGt, pGe, hpc, hstk,
    decide_eq_true_eq, decide_eq_false_iff_not, Nat.reduceAdd, Option.some.injEq, *] at hrun <;>
  subst hrun <;>
  simp only [List.cons.injEq, and_true, isHexDigitF_eq] <;>
  split <;> omega
