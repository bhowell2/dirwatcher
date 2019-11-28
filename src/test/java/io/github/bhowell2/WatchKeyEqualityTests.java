package io.github.bhowell2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import static java.nio.file.StandardWatchEventKinds.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Just some tests to reference and ensure that hash/equality uses
 * made in DirWatcher are correct.
 *
 * @author Blake Howell
 */
public class WatchKeyEqualityTests {

	WatchService watchService;

	@BeforeEach
	public void beforeEach() throws Exception {
		this.watchService = FileSystems.getDefault().newWatchService();
	}

	@AfterEach
	public void afterEach() throws Exception {
	  this.watchService.close();
	}

	@Test
	public void watchKeyEquality() throws Exception {
		Path gradlePath1 = Paths.get("gradle");
		Path gradlePath2 = Paths.get("./gradle");
		WatchKey gradleKey1 = gradlePath1.register(this.watchService, ENTRY_CREATE, ENTRY_DELETE);
		WatchKey gradleKey2 = gradlePath2.register(this.watchService, ENTRY_MODIFY);

		assertEquals(gradleKey1,
		             gradleKey2,
		             "Regardless of the watch event kinds and the non-real path used to create a key, the key is the same.");

		assertEquals(gradleKey1.hashCode(),
		             gradleKey2.hashCode(),
		             "Regardless of the watch event kinds and the non-real path used to create a key, the key is the same.");

		WatchKey innerGradleDir_wrapperKey = Paths.get("gradle/wrapper").register(this.watchService, ENTRY_CREATE);
		assertNotEquals(gradleKey1,
		                innerGradleDir_wrapperKey,
		                "Inner directory watch keys are not the same.");
		assertNotEquals(gradleKey1.hashCode(),
		                innerGradleDir_wrapperKey.hashCode(),
		                "Inner directory watch keys are not the same.");

		// NOTE THE ABOVE ONLY HOLDS FOR WATCH KEYS CREATED WITH THE SAME WATCH SERVICE
		WatchService watchService2 = FileSystems.getDefault().newWatchService();
		WatchKey gradleKey1_newService = gradlePath1.register(watchService2, ENTRY_CREATE, ENTRY_DELETE);

		assertNotEquals(gradleKey1,
		                gradleKey1_newService,
		                "If the same watch service is not used the watch keys will not be equal.");

		assertNotEquals(gradleKey1.hashCode(),
		                gradleKey1_newService.hashCode(),
		                "If the same watch service is not used the watch keys will not be equal.");
	}

}
