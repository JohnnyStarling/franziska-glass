package lab.franziska.pixelbridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.franziska.protocol.Json;
import lab.franziska.protocol.ProtocolException;

/** Pixel-private replacement for owner-selected MyGlass cards, never a Google Mirror client. */
public final class LocalMirrorCatalog {
    public static final int MAX_CARDS = 12;
    public static final class Card {
        public final String title, body;
        Card(String title, String body) { this.title = title; this.body = body; }
        public Map<String,Object> payload() {
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            result.put("title", title);
            result.put("lines", Arrays.<Object>asList(body, "Локально на Pixel"));
            result.put("local_mirror", Boolean.TRUE);
            return result;
        }
    }
    private final ArrayList<Card> cards = new ArrayList<Card>();
    public int size() { return cards.size(); }
    public Card get(int index) { return cards.get(index); }
    public void add(String title, String body) {
        Card card = checked(title, body);
        cards.add(0, card);
        if (cards.size() > MAX_CARDS) cards.remove(cards.size() - 1);
    }
    public void remove(int index) { cards.remove(index); }
    private static Card checked(String title, String body) {
        if (title == null || title.trim().isEmpty() || title.length() > 80 || hasControl(title, false))
            throw new IllegalArgumentException("Заголовок: 1–80 символов без управляющих знаков");
        if (body == null || body.trim().isEmpty() || body.length() > 240 || hasControl(body, true))
            throw new IllegalArgumentException("Текст: 1–240 символов без управляющих знаков");
        return new Card(title.trim(), body.trim());
    }
    private static boolean hasControl(String value, boolean newline) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 && !(newline && c == '\n') || c == 127) return true;
        }
        return false;
    }
    public String encode() {
        List<Object> rows = new ArrayList<Object>();
        for (Card card : cards) rows.add(Arrays.<Object>asList(card.title, card.body));
        try { return Json.stringify(rows); }
        catch (ProtocolException impossible) { throw new IllegalStateException(impossible); }
    }
    public static LocalMirrorCatalog decode(String encoded) {
        LocalMirrorCatalog catalog = new LocalMirrorCatalog();
        if (encoded == null || encoded.isEmpty()) return catalog;
        if (encoded.length() > 10000) throw new IllegalArgumentException("Хранилище карточек повреждено");
        try {
            Object value = Json.parse(encoded);
            if (!(value instanceof List) || ((List<?>) value).size() > MAX_CARDS)
                throw new IllegalArgumentException("Хранилище карточек повреждено");
            for (Object row : (List<?>) value) {
                if (!(row instanceof List) || ((List<?>) row).size() != 2) throw new IllegalArgumentException("Хранилище карточек повреждено");
                List<?> fields = (List<?>) row;
                if (!(fields.get(0) instanceof String) || !(fields.get(1) instanceof String))
                    throw new IllegalArgumentException("Хранилище карточек повреждено");
                catalog.cards.add(checked((String) fields.get(0), (String) fields.get(1)));
            }
            return catalog;
        } catch (ProtocolException invalid) { throw new IllegalArgumentException("Хранилище карточек повреждено", invalid); }
    }
}
