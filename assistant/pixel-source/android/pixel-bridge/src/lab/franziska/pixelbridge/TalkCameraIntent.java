package lab.franziska.pixelbridge;

import java.util.Locale;

/** Only an explicit, completed user command may activate the Glass camera. */
final class TalkCameraIntent {
    private TalkCameraIntent() {}

    static boolean matches(String text) {
        if (text == null || text.length() > 120) return false;
        String normalized = text.toLowerCase(new Locale("ru", "RU"))
                .replace('ё', 'е').trim().replaceAll("[.!?]+$", "").trim().replaceAll("\\s+", " ");
        return "включи камеру".equals(normalized)
                || "включи фотоаппарат".equals(normalized)
                || "сними фото".equals(normalized)
                || "сделай фото".equals(normalized)
                || "сделай снимок".equals(normalized)
                || "сфотографируй".equals(normalized);
    }
}
