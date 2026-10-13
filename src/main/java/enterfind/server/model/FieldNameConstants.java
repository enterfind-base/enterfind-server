package enterfind.server.model;

import java.util.List;

/**
 * Constants for field names used throughout the application.
 * These field names are used as keys in facets, treatment results, and search conditions.
 */
public final class FieldNameConstants {
    
    private FieldNameConstants() {
        // Prevent instantiation
    }
    
    /** Field name for item name */
    public static final String FIELD_NAME = "name";
    
    /** Field name for item path */
    public static final String FIELD_PATH = "path";
    
    /** Field name for datasource name */
    public static final String FIELD_DATASOURCE_NAME = "datasourcename";

    /** Field name for datasource type */
    public static final String FIELD_DATASOURCE_TYPE = "datasourcetype";
    
    /** Field name for content type (MIME type extracted by Tika) */
    public static final String FIELD_CONTENT_TYPE = "content-type";
    
    /** Field name for extracted text content */
    public static final String FIELD_CONTENT = "content";
    
    /** Field name indicating content was truncated due to size limits */
    public static final String FIELD_CONTENT_TRUNCATED = "content_truncated";
    
    /** Field name for OCR extracted content */
    public static final String FIELD_OCR_CONTENT = "ocr-content";
    
    /** Field name for detected object class */
    public static final String FIELD_OBJECT_DETECTED = "objectdetected";

    /** Field name for object detection confidence score */
    public static final String FIELD_OBJECT_DETECTION_CONFIDENCE = "objectdetectionconfidence";

    /**
     * Field name for detected face group label. Written by the FACE_RECOGNITION treatment.
     */
    public static final String FIELD_DETECTED_FACE = "detectedface";

    /** Field name for face detection confidence score */
    public static final String FIELD_DETECTED_FACE_CONFIDENCE = "detectedfaceconfidence";

    /** Field name for the number of faces detected in an image */
    public static final String FIELD_FACE_COUNT = "facecount";

    /** Field name for treatment type identifier */
    public static final String FIELD_TREATMENT_TYPE = "treatmenttype";
    
    /**
     * Standard fields in priority order for UI display.
     * Frontend uses this list to determine field ordering and highlighting.
     * 
     * @return List of standard field names in priority order
     */
    public static List<String> getStandardFieldsInPriorityOrder() {
        return List.of(
            FIELD_PATH,                 // path
            FIELD_NAME,                 // name
            FIELD_DATASOURCE_NAME,      // datasourcename
            FIELD_DATASOURCE_TYPE,      // datasourcetype
            FIELD_CONTENT_TYPE,         // content-type
            FIELD_TREATMENT_TYPE,       // treatmenttype
            FIELD_DETECTED_FACE,        // detectedface
            FIELD_OBJECT_DETECTED       // objectdetected
        );
    }
}
