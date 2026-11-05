package lab.franziska.glass;

/** Presentation-only projection of owned production state. Never grants capture or starts work. */
final class TalkHudState {
    final String connection, heading, cue, detail, control, hint;
    final boolean capturing;
    private TalkHudState(String connection, String heading, String cue, String detail,
                         String control, String hint, boolean capturing) {
        this.connection=connection; this.heading=heading; this.cue=cue;
        this.detail=bounded(detail); this.control=control; this.hint=hint; this.capturing=capturing;
    }
    static TalkHudState dialog(boolean ready, boolean active, boolean audioReady,
                               boolean inputSeen, boolean draining, String status, String transcript) {
        String connection=active ? "Разговор" : ready ? "Pixel на связи" : "Нет связи с Pixel";
        if (active) {
            if (draining) return new TalkHudState(connection, "Завершаю разговор",
                    "Новый вопрос — после завершения", transcript,
                    "Микрофон выключен · тап — Стоп", "Вниз — назад", false);
            if (!audioReady) return new TalkHudState(connection, "Подключаюсь…",
                    "Микрофон ещё не запущен", "",
                    "Тап — Стоп", "Вниз — назад", false);
            // There is no authoritative per-turn playback-complete signal here.
            // Text/received PCM must never become a claim of listening or audible completion.
            return new TalkHudState(connection, "Разговор идёт",
                    inputSeen ? "Следующий вопрос — без тапа" : "Запускаю микрофон…", transcript,
                    inputSeen ? "Микрофон активен · тап — Стоп" : "Тап — Стоп",
                    "Кнопка камеры — фото · вниз — назад", inputSeen);
        }
        return new TalkHudState(connection, ready ? "Чем помочь?" : "Нужен Pixel",
                ready ? "Тап — начать разговор" : "Открой Франциску на Pixel",
                "Чем помочь?".equals(status) ? "" : status,
                "Микрофон выключен", "Вниз — назад", false);
    }
    static TalkHudState photo(boolean pending, AssistPresentation.Stage stage, boolean resume) {
        String heading=pending ? "Готовлю фото…" : stage==AssistPresentation.Stage.RECORDING ? "Снимаю фото…"
                : stage==AssistPresentation.Stage.PREPARING ? "Готовлю фото…"
                : stage==AssistPresentation.Stage.SENDING ? "Отправляю фото…" : "Жду ответ…";
        boolean camera=!pending && stage==AssistPresentation.Stage.RECORDING;
        return new TalkHudState("Одно фото", heading, "Один кадр · без записи голоса",
                resume ? "После передачи — к разговору" : "Фото для Франциски",
                camera ? "Камера · тап — отменить" : "Камера и микрофон выключены",
                "Вниз — отменить", camera);
    }
    private static String bounded(String text) {
        if (text==null) return "";
        String clean=text.replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();
        // Bound layout work even for the maximum-length wire transcript; preserve Unicode pairs.
        int points=clean.codePointCount(0,clean.length());
        return points<=360 ? clean : clean.substring(0,clean.offsetByCodePoints(0,360))+"…";
    }
}
