package enterfind.server.scan;

import enterfind.server.model.DataSourceInterface;
import enterfind.server.scan.treatment.ContentTreatmentHandler;
import enterfind.server.scan.treatment.FaceDetectionTreatmentHandler;
import enterfind.server.scan.treatment.FaceRecognitionTreatmentHandler;
import enterfind.server.scan.treatment.NameAndPathTreatmentHandler;
import enterfind.server.scan.treatment.ObjectDetectionTreatmentHandler;
import enterfind.server.scan.treatment.OcrTreatmentHandler;
import enterfind.server.scan.treatment.TreatmentHandler;
import enterfind.server.util.SpringContext;

/**
 * Enumeration of available treatments that can be applied to items.
 * Each treatment delegates to a dedicated handler bean for the actual processing.
 *
 * <p>The enum carries the handler's <em>class</em>, not an instance: handlers are Spring
 * {@code @Component} beans, and the enum resolves the current one from {@link SpringContext} at
 * call time. This keeps the enum out of the container's construction ordering — nothing is
 * instantiated when the enum class loads — so referencing a constant no longer depends on any bean
 * already existing.
 */
public enum Treatments {
    NAME_AND_PATH(
        NameAndPathTreatmentHandler.class,
        "Name and Path Indexing",
        "Indexes file names and paths for basic search"
    ),
    CONTENT(
        ContentTreatmentHandler.class,
        "Content Indexing",
        "Extracts and indexes text content from files"
    ),
    OCR(
        OcrTreatmentHandler.class,
        "OCR (Optical Character Recognition)",
        "Optical character recognition for scanned documents and images"
    ),
    OBJECT_DETECTION(
        ObjectDetectionTreatmentHandler.class,
        "Object Detection",
        "Detects and catalogs objects in images"
    ),
    FACE_DETECTION(
        FaceDetectionTreatmentHandler.class,
        "Face Detection",
        "Detects faces in images, with bounding boxes and landmarks"
    ),
    FACE_RECOGNITION(
        FaceRecognitionTreatmentHandler.class,
        "Face Recognition",
        "Groups detected faces into identities using an embedding model"
    );

    private final Class<? extends TreatmentHandler> handlerClass;
    private final String label;
    private final String description;

    Treatments(Class<? extends TreatmentHandler> handlerClass, String label, String description) {
        this.handlerClass = handlerClass;
        this.label = label;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Resolve the handler bean for this treatment from the current application context.
     * Not cached: see {@link SpringContext} for why per-call resolution is deliberate.
     */
    private TreatmentHandler handler() {
        return SpringContext.getBean(handlerClass);
    }

    /**
     * Apply this treatment to the given item.
     *
     * @param item the item to process
     * @param dataProvider the data provider to access file contents
     * @param dataSource the data source containing the item
     * @return the treatment's key-value results, plus any typed side data it owns
     */
    public TreatmentOutput applyTreatment(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource) {
        return handler().apply(item, dataProvider, dataSource, this.name());
    }

    /**
     * Returns true if this treatment should be applied to the given item.
     * Used at scan time to skip creating item_treatments rows for items the handler
     * would silently skip anyway (e.g. OCR skipping non-image/non-PDF files).
     */
    public boolean canProcessItem(Item item) {
        return handler().canProcess(item);
    }
}
