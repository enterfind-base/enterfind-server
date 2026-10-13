package enterfind.server.scan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests that a DataProviderInterface correctly handles folder and file names containing commas.
 * Subclasses provide a provider pointed at a directory pre-populated with:
 *   Rust Servers, Services, and Apps/
 *     chapter1.pdf  ("chapter1 content")
 *   Manning Books/
 *     Rust Servers, Services, and Apps/
 *       chapter2.pdf  ("chapter2 content")
 */
abstract class AbstractDataProviderCommaTest {

    protected abstract DataProviderInterface getProvider();

    @Test
    void getFolders_rootLevel_returnsCommaFolderAndManningBooks() {
        List<Folder> folders = getProvider().getFolders(null);
        assertTrue(folders.stream().anyMatch(f -> "Rust Servers, Services, and Apps".equals(f.getName())),
            "Should return root-level folder with comma in name");
        assertTrue(folders.stream().anyMatch(f -> "Manning Books".equals(f.getName())),
            "Should return Manning Books folder");
    }

    @Test
    void getItems_viaSlashPath_returnsChapter1() {
        List<Item> items = getProvider().getItems("Rust Servers, Services, and Apps");
        assertTrue(items.stream().anyMatch(i -> "chapter1.pdf".equals(i.getName())),
            "Should return chapter1.pdf when using slash-path with comma folder name");
    }

    @Test
    void getItems_viaNestedSlashPath_returnsChapter2() {
        List<Item> items = getProvider().getItems("Manning Books/Rust Servers, Services, and Apps");
        assertTrue(items.stream().anyMatch(i -> "chapter2.pdf".equals(i.getName())),
            "Should return chapter2.pdf in nested comma folder");
    }

    @Test
    void getInputStream_rootCommaFolder_opensCorrectly() {
        Item item = new Item();
        item.setPath("Rust Servers, Services, and Apps");
        item.setName("chapter1.pdf");

        assertDoesNotThrow(() -> {
            try (InputStream is = getProvider().getInputStream(item)) {
                byte[] bytes = is.readAllBytes();
                assertArrayEquals("chapter1 content".getBytes(), bytes);
            }
        }, "getInputStream should open file in root-level comma folder");
    }

    @Test
    void getInputStream_nestedCommaFolder_opensCorrectly() {
        Item item = new Item();
        item.setPath("Manning Books/Rust Servers, Services, and Apps");
        item.setName("chapter2.pdf");

        assertDoesNotThrow(() -> {
            try (InputStream is = getProvider().getInputStream(item)) {
                byte[] bytes = is.readAllBytes();
                assertArrayEquals("chapter2 content".getBytes(), bytes);
            }
        }, "getInputStream should open file in nested comma folder");
    }
}
