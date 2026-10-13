package enterfind.server.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import enterfind.server.dao.ItemDao;
import enterfind.server.dao.ItemPreviewDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.util.MimeTypeDetector;

/**
 * Service encapsulating storage-level create/delete operations for items.
 * Shared between UploadController (REST) and GraphQLController (GraphQL).
 */
@Service
public class ItemStorageService {

    private static final Logger logger = LoggerFactory.getLogger(ItemStorageService.class);

    private final ItemDao itemDao;
    private final TreatmentDao treatmentDao;
    private final ItemPreviewDao itemPreviewDao;

    public ItemStorageService(ItemDao itemDao, TreatmentDao treatmentDao, ItemPreviewDao itemPreviewDao) {
        this.itemDao = itemDao;
        this.treatmentDao = treatmentDao;
        this.itemPreviewDao = itemPreviewDao;
    }

    /**
     * Upload (create or overwrite) a file in a datasource.
     * Writes bytes to underlying storage and upserts the ITEM row + treatment queue.
     */
    public void uploadItem(DataSourceInterface dataSource, DataSourceType type,
                           String name, String folder,
                           long contentLength, InputStream content) throws IOException {
        String datasourceId = type.extractDatasourceId(dataSource);
        final Item item = new Item();
        item.setName(name);
        item.setPath(folder);
        item.setDatasourceId(datasourceId);
        item.setMimeType(MimeTypeDetector.detectMimeType(name));
        item.setTimeCreated(LocalDateTime.now());
        item.setTimeModified(LocalDateTime.now());
        if (contentLength > 0) {
            item.setSizeBytes(contentLength);
        }

        final java.util.Optional<Item> existing =
            itemDao.findItemByDatasourceAndPath(datasourceId, folder, name);

        final Item savedItem;
        if (existing.isPresent()) {
            item.setId(existing.get().getId());
            itemDao.updateItem(item);
            savedItem = item;

            List<DataSourceTreatment> treatments = treatmentDao.getTreatmentsByDataSource(datasourceId);
            List<ItemTreatment> existingTreatments = treatmentDao.getItemTreatmentsByItem(savedItem.getId());
            for (DataSourceTreatment treatment : treatments) {
                if (!treatment.getTreatment().canProcessItem(savedItem)) continue;
                ItemTreatment existingTreatment = existingTreatments.stream()
                    .filter(it -> it.getDatasourceTreatmentId().equals(treatment.getId()))
                    .findFirst().orElse(null);
                if (existingTreatment != null) {
                    if (existingTreatment.getStatus() == ItemTreatmentStatus.DONE ||
                        existingTreatment.getStatus() == ItemTreatmentStatus.NEW ||
                        existingTreatment.getStatus() == ItemTreatmentStatus.FAILED) {
                        treatmentDao.updateItemTreatmentStatus(
                            existingTreatment.getId(), ItemTreatmentStatus.UPDATED,
                            existingTreatment.getVersion());
                    }
                } else {
                    treatmentDao.addItemTreatment(treatment.getId(), savedItem.getId(), ItemTreatmentStatus.NEW);
                }
            }
            if (itemPreviewDao.existsByItemId(savedItem.getId())) {
                itemPreviewDao.markAsNew(savedItem.getId());
            } else {
                itemPreviewDao.createItemPreview(savedItem.getId());
            }
        } else {
            savedItem = itemDao.createItem(item);
            List<DataSourceTreatment> treatments = treatmentDao.getTreatmentsByDataSource(datasourceId);
            for (DataSourceTreatment treatment : treatments) {
                if (treatment.getTreatment().canProcessItem(savedItem)) {
                    treatmentDao.addItemTreatment(treatment.getId(), savedItem.getId(), ItemTreatmentStatus.NEW);
                }
            }
            itemPreviewDao.createItemPreview(savedItem.getId());
        }

        DataProviderInterface provider = type.getDataProviderForTypeAndSource(dataSource);
        provider.writeFile(savedItem, content);

        logger.info("Uploaded file: {} to datasource: {}", savedItem.getFullPath(), datasourceId);
        
    }

    /**
     * Delete a file from a datasource.
     * Removes bytes from underlying storage and deletes the ITEM row (cascades to treatments).
     *
     * @return true if an item row was found and deleted
     */
    public boolean deleteItem(DataSourceInterface dataSource, DataSourceType type,
                              String name, String folder) {
        String datasourceId = type.extractDatasourceId(dataSource);                  
        DataProviderInterface provider = type.getDataProviderForTypeAndSource(dataSource);
        Item storageItem = new Item();
        storageItem.setName(name);
        storageItem.setPath(folder);
        provider.deleteFile(storageItem);

        boolean deleted = itemDao.findItemByDatasourceAndPath(datasourceId, folder, name)
            .map(dbItem -> itemDao.deleteItemById(dbItem.getId()))
            .orElse(false);

        logger.info("Deleted item: {}/{} from datasource: {}", folder, name, datasourceId);
        return deleted;
    }
}
