package de.ofahrt.catfish.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

class MultiRunner {
  private final List<Runnable> tasks = new ArrayList<>();

  public void add(Runnable runnable) {
    tasks.add(runnable);
  }

  public void runAll() throws Exception {
    List<Runnable> actualTasks = new ArrayList<>(tasks);
    final int count = actualTasks.size();
    final CyclicBarrier barrier = new CyclicBarrier(count + 1);
    final AtomicInteger failCount = new AtomicInteger();
    for (int i = 0; i < actualTasks.size(); i++) {
      final Runnable runnable = actualTasks.get(i);
      new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            barrier.await();
            runnable.run();
          } catch (Throwable e) {
            e.printStackTrace();
            failCount.incrementAndGet();
          } finally {
            try {
              barrier.await();
            } catch (BrokenBarrierException e) {
              throw new RuntimeException(e);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
//              System.err.println("DONE: " + (count - done.getCount()));
          }
        }
      }).start();
    }
    barrier.await();
    barrier.await();
    int fcount = failCount.intValue();
    if (fcount != 0) {
      throw new RuntimeException(fcount + " threads failed");
    }
  }
}