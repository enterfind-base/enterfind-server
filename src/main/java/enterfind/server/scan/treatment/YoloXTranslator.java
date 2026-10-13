package enterfind.server.scan.treatment;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.translator.YoloV5Translator;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslatorContext;

/**
 * DJL translator for Megvii's YOLOX-tiny ONNX export (Apache-2.0).
 *
 * <p><b>Both</b> pre- and post-processing are hand-written pure Java. This is deliberate and
 * load-bearing for running on ARMv8.0 CPUs:
 * OnnxRuntime's {@code OrtNDArray} implements only a handful of tensor operations, so DJL's
 * stock translators (and the STACK batchifier's {@code unbatchify}) that do NDArray math on the
 * output silently delegate the unsupported ops to the <em>default engine's</em> NDManager. When
 * PyTorch is on the classpath that default is libtorch — and executing any libtorch native code
 * on a pre-ARMv8.1 CPU is exactly the SIGILL crash this design avoids. Doing every
 * tensor operation here in plain Java over {@code toFloatArray()} keeps object detection entirely
 * inside OnnxRuntime, with no fallback engine, so it is both crash-free on ARMv8.0 and correct
 * after PyTorch is removed. {@link YuNetFaceDetectionTranslator} takes the same approach.
 *
 * <p><b>Preprocessing (YOLOX {@code preproc}):</b> letterbox to 416x416 preserving aspect ratio,
 * pad the remainder with 114 anchored top-left, channel order <b>BGR</b>, layout CHW, and
 * <b>raw 0-255 float32 with no {@code /255} and no mean/std normalization</b>. YOLOX's reference
 * pipeline reads images with OpenCV (BGR) and feeds them unnormalized; applying {@code /255}
 * makes the model see values 255x too small and detect nothing.
 *
 * <p><b>Postprocessing:</b> the output is {@code [1, 3549, 85]} = (cx, cy, w, h, obj, 80 class
 * scores). The export does <b>not</b> bake in grid decoding, so cx,cy are grid-relative offsets
 * and w,h are log-space sizes; both are decoded against the per-row grid cell and stride
 * ({@code buildGrid}) into 416-letterbox pixels. Score is {@code obj * max(classScore)}. The
 * object-detection handler stores only class name + confidence and discards boxes, but boxes must
 * be right all the same: per-class NMS runs on them, and on the raw undecoded values (sub-pixel,
 * sometimes negative) NMS cannot dedupe, so one object is reported many times. Decoded boxes are
 * additionally mapped back out of the letterbox into <b>original-image normalized coordinates</b>
 * — the same convention SSD and face detection use — so they are correct for
 * on-page crops if ever surfaced, not merely self-consistent for NMS.
 */
public class YoloXTranslator extends YoloV5Translator {

    private static final int INPUT_SIZE = 416;
    // YOLOX pads with a constant gray of 114 (see yolox/data/data_augment.py:preproc).
    private static final int PAD_VALUE = 114;
    private static final int NUM_BOX_FIELDS = 5; // cx, cy, w, h, objectness
    private static final float NMS_IOU_THRESHOLD = 0.45f;
    // Feature-map strides for the yolox_tiny (non-P6) export. Grid sizes at 416: 52, 26, 13,
    // so the flattened output has 52^2 + 26^2 + 13^2 = 3549 rows in this stride order.
    private static final int[] STRIDES = {8, 16, 32};

    protected YoloXTranslator(YoloV5Translator.Builder builder) {
        super(builder);
    }

    @Override
    public Batchifier getBatchifier() {
        // No batchifier: processInput emits the batch dim itself and processOutput reads the raw
        // output. This avoids the STACK batchifier's unbatchify(), which does an NDArray split on
        // the output — an op OrtNDArray cannot perform without a fallback engine (see class doc).
        return null;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        int origWidth = input.getWidth();
        int origHeight = input.getHeight();

        // Letterbox scale: fit the longer side, preserve aspect ratio. YOLOX truncates (int()).
        float r = Math.min((float) INPUT_SIZE / origWidth, (float) INPUT_SIZE / origHeight);
        int newW = (int) (origWidth * r);
        int newH = (int) (origHeight * r);

        // Canvas pre-filled with 114 gray; resized image anchored at the top-left (0,0),
        // padding falls on the bottom/right — exactly YOLOX's preproc layout.
        BufferedImage origBuf = (BufferedImage) input.getWrappedImage();
        BufferedImage letterboxed = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = letterboxed.createGraphics();
        g.setColor(new java.awt.Color(PAD_VALUE, PAD_VALUE, PAD_VALUE));
        g.fillRect(0, 0, INPUT_SIZE, INPUT_SIZE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(origBuf, 0, 0, newW, newH, 0, 0, origWidth, origHeight, null);
        g.dispose();

        // Build CHW float32 in BGR order, raw 0-255 (no normalization).
        int[] pixels = letterboxed.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        int plane = INPUT_SIZE * INPUT_SIZE;
        float[] data = new float[3 * plane];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            float rCh = (pixel >> 16) & 0xFF;
            float gCh = (pixel >> 8) & 0xFF;
            float bCh = pixel & 0xFF;
            // Plane order BGR: plane 0 = Blue, plane 1 = Green, plane 2 = Red.
            data[i] = bCh;
            data[plane + i] = gCh;
            data[2 * plane + i] = rCh;
        }

        // processOutput maps decoded boxes back out of the letterbox into original-image
        // coordinates, so it needs the source dimensions (pad is 0 — image anchored top-left).
        ctx.setAttachment("origW", origWidth);
        ctx.setAttachment("origH", origHeight);

        NDManager manager = ctx.getNDManager();
        NDArray array = manager.create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        // Output [1, 3549, 85] = (cx, cy, w, h, obj, 80 class scores). The yolox_tiny export does
        // NOT bake in grid decoding, so cx,cy are grid-relative offsets and w,h are log-space;
        // they must be decoded with the per-row grid cell and stride (buildGrid) before use.
        // Skipping the decode yields sub-pixel/negative boxes on which NMS cannot dedupe.
        float[] flat = list.get(0).toFloatArray();
        int numClasses = classes.size();
        int stride = NUM_BOX_FIELDS + numClasses;
        int numBoxes = flat.length / stride;

        // Undo the letterbox: original-image pixels = letterbox pixels / scale (pad is 0, image
        // anchored top-left). Boxes are then normalised by the ORIGINAL dimensions, so they match
        // the convention SSD and face detection use — usable for on-page crops, not just NMS.
        int origW = (int) ctx.getAttachment("origW");
        int origH = (int) ctx.getAttachment("origH");
        float scale = Math.min((float) INPUT_SIZE / origW, (float) INPUT_SIZE / origH);

        int[][] grid = buildGrid(numBoxes); // [row] -> {gridX, gridY, stride}

        List<Detection> detections = new ArrayList<>();
        for (int i = 0; i < numBoxes; i++) {
            int base = i * stride;
            float objectness = flat[base + 4];

            int maxIdx = 0;
            float maxClassScore = flat[base + NUM_BOX_FIELDS];
            for (int c = 1; c < numClasses; c++) {
                float s = flat[base + NUM_BOX_FIELDS + c];
                if (s > maxClassScore) {
                    maxClassScore = s;
                    maxIdx = c;
                }
            }

            float score = objectness * maxClassScore;
            if (score < threshold) {
                continue;
            }

            // YOLOX decode (yolox/utils/demo_utils.py:demo_postprocess): centre offset added to
            // the grid cell then scaled by stride; size is exp() in stride units. Result is in
            // 416-letterbox pixels.
            int gx = grid[i][0];
            int gy = grid[i][1];
            int st = grid[i][2];
            float cx = (flat[base] + gx) * st;
            float cy = (flat[base + 1] + gy) * st;
            float w = (float) Math.exp(flat[base + 2]) * st;
            float h = (float) Math.exp(flat[base + 3]) * st;

            // Corners → original-image pixels (/scale) → clamp into the image → normalise by
            // original dimensions.
            float ox1 = clamp((cx - w / 2) / scale, 0, origW);
            float oy1 = clamp((cy - h / 2) / scale, 0, origH);
            float ox2 = clamp((cx + w / 2) / scale, 0, origW);
            float oy2 = clamp((cy + h / 2) / scale, 0, origH);
            detections.add(new Detection(maxIdx, score, new Rectangle(
                    (double) ox1 / origW, (double) oy1 / origH,
                    (double) (ox2 - ox1) / origW, (double) (oy2 - oy1) / origH)));
        }

        List<Detection> kept = perClassNms(detections);

        List<String> retNames = new ArrayList<>(kept.size());
        List<Double> retProbs = new ArrayList<>(kept.size());
        List<BoundingBox> retBoxes = new ArrayList<>(kept.size());
        for (Detection d : kept) {
            retNames.add(classes.get(d.classId));
            retProbs.add((double) d.score);
            retBoxes.add(d.box);
        }
        return new DetectedObjects(retNames, retProbs, retBoxes);
    }

    /**
     * Build the per-row grid decode table: row index -> {gridX, gridY, stride}. Rows are laid out
     * by stride level (8, 16, 32), each level row-major (y outer, x inner) over its GxG grid, which
     * is the order {@code demo_postprocess} produces via {@code meshgrid(...).reshape(-1, 2)}.
     */
    private static int[][] buildGrid(int numBoxes) {
        int[][] grid = new int[numBoxes][3];
        int r = 0;
        for (int st : STRIDES) {
            int g = INPUT_SIZE / st;
            for (int y = 0; y < g; y++) {
                for (int x = 0; x < g; x++) {
                    if (r < numBoxes) {
                        grid[r][0] = x;
                        grid[r][1] = y;
                        grid[r][2] = st;
                        r++;
                    }
                }
            }
        }
        if (r != numBoxes) {
            // Model geometry differs from the assumed yolox_tiny @416 (e.g. a P6 export or a
            // different input size). Decoding would be wrong; fail loudly rather than emit noise.
            throw new IllegalStateException("YOLOX grid rows (" + r + ") != output rows ("
                    + numBoxes + "); STRIDES/INPUT_SIZE do not match the loaded model");
        }
        return grid;
    }

    private static List<Detection> perClassNms(List<Detection> detections) {
        List<Detection> sorted = new ArrayList<>(detections);
        sorted.sort(Comparator.comparingDouble((Detection d) -> d.score).reversed());

        List<Detection> kept = new ArrayList<>();
        boolean[] suppressed = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (suppressed[i]) {
                continue;
            }
            Detection a = sorted.get(i);
            kept.add(a);
            for (int j = i + 1; j < sorted.size(); j++) {
                if (suppressed[j]) {
                    continue;
                }
                Detection b = sorted.get(j);
                // NMS is per class: boxes of different classes never suppress each other.
                if (a.classId == b.classId && iou(a.box, b.box) > NMS_IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        return kept;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double iou(Rectangle a, Rectangle b) {
        double ax2 = a.getX() + a.getWidth();
        double ay2 = a.getY() + a.getHeight();
        double bx2 = b.getX() + b.getWidth();
        double by2 = b.getY() + b.getHeight();

        double interX1 = Math.max(a.getX(), b.getX());
        double interY1 = Math.max(a.getY(), b.getY());
        double interX2 = Math.min(ax2, bx2);
        double interY2 = Math.min(ay2, by2);
        double interArea = Math.max(0, interX2 - interX1) * Math.max(0, interY2 - interY1);

        double union = a.getWidth() * a.getHeight() + b.getWidth() * b.getHeight() - interArea;
        return union > 0 ? interArea / union : 0;
    }

    private static final class Detection {
        final int classId;
        final float score;
        final Rectangle box;

        Detection(int classId, float score, Rectangle box) {
            this.classId = classId;
            this.score = score;
            this.box = box;
        }
    }
}
