package enterfind.server.scan.treatment;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * DJL Translator for the YuNet ONNX face detection model (face_detection_yunet_2023mar).
 *
 * <p>Replaces the SCRFD {@code det_2.5g} translator. Provenance: Shiqi Yu, MIT licence granted by
 * the author, trained on WIDER Face — unlike SCRFD, which its author restricts to non-commercial
 * research.
 *
 * <p>Deliberately pure Java: every tensor is read via {@code toFloatArray()} and the input is built
 * with {@code manager.create(...)}. No {@code transpose}/{@code norm}/{@code div}/pipeline NDArray
 * op is used, because DJL's {@code OrtNDArray} implements only a handful of operations and silently
 * delegates the rest to the default engine — which was PyTorch, and which SIGILLs on ARMv8.0. This
 * is what lets {@code pytorch-engine} stay out of {@code pom.xml}.
 *
 * <p>Model I/O, read off the {@code .onnx} itself:
 * <pre>
 *   input   : (1, 3, 640, 640)  BGR, raw 0-255, NO (px-127.5)/128 normalisation (unlike SCRFD)
 *   cls_8   : (1, 6400, 1)   cls_16 : (1, 1600, 1)   cls_32 : (1, 400, 1)
 *   obj_8   : (1, 6400, 1)   obj_16 : (1, 1600, 1)   obj_32 : (1, 400, 1)
 *   bbox_8  : (1, 6400, 4)   bbox_16: (1, 1600, 4)   bbox_32: (1, 400, 4)
 *   kps_8   : (1, 6400, 10)  kps_16 : (1, 1600, 10)  kps_32 : (1, 400, 10)
 * </pre>
 * One anchor per feature-map cell (80x80, 40x40, 20x20) — SCRFD had two, so the SCRFD decode loop
 * does not carry over unchanged.
 */
public class YuNetFaceDetectionTranslator implements Translator<Image, List<FaceDetectionResult>> {

    private static final int INPUT_SIZE = 640;
    /** Default minimum detection confidence; overridden at runtime by the user setting. */
    public static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.7f;
    private volatile float confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
    private static final float NMS_IOU_THRESHOLD = 0.3f;
    private static final int[] STRIDES = {8, 16, 32};
    private static final int NUM_LANDMARKS = 5;

    /**
     * Maps YuNet's raw {@code kps} landmark slots onto the order every downstream consumer expects:
     * {@code [left eye, right eye, nose, left mouth corner, right mouth corner]}, fixed by
     * {@link FaceDetectionResult}'s javadoc and by {@code FaceAlignmentUtil.ARCFACE_TEMPLATE}
     * (whose point 0 sits at the smaller x, i.e. the image-left eye).
     *
     * <p>{@code cv::FaceDetectorYN} documents its output as <em>right eye, left eye, nose, right
     * mouth corner, left mouth corner</em> — but those names are anatomical (the subject's right
     * eye), and the subject's right eye appears on the image-<em>left</em>. So slot 0 is already the
     * image-left eye and the mapping is the identity. This is asserted geometrically in
     * {@code FaceDetectionTreatmentIntegrationTest}: a wrong order here is otherwise
     * <em>silent</em>, because {@code umeyamaSimilarity} permits no reflection and quietly fits a
     * ~180°-off rotation, yielding garbage-aligned crops while detection still looks fine. Detection
     * itself never reads landmarks, so that test is the only thing standing between this constant and
     * an inexplicable face-recognition grouping failure.
     */
    private static final int[] LANDMARK_ORDER = {0, 1, 2, 3, 4};

    private float scale;
    private float padX;
    private float padY;
    private int origWidth;
    private int origHeight;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        origWidth = input.getWidth();
        origHeight = input.getHeight();

        // Letterbox into a fixed 640x640: the graph's input dimension is static.
        scale = Math.min((float) INPUT_SIZE / origWidth, (float) INPUT_SIZE / origHeight);
        int newW = Math.round(origWidth * scale);
        int newH = Math.round(origHeight * scale);
        int padLeft = (INPUT_SIZE - newW) / 2;
        int padTop = (INPUT_SIZE - newH) / 2;
        padX = padLeft;
        padY = padTop;

        BufferedImage origBuf = (BufferedImage) input.getWrappedImage();
        BufferedImage letterboxed = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = letterboxed.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // Background is already black (zeros)
        g.drawImage(origBuf, padLeft, padTop, padLeft + newW, padTop + newH,
                0, 0, origBuf.getWidth(), origBuf.getHeight(), null);
        g.dispose();

        // CHW, BGR plane order, raw 0-255. YuNet bakes no normalisation expectation into its
        // preprocessing contract, so scaling the pixels here would be wrong.
        int[] pixels = letterboxed.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        int plane = INPUT_SIZE * INPUT_SIZE;
        float[] data = new float[3 * plane];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            data[i] = pixel & 0xFF;                       // B plane
            data[plane + i] = (pixel >> 8) & 0xFF;        // G plane
            data[2 * plane + i] = (pixel >> 16) & 0xFF;   // R plane
        }

        NDArray array = manager.create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public List<FaceDetectionResult> processOutput(TranslatorContext ctx, NDList list) {
        List<FaceDetectionResult> allDetections = new ArrayList<>();

        for (int strideIdx = 0; strideIdx < STRIDES.length; strideIdx++) {
            float[] cls = list.get(strideIdx).toFloatArray();
            float[] obj = list.get(3 + strideIdx).toFloatArray();
            float[] bboxes = list.get(6 + strideIdx).toFloatArray();
            float[] kps = list.get(9 + strideIdx).toFloatArray();

            int stride = STRIDES[strideIdx];
            int featH = INPUT_SIZE / stride;
            int featW = INPUT_SIZE / stride;

            for (int row = 0; row < featH; row++) {
                for (int col = 0; col < featW; col++) {
                    int idx = row * featW + col;

                    // Both heads are already sigmoid-activated; clamping guards float drift only.
                    float clsScore = clamp(cls[idx], 0f, 1f);
                    float objScore = clamp(obj[idx], 0f, 1f);
                    float score = (float) Math.sqrt(clsScore * objScore);

                    if (score < this.confidenceThreshold) {
                        continue;
                    }

                    // YuNet regresses centre offset (in cells) and log-space size, where SCRFD
                    // regressed distances to the four box edges.
                    int bi = idx * 4;
                    float cx = (col + bboxes[bi]) * stride;
                    float cy = (row + bboxes[bi + 1]) * stride;
                    float w = (float) Math.exp(bboxes[bi + 2]) * stride;
                    float h = (float) Math.exp(bboxes[bi + 3]) * stride;
                    float x1 = cx - w / 2;
                    float y1 = cy - h / 2;
                    float x2 = cx + w / 2;
                    float y2 = cy + h / 2;

                    // Map back to original image coordinates, undoing the letterbox.
                    float origX1 = clamp((x1 - padX) / scale, 0, origWidth);
                    float origY1 = clamp((y1 - padY) / scale, 0, origHeight);
                    float origX2 = clamp((x2 - padX) / scale, 0, origWidth);
                    float origY2 = clamp((y2 - padY) / scale, 0, origHeight);

                    int ki = idx * 10;
                    float[][] landmarks = new float[NUM_LANDMARKS][2];
                    for (int k = 0; k < NUM_LANDMARKS; k++) {
                        int src = LANDMARK_ORDER[k];
                        float lmX = (col + kps[ki + 2 * src]) * stride;
                        float lmY = (row + kps[ki + 2 * src + 1]) * stride;
                        landmarks[k][0] = (lmX - padX) / scale;
                        landmarks[k][1] = (lmY - padY) / scale;
                    }

                    float boxW = origX2 - origX1;
                    float boxH = origY2 - origY1;
                    if (boxW > 0 && boxH > 0) {
                        allDetections.add(new FaceDetectionResult(
                                origX1, origY1, boxW, boxH, score, landmarks));
                    }
                }
            }
        }

        return nms(allDetections, NMS_IOU_THRESHOLD);
    }

    /** Updates the minimum detection confidence. Thread-safe; safe to call between inferences. */
    void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = threshold;
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    /**
     * Greedy non-maximum suppression. Returns detections in descending confidence order, which is
     * also the order the handler assigns {@code face_index} from.
     */
    private static List<FaceDetectionResult> nms(List<FaceDetectionResult> detections, float iouThreshold) {
        List<FaceDetectionResult> sorted = new ArrayList<>(detections);
        sorted.sort(Comparator.comparingDouble(FaceDetectionResult::confidence).reversed());

        List<FaceDetectionResult> kept = new ArrayList<>();
        boolean[] suppressed = new boolean[sorted.size()];

        for (int i = 0; i < sorted.size(); i++) {
            if (suppressed[i]) continue;
            kept.add(sorted.get(i));
            FaceDetectionResult a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                if (suppressed[j]) continue;
                if (iou(a, sorted.get(j)) > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return kept;
    }

    private static float iou(FaceDetectionResult a, FaceDetectionResult b) {
        float ax2 = a.x() + a.width(), ay2 = a.y() + a.height();
        float bx2 = b.x() + b.width(), by2 = b.y() + b.height();

        float interX1 = Math.max(a.x(), b.x()), interY1 = Math.max(a.y(), b.y());
        float interX2 = Math.min(ax2, bx2), interY2 = Math.min(ay2, by2);
        float interArea = Math.max(0, interX2 - interX1) * Math.max(0, interY2 - interY1);

        float areaA = a.width() * a.height();
        float areaB = b.width() * b.height();
        float union = areaA + areaB - interArea;

        return union > 0 ? interArea / union : 0;
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
