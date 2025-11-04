package com.example.toto_app.nlp;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstructionRouter {

    private InstructionRouter(){}

    public enum Action {
        QUERY_TIME,
        QUERY_DATE,
        SET_ALARM,
        CALL,
        UNKNOWN
    }

    public static final class Result {
        public final Action action;
        public final Integer hour;
        public final Integer minute;
        public final String contactName;

        public Result(Action a, Integer h, Integer m, String c) {
            this.action = a; this.hour = h; this.minute = m; this.contactName = c;
        }
        public static Result of(Action a) { return new Result(a, null, null, null); }
    }

    public static Result route(String raw) {
        String pre = raw == null ? "" : raw;

        String t = " " + norm(pre) + " ";

        if (t.contains(" que hora ") || t.contains(" hora es ") || t.contains(" hora tenemos ")
                || t.contains(" hora actual ") || t.matches(".*\\b(dime|decime)\\s+la?\\s+hora\\b.*")) {
            return Result.of(Action.QUERY_TIME);
        }

        if (t.contains(" que dia es ") || t.contains(" que dia es hoy ") || t.contains(" que dia estamos ")
                || t.contains(" me decis el dia ") || t.contains(" me decis la fecha ")
                || t.contains(" decime el dia ") || t.contains(" decime la fecha ")
                || t.contains(" fecha de hoy ") || t.matches(".*\\b(cual|cual es)\\s+la\\s+fecha\\b.*")) {
            return Result.of(Action.QUERY_DATE);
        }

        if (t.contains(" alarma ") || t.contains(" despertame ") || t.contains(" despertarme ")
                || t.contains(" despertar ") || t.contains(" despertas ")
                || t.contains(" pone alarma ") || t.contains(" poneme una alarma ")
                || t.contains(" programa una alarma ") || t.contains(" programame una alarma ")) {

            Matcher m = Pattern.compile("\\ba\\s+las\\s+(\\d{1,2})(?::(\\d{1,2}))?\\b").matcher(t);
            if (m.find()) {
                int h = clamp(parseIntSafe(m.group(1), -1), 0, 23);
                int mm = clamp(parseIntSafe(m.group(2), 0), 0, 59);
                if (h >= 0) return new Result(Action.SET_ALARM, h, mm, null);
            }
            return Result.of(Action.SET_ALARM);
        }

        if (looksLikeCall(t)) {
            String who = extractContact(t);
            return new Result(Action.CALL, null, null, who);
        }

        return Result.of(Action.UNKNOWN);
    }



    private static boolean looksLikeCall(String t) {
        boolean hasLlamToken = t.matches(".*\\bllam\\w+\\b.*");

        boolean hasRequestPatterns =
                t.matches(".*\\b(podes|podrias|podria|puedes|podrias|puede|pueden)\\s+llamar\\b.*") ||
                        t.matches(".*\\b(quiero|quisiera|necesito|me\\s+gustaria)\\s+(?:que\\s+)?llam\\w*\\b.*");

        boolean hasPortunol =
                t.contains(" yama ")  || t.contains(" yamar ") || t.contains(" yamalo ") ||
                        t.contains(" chama ") || t.contains(" chamar ") || t.contains(" chamalo ") ||
                        t.contains(" shama ") || t.contains(" shamar ") || t.contains(" shamalo ");

        boolean hasSecondPerson = t.contains(" llamas ") || t.contains(" llamas a ");


        return hasLlamToken || hasRequestPatterns || hasPortunol || hasSecondPerson;
    }

    private static String extractContact(String t) {
        Pattern[] ps = new Pattern[] {
                Pattern.compile("\\bllam\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\bse\\s+llama(?:\\s+a)?\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\byam\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\bcham\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\bsham\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\b(?:quiero\\s+que\\s+)?llam\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\b(?:me\\s+)?(?:podes|podrias|podria|puedes|puede|pueden)\\s+llamar\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\b(quiero|quisiera|necesito)\\s+llamar\\s+a\\s+([a-z0-9\\s-]{1,40})\\b")
        };
        for (Pattern p : ps) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                String grp = null;
                for (int i = m.groupCount(); i >= 1; i--) {
                    String g = m.group(i);
                    if (g != null) { grp = g; break; }
                }
                if (grp != null) {
                    String cand = cleanContactCandidate(grp);
                    cand = stripLeadingAIfConsonant(cand);
                    if (!cand.isEmpty()) return cand;
                }
            }
        }

        Pattern noPrep = Pattern.compile(
                "\\b(?:llam\\w*|yam\\w*|cham\\w*|sham\\w*)\\s+([a-z0-9][a-z0-9\\s-]{1,40})\\b"
        );
        Matcher m = noPrep.matcher(t);
        if (m.find()) {
            String firstChunk = m.group(1).trim();
            String firstToken = firstChunk.split("\\s+")[0];
            String cand = cleanContactCandidate(firstToken);
            cand = stripLeadingAIfConsonant(cand);
            if (!cand.isEmpty()) return cand;
        }

        m = Pattern.compile("\\ba\\s+([a-z0-9\\s-]{1,40})\\b").matcher(t);
        if (m.find()) {
            String cand = cleanContactCandidate(m.group(1));
            cand = stripLeadingAIfConsonant(cand);
            if (!cand.isEmpty()) return cand;
        }
        return null;
    }

    private static String stripLeadingAIfConsonant(String s) {
        if (s == null || s.length() < 2) return s == null ? "" : s;
        if (s.charAt(0) == 'a') {
            char c = s.charAt(1);
            if ("bcdfghjklmnñpqrstvwxyz".indexOf(c) >= 0) {
                return s.substring(1);
            }
        }
        return s;
    }

    private static String cleanContactCandidate(String s) {
        if (s == null) return "";
        int cut = s.indexOf(" por favor"); if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf(" gracias");      if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf(" ahora");        if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf(" urgente");      if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf(" ya");           if (cut > 0) s = s.substring(0, cut);

        s = s.replaceAll("[^a-z0-9\\s-]", " ");
        s = s.replaceAll("\\s+", " ").trim();

        String[] tok = s.split("\\s+");
        int limit = Math.min(tok.length, 4);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) out.append(' ');
            out.append(tok[i]);
        }
        return out.toString();
    }

    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT);
        n = n.replaceAll("[¿?¡!.,;:()\\[\\]\"]", " ");
        n = n.replaceAll("\\s+", " ").trim();
        return n;
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (Exception ignored) { return def; }
    }
}
