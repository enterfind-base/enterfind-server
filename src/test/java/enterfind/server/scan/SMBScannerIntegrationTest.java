package enterfind.server.scan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.share.DiskShare;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.RemoteFileSystemDataSource;

/**
 * Integration tests for Scanner functionality with SMB data provider
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SMBScannerIntegrationTest extends AbstractScannerIntegrationTest {

    @Override
    protected void createEmptyFolder(String folderName) throws IOException {
        SmbTestHelper.withConnection(share -> {
            String smbFolderPath = smbPath + "/" + folderName;
            if (!share.folderExists(smbFolderPath)) {
                share.mkdir(smbFolderPath);
            }
        });
    }

    @Override
    protected void deleteFile(String filename) throws IOException {
        SmbTestHelper.withConnection(share -> {
            String smbFilePath = smbPath + "/" + filename;
            if (share.fileExists(smbFilePath)) {
                share.rm(smbFilePath);
            }
        });
    }

    @Override
    protected void deleteFolder(String folderName) throws IOException {
        SmbTestHelper.withConnection(share -> {
            String smbFolderPath = smbPath + "/" + folderName;
            if (share.folderExists(smbFolderPath)) {
                SmbTestHelper.deleteRecursively(share, smbFolderPath);
            }
        });
    }

    @Override
    protected Path getTempTestDir() {
        return null; // No temp dir needed for SMB integration
    }

    @Override
    protected Scanner getScanner() {
        return scanner;
    }

    @Override
    protected ItemDao getItemDao() {
        return itemDao;
    }

    @Override
    protected DataSourceInterface getTestDataSource() {
        return testDataSource;
    }

    @Override
    protected String getTestDataSourceId() {
        return testDataSource.id();
    }

    @Override
    protected List<Folder> getFoldersByDatasource(String datasourceId) {
        return itemDao.findFoldersByDatasource(datasourceId);
    }

    @Override
    protected DataSourceInterface createDataSourceWithSkipFiles(String skipFiles) throws IOException {
        RemoteFileSystemDataSource ds = new RemoteFileSystemDataSource(
            null,
            "Test SMB with SkipFiles",
            "0 0 * * *",
            LocalDateTime.now(),
            null,
            DataSourceStatus.IDLE,
            SmbTestHelper.HOST,
            null,
            SmbTestHelper.USER,
            SmbTestHelper.PASSWORD,
            SmbTestHelper.SHARE,
            smbPath,
            skipFiles
        );
        return dataSourceDao.create(ds);
    }

    @Override
    protected void deleteDirectoryRecursively(Path directory) throws IOException {
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

    @Autowired
    private Scanner scanner;
    @Autowired
    private DataSourceDao dataSourceDao;
    @Autowired
    private ItemDao itemDao;
    private RemoteFileSystemDataSource testDataSource;
    private String smbPath;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        try {
            smbPath = SmbTestHelper.newTestPath();
            SmbTestHelper.withConnection(share -> copyClasspathDirToSMB("testshare", smbPath, share));
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up SMB test", e);
        }

        testDataSource = new RemoteFileSystemDataSource(
            null,
            "Test SMB",
            "0 0 * * *",
            LocalDateTime.now(),
            null,
            DataSourceStatus.IDLE,
            SmbTestHelper.HOST,
            null,
            SmbTestHelper.USER,
            SmbTestHelper.PASSWORD,
            SmbTestHelper.SHARE,
            smbPath,
            null
        );
        testDataSource = (RemoteFileSystemDataSource) dataSourceDao.create(testDataSource);
    }

    @AfterEach
    void tearDown() throws IOException {
        super.tearDown();
        if (smbPath == null) return;
        try {
            SmbTestHelper.withConnection(share -> {
                SmbTestHelper.deleteRecursively(share, smbPath);
                // Remove any leftover top-level test folders
                for (FileIdBothDirectoryInformation entry : share.list("")) {
                    String name = entry.getFileName();
                    if (name.startsWith("enterfind-server-tests")) {
                        try {
                            SmbTestHelper.deleteRecursively(share, name);
                        } catch (Exception e) {
                            System.err.println("Failed to delete leftover test folder: " + name + ": " + e.getMessage());
                        }
                    }
                }
            });
        } catch (Exception e) {
            System.err.println("SMB cleanup failed: " + e.getMessage());
        }
    }

    private void copyClasspathDirToSMB(String resourceDir, String smbBasePath, DiskShare share) throws IOException {
        ClassPathResource testShare = new ClassPathResource(java.util.Objects.requireNonNull(resourceDir));
        if (!testShare.exists()) {
            throw new IOException("Resource directory not found: " + resourceDir);
        }
        Path testSharePath = testShare.getFile().toPath();
        try (Stream<Path> files = Files.walk(testSharePath)) {
            files.forEach(source -> {
                try {
                    String smbDestPath = smbBasePath + "/" + testSharePath.relativize(source).toString().replace("\\", "/");
                    if (Files.isDirectory(source)) {
                        SmbTestHelper.mkdir(share, smbDestPath);
                    } else {
                        SmbTestHelper.mkdir(share, smbDestPath.substring(0, smbDestPath.lastIndexOf("/")));
                        try (java.io.InputStream in = Files.newInputStream(source)) {
                            SmbTestHelper.writeFile(share, smbDestPath, in);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to copy to SMB: " + source, e);
                }
            });
        }
    }

    @Override
    protected void createNewFile(String filename, String content) throws IOException {
        SmbTestHelper.withConnection(share ->
            SmbTestHelper.writeFile(share, smbPath + "/" + filename, content));
    }

    @Override
    protected void createNewFolderWithFile(String folderName, String fileName, String content) throws IOException {
        SmbTestHelper.withConnection(share -> {
            String folderPath = smbPath + "/" + folderName;
            if (!share.folderExists(folderPath)) {
                share.mkdir(folderPath);
            }
            SmbTestHelper.writeFile(share, folderPath + "/" + fileName, content);
        });
    }

    @Override
    protected void modifyFile(String filename, String newContent) throws IOException {
        SmbTestHelper.withConnection(share ->
            SmbTestHelper.writeFile(share, smbPath + "/subf/text.txt", newContent));
    }

    @Override
    protected void createNestedFoldersWithFiles(String[] folderNames, String[] fileNames, String[] fileContents) throws IOException {
        SmbTestHelper.withConnection(share -> {
            String level1Path = smbPath + "/" + folderNames[0];
            String level2Path = level1Path + "/" + folderNames[1];
            if (!share.folderExists(level1Path)) share.mkdir(level1Path);
            if (!share.folderExists(level2Path)) share.mkdir(level2Path);
            SmbTestHelper.writeFile(share, level1Path + "/" + fileNames[0], fileContents[0]);
            SmbTestHelper.writeFile(share, level2Path + "/" + fileNames[1], fileContents[1]);
        });
    }

    @Override
    protected void createNestedFoldersWithMultipleLevels(String[] folderStructure, String[] filePaths, String[] fileContents) throws IOException {
        SmbTestHelper.withConnection(share -> {
            for (int i = 0; i < filePaths.length; i++) {
                String smbFilePath = smbPath + "/" + filePaths[i];
                String parentDir = smbFilePath.substring(0, smbFilePath.lastIndexOf("/"));
                SmbTestHelper.mkdir(share, parentDir);
                SmbTestHelper.writeFile(share, smbFilePath, fileContents[i]);
            }
        });
    }
}
