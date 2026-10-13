package enterfind.server.scan;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.ItemPreviewDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.util.ProfileHelper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class Scanner {

    private static final Logger logger = LoggerFactory.getLogger(Scanner.class);
    private static final int SCANNER_PARALLELISM = Math.max(1, Runtime.getRuntime().availableProcessors() - 2); // Number of parallel subfolder scans

    private final ItemDao itemDao;
    private final TreatmentDao treatmentDao;
    private final DataSourceDao dataSourceDao;
    private final TreatmentProcessor treatmentProcessor;
    private final ItemPreviewDao itemPreviewDao;
    private final PreviewGenerator previewGenerator;
    private final ProfileHelper profileHelper;
    private final ForkJoinPool forkJoinPool;

    public Scanner(ItemDao itemDao, TreatmentDao treatmentDao, DataSourceDao dataSourceDao, TreatmentProcessor treatmentProcessor, ItemPreviewDao itemPreviewDao, PreviewGenerator previewGenerator, ProfileHelper profileHelper) {
        this.itemDao = itemDao;
        this.treatmentDao = treatmentDao;
        this.dataSourceDao = dataSourceDao;
        this.treatmentProcessor = treatmentProcessor;
        this.itemPreviewDao = itemPreviewDao;
        this.previewGenerator = previewGenerator;
        this.profileHelper = profileHelper;
        // ForkJoinPool with work-stealing prevents deadlock in recursive tasks
        this.forkJoinPool = new ForkJoinPool(SCANNER_PARALLELISM);
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down Scanner ForkJoinPool...");
        forkJoinPool.shutdown();
        try {
            if (!forkJoinPool.awaitTermination(60, TimeUnit.SECONDS)) {
                logger.warn("ForkJoinPool did not terminate in time, forcing shutdown...");
                forkJoinPool.shutdownNow();
                if (!forkJoinPool.awaitTermination(60, TimeUnit.SECONDS)) {
                    logger.error("ForkJoinPool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            logger.error("ForkJoinPool shutdown interrupted", e);
            forkJoinPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void scan(DataSourceInterface dataSource) {

        String datasourceId = dataSource.getType().extractDatasourceId(dataSource);
        logger.info("Starting scan for datasource: {} (id: {})", dataSource.getName(), datasourceId);

        try {
            // Set status to SCANNING at start
            dataSourceDao.updateStatus(datasourceId, DataSourceStatus.SCANNING);
            logger.info("Set datasource status to SCANNING: {} (id: {})", dataSource.getName(), datasourceId);

            DatabaseDataProvider databaseDataProvider = new DatabaseDataProvider(itemDao);
            databaseDataProvider.setDatasourceId(datasourceId);

            // Create appropriate data provider based on datasource type
            DataProviderInterface sourceDataProvider = dataSource.getType().getDataProviderForTypeAndSource(dataSource);

            // Fetch all treatments for this datasource once at the beginning of scan
            List<DataSourceTreatment> datasourceTreatments = treatmentDao.getTreatmentsByDataSource(datasourceId);

            // Start recursive scanning from the root using ForkJoinPool
            forkJoinPool.invoke(new ScanTask(sourceDataProvider, databaseDataProvider, null, datasourceId, datasourceTreatments, dataSource.getSkipFiles()));

            // Clean up any orphaned data (folders/items whose parent no longer exists)
            cleanupOrphanedData(datasourceId);

            // Update last scanned timestamp
            dataSourceDao.updateLastScanned(datasourceId, LocalDateTime.now());

            // A failure below the root does not fail the scan - it is counted, exactly as a failed
            // treatment is counted at the item level. Log at WARN when the count is non-zero so the
            // log does not contradict the number the UI is showing, and so one grep finds it.
            int foldersWithProblems = itemDao.countFoldersWithProblems(datasourceId);
            if (foldersWithProblems > 0) {
                logger.warn("Scan completed for datasource: {} (id: {}) with {} folder(s) that could not be listed",
                    dataSource.getName(), datasourceId, foldersWithProblems);
            } else {
                logger.info("Scan completed successfully for datasource: {} (id: {})", dataSource.getName(), datasourceId);
            }
            
            
        } catch (Exception e) {
            logger.error("Error during scan for datasource: {} (id: {})", dataSource.getName(), datasourceId, e);
            throw e;
        } finally {
            // Always set status back to IDLE when done (whether successful or failed)
            dataSourceDao.updateStatus(datasourceId, DataSourceStatus.IDLE);
            logger.info("Set datasource status to IDLE: {} (id: {})", dataSource.getName(), datasourceId);
        }
    }

    /**
     * Recursively scan a directory and compare with database state
     */
    private void scanRecursively(DataProviderInterface sourceDataProvider, DatabaseDataProvider databaseDataProvider, Folder folder, String datasourceId, List<DataSourceTreatment> datasourceTreatments, String skipFiles) {
        // folder is null at the scan root, which has no FOLDERS row.
        String path = folder != null ? folder.getFullPath() : null;
        // Get current state from both providers
        try {
            List<Item> sourceItems = sourceDataProvider.getItems(path);
            List<Folder> sourceFolders = sourceDataProvider.getFolders(path);

            // Filter out items that should be skipped
            // Match patterns against both filename and full path
            sourceItems = sourceItems.stream()
                .filter(item -> !shouldSkipFile(item.getName(), item.getFullPath(), skipFiles))
                .collect(Collectors.toList());

            // Filter out folders that should be skipped (same patterns as items)
            // Filtered folders are treated as absent from the source: new ones are never
            // added, and previously-indexed ones are removed by processFolders / deletedFolder.
            sourceFolders = sourceFolders.stream()
                .filter(sourceFolder -> !shouldSkipFile(sourceFolder.getName(), sourceFolder.getFullPath(), skipFiles))
                .collect(Collectors.toList());
            
            List<Item> dbItems = databaseDataProvider.getItems(path);
            List<Folder> dbFolders = databaseDataProvider.getFolders(path);

            // Process items
            processItems(sourceItems, dbItems, datasourceId, datasourceTreatments, skipFiles);

            // Process folders
            processFolders(sourceFolders, dbFolders, datasourceId);

            // Recursively scan subfolders in parallel using ForkJoinPool
            // invokeAll() uses work-stealing and avoids deadlock
            if (!sourceFolders.isEmpty()) {
                List<ScanTask> tasks = sourceFolders.stream()
                    .map(subFolder -> new ScanTask(sourceDataProvider, databaseDataProvider,
                        subFolder, datasourceId, datasourceTreatments, skipFiles))
                    .collect(Collectors.toList());

                // Execute all subfolder scans in parallel
                // invokeAll works correctly whether called from within a ForkJoinPool task or not
                RecursiveAction.invokeAll(tasks);
            }

            // Listing succeeded, so any previously recorded problem for this folder is resolved.
            if (folder != null) {
                itemDao.clearFolderScanFailureCount(datasourceId, folder.getPath(), folder.getName());
            }

        } catch (Exception e) {
           logger.error("Error scanning path '{}' for datasource ID '{}': {}", path, datasourceId, e.getMessage(), e);
           if (folder == null) {
               // The scan root. Nothing at all was compared, created or deleted, so there is no
               // partial result worth protecting and no FOLDERS row to count against - let it out,
               // which skips cleanupOrphanedData and leaves date_last_scanned unstamped.
               throw new SystemIssueException(
                       "Failed to list the scan root for datasource ID '" + datasourceId + "'", e);
           }
           // A subtree failed. The rest of the scan stands; record the problem against this folder
           // so it is reported, and leave its already-indexed contents untouched so the next scan
           // retries rather than reading the failure as "this folder is now empty".
           itemDao.incrementFolderScanFailureCount(datasourceId, folder.getPath(), folder.getName());
        }
    }

    /**
     * RecursiveAction for parallel subfolder scanning with ForkJoinPool.
     * This approach prevents deadlock through work-stealing.
     */
    private class ScanTask extends RecursiveAction {
        private final DataProviderInterface sourceDataProvider;
        private final DatabaseDataProvider databaseDataProvider;
        private final Folder folder;
        private final String datasourceId;
        private final List<DataSourceTreatment> datasourceTreatments;
        private final String skipFiles;

        public ScanTask(DataProviderInterface sourceDataProvider,
                       DatabaseDataProvider databaseDataProvider,
                       Folder folder,
                       String datasourceId,
                       List<DataSourceTreatment> datasourceTreatments,
                       String skipFiles) {
            this.sourceDataProvider = sourceDataProvider;
            this.databaseDataProvider = databaseDataProvider;
            this.folder = folder;
            this.datasourceId = datasourceId;
            this.datasourceTreatments = datasourceTreatments;
            this.skipFiles = skipFiles;
        }

        @Override
        protected void compute() {
            try {
                scanRecursively(sourceDataProvider, databaseDataProvider, folder, datasourceId, datasourceTreatments, skipFiles);
            } catch (Exception e) {
                String path = folder != null ? folder.getFullPath() : null;
                logger.error("Error in parallel subfolder scan for path '{}', datasource ID '{}': {}",
                    path, datasourceId, e.getMessage(), e);
                if (folder == null) {
                    // Root failure is not swallowed here either - see scanRecursively. Propagating
                    // out of compute() makes invokeAll/invoke rethrow it to scan().
                    throw e;
                }
            }
        }
    }

    /**
     * Check if a file should be skipped based on skipFiles regex patterns.
     * Patterns are matched against both the filename AND the full path,
     * allowing patterns to match path fragments (e.g., skip files in specific folders).
     *
     * @param filename The name of the file to check (e.g., ".DS_Store", "~temp.doc")
     * @param fullPath The full path of the file (e.g., "/data/archive/file.txt")
     * @param skipFiles Comma-separated regex patterns (e.g., "^~.*,.*archive.*")
     * @return true if the filename or fullPath matches any pattern and should be skipped
     */
    private boolean shouldSkipFile(String filename, String fullPath, String skipFiles) {
        if (skipFiles == null || skipFiles.trim().isEmpty()) {
            return false;
        }
        String[] patterns = skipFiles.split(",");
        for (String pattern : patterns) {
            pattern = pattern.trim();
            if (!pattern.isEmpty()) {
                try {
                    // Match against filename OR full path
                    if (Pattern.matches(pattern, filename) || Pattern.matches(pattern, fullPath)) {
                        return true;
                    }
                } catch (Exception e) {
                    logger.warn("Invalid regex pattern '{}': {}", pattern, e.getMessage());
                }
            }
        }
        return false;
    }

    /**
     * Compare source items with database items and handle changes
     */
    private void processItems(List<Item> sourceItems, List<Item> dbItems, String datasourceId, List<DataSourceTreatment> datasourceTreatments, String skipFiles) {

        // Create maps for efficient lookup
        Map<String, Item> sourceMap = sourceItems.stream()
            .collect(Collectors.toMap(Item::getFullPath, item -> item));
        Map<String, Item> dbMap = dbItems.stream()
            .collect(Collectors.toMap(Item::getFullPath, item -> item));

        Set<String> sourceFullPaths = sourceMap.keySet();
        Set<String> dbFullPaths = dbMap.keySet();

        // Find new items (in source but not in database)
        for (String fullPath : sourceFullPaths) {
            if (!dbFullPaths.contains(fullPath)) {
                Item newItem = sourceMap.get(fullPath);
                newItem.setDatasourceId(datasourceId);
                newItem(newItem, datasourceTreatments);
            }
        }

        // Find updated items (in both but different)
        for (String fullPath : sourceFullPaths) {
            if (dbFullPaths.contains(fullPath)) {
                Item sourceItem = sourceMap.get(fullPath);
                Item dbItem = dbMap.get(fullPath);
                if (isItemUpdated(sourceItem, dbItem)) {
                    sourceItem.setId(dbItem.getId()); // Keep the database ID
                    sourceItem.setDatasourceId(datasourceId);
                    updatedItem(sourceItem, datasourceTreatments);
                } else {
                    // File content hasn't changed, but check if MIME type needs updating
                    // This allows MIME type metadata to be updated without triggering expensive reprocessing
                    if (!java.util.Objects.equals(sourceItem.getMimeType(), dbItem.getMimeType())) {
                        sourceItem.setId(dbItem.getId());
                        sourceItem.setDatasourceId(datasourceId);
                        itemDao.updateItem(sourceItem);
                        logger.info("Updated MIME type only for item {}: {} -> {}",
                            sourceItem.getFullPath(), dbItem.getMimeType(), sourceItem.getMimeType());
                    }
                }
            }
        }

        // Find deleted or skipped items (in database but not in source)
        Set<String> deletedFullPaths = new HashSet<>();
        Set<String> skippedFullPaths = new HashSet<>();
        for (String fullPath : dbFullPaths) {
            if (!sourceFullPaths.contains(fullPath)) {
                Item dbItem = dbMap.get(fullPath);
                // Match skipFiles patterns against both filename and full path
                if (shouldSkipFile(dbItem.getName(), dbItem.getFullPath(), skipFiles)) {
                    // Item is now skipped, delete the item entirely
                    deletedItem(dbItem);
                    skippedFullPaths.add(fullPath);
                    logger.info("Skipped item (item deleted): {}", fullPath);
                } else {
                    // Item is actually deleted
                    deletedItem(dbItem);
                    deletedFullPaths.add(fullPath);
                }
            }
        }

        // OPTIMIZED: Batch load all treatments for items in this folder
        // Collect all item IDs (excluding deleted and skipped ones)
        List<String> itemIds = dbItems.stream()
            .filter(item -> !deletedFullPaths.contains(item.getFullPath()) && !skippedFullPaths.contains(item.getFullPath()))
            .map(Item::getId)
            .collect(Collectors.toList());

        // Single batch query instead of N individual queries (with automatic chunking for large folders)
        Map<String, List<ItemTreatment>> itemTreatmentsMap =
            treatmentDao.getItemTreatmentsByItemBatch(itemIds);

        // For all items in db (excluding deleted and skipped ones), check for new treatments and create missing item_treatments with status NEW
        for (Item dbItem : dbItems) {
            // Skip items that were just deleted or skipped
            if (deletedFullPaths.contains(dbItem.getFullPath()) || skippedFullPaths.contains(dbItem.getFullPath())) {
                continue;
            }

            // Use pre-loaded treatments from batch query
            List<ItemTreatment> existingTreatments =
                itemTreatmentsMap.getOrDefault(dbItem.getId(), Collections.emptyList());

            Set<String> existingTreatmentIds = existingTreatments.stream()
                .map(it -> it.getDatasourceTreatmentId())
                .collect(Collectors.toSet());

            for (DataSourceTreatment treatment : datasourceTreatments) {
                if (!existingTreatmentIds.contains(treatment.getId())
                        && treatment.getTreatment().canProcessItem(dbItem)) {
                    // Create missing item_treatment with status NEW
                    treatmentDao.addItemTreatment(treatment.getId(), dbItem.getId(), ItemTreatmentStatus.NEW);
                    logger.info("Created missing item treatment for item {} with treatment {}", dbItem.getFullPath(), treatment.getTreatment());
                } else {
                    logger.info("No new item treatment needed for item {} with treatment {} (existing treatments: {})",
                                dbItem.getFullPath(), treatment.getTreatment(), existingTreatmentIds);  
                }
            }
        }
        
    }

    /**
     * Compare source folders with database folders and handle changes
     */
    private void processFolders(List<Folder> sourceFolders, List<Folder> dbFolders, String datasourceId) {
        // Create maps for efficient lookup
        Map<String, Folder> sourceMap = sourceFolders.stream()
            .collect(Collectors.toMap(Folder::getFullPath, folder -> folder));
        Map<String, Folder> dbMap = dbFolders.stream()
            .collect(Collectors.toMap(Folder::getFullPath, folder -> folder));

        Set<String> sourceFullPaths = sourceMap.keySet();
        Set<String> dbFullPaths = dbMap.keySet();

        // Find new folders (in source but not in database)
        for (String fullPath : sourceFullPaths) {
            if (!dbFullPaths.contains(fullPath)) {
                Folder newFolder = sourceMap.get(fullPath);
                newFolder.setDatasourceId(datasourceId);
                newFolder(newFolder);
            }
        }

        // Note: No folder updates - renamed folders are handled as delete + new

        // Find deleted folders (in database but not in source)
        for (String fullPath : dbFullPaths) {
            if (!sourceFullPaths.contains(fullPath)) {
                Folder deletedFolder = dbMap.get(fullPath);
                deletedFolder(deletedFolder);
            }
        }
    }

    /**
     * Check if an item has been updated by comparing key attributes
     */
    private boolean isItemUpdated(Item sourceItem, Item dbItem) {
        // Compare file size
        if (!java.util.Objects.equals(sourceItem.getSizeBytes(), dbItem.getSizeBytes())) {
            logger.info("Item updated (size changed): {}", sourceItem.getFullPath());
            return true;
        }

        // Compare modification time with tolerance of 1/100 second (10 ms)
        if (sourceItem.getTimeModified() != null && dbItem.getTimeModified() != null) {
            long srcMillis = sourceItem.getTimeModified().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            long dbMillis = dbItem.getTimeModified().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (Math.abs(srcMillis - dbMillis) > 10) {
                logger.info("item updated (time modified diff > 10ms) {} {} {}", sourceItem.getFullPath(), sourceItem.getTimeModified(), dbItem.getTimeModified());
                return true;
            }
        } else if ((sourceItem.getTimeModified() == null) != (dbItem.getTimeModified() == null)) {
            // One is null, the other is not
            logger.info("item updated (one time modified null) {}", sourceItem.getFullPath());
            return true;
        }
        // If both are null, do not consider updated

        // MIME type changes are excluded from update detection
        // MIME type is metadata that can be updated independently without triggering
        // expensive content reprocessing (DELETE+INSERT pattern with 10 index updates)
        // ContentTreatmentHandler will update MIME types during normal processing
        // if (!java.util.Objects.equals(sourceItem.getMimeType(), dbItem.getMimeType())) {
        //     logger.info("Item updated (MIME type changed): {}", sourceItem.getFullPath());
        //     return true;
        // }

        logger.info("Item not updated: {}", sourceItem.getFullPath());
        return false;
    }

    private boolean isAlphaProfile() {
        return profileHelper.isProfile("alpha");
    }

    private void newItem(Item item, List<DataSourceTreatment> datasourceTreatments) {
        logger.info("New item found: {}", item.getFullPath());
        Item createdItem = itemDao.createItem(item);
        createItemTreatments(createdItem, datasourceTreatments);
        // Create preview record with status NEW
        itemPreviewDao.createItemPreview(createdItem.getId());
    }

    private void newFolder(Folder folder) {
        logger.info("New folder found: {}", folder.getFullPath());
        itemDao.createFolder(folder);
    }

    private void updatedItem(Item item, List<DataSourceTreatment> datasourceTreatments) {
        logger.info("Updated item found: {}", item.getFullPath());
        itemDao.updateItem(item);
        updateItemTreatmentsForUpdatedItem(item, datasourceTreatments);
        // Mark preview as NEW if it exists, otherwise create it
        if (itemPreviewDao.existsByItemId(item.getId())) {
            itemPreviewDao.markAsNew(item.getId());
        } else {
            itemPreviewDao.createItemPreview(item.getId());
        }
    }

    private void deletedItem(Item item) {
        logger.info("Deleted item found: {}", item.getFullPath());
        itemDao.deleteItemById(item.getId());
    }

    private void deletedFolder(Folder folder) {
        logger.info("Deleted folder found: {}", folder.getFullPath());
        // Delete all items in this folder and all subdirectories
        itemDao.deleteItemsByDatasourceAndPath(folder.getDatasourceId(), folder.getFullPath());
        // Delete all subfolders
        itemDao.deleteFoldersByDatasourceAndPath(folder.getDatasourceId(), folder.getFullPath());
        // Then delete the folder itself
        itemDao.deleteFolderById(folder.getId());
    }

    /**
     * Clean up orphaned data - folders and items whose parent folder no longer exists.
     * This handles cases where a parent folder was deleted before the fix that recursively
     * deletes children, leaving orphaned child folders/items in the database.
     *
     * Orphaned data is detected by checking if each folder/item's parent path exists as a
     * valid folder in the database. If not, the folder/item is deleted along with all its children.
     */
    private void cleanupOrphanedData(String datasourceId) {
        logger.info("Checking for orphaned folders and items for datasource: {}", datasourceId);

        // Find and clean up orphaned folders
        List<Folder> orphanedFolders = itemDao.findOrphanedFolders(datasourceId);

        int totalDeletedFolders = 0;
        int totalDeletedItems = 0;

        for (Folder orphanedFolder : orphanedFolders) {
            String fullPath = orphanedFolder.getFullPath();
            logger.warn("Orphaned folder detected: {} (parent path '{}' doesn't exist)",
                       fullPath, orphanedFolder.getPath());

            // Delete all items in this orphaned folder and its subdirectories
            int deletedItems = itemDao.deleteItemsByDatasourceAndPath(datasourceId, fullPath);

            // Delete all subfolders under this orphaned folder
            int deletedSubfolders = itemDao.deleteFoldersByDatasourceAndPath(datasourceId, fullPath);

            // Delete the orphaned folder itself
            itemDao.deleteFolderById(orphanedFolder.getId());

            totalDeletedFolders += (1 + deletedSubfolders);
            totalDeletedItems += deletedItems;

            logger.info("Cleaned up orphaned folder '{}': deleted {} items and {} subfolders",
                       fullPath, deletedItems, deletedSubfolders);
        }

        // Find and clean up orphaned items (items whose parent folder doesn't exist)
        List<String> orphanedItemIds = itemDao.findOrphanedItemIds(datasourceId);

        for (String itemId : orphanedItemIds) {
            itemDao.deleteItemById(itemId);
            totalDeletedItems++;
        }

        if (!orphanedItemIds.isEmpty()) {
            logger.info("Cleaned up {} orphaned items (parent folders don't exist)", orphanedItemIds.size());
        }

        if (totalDeletedFolders > 0 || totalDeletedItems > 0) {
            logger.info("Orphan cleanup completed: removed {} folders and {} items",
                       totalDeletedFolders, totalDeletedItems);
        } else {
            logger.info("No orphaned data found");
        }
    }

    /**
     * Create ItemTreatment records for a new item based on all active treatments for its datasource
     */
    private void createItemTreatments(Item item, List<DataSourceTreatment> activeTreatments) {
        try {
            // Create item treatment records for each active treatment
            for (DataSourceTreatment treatment : activeTreatments) {
                if (!treatment.getTreatment().canProcessItem(item)) {
                    logger.info("Skipping item treatment for item {} with treatment {} (not applicable for this file type)",
                                item.getFullPath(), treatment.getTreatment());
                    continue;
                }
                treatmentDao.addItemTreatment(treatment.getId(), item.getId(), ItemTreatmentStatus.NEW);
                logger.info("Created item treatment for item {} with treatment {}",
                            item.getFullPath(), treatment.getTreatment());
            }
        } catch (Exception e) {
            logger.error("Error creating item treatments for item {}: {}", item.getFullPath(), e.getMessage(), e);
        }
    }

    /**
     * Update ItemTreatment status for an updated item.
     * When an item is updated, we mark existing treatment records as UPDATED so they can be reprocessed.
     * If no records can be updated (due to status), we create new ones.
     */
    private void updateItemTreatmentsForUpdatedItem(Item item, List<DataSourceTreatment> activeTreatments) {
        try {
            // Get all existing item treatments for this item
            List<enterfind.server.model.ItemTreatment> existingTreatments = treatmentDao.getItemTreatmentsByItem(item.getId());

            // For each active treatment, try to update existing record or create new one
            for (DataSourceTreatment treatment : activeTreatments) {
                // Find existing treatment record for this treatment
                enterfind.server.model.ItemTreatment existingTreatment = existingTreatments.stream()
                    .filter(it -> it.getDatasourceTreatmentId().equals(treatment.getId()))
                    .findFirst()
                    .orElse(null);

                if (existingTreatment != null) {
                    // Only update existing record if it's in DONE, NEW, or FAILED status
                    // Skip treatments that are INPROGRESS (leave them unchanged)
                    if (existingTreatment.getStatus() == ItemTreatmentStatus.DONE ||
                        existingTreatment.getStatus() == ItemTreatmentStatus.NEW ||
                        existingTreatment.getStatus() == ItemTreatmentStatus.FAILED) {
                        boolean updated = treatmentDao.updateItemTreatmentStatus(
                            existingTreatment.getId(),
                            ItemTreatmentStatus.UPDATED,
                            existingTreatment.getVersion()
                        ).isPresent();
                        if (updated) {
                            logger.info("Updated item treatment status to UPDATED for item {}", item.getFullPath());
                        } else {
                            logger.error("Optimistic lock failed for item treatment {} (version {})", existingTreatment.getId(), existingTreatment.getVersion());
                            throw new IllegalStateException("Failed to update item treatment due to version mismatch");
                        }
                    } else {
                        // Skip treatments in INPROGRESS or other non-updatable statuses
                        logger.info("Skipped item treatment for item {} with treatment {} (existing status: {})",
                                    item.getFullPath(), treatment.getTreatment(), existingTreatment.getStatus());
                    }
                } else {
                    // No existing record, create new item treatment
                    treatmentDao.addItemTreatment(treatment.getId(), item.getId(), ItemTreatmentStatus.NEW);
                    logger.info("Created new item treatment for updated item {} with treatment {}",
                                item.getFullPath(), treatment.getTreatment());
                }
            }
        } catch (Exception e) {
            logger.error("Error updating item treatments for item {}: {}", item.getFullPath(), e.getMessage(), e);
        }
    }

    /**
     * Asynchronously scan a data source.
     * This method returns immediately while the scan runs in the background.
     * Status management and timestamp updates are handled by scan().
     */
    @Async
    public void scanAsync(DataSourceInterface dataSource) {
        logger.info("Starting async scan for datasource: {}", dataSource.getName());

        try {
            scan(dataSource);

            // Process treatments immediately after scan only in alpha profile
            // (In other profiles, the TreatmentProcessorScheduler handles this)
            if (isAlphaProfile()) {
                logger.info("Processing treatments for datasource: {} (alpha profile)", dataSource.getName());
                int totalProcessed = 0;
                int batchProcessed;
                do {
                    batchProcessed = treatmentProcessor.processTreatments();
                    totalProcessed += batchProcessed;
                } while (batchProcessed > 0);
                logger.info("Treatment processing completed for datasource: {} ({} treatments)", dataSource.getName(), totalProcessed);
            }
        } catch (Exception e) {
            logger.error("Error during async scan for datasource: {}", dataSource.getName(), e);
        }
    }

    @PostConstruct
    public void postCreate() {
        // In alpha environment, generate previews immediately after startup
        if (isAlphaProfile()) {
            logger.info("Alpha environment: generating all previews immediately after startup");
            int totalGenerated = 0;
            int batchGenerated;
            do {
                batchGenerated = previewGenerator.generatePreviews();
                totalGenerated += batchGenerated;
            } while (batchGenerated > 0);

            if (totalGenerated > 0) {
                logger.info("Generated {} previews at startup", totalGenerated);
            } else {
                logger.info("No previews to generate at startup");
            }
        }
    }
}
