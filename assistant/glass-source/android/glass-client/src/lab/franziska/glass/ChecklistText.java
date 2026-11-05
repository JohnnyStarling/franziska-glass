package lab.franziska.glass;

import java.util.ArrayList;
import java.util.List;

/** Measurer is supplied by Android Paint; host tests use deterministic widths. */
final class ChecklistText {
    interface Measurer { float width(String text); }
    static String fit(String text, float width, Measurer measure) {
        if (measure.width(text) <= width) return text;
        int end = text.length();
        while (end > 0 && measure.width(text.substring(0, end) + "…") > width)
            end = text.offsetByCodePoints(end, -1);
        return measure.width("…") <= width ? text.substring(0, end) + "…" : "";
    }
    static List<String> wrap(String text, float width, int maxLines, Measurer measure) {
        ArrayList<String> result = new ArrayList<String>();
        String remaining = text.replace('\n', ' ').replace('\r', ' ').trim();
        while (remaining.length() > 0 && result.size() < maxLines) {
            if (result.size() == maxLines - 1) {
                result.add(fit(remaining, width, measure));
                break;
            }
            int end = 0;
            while (end < remaining.length()) {
                int next = remaining.offsetByCodePoints(end, 1);
                if (measure.width(remaining.substring(0, next)) > width) break;
                end = next;
            }
            if (end == remaining.length()) { result.add(remaining); break; }
            if (end == 0) { result.add(fit(remaining, width, measure)); break; }
            int space = remaining.lastIndexOf(' ', end);
            if (space > 0) end = space;
            result.add(remaining.substring(0, end));
            remaining = remaining.substring(end).trim();
        }
        return result;
    }
}
