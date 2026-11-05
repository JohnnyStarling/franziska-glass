package lab.franziska.pixelbridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import java.util.List;

/** A single, owner-initiated filtered Contacts query. Never transmits a row or
 * stores a contact snapshot; the provider and local cursor both have hard caps.
 */
final class PhoneContactLookup {
    private static final String[] PROJECTION = {Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER};

    private PhoneContactLookup() { }

    static List<PhoneContactChoices.Choice> search(Context context, String rawQuery, CancellationSignal signal) {
        String term = PhoneContactChoices.query(rawQuery);
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("contacts permission required");
        Uri uri = Phone.CONTENT_FILTER_URI.buildUpon().appendPath(term)
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY,
                        Integer.toString(PhoneContactChoices.MAX_SCANNED_ROWS)).build();
        try (Cursor cursor = context.getContentResolver().query(uri, PROJECTION, null, null, null, signal)) {
            if (cursor == null) throw new IllegalStateException("contacts provider unavailable");
            final int nameColumn = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME_PRIMARY);
            final int numberColumn = cursor.getColumnIndexOrThrow(Phone.NUMBER);
            return PhoneContactChoices.collect(new PhoneContactChoices.Rows() {
                @Override public boolean moveToNext() { return cursor.moveToNext(); }
                @Override public String displayName() { return cursor.getString(nameColumn); }
                @Override public String number() { return cursor.getString(numberColumn); }
            });
        }
    }
}
