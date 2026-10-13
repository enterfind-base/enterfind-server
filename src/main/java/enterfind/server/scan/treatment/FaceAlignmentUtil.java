package enterfind.server.scan.treatment;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Utility for aligning face images using similarity transform (Umeyama algorithm).
 * Maps 5 detected facial landmarks to the ArcFace 112x112 reference template,
 * producing properly aligned face crops for embedding extraction.
 */
public final class FaceAlignmentUtil {

    private static final int OUTPUT_SIZE = 112;

    // ArcFace reference landmarks for 112x112 aligned face
    private static final double[][] ARCFACE_TEMPLATE = {
            {38.2946, 51.6963},  // left eye
            {73.5318, 51.5014},  // right eye
            {56.0252, 71.7366},  // nose
            {41.5493, 92.3655},  // left mouth corner
            {70.7299, 92.2041},  // right mouth corner
    };

    private FaceAlignmentUtil() {}

    /**
     * Align a face from the source image using the detected 5-point landmarks.
     * Computes a similarity transform (rotation + uniform scale + translation)
     * and warps the source image to produce a 112x112 aligned face.
     *
     * @param sourceImage the original full image
     * @param landmarks   detected landmarks [5][2] in pixel coordinates (x, y)
     * @return aligned 112x112 BufferedImage
     */
    public static BufferedImage alignFace(BufferedImage sourceImage, float[][] landmarks) {
        double[][] src = new double[5][2];
        for (int i = 0; i < 5; i++) {
            src[i][0] = landmarks[i][0];
            src[i][1] = landmarks[i][1];
        }

        // Compute similarity transform from src landmarks to ARCFACE_TEMPLATE
        double[] params = umeyamaSimilarity(src, ARCFACE_TEMPLATE);
        double cosTheta = params[0]; // scale * cos(angle)
        double sinTheta = params[1]; // scale * sin(angle)
        double tx = params[2];
        double ty = params[3];

        // The transform maps source -> destination:
        //   dst_x = cosTheta * src_x - sinTheta * src_y + tx
        //   dst_y = sinTheta * src_x + cosTheta * src_y + ty
        //
        // AffineTransform in Java uses the convention:
        //   [ m00 m01 m02 ] [x]   [m00*x + m01*y + m02]
        //   [ m10 m11 m12 ] [y] = [m10*x + m11*y + m12]
        //
        // For forward transform (src -> dst):
        //   m00 = cosTheta, m01 = -sinTheta, m02 = tx
        //   m10 = sinTheta, m11 = cosTheta,  m12 = ty

        AffineTransform forwardTransform = new AffineTransform(
                cosTheta, sinTheta, -sinTheta, cosTheta, tx, ty);

        // Check for degenerate transform
        double det = cosTheta * cosTheta + sinTheta * sinTheta;
        if (det < 1e-10) {
            return crudeCrop(sourceImage, landmarks);
        }

        BufferedImage aligned = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = aligned.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setTransform(forwardTransform);
        g.drawImage(sourceImage, 0, 0, null);
        g.dispose();

        return aligned;
    }

    /**
     * Umeyama similarity transform estimation.
     * Finds the best similarity transform (uniform scale + rotation + translation)
     * that maps src points to dst points in a least-squares sense.
     *
     * @param src source points [N][2]
     * @param dst destination points [N][2]
     * @return [cosTheta, sinTheta, tx, ty] where cosTheta = scale*cos(angle), sinTheta = scale*sin(angle)
     */
    static double[] umeyamaSimilarity(double[][] src, double[][] dst) {
        int n = src.length;

        // Compute centroids
        double srcMeanX = 0, srcMeanY = 0, dstMeanX = 0, dstMeanY = 0;
        for (int i = 0; i < n; i++) {
            srcMeanX += src[i][0];
            srcMeanY += src[i][1];
            dstMeanX += dst[i][0];
            dstMeanY += dst[i][1];
        }
        srcMeanX /= n;
        srcMeanY /= n;
        dstMeanX /= n;
        dstMeanY /= n;

        // Center the points
        double[][] srcC = new double[n][2];
        double[][] dstC = new double[n][2];
        for (int i = 0; i < n; i++) {
            srcC[i][0] = src[i][0] - srcMeanX;
            srcC[i][1] = src[i][1] - srcMeanY;
            dstC[i][0] = dst[i][0] - dstMeanX;
            dstC[i][1] = dst[i][1] - dstMeanY;
        }

        // Compute the 2x2 cross-covariance matrix H = src^T * dst
        // H = [ h00 h01 ]
        //     [ h10 h11 ]
        double h00 = 0, h01 = 0, h10 = 0, h11 = 0;
        for (int i = 0; i < n; i++) {
            h00 += srcC[i][0] * dstC[i][0];
            h01 += srcC[i][0] * dstC[i][1];
            h10 += srcC[i][1] * dstC[i][0];
            h11 += srcC[i][1] * dstC[i][1];
        }

        // For a similarity transform, we solve for scale*R that minimizes
        // sum || dst_i - (scale*R * src_i + t) ||^2
        //
        // The optimal rotation+scale combination for 2D similarity is:
        //   a = (sum srcC . dstC) / (sum |srcC|^2)
        //   b = (sum srcC x dstC) / (sum |srcC|^2)
        // where a = scale*cos(angle), b = scale*sin(angle)

        double srcVar = 0;
        for (int i = 0; i < n; i++) {
            srcVar += srcC[i][0] * srcC[i][0] + srcC[i][1] * srcC[i][1];
        }

        if (srcVar < 1e-10) {
            // Degenerate case: all source points are the same
            return new double[]{1, 0, dstMeanX - srcMeanX, dstMeanY - srcMeanY};
        }

        // a = (h00 + h11) / srcVar  (sum of dot products)
        // b = (h01 - h10) / srcVar  (sum of cross products: src_x*dst_y - src_y*dst_x)
        // Note: cross product in 2D: src x dst = src_x*dst_y - src_y*dst_x
        // For similarity transform dst = [a -b; b a] * src + t:
        //   a = scale*cos(angle), b = scale*sin(angle)
        // From cross-covariance H = src^T * dst:
        //   a = (h00 + h11)/srcVar, b = (h01 - h10)/srcVar
        double cosTheta = (h00 + h11) / srcVar;
        double sinTheta = (h01 - h10) / srcVar;

        // Translation: t = dstMean - R * srcMean
        double tx = dstMeanX - (cosTheta * srcMeanX - sinTheta * srcMeanY);
        double ty = dstMeanY - (sinTheta * srcMeanX + cosTheta * srcMeanY);

        return new double[]{cosTheta, sinTheta, tx, ty};
    }

    private static BufferedImage crudeCrop(BufferedImage source, float[][] landmarks) {
        // Find bounding box of landmarks and add margin
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for (float[] lm : landmarks) {
            minX = Math.min(minX, lm[0]);
            minY = Math.min(minY, lm[1]);
            maxX = Math.max(maxX, lm[0]);
            maxY = Math.max(maxY, lm[1]);
        }
        float margin = Math.max(maxX - minX, maxY - minY) * 0.5f;
        int x = Math.max(0, (int) (minX - margin));
        int y = Math.max(0, (int) (minY - margin));
        int w = Math.min(source.getWidth() - x, (int) (maxX - minX + 2 * margin));
        int h = Math.min(source.getHeight() - y, (int) (maxY - minY + 2 * margin));
        if (w <= 0 || h <= 0) {
            w = Math.min(OUTPUT_SIZE, source.getWidth());
            h = Math.min(OUTPUT_SIZE, source.getHeight());
            x = 0;
            y = 0;
        }

        BufferedImage cropped = source.getSubimage(x, y, w, h);
        BufferedImage resized = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cropped, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, null);
        g.dispose();
        return resized;
    }
}
