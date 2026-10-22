package enterfind.server.model;

public enum UserSettingName {
    // Known setting names
    CONCURRENT_TREATMENT_PROCESSORS("ConcurrentTreatmentProcessors"),
    CONCURRENT_OCR_HANDLERS("ConcurrentOCRHandlers"),
    CONCURRENT_OBJECT_DETECTION_HANDLERS("ConcurrentObjectDetectionHandlers"),
    CONCURRENT_FACE_DETECTION_HANDLERS("ConcurrentFaceDetectionHandlers"),
    // Concurrency cap for the FACE_RECOGNITION treatment. Its grouping
    // threshold is NOT here: it lives in SYSTEM_SETTINGS beside the embedding model whose cosine
    // scale it is derived from, since changing it has to regroup rather than merely take effect.
    CONCURRENT_FACE_RECOGNITION_HANDLERS("ConcurrentFaceRecognitionHandlers"),
    FACE_DETECTION_CONFIDENCE_THRESHOLD("FaceDetectionConfidenceThreshold"),
    // The floor a detection must clear to be *embedded*, deliberately above the detection floor
    // above: a face too degraded to identify still detects, still shows as a crop, and simply gets
    // no identity. On the detector's confidence scale, not the embedding model's — see
    // FaceRecognitionTreatmentHandler for why that is the stable half to calibrate against.
    FACE_RECOGNITION_MIN_CONFIDENCE("FaceRecognitionMinConfidence"),
    TREATMENT_MAX_FAILURE_COUNT("TreatmentMaxFailureCount"),

    // Authentication settings
    SESSION_DURATION_MINUTES("SessionDurationMinutes"),
    REMEMBER_ME_DURATION_DAYS("RememberMeDurationDays"),
    API_SESSION_DURATION_MINUTES("ApiSessionDurationMinutes"),

    // UI settings
    BANNER("Banner");

    private final String dbName;

    UserSettingName(String dbName) {
        this.dbName = dbName;
    }

    public String dbName() {
        return dbName;
    }

    public static UserSettingName fromString(String name) {
        for (UserSettingName s : values()) {
            if (s.dbName.equalsIgnoreCase(name) || s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown setting name: " + name);
    }

    @Override
    public String toString() {
        return dbName;
    }
}
