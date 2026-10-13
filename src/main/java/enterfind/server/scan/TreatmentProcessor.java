package enterfind.server.scan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.dao.UserSettingsDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.ItemTreatmentWithDetails;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.treatment.FaceEmbeddingModelService;
import enterfind.server.util.NamedLockRegistry;

@Component
public class TreatmentProcessor {


    public TreatmentProcessor(TreatmentDao treatmentDao, DataSourceDao dataSourceDao,
            UserSettingsDao userSettingsDao, FaceEmbeddingModelService faceEmbeddingModelService,
            NamedLockRegistry namedLockRegistry) {
        this.treatmentDao = treatmentDao;
        this.dataSourceDao = dataSourceDao;
        this.userSettingsDao = userSettingsDao;
        this.faceEmbeddingModelService = faceEmbeddingModelService;
        this.namedLockRegistry = namedLockRegistry;
    }

    private final TreatmentDao treatmentDao;
    private final DataSourceDao dataSourceDao;
    private final UserSettingsDao userSettingsDao;
    private final FaceEmbeddingModelService faceEmbeddingModelService;
    private final NamedLockRegistry namedLockRegistry;

    private static final Logger logger = LoggerFactory.getLogger(TreatmentProcessor.class);

    /**
     * Process a batch of treatments ready for processing.
     * Phase 1: Claims items in a short transaction (SELECT FOR UPDATE + mark INPROGRESS)
     * Phase 2: Processes each item outside transaction (slow OCR operations)
     * Phase 3: Saves results in individual short transactions per item
     *
     * This approach prevents long-running transactions that hold database locks
     * during slow processing operations like OCR.
     *
     * Batch size is dynamically adjusted based on current memory usage to prevent
     * out-of-memory errors during high memory pressure.
     *
     * @return the number of records processed in this batch
     */
    public int processTreatments() {
        int maxFailureCount = Integer.parseInt(
            userSettingsDao.getSetting(UserSettingName.TREATMENT_MAX_FAILURE_COUNT)
                .orElse("4")
        );

        // Whether a usable embedding model exists. Computed once per claim so the claim query can
        // gate FACE_RECOGNITION rows via a bind parameter (SQL cannot read the SYSTEM_SETTINGS mode):
        // with no model, recognition rows are never handed out and never stall as INPROGRESS.
        boolean embeddingModelReady = faceEmbeddingModelService.isReady();

        // PHASE 1: Claim a single item in a short transaction.
        // Claiming one at a time ensures only the item actually being processed is INPROGRESS,
        // so a server restart only penalizes the item that was mid-processing, not batch bystanders.
        // The scheduler's do-while loop drives throughput by calling processTreatments() repeatedly.
        List<ItemTreatmentWithDetails> claimedItems =
                treatmentDao.claimItemsForProcessing(1, maxFailureCount, embeddingModelReady);
        if (claimedItems.isEmpty()) {
            return 0;
        }

        // PHASE 2: Process items outside of transaction (no locks held)
        // Cache for data sources by data source ID to avoid redundant database lookups
        Map<String, DataSourceInterface> dataSourceCache = new HashMap<>();

        for (ItemTreatmentWithDetails itemTreatment : claimedItems) {
            Treatments treatment = itemTreatment.getTreatmentName();
            Item item = itemTreatment.getItem();
            String dataSourceId = itemTreatment.getDataSourceId();

            // Get or create data source for this data source ID
            DataSourceInterface dataSource = dataSourceCache.get(dataSourceId);
            if (dataSource == null) {
                dataSource = dataSourceDao.findById(dataSourceId).orElse(null);
                if (dataSource == null) {
                    logger.error("Data source not found for ID: {}, marking treatment as FAILED", dataSourceId);
                    treatmentDao.saveItemTreatmentResult(itemTreatment.getItemTreatmentId(),
                        itemTreatment.getVersion(), ItemTreatmentStatus.FAILED, null, false);
                    continue;
                }
                dataSourceCache.put(dataSourceId, dataSource);
            }

            // Get data provider (cached in DataSourceType)
            DataProviderInterface dataProvider = itemTreatment.getDataSourceType().getDataProviderForTypeAndSource(dataSource);

            // Any locks a handler acquired in apply() and handed back on its output are held until
            // this item is fully persisted, then released in the finally below. Because the save is
            // an external @Transactional call, control returns here only after it has committed (or
            // rolled back), so the release happens post-commit — which is what a grouping lock needs
            // to keep its read-decide-persist span serialized. Acquisition is the handler's (a domain
            // decision); release is the processor's (it owns the apply→save lifecycle). Empty for
            // every treatment that returns no locks, so this is a no-op for them.
            TreatmentOutput output = null;
            try {
                // Re-check canProcessItem in case the item no longer meets requirements
                // (e.g. size limit added after the item_treatment row was already created)
                if (!treatment.canProcessItem(item)) {
                    logger.info("Skipping treatment {} for item {} (canProcess returned false), marking DONE",
                        treatment, item.getFullPath());
                    // An item that has stopped qualifying (e.g. it grew past the size limit) may
                    // still carry geometry from when it did. The handler never runs on this path,
                    // so the clear has to be requested here or those rows outlive their facecount.
                    // Safe to ask for unconditionally: the clear is scoped to this item treatment,
                    // which for any treatment but FACE_DETECTION owns no face rows to begin with.
                    treatmentDao.saveItemTreatmentResult(itemTreatment.getItemTreatmentId(),
                        itemTreatment.getVersion(), ItemTreatmentStatus.DONE, null, false,
                        List.of(), List.of());
                    continue;
                }

                // This is the slow part (OCR, etc.) - happens outside transaction. A handler that
                // acquires a lock releases it itself if it throws here, so output stays null.
                output = treatment.applyTreatment(item, dataProvider, dataSource);

                // PHASE 3: Save results in a short transaction (via DAO to ensure @Transactional works)
                boolean deleteExistingResults = (itemTreatment.getStatus() == ItemTreatmentStatus.UPDATED);
                treatmentDao.saveTreatmentOutput(itemTreatment.getItemTreatmentId(),
                    itemTreatment.getVersion(), ItemTreatmentStatus.DONE, deleteExistingResults, output);

                // After a recognition item drains, carry human labels across a model change once the
                // whole drain completes. Cheap no-op when no label snapshot is pending.
                if (treatment == Treatments.FACE_RECOGNITION) {
                    faceEmbeddingModelService.finalizeCarryoverIfDrained();
                }

            } catch (UnsupportedOperationException e) {
                logger.warn("Unsupported operation for data source ID: {}", dataSourceId);
                // PHASE 3: Mark treatment as FAILED in a short transaction (via DAO to ensure @Transactional works)
                treatmentDao.saveItemTreatmentResult(itemTreatment.getItemTreatmentId(),
                    itemTreatment.getVersion(), ItemTreatmentStatus.FAILED, null, false);

            } catch (Throwable e) {
                logger.error("Error processing treatment for data source ID: {}", dataSourceId, e);
                // PHASE 3: Mark treatment as FAILED in a short transaction (via DAO to ensure @Transactional works)
                treatmentDao.saveItemTreatmentResult(itemTreatment.getItemTreatmentId(),
                    itemTreatment.getVersion(), ItemTreatmentStatus.FAILED, null, false);

            } finally {
                if (output != null) {
                    for (String lockKey : output.locks()) {
                        namedLockRegistry.unlock(lockKey);
                    }
                }
            }
        }
        return claimedItems.size();
    }
}
