package io.github.bhowell2;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Just some tests to reference and ensure that hash/equality uses
 * made in DirWatcher are correct.
 *
 * @author Blake Howell
 */
public class PathEqualityTests {

	@Test
	public void pathEquality() throws Exception {
		Path gradlePath1 = Paths.get("./gradle");
		Path gradlePath2 = Paths.get("gradle");

		assertNotEquals(gradlePath1,
		                gradlePath2,
		                "Even though the .toRealPath() of these is the same these and toAbsolute are not.");

		assertEquals(gradlePath1.normalize(),
		             gradlePath2.normalize(),
		             "These are equal, because normalize removes redundant characters, such as './'");

		assertNotEquals(gradlePath1.hashCode(),
		                gradlePath2.hashCode(),
		                "Even though the .toRealPath() of these is the same these and toAbsolute are not.");

		assertEquals(Paths.get("./gradle"),
		             gradlePath1,
		             "Paths made from the same exact string have equality.");

		assertEquals(Paths.get("./gradle").hashCode(),
		             gradlePath1.hashCode(),
		             "Paths made from the same exact string have equality.");

		assertEquals(Paths.get("gradle"),
		             gradlePath2,
		             "Paths made from the same exact string have equality.");

		assertEquals(Paths.get("gradle").hashCode(),
		             gradlePath2.hashCode(),
		             "Paths made from the same exact string have equality.");
	}

	@Test
	public void absolutePathEquality() throws Exception {
		Path gradleAbsolutePath1 = Paths.get("./gradle").toAbsolutePath();
		Path gradleAbsolutePath2 = Paths.get("gradle").toAbsolutePath();

		assertNotEquals(gradleAbsolutePath1,
		                gradleAbsolutePath2,
		                "Even though the .toRealPath() of these is the same these and toAbsolute are not.");

		assertNotEquals(gradleAbsolutePath1.hashCode(),
		                gradleAbsolutePath2.hashCode(),
		                "Even though the .toRealPath() of these is the same these and toAbsolute are not.");

		assertNotEquals(gradleAbsolutePath1.toAbsolutePath(),
		                gradleAbsolutePath2.toAbsolutePath(),
		                "Sanity check.. Should not have changed from above.");

		assertEquals(Paths.get("./gradle").toAbsolutePath(),
		             gradleAbsolutePath1,
		             "Paths made from the same string are the same.");

		assertEquals(Paths.get("./gradle").toAbsolutePath().hashCode(),
		             gradleAbsolutePath1.hashCode(),
		             "Paths made from the same string are the same.");

		assertEquals(Paths.get("gradle").toAbsolutePath(),
		             gradleAbsolutePath2,
		             "Paths made from the same string are the same.");

		assertEquals(Paths.get("gradle").toAbsolutePath().hashCode(),
		             gradleAbsolutePath2.hashCode(),
		             "Paths made from the same string are the same.");
	}

	@Test
	public void realPathEquality() throws Exception {
		Path gradleRealPath1 = Paths.get("./gradle").toRealPath();
		Path gradleRealPath2 = Paths.get("gradle").toRealPath();

		assertEquals(gradleRealPath1,
		             gradleRealPath2,
		             "Real paths should be equal");

		assertEquals(gradleRealPath1.hashCode(),
		             gradleRealPath2.hashCode(),
		             "Real path hash codes should be the same.");

		assertEquals(gradleRealPath1.toAbsolutePath(),
		             gradleRealPath2.toAbsolutePath(),
		             "Real paths converted to absolute paths should be equal");

	}

	@Test
	public void pathResolution() throws Exception {
		Path gradleRealPath = Paths.get("./gradle").toRealPath();
		Path wrapperRealPath = Paths.get("./gradle/wrapper").toRealPath();
		assertTrue(gradleRealPath.resolve(wrapperRealPath).endsWith("gradle/wrapper"));
		assertFalse(gradleRealPath.resolve(wrapperRealPath).endsWith("gradle/gradle/wrapper"));
	}

}
