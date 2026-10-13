package enterfind.server.scan;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.ItemPreviewDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.ItemPreview;
import enterfind.server.model.ItemPreviewStatus;

/**
 * Background processor that generates preview thumbnails for items.
 * Similar to TreatmentProcessor but specifically for image/PDF previews.
 */
@Component
public class PreviewGenerator {

    private static final Logger logger = LoggerFactory.getLogger(PreviewGenerator.class);
    private static final int PREVIEW_WIDTH = 200;
    private static final int PREVIEW_HEIGHT = 200;
    private static final int BATCH_SIZE = 10;

    // File size limits to prevent memory issues with very large files
    private static final long MAX_IMAGE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB
    private static final long MAX_PDF_SIZE_BYTES = 100 * 1024 * 1024;  // 100MB
    
    private final ItemPreviewDao itemPreviewDao;
    private final ItemDao itemDao;
    private final DataSourceDao dataSourceDao;

    public PreviewGenerator(ItemPreviewDao itemPreviewDao, ItemDao itemDao, DataSourceDao dataSourceDao) {
        this.itemPreviewDao = itemPreviewDao;
        this.itemDao = itemDao;
        this.dataSourceDao = dataSourceDao;
    }

    /**
     * Generate previews for items with status NEW.
     * Returns the number of previews generated.
     */
    public int generatePreviews() {
        // Claim previews for processing
        List<ItemPreview> previews = itemPreviewDao.claimPreviewsForProcessing(BATCH_SIZE);
        
        if (previews.isEmpty()) {
            return 0;
        }

        logger.info("Processing {} previews", previews.size());

        for (ItemPreview preview : previews) {
            try {
                // Get item details
                Item item = itemDao.findItemById(preview.getItemId()).orElse(null);
                if (item == null) {
                    logger.warn("Item not found for preview: {}", preview.getItemId());
                    itemPreviewDao.savePreviewData(preview.getItemId(), preview.getVersion(), 
                                                   null, null, ItemPreviewStatus.FAILED);
                    continue;
                }

                // Get data source
                DataSourceInterface dataSource = dataSourceDao.findById(item.getDatasourceId()).orElse(null);
                if (dataSource == null) {
                    logger.warn("Data source not found for item: {}", item.getId());
                    itemPreviewDao.savePreviewData(preview.getItemId(), preview.getVersion(), 
                                                   null, null, ItemPreviewStatus.FAILED);
                    continue;
                }

                // Get data provider
                DataProviderInterface dataProvider = dataSource.getType()
                    .getDataProviderForTypeAndSource(dataSource);

                // Generate preview based on MIME type
                byte[] previewData = null;
                String contentType = "image/jpeg";

                String mimeType = item.getMimeType();
                long fileSize = item.getSizeBytes();

                if (mimeType != null) {
                    if (mimeType.startsWith("image/")) {
                        if (fileSize > MAX_IMAGE_SIZE_BYTES) {
                            logger.info("Skipping image preview for {}: file size {}MB exceeds limit of {}MB",
                                item.getFullPath(), fileSize / (1024 * 1024), MAX_IMAGE_SIZE_BYTES / (1024 * 1024));
                        } else {
                            previewData = generateImagePreview(item, dataProvider);
                        }
                    } else if (mimeType.equals("application/pdf")) {
                        if (fileSize > MAX_PDF_SIZE_BYTES) {
                            logger.info("Skipping PDF preview for {}: file size {}MB exceeds limit of {}MB",
                                item.getFullPath(), fileSize / (1024 * 1024), MAX_PDF_SIZE_BYTES / (1024 * 1024));
                        } else {
                            previewData = generatePdfPreview(item, dataProvider);
                        }
                    }
                }

                if (previewData != null) {
                    itemPreviewDao.savePreviewData(preview.getItemId(), preview.getVersion(), 
                                                   previewData, contentType, ItemPreviewStatus.DONE);
                    logger.info("Generated preview for item: {}", item.getFullPath());
                } else {
                    // Not a supported type or generation failed
                    itemPreviewDao.savePreviewData(preview.getItemId(), preview.getVersion(), 
                                                   null, null, ItemPreviewStatus.FAILED);
                }

            } catch (Exception e) {
                logger.error("Error generating preview for item: {}", preview.getItemId(), e);
                try {
                    itemPreviewDao.savePreviewData(preview.getItemId(), preview.getVersion(), 
                                                   null, null, ItemPreviewStatus.FAILED);
                } catch (Exception ex) {
                    logger.error("Error marking preview as failed: {}", preview.getItemId(), ex);
                }
            }
        }

        return previews.size();
    }

    /**
     * Generate thumbnail for image files
     */
    private byte[] generateImagePreview(Item item, DataProviderInterface dataProvider) {
        try (InputStream is = dataProvider.getInputStream(item)) {
            BufferedImage originalImage = ImageIO.read(is);
            if (originalImage == null) {
                logger.warn("Could not read image: {}", item.getFullPath());
                return null;
            }

            // Calculate thumbnail dimensions maintaining aspect ratio
            int[] dimensions = calculateThumbnailDimensions(
                originalImage.getWidth(), originalImage.getHeight()
            );

            // Create thumbnail
            BufferedImage thumbnail = new BufferedImage(
                dimensions[0], dimensions[1], BufferedImage.TYPE_INT_RGB
            );
            
            Graphics2D g = thumbnail.createGraphics();
            Image scaledImage = originalImage.getScaledInstance(
                dimensions[0], dimensions[1], Image.SCALE_SMOOTH
            );
            g.drawImage(scaledImage, 0, 0, null);
            g.dispose();

            // Convert to JPEG bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "jpeg", baos);
            return baos.toByteArray();

        } catch (Exception e) {
            logger.error("Error generating image preview: {}", item.getFullPath(), e);
            return null;
        }
    }

    /**
     * Generate thumbnail for PDF files (first page).
     * Uses streaming PDF loading to avoid loading entire file into memory.
     */
    private byte[] generatePdfPreview(Item item, DataProviderInterface dataProvider) {
        try (InputStream is = dataProvider.getInputStream(item);
             RandomAccessReadBuffer readBuffer = new RandomAccessReadBuffer(is);
             PDDocument document = Loader.loadPDF(readBuffer)) {

            if (document.getNumberOfPages() == 0) {
                return null;
            }

            // Render first page
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage pageImage = renderer.renderImage(0, 2.0f);

            // Calculate thumbnail dimensions
            int[] dimensions = calculateThumbnailDimensions(
                pageImage.getWidth(), pageImage.getHeight()
            );

            // Create thumbnail
            BufferedImage thumbnail = new BufferedImage(
                dimensions[0], dimensions[1], BufferedImage.TYPE_INT_RGB
            );

            Graphics2D g = thumbnail.createGraphics();
            Image scaledImage = pageImage.getScaledInstance(
                dimensions[0], dimensions[1], Image.SCALE_SMOOTH
            );
            g.drawImage(scaledImage, 0, 0, null);
            g.dispose();

            // Convert to JPEG bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnail, "jpeg", baos);
            return baos.toByteArray();

        } catch (Exception e) {
            logger.error("Error generating PDF preview: {}", item.getFullPath(), e);
            return null;
        }
    }

    /**
     * Calculate thumbnail dimensions maintaining aspect ratio
     */
    private int[] calculateThumbnailDimensions(int originalWidth, int originalHeight) {
        double aspectRatio = (double) originalWidth / originalHeight;
        
        int width = PREVIEW_WIDTH;
        int height = PREVIEW_HEIGHT;
        
        if (aspectRatio > 1) {
            // Landscape - width is limiting
            height = (int) (width / aspectRatio);
        } else {
            // Portrait or square - height is limiting
            width = (int) (height * aspectRatio);
        }
        
        return new int[]{width, height};
    }
}
