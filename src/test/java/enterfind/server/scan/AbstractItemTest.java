package enterfind.server.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AbstractItemTest {

    // ---- getFullPath ----

    @Test
    void getFullPath_nameWithComma_nullPath() {
        Item item = new Item();
        item.setPath(null);
        item.setName("Rust Servers, Services, and Apps");
        assertEquals("Rust Servers, Services, and Apps", item.getFullPath());
    }

    @Test
    void getFullPath_nameWithComma_nonNullPath() {
        Item item = new Item();
        item.setPath("Manning Books");
        item.setName("Rust Servers, Services, and Apps");
        assertEquals("Manning Books/Rust Servers, Services, and Apps", item.getFullPath());
    }

    @Test
    void getFullPath_nameWithoutComma() {
        Item item = new Item();
        item.setPath("books");
        item.setName("Docker in Action");
        assertEquals("books/Docker in Action", item.getFullPath());
    }

    @Test
    void getFullPath_emptyPath() {
        Item item = new Item();
        item.setPath("");
        item.setName("readme.txt");
        assertEquals("readme.txt", item.getFullPath());
    }

    // ---- Item.getFolder ----

    @Test
    void item_getFolder_returnsPathUnchanged() {
        Item item = new Item();
        item.setPath("Manning Books/Rust Servers, Services, and Apps");
        assertEquals("Manning Books/Rust Servers, Services, and Apps", item.getFolder());
    }

    @Test
    void item_getFolder_nullPathReturnsNull() {
        Item item = new Item();
        item.setPath(null);
        assertNull(item.getFolder());
    }

    // ---- Folder.getFolder ----

    @Test
    void folder_getFolder_returnsPathUnchanged() {
        Folder folder = new Folder();
        folder.setPath("Manning Books/Rust Servers, Services, and Apps");
        assertEquals("Manning Books/Rust Servers, Services, and Apps", folder.getFolder());
    }

    @Test
    void folder_getFolder_nullPathReturnsNull() {
        Folder folder = new Folder();
        folder.setPath(null);
        assertNull(folder.getFolder());
    }
}
