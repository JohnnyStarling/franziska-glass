package lab.franziska.pixelbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Bounds for a local video-note draft. This class has no Android dependency. */
final class CircleDraftPolicy {
    static final int RECORDING_CUTOFF_MS = 58_500;
    static final int MAX_DURATION_MS = 60_000;
    static final long MAX_FILE_BYTES = 50L * 1024L * 1024L;

    private CircleDraftPolicy() {}

    /** Prefer a native square recording, then a modest H.264 size to crop on the Mac. */
    static int[] chooseVideoSize(int[][] supported) {
        int[][] ranked = rankVideoSizes(supported, 1);
        return ranked.length == 0 ? null : ranked[0];
    }

    /** Bounded, distinct encoder attempts in preference order; never probe 4K. */
    static int[][] rankVideoSizes(int[][] supported, int limit) {
        List<int[]> ranked = new ArrayList<int[]>();
        if (supported == null || limit <= 0) return new int[0][];
        for (int[] size : supported) {
            if (size == null || size.length < 2) continue;
            int width = size[0], height = size[1];
            if (width < 480 || height < 480 || width > 1920 || height > 1920) continue;
            boolean duplicate = false;
            for (int[] existing : ranked) {
                if (existing[0] == width && existing[1] == height) { duplicate = true; break; }
            }
            if (!duplicate) ranked.add(new int[] {width, height});
        }
        Collections.sort(ranked, new Comparator<int[]>() {
            @Override public int compare(int[] left, int[] right) {
                int order = Long.compare(sizeScore(left), sizeScore(right));
                if (order != 0) return order;
                order = Integer.compare(left[0], right[0]);
                return order != 0 ? order : Integer.compare(left[1], right[1]);
            }
        });
        int count = Math.min(limit, ranked.size());
        List<int[]> attempts = new ArrayList<int[]>(ranked.subList(0, count));
        // Camera2 may advertise several square PRIVATE sizes that an H.264
        // encoder cannot accept. Keep one ordinary rectangle as a true fallback.
        if (count >= 2 && ranked.size() > count) {
            boolean hasRectangle = false;
            for (int[] size : attempts) if (size[0] != size[1]) hasRectangle = true;
            if (!hasRectangle) {
                for (int i = count; i < ranked.size(); i++) {
                    int[] size = ranked.get(i);
                    if (size[0] != size[1]) { attempts.set(count - 1, size); break; }
                }
            }
        }
        return attempts.toArray(new int[0][]);
    }

    private static long sizeScore(int[] size) {
        int width = size[0], height = size[1];
        boolean square = width == height && width <= 1080;
        // Native 720 square is ideal. Otherwise prefer ordinary 720p, then
        // the next closest bounded encoder size. Never silently demand 4K.
        return square
                ? Math.abs(width - 720L) * 1000L
                : 10_000_000L + Math.abs((long) width * height - 1280L * 720L)
                + Math.abs((long) width * 9L - (long) height * 16L) * 100L;
    }

    static boolean playable(long durationMs, long bytes, boolean hasAudio,
                            int width, int height) {
        return durationMs >= 500 && durationMs <= MAX_DURATION_MS
                && bytes >= 1024 && bytes <= MAX_FILE_BYTES
                && hasAudio && width > 0 && height > 0;
    }

    static boolean requiresSquareConversion(int width, int height) {
        return width != height;
    }
}
