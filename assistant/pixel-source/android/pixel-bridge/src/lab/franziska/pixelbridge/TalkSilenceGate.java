package lab.franziska.pixelbridge;

import java.util.Arrays;

/** Removes only the XE24 microphone noise floor so Gemini can see an audio stream end. */
final class TalkSilenceGate {
    static boolean apply(byte[] pcm) {
        if (pcm == null || pcm.length < 2 || (pcm.length & 1) != 0) return false;
        long energy = 0;
        int peak = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            int value = (short)((pcm[i] & 255) | ((pcm[i + 1] & 255) << 8));
            int magnitude = Math.abs(value);
            if (magnitude > peak) peak = magnitude;
            energy += (long)value * value;
        }
        // A whole frame must be quiet. Preserve even weak speech/transients.
        if (peak > 48 || energy > (long)(pcm.length / 2) * 16 * 16) return false;
        Arrays.fill(pcm, (byte)0);
        return true;
    }
}
