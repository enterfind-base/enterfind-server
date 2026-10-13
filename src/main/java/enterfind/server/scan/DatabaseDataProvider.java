package enterfind.server.scan;

import java.io.InputStream;
import java.util.List;

import enterfind.server.dao.ItemDao;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.SystemIssueException;

public class DatabaseDataProvider implements DataProviderInterface {
    
    private final ItemDao itemDao;
    private String datasourceId;

    public DatabaseDataProvider(ItemDao itemDao) {
        this.itemDao = itemDao;
    }

    /**
     * Set the datasource ID for this provider instance
     */
    public void setDatasourceId(String datasourceId) {
        this.datasourceId = datasourceId;
    }

    @Override
    public List<Item> getItems(String path) {
        if (datasourceId == null) {
            throw new InvalidArgumentException("Datasource ID must be set before querying items");
        }
        return itemDao.findItemsByDatasourceAndPath(datasourceId, path);
    }

    @Override
    public List<Folder> getFolders(String path) {
        if (datasourceId == null) {
            throw new InvalidArgumentException("Datasource ID must be set before querying folders");
        }
        return itemDao.findFoldersByDatasourceAndPath(datasourceId, path);
    }

    @Override
    public InputStream getInputStream(Item item) {
        // DatabaseDataProvider doesn't provide actual file content streams
        // This method is used by other data providers that access actual files
        throw new SystemIssueException("DatabaseDataProvider cannot provide file content streams");
    }

    @Override
    public void writeFile(Item item, InputStream data) {
        throw new SystemIssueException("DatabaseDataProvider cannot write files");
    }

    @Override
    public void deleteFile(Item item) {
        throw new SystemIssueException("DatabaseDataProvider cannot delete files");
    }

    @Override
    public void shutdown() {
        // Database data provider doesn't maintain external connections or resources
        // No cleanup needed on shutdown
    }
    
}
