package enterfind.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import org.springframework.core.io.ClassPathResource;

/**
 * Utility class for common test file operations
 */
public class TestFileUtils {

    /**
     * Copy the testshare directory from classpath resources to the given temp directory
     * 
     * @param tempTestDir the target directory where testshare will be copied
     * @throws IOException if copy operation fails
     */
    public static void copyTestShareToTempDir(Path tempTestDir) throws IOException {
        ClassPathResource testShare = new ClassPathResource("testshare");
        Path testSharePath = testShare.getFile().toPath();
        try (Stream<Path> files = Files.walk(testSharePath)) {
            files.forEach(source -> {
                try {
                    Path dest = tempTestDir.resolve(testSharePath.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy test file: " + source, e);
                }
            });
        }
    }

    /**
     * Recursively delete a directory and all its contents
     * 
     * @param directory the directory to delete
     * @throws IOException if delete operation fails
     */
    public static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted((a, b) -> b.compareTo(a))
                 .forEach(path -> {
                     try {
                         Files.delete(path);
                     } catch (IOException e) {
                         throw new RuntimeException("Failed to delete: " + path, e);
                     }
                 });
        }
    }
}
