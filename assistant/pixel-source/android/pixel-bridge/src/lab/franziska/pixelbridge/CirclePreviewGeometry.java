package lab.franziska.pixelbridge;

/** Maps the camera buffer to a rotated, undistorted centre-crop of the view. */
final class CirclePreviewGeometry {
    private CirclePreviewGeometry() {}

    /** Android Matrix values for the TextureView's initially fill-scaled buffer. */
    static float[] transform(int bufferWidth, int bufferHeight,
                             int viewWidth, int viewHeight, int clockwiseDegrees) {
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0
                || (clockwiseDegrees != 0 && clockwiseDegrees != 90
                && clockwiseDegrees != 180 && clockwiseDegrees != 270))
            throw new IllegalArgumentException("Invalid preview geometry");
        boolean quarterTurn = clockwiseDegrees == 90 || clockwiseDegrees == 270;
        double rotatedWidth = quarterTurn ? bufferHeight : bufferWidth;
        double rotatedHeight = quarterTurn ? bufferWidth : bufferHeight;
        double cover = Math.max(viewWidth / rotatedWidth, viewHeight / rotatedHeight);
        int cos = clockwiseDegrees == 90 || clockwiseDegrees == 270 ? 0
                : clockwiseDegrees == 180 ? -1 : 1;
        int sin = clockwiseDegrees == 90 ? 1 : clockwiseDegrees == 270 ? -1 : 0;
        // TextureView first stretches the buffer to the view bounds. Undo that
        // stretch before the same rotation used in the MP4 orientation hint.
        double a = cover * cos * bufferWidth / viewWidth;
        double b = -cover * sin * bufferHeight / viewHeight;
        double c = cover * sin * bufferWidth / viewWidth;
        double d = cover * cos * bufferHeight / viewHeight;
        double cx = viewWidth / 2.0, cy = viewHeight / 2.0;
        return new float[] {(float) a, (float) b, (float) (cx - a * cx - b * cy),
                (float) c, (float) d, (float) (cy - c * cx - d * cy), 0f, 0f, 1f};
    }
}
