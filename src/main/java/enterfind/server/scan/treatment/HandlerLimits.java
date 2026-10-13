package enterfind.server.scan.treatment;

/**
 * Shared file size limits for treatment handlers to prevent memory issues with very large files.
 */
public final class HandlerLimits {

    private HandlerLimits() {}

    public static final long MAX_IMAGE_SIZE_BYTES = 25 * 1024 * 1024;  // 25MB
    public static final long MAX_PDF_SIZE_BYTES = 25 * 1024 * 1024;    // 25MB

    public static final int MAX_OCR_CONTENT_LENGTH = 20 * 1024 * 1024; // 20MB
    public static final int OCR_TIMEOUT_SECONDS = 120;
    public static final int MIN_TEXT_CHARS_FOR_TRUE_PDF = 100;
}
