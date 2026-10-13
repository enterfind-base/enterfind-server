package enterfind.server.scan.treatment;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * DJL translator for a face-embedding ONNX model. Input is an ArcFace-aligned 112×112 face crop;
 * output is a fixed-dimension L2-normalized embedding.
 *
 * <p><b>Pure Java, on purpose — the same constraint as {@link YuNetFaceDetectionTranslator}.</b>
 * NDArray ops such as {@code transpose(...)}, {@code norm()} and
 * {@code div(...)} are off limits: DJL's {@code OrtNDArray} implements none of those and silently
 * delegates to the default engine, which would be PyTorch and SIGILLs on ARMv8.0. Everything here is
 * plain loops and {@code manager.create(...)}: transpose HWC→CHW by hand, read {@code toFloatArray()},
 * L2-normalize in {@link FaceEmbeddingUtils}. This is what keeps {@code pytorch-engine} out of
 * {@code pom.xml}. Any NDArray op reintroduced here drags it back in.
 *
 * <p>Two things are read from the model, not assumed:
 * <ul>
 *   <li><b>Output dimension</b> — taken from {@code toFloatArray().length}, so 128-dim models work
 *       beside 512-dim ones.</li>
 *   <li><b>Preprocessing</b> — channel order and normalization come from a {@link FaceEmbeddingPreproc},
 *       so one translator serves the built-in and any bring-your-own model, and the set-model probe
 *       can drive it across the detection grid. The field is volatile because the probe flips it
 *       between forward passes.</li>
 * </ul>
 */
public class FaceEmbeddingTranslator implements Translator<Image, float[]> {

    private static final int INPUT_SIZE = 112;
    private static final float MEAN = 127.5f;
    private static final float SCALE = 127.5f;

    private volatile FaceEmbeddingPreproc preproc;

    public FaceEmbeddingTranslator(FaceEmbeddingPreproc preproc) {
        this.preproc = preproc;
    }

    /** Swap the preprocessing convention. Used by the probe to sweep the {RGB,BGR}×{raw,centered} grid. */
    public void setPreproc(FaceEmbeddingPreproc preproc) {
        this.preproc = preproc;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        FaceEmbeddingPreproc p = this.preproc;

        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage sized = src;
        if (src.getWidth() != INPUT_SIZE || src.getHeight() != INPUT_SIZE) {
            sized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = sized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
            g.dispose();
        }

        int[] pixels = sized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        int plane = INPUT_SIZE * INPUT_SIZE;
        float[] data = new float[3 * plane];

        // CHW. Which colour lands in plane 0 vs plane 2 is the channel-order axis; whether the value
        // is centred or raw is the normalization axis. Both come from the detected preproc.
        int firstPlane = p.isBgr() ? 2 : 0;   // BGR wants B first (blue -> plane 0)
        int lastPlane = p.isBgr() ? 0 : 2;
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            float r = (pixel >> 16) & 0xFF;
            float g = (pixel >> 8) & 0xFF;
            float b = pixel & 0xFF;
            if (p.isCentered()) {
                r = (r - MEAN) / SCALE;
                g = (g - MEAN) / SCALE;
                b = (b - MEAN) / SCALE;
            }
            data[firstPlane * plane + i] = r;   // plane holding R
            data[plane + i] = g;                // G is always the middle plane
            data[lastPlane * plane + i] = b;    // plane holding B
        }

        NDArray array = manager.create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        // toFloatArray() flattens the [1, dim] output to length dim — no NDArray indexing, and the
        // dimension is whatever the model emits rather than a hardcoded 512.
        float[] raw = list.get(0).toFloatArray();
        return FaceEmbeddingUtils.l2Normalize(raw);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
