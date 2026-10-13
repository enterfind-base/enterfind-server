package enterfind.server.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Utility class for detecting MIME types based on file extensions.
 * Provides reliable MIME type detection that works across all platforms,
 * unlike Files.probeContentType() which is unreliable in Docker/Linux environments.
 */
public class MimeTypeDetector {

    private static final Map<String, String> EXTENSION_TO_MIME_TYPE = new HashMap<>();

    static {
        // Documents
        EXTENSION_TO_MIME_TYPE.put("pdf", "application/pdf");
        EXTENSION_TO_MIME_TYPE.put("doc", "application/msword");
        EXTENSION_TO_MIME_TYPE.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        EXTENSION_TO_MIME_TYPE.put("xls", "application/vnd.ms-excel");
        EXTENSION_TO_MIME_TYPE.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        EXTENSION_TO_MIME_TYPE.put("ppt", "application/vnd.ms-powerpoint");
        EXTENSION_TO_MIME_TYPE.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        EXTENSION_TO_MIME_TYPE.put("odt", "application/vnd.oasis.opendocument.text");
        EXTENSION_TO_MIME_TYPE.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        EXTENSION_TO_MIME_TYPE.put("odp", "application/vnd.oasis.opendocument.presentation");
        EXTENSION_TO_MIME_TYPE.put("rtf", "application/rtf");
        EXTENSION_TO_MIME_TYPE.put("txt", "text/plain");
        EXTENSION_TO_MIME_TYPE.put("csv", "text/csv");

        // Images
        EXTENSION_TO_MIME_TYPE.put("jpg", "image/jpeg");
        EXTENSION_TO_MIME_TYPE.put("jpeg", "image/jpeg");
        EXTENSION_TO_MIME_TYPE.put("png", "image/png");
        EXTENSION_TO_MIME_TYPE.put("gif", "image/gif");
        EXTENSION_TO_MIME_TYPE.put("bmp", "image/bmp");
        EXTENSION_TO_MIME_TYPE.put("webp", "image/webp");
        EXTENSION_TO_MIME_TYPE.put("svg", "image/svg+xml");
        EXTENSION_TO_MIME_TYPE.put("ico", "image/x-icon");
        EXTENSION_TO_MIME_TYPE.put("tif", "image/tiff");
        EXTENSION_TO_MIME_TYPE.put("tiff", "image/tiff");
        EXTENSION_TO_MIME_TYPE.put("heic", "image/heic");
        EXTENSION_TO_MIME_TYPE.put("heif", "image/heif");

        // Audio
        EXTENSION_TO_MIME_TYPE.put("mp3", "audio/mpeg");
        EXTENSION_TO_MIME_TYPE.put("wav", "audio/wav");
        EXTENSION_TO_MIME_TYPE.put("ogg", "audio/ogg");
        EXTENSION_TO_MIME_TYPE.put("m4a", "audio/mp4");
        EXTENSION_TO_MIME_TYPE.put("flac", "audio/flac");
        EXTENSION_TO_MIME_TYPE.put("aac", "audio/aac");
        EXTENSION_TO_MIME_TYPE.put("wma", "audio/x-ms-wma");

        // Video
        EXTENSION_TO_MIME_TYPE.put("mp4", "video/mp4");
        EXTENSION_TO_MIME_TYPE.put("avi", "video/x-msvideo");
        EXTENSION_TO_MIME_TYPE.put("mov", "video/quicktime");
        EXTENSION_TO_MIME_TYPE.put("wmv", "video/x-ms-wmv");
        EXTENSION_TO_MIME_TYPE.put("flv", "video/x-flv");
        EXTENSION_TO_MIME_TYPE.put("mkv", "video/x-matroska");
        EXTENSION_TO_MIME_TYPE.put("webm", "video/webm");
        EXTENSION_TO_MIME_TYPE.put("m4v", "video/x-m4v");

        // Archives
        EXTENSION_TO_MIME_TYPE.put("zip", "application/zip");
        EXTENSION_TO_MIME_TYPE.put("rar", "application/vnd.rar");
        EXTENSION_TO_MIME_TYPE.put("7z", "application/x-7z-compressed");
        EXTENSION_TO_MIME_TYPE.put("tar", "application/x-tar");
        EXTENSION_TO_MIME_TYPE.put("gz", "application/gzip");
        EXTENSION_TO_MIME_TYPE.put("bz2", "application/x-bzip2");

        // Code/Text
        EXTENSION_TO_MIME_TYPE.put("html", "text/html");
        EXTENSION_TO_MIME_TYPE.put("htm", "text/html");
        EXTENSION_TO_MIME_TYPE.put("xml", "application/xml");
        EXTENSION_TO_MIME_TYPE.put("json", "application/json");
        EXTENSION_TO_MIME_TYPE.put("js", "application/javascript");
        EXTENSION_TO_MIME_TYPE.put("css", "text/css");
        EXTENSION_TO_MIME_TYPE.put("java", "text/x-java-source");
        EXTENSION_TO_MIME_TYPE.put("py", "text/x-python");
        EXTENSION_TO_MIME_TYPE.put("c", "text/x-c");
        EXTENSION_TO_MIME_TYPE.put("cpp", "text/x-c++");
        EXTENSION_TO_MIME_TYPE.put("h", "text/x-c");
        EXTENSION_TO_MIME_TYPE.put("sh", "application/x-sh");
        EXTENSION_TO_MIME_TYPE.put("sql", "application/sql");
        EXTENSION_TO_MIME_TYPE.put("md", "text/markdown");
        EXTENSION_TO_MIME_TYPE.put("yaml", "application/x-yaml");
        EXTENSION_TO_MIME_TYPE.put("yml", "application/x-yaml");

        // Other common formats
        EXTENSION_TO_MIME_TYPE.put("eml", "message/rfc822");
        EXTENSION_TO_MIME_TYPE.put("msg", "application/vnd.ms-outlook");
        EXTENSION_TO_MIME_TYPE.put("ics", "text/calendar");
        EXTENSION_TO_MIME_TYPE.put("vcf", "text/vcard");
    }

    /**
     * Detect MIME type based on filename.
     * Returns null if the extension is not recognized.
     *
     * @param filename The name of the file
     * @return The MIME type, or null if unknown
     */
    public static String detectMimeType(String filename) {
        if (filename == null || filename.isEmpty()) {
            return null;
        }

        // Extract extension
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            // No extension or extension is empty
            return null;
        }

        String extension = filename.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
        return EXTENSION_TO_MIME_TYPE.get(extension);
    }
}
