package com.example.toto_app.nlp;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QuickTimeParser {

    private QuickTimeParser(){}

    // ===== Modelos de salida =====
    public static final class Relative {
        public final int minutesTotal;
        public Relative(int minutesTotal){ this.minutesTotal = minutesTotal; }
    }
    public static final class Absolute {
        public final int hour24;
        public final int minute;
        public Absolute(int hour24, int minute){ this.hour24 = hour24; this.minute = minute; }
    }

    // ====== Util ======
    private static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private static final Map<String,Integer> WORD2NUM = new HashMap<>();
    static {
        // básicos
        WORD2NUM.put("uno",1); WORD2NUM.put("una",1); WORD2NUM.put("un",1);
        WORD2NUM.put("dos",2); WORD2NUM.put("tres",3); WORD2NUM.put("cuatro",4);
        WORD2NUM.put("cinco",5); WORD2NUM.put("seis",6); WORD2NUM.put("siete",7);
        WORD2NUM.put("ocho",8); WORD2NUM.put("nueve",9); WORD2NUM.put("diez",10);
        WORD2NUM.put("once",11); WORD2NUM.put("doce",12); WORD2NUM.put("trece",13);
        WORD2NUM.put("catorce",14); WORD2NUM.put("quince",15); WORD2NUM.put("veinte",20);
        WORD2NUM.put("treinta",30); WORD2NUM.put("cuarenta",40); WORD2NUM.put("cincuenta",50);

        // compuestos frecuentes para minutos
        WORD2NUM.put("veinticinco",25);
        WORD2NUM.put("veintidos",22); WORD2NUM.put("veintitres",23); WORD2NUM.put("veinticuatro",24);
        WORD2NUM.put("treinta y cinco",35);
        WORD2NUM.put("cuarenta y cinco",45);
        WORD2NUM.put("cincuenta y cinco",55);

        // palabras especiales de minutos
        WORD2NUM.put("media",30); WORD2NUM.put("medio",30); // minutos, no horas
        WORD2NUM.put("cuarto",15);
        WORD2NUM.put("quince",15);
    }

    private static Integer wordToInt(String w) {
        return WORD2NUM.get(norm(w));
    }

    // ====== RELATIVE ======
    public static Relative parseRelativeEsAr(String textRaw) {
        String text = " " + norm(textRaw) + " ";

        // 0) “en media hora”, “dentro de media hora”
        if (text.matches(".*\\b(en|dentro de)\\s+media\\s+hora(s)?\\b.*")) {
            return new Relative(30);
        }
        // 0b) “en un cuarto de hora”
        if (text.matches(".*\\b(en|dentro de)\\s+(un\\s+)?cuarto\\s+de\\s+hora\\b.*")) {
            return new Relative(15);
        }

        // 1) X minutos
        Matcher mMin = Pattern.compile("\\b(en|dentro de)\\s+(\\d{1,3})\\s+(minutos?|mins?)\\b").matcher(text);
        if (mMin.find()) {
            int mins = Math.max(1, Integer.parseInt(mMin.group(2)));
            return new Relative(mins);
        }
        Matcher mMinW = Pattern.compile("\\b(en|dentro de)\\s+([a-z\\s]+?)\\s+(minutos?|mins?)\\b").matcher(text);
        if (mMinW.find()) {
            Integer val = wordToInt(mMinW.group(2));
            if (val != null) return new Relative(Math.max(1, val));
        }

        // 2) X horas (con o sin “y media”)
        // ej: “en 2 horas”, “en dos horas”, “en una hora y media”
        Matcher mHourNum = Pattern.compile("\\b(en|dentro de)\\s+(\\d{1,2})\\s+horas?\\b").matcher(text);
        if (mHourNum.find()) {
            int h = Math.max(1, Integer.parseInt(mHourNum.group(2)));
            int minutes = h * 60;
            // ¿tiene “y media” después?
            if (text.substring(mHourNum.end()).matches(".*\\by\\s+media\\b.*")) minutes += 30;
            return new Relative(minutes);
        }
        Matcher mHourWord = Pattern.compile("\\b(en|dentro de)\\s+([a-z]+)\\s+horas?\\b").matcher(text);
        if (mHourWord.find()) {
            Integer h = wordToInt(mHourWord.group(2));
            if (h != null) {
                int minutes = Math.max(1, h) * 60;
                if (text.substring(mHourWord.end()).matches(".*\\by\\s+media\\b.*")) minutes += 30;
                return new Relative(minutes);
            }
        }

        // 2b) “en una hora y media” (hora en singular)
        Matcher mHourSing = Pattern.compile("\\b(en|dentro de)\\s+(un|una|1)\\s+hora\\b").matcher(text);
        if (mHourSing.find()) {
            int minutes = 60;
            if (text.substring(mHourSing.end()).matches(".*\\by\\s+media\\b.*")) minutes += 30;
            return new Relative(minutes);
        }

        // 3) “en dos horas y quince”, “en 1 hora y 20”
        Matcher mHourPlusMin = Pattern.compile("\\b(en|dentro de)\\s+(\\d+|[a-z]+)\\s+horas?\\s+y\\s+(\\d+|[a-z\\s]+)\\b").matcher(text);
        if (mHourPlusMin.find()) {
            Integer h = null, mm = null;
            String hTok = mHourPlusMin.group(2), mTok = mHourPlusMin.group(3);
            h = hTok.matches("\\d+") ? Integer.parseInt(hTok) : wordToInt(hTok);
            mTok = mTok.trim();
            mm = mTok.matches("\\d+") ? Integer.parseInt(mTok) : wordToInt(mTok);
            if (h != null && mm != null) return new Relative(Math.max(1, h*60 + mm));
        }

        return null;
    }

    // ====== ABSOLUTE ======
    public static Absolute parseAbsoluteEsAr(String raw) {
        String text = norm(raw);

        // mediodía / medianoche
        if (text.contains("mediodia")) return new Absolute(12, 0);
        if (text.contains("medianoche")) return new Absolute(0, 0);

        // 1) “a las 19:30”, “7:05”, “7h05”, “7.05”
        Matcher colon = Pattern.compile("\\b(?:a\\s+las|para\\s+las)?\\s*(\\d{1,2})[:h\\.](\\d{1,2})\\b").matcher(text);
        if (colon.find()) {
            int h = clamp(Integer.parseInt(colon.group(1)), 0, 23);
            int m = clamp(Integer.parseInt(colon.group(2)), 0, 59);
            h = applyAmPm(h, text);
            return new Absolute(h, m);
        }

        // 1b) “a las 7 30” (espacio como separador)
        Matcher spaceSep = Pattern.compile("\\b(?:a\\s+las|para\\s+las)?\\s*(\\d{1,2})\\s+(\\d{2})\\b").matcher(text);
        if (spaceSep.find()) {
            int h = clamp(Integer.parseInt(spaceSep.group(1)), 0, 23);
            int m = clamp(Integer.parseInt(spaceSep.group(2)), 0, 59);
            h = applyAmPm(h, text);
            return new Absolute(h, m);
        }

        // 2) “a las 7 y media / cuarto / quince / 30”
        Matcher yMediaNum = Pattern.compile("\\b(?:a\\s+las|para\\s+las)?\\s*(\\d{1,2})\\s+y\\s+(media|cuarto|quince|\\d{1,2})\\b").matcher(text);
        if (yMediaNum.find()) {
            int h = clamp(Integer.parseInt(yMediaNum.group(1)), 0, 23);
            String mTok = yMediaNum.group(2);
            int m = ("media".equals(mTok) ? 30 : "cuarto".equals(mTok) || "quince".equals(mTok) ? 15 : clamp(parseIntSafe(mTok, 0), 0, 59));
            h = applyAmPm(h, text);
            return new Absolute(h, m);
        }

        // 3) Hora en palabras + “y …” (ej: “ocho y media”, “ocho y 25”, “once y veinte”)
        String HORA_WORD_RE = "(?:una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)";
        String MIN_WORD_RE  = "(?:media|cuarto|quince|cinco|diez|veinte|veinticinco|treinta|treinta y cinco|cuarenta|cuarenta y cinco|cincuenta|cincuenta y cinco)";
        Matcher yMediaWord = Pattern.compile("\\b(?:a\\s+las|para\\s+las)?\\s*(" + HORA_WORD_RE + ")\\s+y\\s+(" + MIN_WORD_RE + "|\\d{1,2})\\b").matcher(text);
        if (yMediaWord.find()) {
            Integer h = wordToInt(yMediaWord.group(1));
            String mTok = yMediaWord.group(2).trim();
            Integer m = mTok.matches("\\d{1,2}") ? clamp(parseIntSafe(mTok, 0), 0, 59) : wordToInt(mTok);
            if (h != null && m != null) {
                h = applyAmPm(h, text);
                return new Absolute(h, m);
            }
        }

        // 4) “ocho menos cuarto”, “8 menos cuarto”
        Matcher menosCuartoNum = Pattern.compile("\\b(?:a\\s+las|para\\s+las)?\\s*(\\d{1,2})\\s+menos\\s+cuarto\\b").matcher(text);
        if (menosCuartoNum.find()) {
            int h = clamp(Integer.parseInt(menosCuartoNum.group(1)), 0, 23);
            h = applyAmPm(h, text);
            h = (h + 23) % 24; // hora-1 con wrap
            return new Absolute(h, 45);
        }
        Matcher menosCuartoWord = Pattern.compile("\\b(?:a\\s+las|para\\s+las)?\\s*(" + HORA_WORD_RE + ")\\s+menos\\s+cuarto\\b").matcher(text);
        if (menosCuartoWord.find()) {
            Integer h = wordToInt(menosCuartoWord.group(1));
            if (h != null) {
                h = applyAmPm(h, text);
                h = (h + 23) % 24;
                return new Absolute(h, 45);
            }
        }

        // 5) “a las 7”, “once de la noche”
        Matcher onlyH = Pattern.compile("\\b(?:a\\s+las|para\\s+las)?\\s*(\\d{1,2})\\b").matcher(text);
        if (onlyH.find()) {
            int h = clamp(Integer.parseInt(onlyH.group(1)), 0, 23);
            int m = 0;
            h = applyAmPm(h, text);
            return new Absolute(h, m);
        }
        Matcher onlyHWord = Pattern.compile("\\b(?:a\\s+las|para\\s+las)?\\s*(" + HORA_WORD_RE + ")\\b").matcher(text);
        if (onlyHWord.find()) {
            Integer h = wordToInt(onlyHWord.group(1));
            if (h != null) {
                h = applyAmPm(h, text);
                return new Absolute(h, 0);
            }
        }

        return null;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Aplica “de la tarde/noche/mañana”, “am/pm” al rango 24h cuando viene hora sin 24h explícito. */
    private static int applyAmPm(int hour, String textRaw) {
        String text = " " + textRaw + " ";
        boolean hasPM = text.contains(" pm ") || text.contains(" de la tarde ") || text.contains(" de la noche ") || text.contains(" del mediodia ");
        boolean hasAM = text.contains(" am ") || text.contains(" de la manana ") || text.contains(" de la madrugada ");

        if (hasPM) {
            if (hour == 12) return 12;        // 12 pm = 12
            if (hour >= 1 && hour <= 11) return hour + 12; // 1..11 pm = 13..23
        } else if (hasAM) {
            if (hour == 12) return 0;         // 12 am = 00
            return hour;                      // 1..11 am = 1..11
        }
        return hour;
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (Exception ignored) { return def; }
    }
}
