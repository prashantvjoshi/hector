package me.prettyprint.cassandra.locking;

import static me.prettyprint.hector.api.factory.HFactory.getOrCreateCluster;
import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import me.prettyprint.cassandra.BaseEmbededServerSetupTest;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.KeyspaceDefinition;
import me.prettyprint.hector.api.locking.HLock;
import me.prettyprint.hector.api.locking.HLockManager;
import me.prettyprint.hector.api.locking.HLockManagerConfigurator;
import me.prettyprint.hector.api.locking.HLockTimeoutException;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HLockManagerImplTest extends BaseEmbededServerSetupTest {

  private static final Logger logger = LoggerFactory.getLogger(HLockManagerImplTest.class);

  Cluster cluster;
  HLockManager lm;
  HLockManagerConfigurator hlc;

  @Before
  public void setupTest() {
    cluster = getOrCreateCluster("MyCluster", getCHCForTest());
    hlc = new HLockManagerConfigurator();
    hlc.setReplicationFactor(1);
    lm = new HLockManagerImpl(cluster, hlc);
    lm.init();
  }

  @Test
  public void testInitWithDefaults() {

    KeyspaceDefinition keyspaceDef = cluster.describeKeyspace(lm.getKeyspace().getKeyspaceName());
    assertNotNull(keyspaceDef);
    assertTrue(verifyCFCreation(keyspaceDef.getCfDefs()));
  }

  @Test
  public void testHeartbeatNoExpiration() throws InterruptedException {
    HLock lock = lm.createLock("/testHeartbeatNoExpiration");
    lm.acquire(lock);

    assertTrue(lock.isAcquired());

    // Force timeout if heartbeat isn't working
    Thread.sleep(hlc.getLocksTTLInMillis() + 2000);

    HLock newLock = lm.createLock("/testHeartbeatNoExpiration");

    boolean lockTimedOut = false;

    try {
      lm.acquire(newLock, 0);
    } catch (HLockTimeoutException te) {
      lockTimedOut = true;
    }

    assertTrue(lock.isAcquired());
    assertFalse(newLock.isAcquired());
    assertTrue(lockTimedOut);

    lm.release(lock);

  }

  @Test
  public void testHeartbeatFailure() throws InterruptedException {

    HLockManagerImpl failedLockManager = new HLockManagerImpl(cluster, hlc);
    failedLockManager.init();

    HLock lock = failedLockManager.createLock("/testHeartbeatFailure");
    failedLockManager.acquire(lock);

    assertTrue(lock.isAcquired());

    failedLockManager.shutdownScheduler();

    // Force timeout since heartbeat isn't working
    Thread.sleep(hlc.getLocksTTLInMillis() + 2000);

    // use a different lock manager to try and get the lock, should succeed

    HLock newLock = lm.createLock("/testHeartbeatFailure");

    boolean lockTimedOut = false;

    try {
      lm.acquire(newLock, 0);
    } catch (HLockTimeoutException te) {
      lockTimedOut = true;
    }

    assertFalse(lockTimedOut);
    assertTrue(newLock.isAcquired());

    lm.release(newLock);

  }

  @Test
  public void testNonConcurrentLockUnlock() {
    HLock lock = lm.createLock("/testNonConcurrentLockUnlock");
    lm.acquire(lock);

    assertTrue(lock.isAcquired());

    //should time out.  Part of timing out is cleanup, we want to be sure we can immediately acquire a lock on the same path after timing out
    try {
      HLock lock2 = lm.createLock("/testNonConcurrentLockUnlock");
      lm.acquire(lock2, 1000);
      fail();
    } catch (HLockTimeoutException e) {
      // ok, this should happen
    }

    lm.release(lock);

    assertFalse(lock.isAcquired());

    // test we can re-acquire it
    HLock nextLock = lm.createLock("/testNonConcurrentLockUnlock");
    lm.acquire(nextLock, 0);
    assertTrue(nextLock.isAcquired());

    lm.release(nextLock);
  }

  @Test
  public void testNoConflict() throws InterruptedException {
    LockWorkerPool pool = new LockWorkerPool(100, "/testNoConflict", lm);
    pool.go();

    assertFalse(pool.isFailed());

  }

  private boolean verifyCFCreation(List<ColumnFamilyDefinition> cfDefs) {
    for (ColumnFamilyDefinition cfDef : cfDefs) {
      if (cfDef.getName().equals(HLockManagerConfigurator.DEFAUT_LOCK_MANAGER_CF))
        return true;
    }
    return false;
  }

  private static class LockWorkerPool {
    private int numberLocks;
    private String path;
    private HLockManager lm;

    private ExecutorService executor;
    private CountDownLatch startLatch;
    private CountDownLatch finishLatch;
    private Semaphore failSemaphore = new Semaphore(1);
    private boolean failed;

    private LockWorkerPool(int numberLocks, String path, HLockManager lm) {
      this.numberLocks = numberLocks;
      this.path = path;
      this.lm = lm;
      this.executor = Executors.newFixedThreadPool(8);
      startLatch = new CountDownLatch(1);
      finishLatch = new CountDownLatch(numberLocks);
      failed = false;
    }

    private void go() throws InterruptedException {

      // fire up the executors so they're running and blocking
      for (int i = 0; i < numberLocks; i++) {
        executor.execute(new LockWorker(this));
      }

      // now release the latch
      startLatch.countDown();

      // wait for workers to finish
      finishLatch.await();

    }

    private void setFailed() {
      failed = true;
      // kill the test
      List<Runnable> waiting = executor.shutdownNow();

      // Countdown the finish latch so the test continues
      for (int i = 0; i < waiting.size() + 1; i++) {
        finishLatch.countDown();
      }

    }

    private boolean isFailed() {
      return failed;
    }

  }

  private static class LockWorker implements Runnable {

    private LockWorkerPool pool;

    /**
     * @param path
     * @param lm
     */
    public LockWorker(LockWorkerPool pool) {
      this.pool = pool;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
      HLock lock = pool.lm.createLock(pool.path);

      try {
        // sync up all threads to wait to acquire lock at the same time
        try {
          pool.startLatch.await();
        } catch (InterruptedException e) {
        }

        // get our lock
        pool.lm.acquire(lock);

        logger.info("Acquired lock {}", lock);

        if (!pool.failSemaphore.tryAcquire()) {
          pool.setFailed();
        }

        // sleep for 100 ms, to allow a conflict to occur
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
        }

        // release the semaphore
        pool.failSemaphore.release();

        // release the lock
        pool.lm.release(lock);
      } catch (Exception e) {
        logger.error("Error when trying to acquire lock", e);
        pool.setFailed();
      } finally {
       
        pool.finishLatch.countDown();

      }

    }

  }
}
