package enterfind.server.scan;

import java.io.InputStream;
import java.util.List;

public interface DataProviderInterface {
    public List<Item> getItems(String path);
    public List<Folder> getFolders(String path);
    public InputStream getInputStream(Item item);

    /**
     * Write the given data to the location represented by item (path + name).
     * Creates the file if it does not exist, or replaces it if it does.
     */
    public void writeFile(Item item, InputStream data);

    /**
     * Delete the file represented by item from underlying storage.
     */
    public void deleteFile(Item item);

    /**
     * Shutdown the data provider and clean up resources
     */
    public void shutdown();
}
