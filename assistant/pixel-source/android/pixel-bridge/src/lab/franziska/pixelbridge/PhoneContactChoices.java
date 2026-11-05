package lab.franziska.pixelbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lab.franziska.protocol.CallWire;
import lab.franziska.protocol.ProtocolException;

/** In-memory, bounded contact choices for the owner-operated Pixel call form.
 * This class neither reads Contacts nor prepares a call; the selected number is
 * kept exactly as stored and must still pass the ordinary Glass call gate.
 */
final class PhoneContactChoices {
    static final int MAX_SCANNED_ROWS = 24;
    static final int MAX_CHOICES = 8;
    private static final String VALIDATION_ID = "00000000-0000-4000-8000-000000000000";

    interface Rows {
        boolean moveToNext();
        String displayName();
        String number();
    }

    static final class Choice {
        final String label;
        final String e164;
        Choice(String label, String e164) { this.label = label; this.e164 = e164; }
    }

    private PhoneContactChoices() { }

    /** Require a specific owner-entered name fragment, never an empty/broad scan. */
    static String query(String raw) {
        if (raw == null) throw new IllegalArgumentException("contact query");
        int start = 0, end = raw.length();
        while (start < end && Character.isWhitespace(raw.charAt(start))) start++;
        while (end > start && Character.isWhitespace(raw.charAt(end - 1))) end--;
        String term = raw.substring(start, end);
        if (term.length() > 40) throw new IllegalArgumentException("contact query");
        int letters = 0;
        for (int i = 0; i < term.length();) {
            int c = term.codePointAt(i);
            if (Character.isLetter(c)) letters++;
            else if (!(Character.isDigit(c) || Character.isWhitespace(c)
                    || c == '-' || c == '\'' || c == 0x2019 || c == '.'))
                throw new IllegalArgumentException("contact query");
            i += Character.charCount(c);
        }
        if (letters < 3) throw new IllegalArgumentException("contact query");
        return term;
    }

    /** Scan at most 24 filtered rows and return at most eight exact, call-valid
     * E.164 pairs. Non-E.164/local numbers are never guessed or normalized.
     */
    static List<Choice> collect(Rows rows) {
        if (rows == null) throw new IllegalArgumentException("contact rows");
        ArrayList<Choice> found = new ArrayList<Choice>();
        Set<String> seen = new LinkedHashSet<String>();
        int scanned = 0;
        while (scanned < MAX_SCANNED_ROWS && found.size() < MAX_CHOICES && rows.moveToNext()) {
            scanned++;
            String rawName = rows.displayName();
            String number = rows.number();
            if (rawName == null || number == null) continue;
            String label = rawName.trim();
            try {
                CallWire.validate("call.prepare", CallWire.prepare(VALIDATION_ID, label, number));
            } catch (ProtocolException invalid) {
                continue;
            }
            if (seen.add(label + '\u0000' + number)) found.add(new Choice(label, number));
        }
        return Collections.unmodifiableList(found);
    }
}
