package io.github.bhowell2.dirwatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Provides convenient functionality for watching a directory for changes.
 * A {@link Callback} is registered for events
 * ({@link java.nio.file.WatchEvent.Kind}) that occur in the directory. NOTE:
 * this class only supports {@link java.nio.file.StandardWatchEventKinds} and
 * not other types. Multiple callbacks can be registered for a single directory
 * and only the {@link java.nio.file.WatchEvent.Kind}s the user subscribed that
 * callback to will trigger it.
 *
 * Notes about {@link WatchService} and {@link WatchKey} in general:
 * Normally it is only possible to register one {@link WatchKey} per {@link WatchService}
 * per {@link Path} (directory). This means that the WatchKey is overridden by the
 * most recently registered {@link java.nio.file.WatchEvent.Kind} and will not be
 * triggered for the former {@link java.nio.file.WatchEvent.Kind}s (some unit test
 * have been created to show this behavior).
 *
 * This class is thread-safe.
 *
 * @author Blake Howell
 */
public class DirWatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger(DirWatcher.class);

	// used to number the thread the watch service runs on
	private static final AtomicInteger DIR_WATCHER_COUNT = new AtomicInteger(0);

	@FunctionalInterface
	public interface Callback {
		/**
		 * This passes in the directory path and the relativized file path
		 * (i.e., just the file name) so that the user can respond to the
		 * change. If an {@link Executor} was provided when registering
		 * the callback then this will be run on the provided executor, if
		 * one was not provided then it will fallback to running on the executor
		 * provided when creating the DirWatcher, if neither executor was
		 * provided then this will run on the DirWatcher's watch thread and
		 * this block it. If no executors were provided then the callback should,
		 * ideally, immediately call the code to run on another thread if it will
		 * be long running.
		 *
		 * To obtain the full path to the file the user must resolve the
		 * {@code relativeFilePath} against the {@code dirPath} (i.e.,
		 * {@code dirPath.resolve(relativeFilePath)}).
		 *
		 * @param dirPath the directory that is being watched where the event occurred
		 * @param relativeFilePath the file or directory that caused the event inside the watched directory ({@code dirPath})
		 * @param kind the event type (create, modify, delete, overflow, or some custom kind)
		 */
		void call(Path dirPath, Path relativeFilePath, WatchEvent.Kind kind);
	}

	private static final WatchEvent.Kind[] ALL_STANDARD_WATCH_EVENT_KINDS = new WatchEvent.Kind[] {
		ENTRY_CREATE,
		ENTRY_MODIFY,
		ENTRY_DELETE,
		OVERFLOW
	};

	private final ConcurrentHashMap<Path, WatchKey> pathWatchKeys;
	private final ConcurrentHashMap<WatchKey, Set<WatchKeyEventHandler>> watchKeyHandlers;
	private final WatchService watchService;
	private final Executor defaultCallbackExecutor;   // may be null

	private volatile boolean started = false;
	private boolean stopWatcher = false;

	/**
	 * Creates a DirWatcher with the default file system's watch service
	 * and no default executor for callbacks (i.e., callbacks will run on
	 * the watch thread).
	 *
	 * @throws  UnsupportedOperationException
	 *          If this {@code FileSystem} does not support watching file system
	 *          objects for changes and events. This exception is not thrown
	 *          by {@code FileSystems} created by the default provider.
	 * @throws  IOException
	 *          If an I/O error occurs
	 */
	public DirWatcher() throws Exception {
		this(FileSystems.getDefault().newWatchService(), null);
	}

	/**
	 * Creates a DirWatcher with the supplied watch service and no
	 * default executor for callbacks (i.e., callbacks will run on
	 * the watch thread).
	 *
	 * @param watchService used to watch a directory for changes
	 */
	public DirWatcher(WatchService watchService) {
		this(watchService, null);
	}

	/**
	 * Creates a DirWatcher with the default file system's watch service
	 * and sets the default executor for callbacks.
	 *
	 * @param defaultCallbackExecutor executor to use for callbacks if one is not supplied when registering the callback.
	 *                                if this is not supplied the callback will run on the watch thread.
	 *
	 * @throws  UnsupportedOperationException
	 *          If this {@code FileSystem} does not support watching file system
	 *          objects for changes and events. This exception is not thrown
	 *          by {@code FileSystems} created by the default provider.
	 * @throws  IOException
	 *          If an I/O error occurs
	 */
	public DirWatcher(Executor defaultCallbackExecutor) throws Exception {
		this(FileSystems.getDefault().newWatchService(), defaultCallbackExecutor);
	}

	/**
	 * Creates a DirWatcher with the supplied watch service and
	 * default callback executor.
	 *
	 * @param watchService used to watch a directory for changes
	 * @param defaultCallbackExecutor executor to use for callbacks if none are supplied for the callback specifically
	 */
	public DirWatcher(WatchService watchService, Executor defaultCallbackExecutor) {
		Objects.requireNonNull(watchService, "Cannot watch without a WatchService.");
		this.watchService = watchService;
		this.defaultCallbackExecutor = defaultCallbackExecutor;
		this.pathWatchKeys = new ConcurrentHashMap<>(1, 0.7f, 1);
		this.watchKeyHandlers = new ConcurrentHashMap<>(1, 0.7f, 1);
	}

	/**
	 * Registers the directory to be watched for all {@link java.nio.file.StandardWatchEventKinds}.
	 *
	 * Does not register subdirectories.
	 *
	 * @param dirPath directory to watch (must exist and convertible to {@link Path#toRealPath(LinkOption...)})
	 * @param callback callback for a change on the directory
	 * @throws Exception when an error occurs registering a directory
	 */
	public void registerDirectoryForAllKinds(Path dirPath, Callback callback) throws Exception {
		registerDirectoryForAllKinds(dirPath, false, callback);
	}

	/**
	 * Registers the directory (and all subdirectories if {@code recursive = true})
	 * to be watched for all {@link java.nio.file.StandardWatchEventKinds}.
	 *
	 * If set to recursively register directories all subdirectories that currently
	 * exist and that are created will be registered with the provided callback. If
	 * there are many subdirectories this could block for an extended period of time.
	 *
	 * @param dirPath directory to watch (must exist and convertible to {@link Path#toRealPath(LinkOption...)})
	 * @param recursive whether or not to recursively register subdirectories to the provided callback
	 * @param callback callback for a change on the directory
	 * @throws Exception when an error occurs registering a directory
	 */
	public void registerDirectoryForAllKinds(Path dirPath,
	                                         boolean recursive,
	                                         Callback callback) throws Exception {
		registerDirectoryForAllKinds(dirPath, recursive, null, callback);
	}

	/**
	 * Registers the directory (and all subdirectories if {@code recursive = true})
	 * to be watched for all {@link java.nio.file.StandardWatchEventKinds}.
	 *
	 * If set to recursively register directories all subdirectories that currently
	 * exist and that are created will be registered with the provided callback. If
	 * there are many subdirectories this could block for an extended period of time.
	 *
	 * @param dirPath directory to watch (must exist and convertible to {@link Path#toRealPath(LinkOption...)})
	 * @param recursive whether or not to recursively register subdirectories to the provided callback
	 * @param callbackExecutor executor to use for the provided callback (overrides default if supplied)
	 * @param callback callback for a change on the directory
	 * @throws Exception when an error occurs registering a directory
	 */
	public void registerDirectoryForAllKinds(Path dirPath,
	                                         boolean recursive,
	                                         Executor callbackExecutor,
	                                         Callback callback) throws Exception {
		registerDirectory(dirPath, recursive, callbackExecutor, callback, ALL_STANDARD_WATCH_EVENT_KINDS);
	}

	/**
	 *
	 * Registers the directory to be watched for the provided kinds.
	 *
	 * Does not register subdirectories.
	 *
	 * @param dirPath directory to watch (must exist and convertible to {@link Path#toRealPath(LinkOption...)})
	 * @param callback callback for a change (of the supplied {@code kind}) on the directory
	 * @param kinds what events will trigger the {@code callback}
	 * @throws Exception when an error occurs registering a directory
	 */
	public void registerDirectory(Path dirPath,
	                              Callback callback,
	                              WatchEvent.Kind... kinds) throws Exception {
		registerDirectory(dirPath, false, callback, kinds);
	}

	/**
	 * Registers the directory (and all subdirectories if {@code recursive = true})
	 * to be watched for all {@link java.nio.file.StandardWatchEventKinds}.
	 *
	 * If set to recursively register directories all subdirectories that currently
	 * exist and that are created will be registered with the provided callback. If
	 * there are many subdirectories this could block for an extended period of time.
	 *
	 * @param dirPath directory to watch (must exist and convertible to {@link Path#toRealPath(LinkOption...)})
	 * @param recursive whether or not to recursively register subdirectories to the provided callback
	 * @param callback callback for a change on the directory
	 * @throws Exception when an error occurs registering a directory
	 */
	public void registerDirectory(Path dirPath,
	                              boolean recursive,
	                              Callback callback,
	                              WatchEvent.Kind... kinds) throws Exception {
		registerDirectory(dirPath, recursive, null, callback, kinds);
	}

	/**
	 * Registers the directory (and all subdirectories if {@code recursive = true})
	 * to be watched for the {@link java.nio.file.WatchEvent.Kind} supplied. In the
	 * case that all of the {@code kinds} supplied are part of the
	 * {@link java.nio.file.StandardWatchEventKinds} then all standard watch event kinds
	 * will be registered for the key. However, if some custom
	 * {@link java.nio.file.WatchEvent.Kind} is supplied, then only the
	 *
	 * If set to recursively register directories all subdirectories that currently
	 * exist and that are created will be registered with the provided callback. If
	 * there are many subdirectories this could block for an extended period of time.
	 *
	 * @param dirPath the directory path to watch. this must be a real path (i.e., {@link Path#toRealPath(LinkOption...)}
	 *                resolves and must be a directory, not a file.
	 * @param recursive {@code true} if all subdirectories in {@code dirPath} should also be registered for the
	 *                  {@code callback}
	 * @param callbackExecutor used to run {@code callback }. may be null, will fallback to
	 *                         {@link Executor} provided in constructor or null if none was provided
	 * @param callback called when an event of {@code kinds} occurs
	 * @param kinds the types of events that will trigger the {@code callback}
	 * @throws Exception
	 */
	public void registerDirectory(Path dirPath,
	                              boolean recursive,
	                              Executor callbackExecutor,
	                              Callback callback,
	                              WatchEvent.Kind... kinds) throws Exception {
		Objects.requireNonNull(callback);
		Objects.requireNonNull(kinds);
		Path realDirPath = dirPath.toRealPath();
		// need to synchronize, because potentially adding to two different maps
		synchronized (this) {
			WatchKey realDirPathWatchKey = pathWatchKeys.get(realDirPath);
			if (realDirPathWatchKey == null) {
				/*
				 * It is necessary to register all events.
				 * */
				realDirPathWatchKey = realDirPath.register(this.watchService, ALL_STANDARD_WATCH_EVENT_KINDS);
				pathWatchKeys.putIfAbsent(realDirPath, realDirPathWatchKey);
			}
			WatchKeyEventHandler handler = new WatchKeyEventHandler(realDirPathWatchKey,
			                                                        realDirPath,
			                                                        recursive, callbackExecutor, callback,
			                                                        Arrays.asList(kinds)
			);
			// handlers set is unmodifiable, so must make copy
			Set<WatchKeyEventHandler> handlers = watchKeyHandlers.get(realDirPathWatchKey);
			if (handlers == null) {
				handlers = new HashSet<>();
				handlers.add(handler);
				watchKeyHandlers.put(realDirPathWatchKey, Collections.unmodifiableSet(handlers));
			} else if (!handlers.contains(handler)) {
				handlers = new HashSet<>(handlers);
				handlers.add(handler);
				watchKeyHandlers.put(realDirPathWatchKey, Collections.unmodifiableSet(handlers));
			} else {
				LOGGER.warn("Overriding callback for path {}.", dirPath);
				// use current size, just replace the one element with the other
				Set<WatchKeyEventHandler> tmp = new HashSet<>(handlers.size());
				for (WatchKeyEventHandler h : handlers) {
					if (!h.equals(handler)) {
						tmp.add(h);
					}
				}
				tmp.add(handler);
				watchKeyHandlers.put(realDirPathWatchKey, Collections.unmodifiableSet(tmp));
			}
			// starts if not started
			startWatcher();
		}
		if (recursive) {
			/*
			 * Want to use list rather than walk, because walk will go into sub-directories
			 * which is what will already be done when registerDirectory is called recursively
			 * by this here.
			 * */
			Files.list(dirPath)
			     .filter(p -> Files.isDirectory(p))
			     .forEach(p -> {
				     try {
					     registerDirectory(p,
					                       recursive, callbackExecutor, callback,
					                       kinds);
				     } catch (Exception e) {
					     LOGGER.error("Failed to recursively register directory.", e);
				     }
			     });
		}
	}

	// TODO: implement unregister recursive

	/**
	 * Removes all currently registered callbacks for the provided directory path.
	 *
	 * @param dirPath the directory path to remove all callbacks for
	 * @return {@code true} if it existed and was removed, {@code false} otherwise
	 */
	public boolean unregisterAllCallbacksForPath(Path dirPath) throws Exception {
		synchronized (this) {
			WatchKey removedKey = pathWatchKeys.remove(dirPath.toRealPath());
			if (removedKey != null) {
				watchKeyHandlers.remove(removedKey);
				return true;
			}
			// did not exist
			return false;
		}
	}

	/**
	 * Removes the provided callback for the provided directory path if it exists.
	 *
	 * @param dirPath the directory path the callback was registered for
	 * @param callback the callback to remove
	 * @return {@code true} if it existed and was removed, {@code false} otherwise
	 * @throws Exception thrown if {@link Path#toRealPath(LinkOption...)} fails.
	 */
	public boolean unregisterCallbackForPath(Path dirPath, Callback callback) throws Exception {
		synchronized (this) {
			dirPath = dirPath.toRealPath();
			WatchKey key = pathWatchKeys.get(dirPath);
			if (key != null) {
				// the set is unmodifiable, so must re-create
				Set<WatchKeyEventHandler> handlers = watchKeyHandlers.get(key);
				if (handlers != null) {
					Set<WatchKeyEventHandler> tmp = new HashSet<>(handlers.size() - 1);
					for (WatchKeyEventHandler h : handlers) {
						if (!h.callback.equals(callback)) {
							tmp.add(h);
						}
					}
					if (tmp.size() == 0) {
						// can completely remove the watch key
						pathWatchKeys.remove(dirPath);
						watchKeyHandlers.remove(key);
					} else {
						watchKeyHandlers.put(key, Collections.unmodifiableSet(tmp));
					}
					return handlers.size() != tmp.size();
				}
			}
			return false;
		}
	}

	/**
	 * Creates watch thread if it has not yet been created.
	 */
	private synchronized void startWatcher() {
		if (!started) {
			new Thread(() -> {
				for (; ; ) {
					if (this.stopWatcher) {
						try {
							watchService.close();
						} catch (Exception e) {
							// ignore
						}
						return;
					}
					// Check if it has changed
					WatchKey key = null;
					try {
						// retrieves the next key if an event has occurred
						// poll so that loop will restart if nothing occurs and check if it should stop
						key = watchService.poll(1, TimeUnit.MILLISECONDS);
					} catch (InterruptedException x) {
						// do nothing
						return;
					}
					if (key != null) {
						Set<WatchKeyEventHandler> handlers = watchKeyHandlers.get(key);
						List<WatchEvent<?>> watchEvents = key.pollEvents();
						/*
						 * Will send to list at time of event. This set is immutable, so there will be
						 * no issue with concurrent modification.
						 * */
						if (handlers != null) {
							for (WatchKeyEventHandler handler : handlers) {
								handler.handleEvents(watchEvents);
							}
						}
						/*
						 * Must reset key to receive future watch events. Will be invalid if
						 * the key was cancelled by the user or because the directory was
						 * deleted. If the key is no longer valid it will not become revalidated
						 * and operation again. E.g., if the directory the key is registered
						 * for gets deleted and then re-created it will not start triggering
						 * events again (even if it is reset again after it was re-created).
						 * Thus, when a key becomes invalid all callbacks can be removed as
						 * they will never be called again.
						 * */
						boolean valid = key.reset();
						if (!valid) {
							synchronized (this) {
								// go through each and find the key that was cancelled and then the path
								Path p = null;
								for (Map.Entry<Path, WatchKey> entry : pathWatchKeys.entrySet()) {
									if (entry.getValue().equals(key)) {
										p = entry.getKey();
										break;
									}
								}
							}
						}
					}
				}
			}, "DirWatcher-" + DIR_WATCHER_COUNT.getAndIncrement()).start();
			this.started = true;
		}
	}

	/**
	 * Will asynchronously stop the WatchService and its watching thread.
	 */
	public void stopWatcher() throws IOException  {
		// watch service is closed in the watching thread
		this.stopWatcher = true;
	}

	/**
	 * Wraps the {@link Callback} and {@link java.nio.file.WatchEvent.Kind}s that the
	 * callback is subscribed to. This allows for registering different callbacks for
	 * different event kinds for a given {@link WatchKey} and {@link Path}. Note a
	 * {@link WatchKey} is unique based on the {@link WatchService} and {@link Path}
	 * only (the event kinds do not change this - see tests for examples) - this
	 * causes the problem of a WatchKey's events being overridden by the most recent
	 * event registration kinds. This is best illustrated with an example:
	 *
	 * If the WatchService and Path are the same, the following calls will override
	 * which event kinds are triggered on the key.
	 *
	 * WatchKey key1 = path.register(watchService,
	 *                               StandardWatchEventKinds.ENTRY_CREATE,
	 *                               StandardWatchEventKinds.ENTRY_MODIFY);
	 * WatchKey key2 = path.register(watchService,
	 *                               StandardWatchEventKinds.ENTRY_CREATE);
	 * key1 == key2 is true, but now the only events that the key is triggered on
	 * are ENTRY_CREATE events, rather than both ENTRY_CREATE and ENTRY_MODIFY.
	 *
	 * The previous example can obviously cause undesirable effects and thus every
	 * new {@link WatchKey} is created by watching for ALL event kinds and then
	 * this class will determine whether or not to actually trigger the callback,
	 * based on whether or not the user indicated when registering the directory
	 * that they wanted to
	 *

	 * One of these will be created for each unique callback and path combination
	 * where the path is determined by the per {@link WatchKey}.
	 *
	 */
	private final class WatchKeyEventHandler {

		private final WatchKey key;
		private final Path realDirPath;
		private final Callback callback;
		private final Set<WatchEvent.Kind> subscriptionKinds;
		private final boolean recursive;
		// optional executor to supply for callback
		private final Executor executor;

		public WatchKeyEventHandler(WatchKey key,
		                            Path realDirPath,
		                            boolean recursive,
		                            Executor executor,
		                            Callback callback,
		                            List<WatchEvent.Kind> kinds) {
			Objects.requireNonNull(key);
			Objects.requireNonNull(realDirPath);
			Objects.requireNonNull(callback);
			Objects.requireNonNull(kinds);
			this.key = key;
			this.realDirPath = realDirPath;
			this.executor = executor;
			this.callback = callback;
			this.subscriptionKinds = new HashSet<>(kinds);
			this.recursive = recursive;
		}

		private void runCallback(Path relativizedPath, WatchEvent.Kind kind) {
			if (this.executor != null) {
				executor.execute(() -> {
					this.callback.call(this.realDirPath, relativizedPath, kind);
				});
			} else if (defaultCallbackExecutor != null) {
				defaultCallbackExecutor.execute(() -> {
					this.callback.call(this.realDirPath, relativizedPath, kind);
				});
			} else {
				this.callback.call(this.realDirPath, relativizedPath, kind);
			}
		}

		/**
		 * Call the callback if subscribed to the event kind. If {@code lastEventOnly = true}
		 * then the last subscribed event will be sent.
		 * @param events
		 */
		public void handleEvents(List<WatchEvent<?>> events) {
			int size = events.size();
			for (int i = 0; i < size; i++) {
				WatchEvent event = events.get(i);
				Path fileOrDir = (Path) event.context();
				WatchEvent.Kind eventKind = event.kind();
				/*
				 * Recursive is guaranteed to be valid here (i.e., this handler should register
				 * created directories), because the registerDirectory method throws if the
				 * callback was not registered for ENTRY_CREATE kinds.
				 * */
				if (recursive && eventKind == ENTRY_CREATE && Files.isDirectory(this.realDirPath.resolve(fileOrDir))) {
					try {
						registerDirectory(this.realDirPath.resolve(fileOrDir),
						                  this.recursive, this.executor, this.callback,
						                  this.subscriptionKinds.toArray(new WatchEvent.Kind[0]));
					} catch (Exception e) {
						LOGGER.error("Failed to recursively register directory.", e);
					}
				}
				if (subscriptionKinds.contains(event.kind())) {
					runCallback((Path) event.context(), event.kind());
				}
			}
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			WatchKeyEventHandler that = (WatchKeyEventHandler) o;
			return realDirPath.equals(that.realDirPath) &&
				callback.equals(that.callback);
		}

		@Override
		public int hashCode() {
			return Objects.hash(realDirPath, callback);
		}

	}

}
