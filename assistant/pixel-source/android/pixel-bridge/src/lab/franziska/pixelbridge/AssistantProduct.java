package lab.franziska.pixelbridge;

/** Fixed-code product presentation. Never accepts routes, server errors, answers or media. */
public final class AssistantProduct {
    public enum Code {
        NONE, STARTING, ACCESS_ENABLED, STOPPED, CONFIG_CHANGED, CONVERSATION_DISABLED, PAIR_REQUIRED, PAIR_FAILED,
        ROUTE_REQUIRED, ENDPOINT_FAILED, RECEIVING, WAITING, REPLY_QUEUED,
        REQUEST_FAILED, TRANSFER_FAILED, TRANSFER_TIMEOUT, CANCELLED, DISCONNECTED, INTERRUPTED
    }
    private Code last = Code.NONE, previous = Code.NONE;
    public AssistantProduct(String last, String previous) {
        this.last = safe(last); this.previous = safe(previous);
        if (this.last == Code.RECEIVING || this.last == Code.WAITING || this.last == Code.STARTING)
            record(Code.INTERRUPTED);
    }
    private static Code safe(String value) {
        try { return Code.valueOf(value); } catch (Exception invalid) { return Code.NONE; }
    }
    public void record(Code code) { if (last != code) { previous = last; last = code; } }
    public String last() { return last.name(); }
    public String previous() { return previous.name(); }
    public boolean setupNeeded() { return last == Code.PAIR_REQUIRED || last == Code.PAIR_FAILED || last == Code.ROUTE_REQUIRED; }
    /** Compact home copy. Full fixed-code explanation remains in Tools. */
    public static String brief(String code) {
        switch (safe(code)) {
            case PAIR_REQUIRED: return "Сначала подключите Glass.";
            case PAIR_FAILED: return "Не удалось проверить пару Glass.";
            case ROUTE_REQUIRED: return "Нужен адрес OpenClaw.";
            case ENDPOINT_FAILED: return "Glass пока не на связи.";
            case RECEIVING: return "Получаем вопрос с Glass.";
            case WAITING: return "Ждём ответ Франциски.";
            case REPLY_QUEUED: return "Ответ передан Glass; воспроизведение не подтверждено.";
            case REQUEST_FAILED: return "Ответ не получен. Можно попробовать снова на Glass.";
            case TRANSFER_FAILED: return "Вопрос не передан. Можно попробовать снова на Glass.";
            case TRANSFER_TIMEOUT: return "Время передачи истекло. Можно попробовать снова на Glass.";
            case CANCELLED: return "Вопрос отменён.";
            case DISCONNECTED: return "Связь с Glass прервалась.";
            case INTERRUPTED: return "Предыдущее действие прервалось.";
            default: return "";
        }
    }
    public static String describe(String code) {
        switch (safe(code)) {
            case STARTING: return "Проверяем сохранённое сопряжение. Запись не начинается.";
            case ACCESS_ENABLED: return "Сохранённый доступ включён локально. Это не подтверждение связи с OpenClaw или показа на Glass.";
            case STOPPED: return "Связь остановлена. Сопряжение сохранено. Запуск — только кнопкой.";
            case CONVERSATION_DISABLED: return "Разговор выключен. Связь с Glass не остановлена; включение — кнопкой «Подготовить и проверить».";
            case CONFIG_CHANGED: return "Настройки изменены; разговор выключен. Нажмите «Подготовить и проверить».";
            case PAIR_REQUIRED: return "Нужно сопряжение Pixel с Glass. Откройте настройки подключения.";
            case PAIR_FAILED: return "Не удалось проверить пару Glass. Проверьте подключение в настройках.";
            case ROUTE_REQUIRED: return "Нужно подключение к OpenClaw. Откройте настройки подключения.";
            case ENDPOINT_FAILED: return "Не удалось открыть связь с Glass. Проверьте сеть и запустите снова.";
            case RECEIVING: return "Получаем вопрос с Glass. Это ещё не запрос к модели.";
            case WAITING: return "Ждём ответ OpenClaw. Вопрос передан обработчику; ответ ещё не получен.";
            case REPLY_QUEUED: return "Ответ получен и поставлен в очередь Glass. Показ владельцу не подтверждён.";
            case REQUEST_FAILED: return "Ответ не получен. Проверьте сеть Pixel и OpenClaw; новый вопрос — только тапом на Glass.";
            case TRANSFER_FAILED: return "Передача вопроса не завершена. Новый вопрос — только тапом на Glass.";
            case TRANSFER_TIMEOUT: return "Истекло время передачи вопроса. Данные не повторяются; новый вопрос — тапом на Glass.";
            case CANCELLED: return "Вопрос отменён. Автоматической повторной отправки нет.";
            case DISCONNECTED: return "Связь с Glass потеряна. Вопрос не повторяется. Проверьте Glass и локальную сеть.";
            case INTERRUPTED: return "Предыдущая операция прервана. Автоматического запуска и повтора нет.";
            default: return "Попыток ещё нет. Запись начинается только вашим действием на Glass.";
        }
    }
    public static String connection(boolean running, boolean stopped, boolean connected, boolean validating) {
        if (stopped) return "Glass: связь остановлена";
        if (validating) return "Glass: проверка сохранённой пары";
        if (!running) return "Glass: связь выключена";
        return connected ? "Glass: защищённая связь установлена" : "Glass: ожидаем подключение очков";
    }
    public static String readiness(boolean running, boolean stopped, boolean connected, boolean capable,
            AssistantStart.Phase phase, String assistState) {
        if (stopped || !running && phase != AssistantStart.Phase.VALIDATING_PAIR) return "Разговор выключен";
        if (phase == AssistantStart.Phase.VALIDATING_PAIR || phase == AssistantStart.Phase.VALIDATING_ROUTE)
            return "Разговор: проверяем сохранённый доступ";
        if ("disabled".equals(assistState)) return "Разговор выключен — нажмите «Подготовить и проверить»";
        if (!connected) return "Доступ разрешён локально; ждём Glass. OpenClaw ещё не проверен запросом.";
        if (!capable) return "Откройте «Франциска» на Glass. Запись не начинается автоматически.";
        if ("busy".equals(assistState)) return "Вопрос обрабатывается — смотрите последнюю попытку";
        if ("error".equals(assistState)) return "Вопрос не завершён — смотрите причину ниже";
        return "Можно задать вопрос тапом на Glass. Доступ к OpenClaw проверится при отправке.";
    }
}
