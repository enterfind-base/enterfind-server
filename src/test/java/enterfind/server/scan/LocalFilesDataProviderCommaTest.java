package enterfind.server.scan;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import enterfind.server.model.DataSourceType;
import enterfind.server.model.LocalFileSystemDataSource;

@SpringBootTest
class LocalFilesDataProviderCommaTest extends AbstractDataProviderCommaTest {

    @TempDir
    Path tempDir;

    private LocalFilesDataProvider provider;

    @BeforeEach
    void setup() throws Exception {
        Path commaFolderRoot = tempDir.resolve("Rust Servers, Services, and Apps");
        Files.createDirectory(commaFolderRoot);
        Files.write(commaFolderRoot.resolve("chapter1.pdf"), "chapter1 content".getBytes());

        Path commaFolderNested = tempDir.resolve("Manning Books").resolve("Rust Servers, Services, and Apps");
        Files.createDirectories(commaFolderNested);
        Files.write(commaFolderNested.resolve("chapter2.pdf"), "chapter2 content".getBytes());

        LocalFileSystemDataSource dataSource = new LocalFileSystemDataSource(
            "test-id", "test-datasource", null, null, null, null, tempDir.toString(), null);
        provider = new LocalFilesDataProvider(dataSource);
    }

    @AfterEach
    void tearDown() {
        DataSourceType.shutdownAllProviders();
    }

    @Override
    protected DataProviderInterface getProvider() {
        return provider;
    }
}
