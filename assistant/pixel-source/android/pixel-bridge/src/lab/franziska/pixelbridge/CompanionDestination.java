package lab.franziska.pixelbridge;

/** Pure presentation routes; selecting a destination never authorizes a sensor or service. */
final class CompanionDestination {
    static final String EXTRA_SECTION = "lab.franziska.pixelbridge.SECTION";
    static final String TALK = "talk", MESSAGES = "messages", ROUTE = "route", MEDIA = "media";
    static final String SETTINGS = "settings", TOOLS = "tools", CALLS = "calls";
    static final String ROUTE_LIMIT = "Повороты, расстояние и ETA на Glass пока недоступны.";
    static final String MESSAGE_LIMIT = "Чаты и ответы с Glass пока не подключены. Уведомления автоматически не пересылаются.";

    private CompanionDestination() {}

    static String bridge(String requested) {
        if (ROUTE.equals(requested) || SETTINGS.equals(requested) || TOOLS.equals(requested)) return requested;
        return TALK;
    }

    static String capabilities(String requested, boolean hasDialNumber) {
        return hasDialNumber || CALLS.equals(requested) ? CALLS : MESSAGES;
    }

    static String task(String section) {
        if (ROUTE.equals(section) || MEDIA.equals(section)) return section;
        if (MESSAGES.equals(section) || CALLS.equals(section) || "more".equals(section)) return MESSAGES;
        if (TALK.equals(section) || "home".equals(section)) return TALK;
        return ""; // Secondary settings screens do not pretend to be a task destination.
    }

    static String bridgeBack(String section, String lastTask) {
        if (TOOLS.equals(section)) return SETTINGS;
        if (SETTINGS.equals(section)) return ROUTE.equals(lastTask) ? ROUTE : TALK;
        if (ROUTE.equals(section)) return TALK;
        return null;
    }
}
