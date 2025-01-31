package io.neonbee.test.listeners;

import static java.lang.Boolean.parseBoolean;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import io.vertx.core.Vertx;

/**
 * The {@link StaleThreadChecker} checks for any stale threads after test execution. Generally after a test finishes to
 * execute it must clean up all resources. If not this listener will print an error to the logs.
 */
public class StaleThreadChecker implements TestExecutionListener {
    public static final SetMultimap<Vertx, String> VERTX_TEST_MAP = HashMultimap.create();

    static final String VERTX_THREAD_NAME_PREFIX = "vert.x-";

    static final boolean VERBOSE = false;

    private static final Logger LOGGER = LoggerFactory.getLogger(StaleThreadChecker.class);

    protected Set<Thread> ignoredThreads;

    protected boolean parallelExecution;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        parallelExecution = parseBoolean(System.getProperty("junit.jupiter.execution.parallel.enabled"));
        if (parallelExecution) {
            LOGGER.warn("Cannot check for stale threads when running JUnit in parallel execution mode");
        }

        if (LOGGER.isDebugEnabled()) {
            // do not report the non-daemon threads that are alive even before the test plan executed started
            (ignoredThreads = findNonDaemonThreads().collect(Collectors.toSet())).stream().forEach(thread -> {
                LOGGER.debug("Ignoring non-daemon thread {} that has been alive before test plan execution started",
                        thread);
            });
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Checking for non-daemon threads after test plan execution finished");
            findNonDaemonThreads().filter(thread -> ignoredThreads == null || !ignoredThreads.contains(thread))
                    .forEach(thread -> LOGGER.debug("Non-daemon thread {} is still alive", thread));
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!parallelExecution) {
            checkForStaleThreads("Vert.x", VERTX_THREAD_NAME_PREFIX);
            checkForStaleThreads("Hazelcast", "hz.");
            checkForStaleThreads("WatchService", "FileSystemWatch");
            if (VERBOSE) {
                // also print non-daemon threads on every execution
                testPlanExecutionFinished(null);
            }
        }
    }

    private static void checkForStaleThreads(String name, String namePrefix) {
        LOGGER.info("Checking for stale {} threads with '{}' prefix", name, namePrefix);
        List<Thread> staleThreads = findStaleThreads(namePrefix).collect(Collectors.toList());
        if (!staleThreads.isEmpty() && LOGGER.isErrorEnabled()) {
            LOGGER.error("Stale {} thread(s) detected!! Not closing the thread {} "
                    + "could result in the test runner not signaling completion", name, staleThreads.get(0));
        }
    }

    protected static Stream<Thread> findNonDaemonThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(Predicate.not(Thread::isDaemon));
    }

    protected static Stream<Thread> findStaleThreads(String namePrefix) {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith(namePrefix));
    }
}
