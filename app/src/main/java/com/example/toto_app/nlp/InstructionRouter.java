package com.example.toto_app.nlp;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstructionRouter {

    public enum Action {
        CALL, SET_ALARM, QUERY_TIME, QUERY_DATE, NONE
    }

    public static final class Result {
        public final Action action;
        public final String contactName;
        public final Integer hour;
        public final Integer minute;

        private Result(Action a, String name, Integer h, Integer m) {
            this.action = a; this.contactName = name; this.hour = h; this.minute = m;
        }

        public static Result none() { return new Result(Action.NONE, null, null, null); }
        public static Result call(String name) { return new Result(Action.CALL, name, null, null); }
        public static Result time() { return new Result(Action.QUERY_TIME, null, null, null); }
        public static Result date() { return new Result(Action.QUERY_DATE, null, null, null); }
        public static Result alarm(int h, int m) { return new Result(Action.SET_ALARM, null, h, m); }
    }

    private InstructionRouter(){}

    public static Result route(String original) {
        if (original == null) return Result.none();
        String text = normalize(original);

        // 1) Hora/Fecha (rápidos)
        if (text.contains("que hora es") || text.contains("qué hora es") || text.contains("hora actual"))
            return Result.time();

        if (text.contains("que dia es hoy") || text.contains("qué dia es hoy") ||
                text.contains("que día es hoy") || text.contains("qué día es hoy") ||
                text.contains("fecha de hoy") || text.equals("fecha"))
            return Result.date();

        // 2) Llamadas: "llama a <nombre>", "llamá a <nombre>", "llamar a <nombre>"
        Matcher mCall = Pattern.compile("\\bllam(a|á)(r)?(me)?\\s+a\\s+(.+)$").matcher(text);
        if (mCall.find()) {
            String name = sentenceCase(mCall.group(4).trim());
            return Result.call(name);
        }
        // fallback: "llama <nombre>"
        mCall = Pattern.compile("\\bllam(a|á)\\s+(.+)$").matcher(text);
        if (mCall.find()) {
            String name = sentenceCase(mCall.group(2).trim());
            return Result.call(name);
        }

        // 3) Alarma: si menciona "alarma" o "despertador" + intentar parsear hora
        if (text.contains("alarma") || text.contains("despertador")) {
            TimeParse tp = parseTimeEs(text);
            if (tp != null) return Result.alarm(tp.hour, tp.minute);
        }
        // También soportar "pone/poneme ... a las X"
        if (text.matches(".*\\bpon(e|é)(me)?\\b.*") || text.contains("configura") || text.contains("configurá")) {
            if (text.contains("alarma") || text.contains("despertador")) {
                TimeParse tp = parseTimeEs(text);
                if (tp != null) return Result.alarm(tp.hour, tp.minute);
            }
        }

        // Nada detectado → fallback a LLM
        return Result.none();
    }

    // ===== Parser de hora español simple =====

    private static final Pattern P_HH_MM =
            Pattern.compile("a las\\s+(\\d{1,2})(?::|h)?(\\d{1,2})?\\s*(am|pm)?");
    private static final Pattern P_YMEDIA =
            Pattern.compile("a las\\s+(\\d{1,2})\\s+y\\s+media");
    private static final Pattern P_YCUARTO =
            Pattern.compile("a las\\s+(\\d{1,2})\\s+y\\s+cuarto");
    private static final Pattern P_MENOSCUARTO =
            Pattern.compile("a las\\s+(\\d{1,2})\\s+menos\\s+cuarto");

    private static final class TimeParse { final int hour, minute; TimeParse(int h,int m){hour=h;minute=m;} }

    private static TimeParse parseTimeEs(String text) {
        text = text.replace(".", ":");

        // a las 7:30 / 7h30 / 7 pm
        Matcher m = P_HH_MM.matcher(text);
        if (m.find()) {
            int h = clampHour(Integer.parseInt(m.group(1)));
            int min = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
            String ampm = m.group(3);

            // de la tarde/noche/mañana
            if (text.contains("de la tarde") || text.contains("de la noche"))
                if (h >= 1 && h <= 11) h += 12;
            if (text.contains("de la mañana"))
                if (h == 12) h = 0;

            if (ampm != null) {
                if (ampm.equals("pm") && h >= 1 && h <= 11) h += 12;
                if (ampm.equals("am") && h == 12) h = 0;
            }
            return new TimeParse(h, clampMinute(min));
        }

        // a las 7 y media
        m = P_YMEDIA.matcher(text);
        if (m.find()) { int h = clampHour(Integer.parseInt(m.group(1))); return new TimeParse(adjustPm(text, h), 30);}

        // a las 7 y cuarto
        m = P_YCUARTO.matcher(text);
        if (m.find()) { int h = clampHour(Integer.parseInt(m.group(1))); return new TimeParse(adjustPm(text, h), 15);}

        // a las 7 menos cuarto => 6:45
        m = P_MENOSCUARTO.matcher(text);
        if (m.find()) {
            int h = clampHour(Integer.parseInt(m.group(1)));
            h = adjustPm(text, h);
            h = (h == 0) ? 23 : (h - 1);
            return new TimeParse(h, 45);
        }

        // Palabras especiales
        if (text.contains("mediodia") || text.contains("mediodía")) return new TimeParse(12, 0);
        if (text.contains("medianoche")) return new TimeParse(0, 0);

        return null;
    }

    private static int adjustPm(String txt, int h) {
        if (txt.contains("de la tarde") || txt.contains("de la noche")) {
            if (h >= 1 && h <= 11) return h + 12;
        }
        return h;
    }

    private static int clampHour(int h) { return Math.max(0, Math.min(23, h)); }
    private static int clampMinute(int m) { return Math.max(0, Math.min(59, m)); }

    // ===== Utils =====
    private static String normalize(String s) {
        s = s.toLowerCase(Locale.ROOT).trim();
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[\\p{Punct}]+", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String sentenceCase(String name) {
        if (name == null || name.isEmpty()) return name;
        String[] parts = name.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
