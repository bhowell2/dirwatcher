package io.github.bhowell2.dirwatcher;

import io.github.bhowell2.asynctesthelper.AsyncTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Depending on the platform these tests can take a while due to how
 * the system is notified of a file/directory change. The following
 * directory structure will be created for testing in the project root
 * folder - it is created before each test and deleted after so each
 * test is independent and clean.
 *
 * Note, no files should be created
 *
 * testdirectory/
 *  - dir1/
 *      - file1
 *
 * @author Blake Howell
 */
public class DirWatcherTests {

	static {
		AsyncTestHelper.DEFAULT_AWAIT_TIME = 60L;
		AsyncTestHelper.DEFAULT_AWAIT_TIME_UNIT = TimeUnit.SECONDS;
		System.out.println(System.getProperty("java.version"));
	}

	public static final Path TEST_DIRECTORY = Paths.get("testdirectory");
	public static final Path TEST_DIRECTORY_FILE1 = TEST_DIRECTORY.resolve(Paths.get("file1"));
	public static final Path TEST_DIRECTORY_FILE2 = TEST_DIRECTORY.resolve(Paths.get("file2"));
	// inner directory
	public static final Path INNER_TEST_DIRECTORY_1 = TEST_DIRECTORY.resolve(Paths.get("inner1"));
	public static final Path INNER_TEST_DIRECTORY_FILE1 = INNER_TEST_DIRECTORY_1.resolve(Paths.get("file1"));
	public static final Path INNER_TEST_DIRECTORY_FILE2 = INNER_TEST_DIRECTORY_1.resolve(Paths.get("file2"));

	private static void createDirectoryIfNotExists(Path dir) throws Exception {
		try {
			Files.createDirectory(dir);
		} catch (FileAlreadyExistsException e) {
			// ignore
		}
	}

	private static void createFileIfNotExists(Path file) throws Exception {
		try {
			Files.createFile(file);
		} catch (FileAlreadyExistsException e) {
			// ignore
		}
	}

	private static void createStartingTestDirectoryStructure() throws Exception {
		createDirectoryIfNotExists(TEST_DIRECTORY);
		createFileIfNotExists(TEST_DIRECTORY_FILE1);
		createFileIfNotExists(TEST_DIRECTORY_FILE2);
		createDirectoryIfNotExists(INNER_TEST_DIRECTORY_1);
		createFileIfNotExists(INNER_TEST_DIRECTORY_FILE1);
		createFileIfNotExists(INNER_TEST_DIRECTORY_FILE2);
	}

	private static void clearStartingDirectoryStructure() throws Exception {
		if (Files.exists(TEST_DIRECTORY)) {
			Files.walk(TEST_DIRECTORY)
			     .sorted(Comparator.reverseOrder())
			     .map(Path::toFile)
			     .forEach(File::delete);
		}
	}

	DirWatcher watcher;

	@BeforeEach
	public void beforeEach() throws Exception {
		clearStartingDirectoryStructure();
		Thread.sleep(1000);
		this.watcher = new DirWatcher();
		createStartingTestDirectoryStructure();
		// give a bit of time for file system to update
		Thread.sleep(1000);
	}

	@AfterEach
	public void afterEach() throws Exception {
		this.watcher.stopWatcher();
		clearStartingDirectoryStructure();
		Thread.sleep(500);
	}

	@Test
	public void shouldCallbackForAllChanges() throws Throwable {
		AsyncTestHelper helper = new AsyncTestHelper();
		CountDownLatch latch = helper.getNewCountDownLatch(3);
		/*
		 * Need to systematically check each kind, because the platform (osx, linux, etc.)
		 * may register an event multiple times for what might be considered a single operation.
		 * */
		this.watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, (realDirectoryPath, file, kind) -> {
			helper.wrapAsyncThrowable(() -> {
				if (file.endsWith("test55") && kind == ENTRY_CREATE) {
					latch.countDown();
				}
				// root test directory file 1 is modified below
				else if (file.equals(TEST_DIRECTORY_FILE1.getFileName()) && kind == ENTRY_MODIFY) {
					latch.countDown();
				}
				// root test directory file 2 is deleted below
				else if (file.equals(TEST_DIRECTORY_FILE2.getFileName()) && kind == ENTRY_DELETE) {
					latch.countDown();
				}
			});
		});
		Thread.sleep(100);  // sleep to give time for callback to be registered
		Files.createFile(TEST_DIRECTORY.resolve(Paths.get("test55")));
		Files.write(TEST_DIRECTORY_FILE1, "whatever".getBytes());
		Files.delete(TEST_DIRECTORY_FILE2);
		helper.await();
	}

	@Test
	public void shouldRecursivelyRegisterCallbackForNewlyCreatedDirectories() throws Throwable {
		AsyncTestHelper helper = new AsyncTestHelper();
		CountDownLatch latch = helper.getNewCountDownLatch(3);
		/*
		 * create file55
		 * create dir99
		 * create dir99/file99 - created after directory is registered (i.e., in callback)
		 * */
		DirWatcher.Callback callback = (dirPath, relativeFilePath, kind) -> {
			helper.wrapAsyncThrowable(() -> {
				if (relativeFilePath.endsWith("file55") && kind == ENTRY_CREATE) {
					latch.countDown();
				} else if (dirPath.endsWith(TEST_DIRECTORY) && relativeFilePath.endsWith("dir99") && kind == ENTRY_CREATE) {
					Files.createFile(TEST_DIRECTORY.resolve("dir99").resolve("file99"));
					latch.countDown();
				} else if (dirPath.endsWith("dir99") && relativeFilePath.endsWith("file99") && kind == ENTRY_CREATE) {
					latch.countDown();
				}
			});
		};
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, true, callback);
		Thread.sleep(100);
		Files.createFile(TEST_DIRECTORY.resolve("file55"));
		Files.createDirectory(TEST_DIRECTORY.resolve("dir99"));
		helper.await();
	}

	@Test
	public void shouldReceiveOnlySubscribedEvents() throws Throwable {
		AsyncTestHelper helper = new AsyncTestHelper();
		CountDownLatch latch = helper.getNewCountDownLatch(3);
		AtomicInteger createCallbackCount = new AtomicInteger(0),
			allCallbackCount = new AtomicInteger(0);
		watcher.registerDirectory(TEST_DIRECTORY, false, ((dirPath, relativeFilePath, kind) -> {
			helper.wrapAsyncThrowable(() -> {
				assertSame(ENTRY_CREATE, kind);
			});
			createCallbackCount.incrementAndGet();
			latch.countDown();
		}), ENTRY_CREATE);
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, ((dirPath, relativeFilePath, kind) -> {
			allCallbackCount.incrementAndGet();
			latch.countDown();
		}));
		Thread.sleep(100);
		Files.createFile(TEST_DIRECTORY.resolve("testfile11"));
		Files.write(TEST_DIRECTORY_FILE1, "whatever".getBytes());
		helper.await();
		// sleep after to make sure no other events are called on the callbacks (counters would be off)
		Thread.sleep(50);
		assertTrue(createCallbackCount.get() >= 1);
		assertTrue(allCallbackCount.get() >= 2);
	}

	@Test
	public void shouldCallMultipleCallbacks() throws Throwable {
		AsyncTestHelper helper = new AsyncTestHelper();
		CountDownLatch latch1 = helper.getNewCountDownLatch(1),
		latch2 = helper.getNewCountDownLatch(1);
		AtomicInteger firstCallbackCount = new AtomicInteger(0),
			secondCallbackCount = new AtomicInteger(0);
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, ((dirPath, relativeFilePath, kind) -> {
			firstCallbackCount.incrementAndGet();
			latch1.countDown();
		}));
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, ((dirPath, relativeFilePath, kind) -> {
			secondCallbackCount.incrementAndGet();
			latch2.countDown();
		}));
		Thread.sleep(100);
		Files.createFile(TEST_DIRECTORY.resolve("testfile11"));
		helper.await();
		assertTrue(firstCallbackCount.get() >= 1);
		assertTrue(secondCallbackCount.get() >= 1);
	}


	@Test
	public void shouldRunOnSuppliedExecutor() throws Throwable {
		DirWatcher watcher = new DirWatcher(Executors.newSingleThreadExecutor());
		AsyncTestHelper helper = new AsyncTestHelper();
		CountDownLatch latch = helper.getNewCountDownLatch(1);
		CountDownLatch latch2 = helper.getNewCountDownLatch(1);
		AtomicReference<Thread> dirWatcherCustomExecutor = new AtomicReference<>(null),
			callbackCustomExecutor = new AtomicReference<>(null);
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, ((dirPath, relativeFilePath, kind) -> {
			dirWatcherCustomExecutor.set(Thread.currentThread());
			latch.countDown();
		}));
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, false, Executors.newSingleThreadExecutor(), ((dirPath, relativeFilePath, kind) -> {
			/*
			 * Runs on the executor supplied with the callback. Other callbacks run on
			 * the watch thread or the executor supplied when creating the watcher.
			 * */
			callbackCustomExecutor.set(Thread.currentThread());
			latch2.countDown();
		}));
		Thread.sleep(50);
		Files.createFile(TEST_DIRECTORY.resolve(Paths.get("whatever")));
		helper.await();
		assertNotNull(dirWatcherCustomExecutor.get());
		assertNotNull(callbackCustomExecutor.get());
		assertNotEquals(dirWatcherCustomExecutor.get(), callbackCustomExecutor.get());
	}

	@Test
	public void shouldStopWatcher() throws Throwable {
		// more for code coverage than anything
		DirWatcher watcher = new DirWatcher();
		watcher.stopWatcher();
		Thread.sleep(1000);
	}

	@Test
	public void shouldOverrideCallback() throws Throwable {
		AsyncTestHelper helper = new AsyncTestHelper();
		AtomicBoolean hasOverridden = new AtomicBoolean(false);
		AtomicInteger callback1Counter = new AtomicInteger(0),
			callback2Counter = new AtomicInteger(0);
		DirWatcher.Callback callback = ((dirPath, relativeFilePath, kind) -> {
			helper.wrapAsyncThrowable(() -> {
				callback1Counter.incrementAndGet();
				if (hasOverridden.get()) {
					if (kind != ENTRY_DELETE) {
						helper.fail("Should have only been called on ENTRY_DELETE kinds after override.");
					}
				} else {
					// only registered for entry modify before override
					if (kind != ENTRY_MODIFY) {
						helper.fail("Should have only been called on ENTRY_MODIFY kinds for first run.");
					}
				}
			});
		});
		DirWatcher.Callback callback2 = (dirPath, relativeFilePath, kind) -> {
			callback2Counter.incrementAndGet();
		};
		watcher.registerDirectory(TEST_DIRECTORY, callback, ENTRY_MODIFY);
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, callback2);
		Thread.sleep(100);
		Files.write(TEST_DIRECTORY_FILE1, "...".getBytes());
		Files.delete(TEST_DIRECTORY_FILE2);
		helper.sleepAndThrowIfFailureOccurs(20, TimeUnit.SECONDS);
		watcher.registerDirectory(TEST_DIRECTORY, callback, ENTRY_DELETE);
		hasOverridden.set(true);
		int callback1CountBeforeNextEvent = callback1Counter.get();
		int callback2CountBeforeNextEvent = callback2Counter.get();
		Thread.sleep(100);
		Files.delete(TEST_DIRECTORY_FILE1);
		helper.sleepAndThrowIfFailureOccurs(20, TimeUnit.SECONDS);
		assertTrue(callback1Counter.get() > callback1CountBeforeNextEvent);
		assertTrue(callback2Counter.get() > callback2CountBeforeNextEvent);
	}

	@Test
	public void shouldRecursivelyRegisterSubDirectoriesOnDirectoryRegistration() throws Throwable {
		AsyncTestHelper helper = new AsyncTestHelper();
		CountDownLatch latch = helper.getNewCountDownLatch(1);
		DirWatcher.Callback callback = ((dirPath, relativeFilePath, kind) -> {
			// will timeout if did not occur in inner directory
			if (dirPath.endsWith(INNER_TEST_DIRECTORY_1.getFileName())) {
				latch.countDown();
			}
		});
		watcher.registerDirectory(TEST_DIRECTORY, true, callback, ENTRY_MODIFY);
		Thread.sleep(100);
		Files.write(INNER_TEST_DIRECTORY_FILE1, "heyy".getBytes());
		// will timeout if there is an error. otherwise is fine.
		helper.await();
	}

	@Test
	public void shouldUnregisterAllCallbacksForPath() throws Throwable {
		AsyncTestHelper helper = new AsyncTestHelper();
		AtomicInteger callback1Counter = new AtomicInteger(0),
			callback2Counter = new AtomicInteger(0);
		AtomicBoolean callback1ShouldNotBeCalledAgain = new AtomicBoolean(false),
			callback2ShouldNotBeCalledAgain = new AtomicBoolean(false);
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, ((dirPath, relativeFilePath, kind) -> {
			callback1Counter.getAndIncrement();
			if (callback1ShouldNotBeCalledAgain.get()) {
				helper.fail("Callback 1 should not have been called again.");
			}
		}));
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, ((dirPath, relativeFilePath, kind) -> {
			callback2Counter.getAndIncrement();
			if (callback2ShouldNotBeCalledAgain.get()) {
				helper.fail("Callback 2 should not have been called again.");
			}
		}));
		Thread.sleep(100);
		Files.write(TEST_DIRECTORY_FILE2, "whatever".getBytes());
		helper.sleepAndThrowIfFailureOccurs(20, TimeUnit.SECONDS);
		assertTrue(callback1Counter.get() > 0);
		assertTrue(callback2Counter.get() > 0);
		watcher.unregisterAllCallbacksForPath(TEST_DIRECTORY);
		callback1ShouldNotBeCalledAgain.set(true);
		callback2ShouldNotBeCalledAgain.set(true);
		Thread.sleep(10);
		Files.write(TEST_DIRECTORY_FILE2, "whatever".getBytes());
		helper.sleepAndThrowIfFailureOccurs(20, TimeUnit.SECONDS);
	}

	@Test
	public void shouldUnregisterSingleCallbackForPath() throws Throwable {
		AsyncTestHelper helper = new AsyncTestHelper();
		AtomicBoolean callback1ShouldNotBeCalledAgain = new AtomicBoolean(false);
		AtomicInteger callback1Counter = new AtomicInteger(0),
			callback2Counter = new AtomicInteger(0);
		DirWatcher.Callback callback1 = ((dirPath, relativeFilePath, kind) -> {
			callback1Counter.incrementAndGet();
			if (callback1ShouldNotBeCalledAgain.get()) {
				helper.fail("Callback 1 should not have been called again.");
			}
		});
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, callback1);
		DirWatcher.Callback callback2 = ((dirPath, relativeFilePath, kind) -> {
			callback2Counter.getAndIncrement();
		});
		watcher.registerDirectoryForAllKinds(TEST_DIRECTORY, callback2);
		Files.write(TEST_DIRECTORY_FILE2, "whatever".getBytes());
		helper.sleepAndThrowIfFailureOccurs(20, TimeUnit.SECONDS);
		assertTrue(callback1Counter.get() > 0);
		int callback1CountBeforeUnregister = callback1Counter.get();
		int callback2CountBeforeUnregister = callback2Counter.get();
		callback1ShouldNotBeCalledAgain.set(true);
		watcher.unregisterCallbackForPath(TEST_DIRECTORY, callback1);
		Thread.sleep(10);
		Files.write(TEST_DIRECTORY_FILE2, "whatever".getBytes());
		helper.sleepAndThrowIfFailureOccurs(20, TimeUnit.SECONDS);
		assertEquals(callback1CountBeforeUnregister, callback1Counter.get());
		assertTrue(callback2CountBeforeUnregister < callback2Counter.getAndIncrement());
	}

	@Test
	public void shouldClearCallbacksWhenOneIsRemovedAndNoMoreExistsForPath() throws Throwable {
		// this is more for code coverage more than anything else.
		AsyncTestHelper helper = new AsyncTestHelper();
		DirWatcher dirWatcher = new DirWatcher(FileSystems.getDefault().newWatchService());
		AtomicReference<CountDownLatch> latchForReRegister = new AtomicReference<>(null);
		DirWatcher.Callback callback = (dirPath, relativeFilePath, kind) -> {
			if (latchForReRegister.get() != null) {
				latchForReRegister.get().countDown();
			} else {
				helper.fail("Should have never been called when latch was not set.");
			}
		};
		dirWatcher.registerDirectoryForAllKinds(TEST_DIRECTORY, callback);
		dirWatcher.unregisterCallbackForPath(TEST_DIRECTORY, callback);
		Files.write(TEST_DIRECTORY_FILE1, "hey".getBytes());
		// ensure nothing is called..
		helper.sleepAndThrowIfFailureOccurs(20, TimeUnit.SECONDS);
		latchForReRegister.set(helper.getNewCountDownLatch(1));
		dirWatcher.registerDirectoryForAllKinds(TEST_DIRECTORY, callback);
		Files.write(TEST_DIRECTORY_FILE1, "hey".getBytes());
		helper.await("Should not have timed out, because callback should have been called and latch cleared.");
	}

	@Test
	public void shouldReturnFalseWhenNothingIsUnregistered() throws Exception {
		DirWatcher.Callback callback = (dirPath, relativeFilePath, kind) -> {};
		assertFalse(watcher.unregisterCallbackForPath(TEST_DIRECTORY, callback));
		assertFalse(watcher.unregisterAllCallbacksForPath(TEST_DIRECTORY));
	}

}
