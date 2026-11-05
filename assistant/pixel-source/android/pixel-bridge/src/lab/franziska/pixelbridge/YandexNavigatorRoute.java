package lab.franziska.pixelbridge;

/** A destination-only Navigator deep link; Navigator itself supplies the starting position. */
public final class YandexNavigatorRoute {
    public static final String PACKAGE = "ru.yandex.yandexnavi";
    private YandexNavigatorRoute() { }

    public static String to(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Недопустимые координаты");
        // Yandex Navigator URL API: https://yandex.ru/dev/navigator/doc/ru/concepts/navigator-url-params
        return "yandexnavi://build_route_on_map?lat_to=" + Double.toString(latitude)
                + "&lon_to=" + Double.toString(longitude);
    }
}
