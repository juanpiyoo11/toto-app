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
        public final Integer hour;      // para SET_ALARM
        public final Integer minute;    // para SET_ALARM
        public final String contactName;// para CALL

        public Result(Action a, Integer h, Integer m, String c) {
            this.action = a; this.hour = h; this.minute = m; this.contactName = c;
        }
        public static Result of(Action a) { return new Result(a, null, null, null); }
    }

    public static Result route(String raw) {
        // 1) Despegar preposición “a” pegada a nombre en algunos casos (lo dejamos muy conservador)
        String pre = raw == null ? "" : raw;

        // 2) Normalización
        String t = " " + norm(pre) + " ";

        // ====== HORA ======
        if (t.contains(" que hora ") || t.contains(" hora es ") || t.contains(" hora tenemos ")
                || t.contains(" hora actual ") || t.matches(".*\\b(dime|decime)\\s+la?\\s+hora\\b.*")) {
            return Result.of(Action.QUERY_TIME);
        }

        // ====== FECHA / DÍA ======
        if (t.contains(" que dia es ") || t.contains(" que dia es hoy ") || t.contains(" que dia estamos ")
                || t.contains(" me decis el dia ") || t.contains(" me decis la fecha ")
                || t.contains(" decime el dia ") || t.contains(" decime la fecha ")
                || t.contains(" fecha de hoy ") || t.matches(".*\\b(cual|cual es)\\s+la\\s+fecha\\b.*")) {
            return Result.of(Action.QUERY_DATE);
        }

        // ====== ALARMA ======
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

        // ====== LLAMAR ======
        if (looksLikeCall(t)) {
            String who = extractContact(t);
            return new Result(Action.CALL, null, null, who);
        }

        return Result.of(Action.UNKNOWN);
    }

    // --- Helpers ---

    /**
     * CALL robusto y amplio:
     * - Verbos: todas las formas de “llamar” (llama, llamás, llamas, llame, llames, llamen, llamar, llamada, llamalo, llamame…)
     * - Pedidos de capacidad/permisos: podés/podrías/puedes/puede/pueden llamar…
     * - Intenciones: quiero/quisiera/necesito llamar…
     * - Variantes de STT/portuñol: yama/chama/shama…
     * - Frases interrogativas y afirmativas por igual.
     */
    private static boolean looksLikeCall(String t) {
        // 1) Cualquier palabra que empiece con "llam" (llama, llamas, llamás, llamar, llamada, llamalo, llamame, llames, llame, llamen…)
        boolean hasLlamToken = t.matches(".*\\bllam\\w+\\b.*");

        // 2) Pedidos de capacidad / permiso / intención comunes
        boolean hasRequestPatterns =
                t.matches(".*\\b(podes|podrias|podria|puedes|podrias|puede|pueden)\\s+llamar\\b.*") ||
                        t.matches(".*\\b(quiero|quisiera|necesito|me\\s+gustaria)\\s+(?:que\\s+)?llam\\w*\\b.*");

        // 3) Variantes por STT/portuñol (yamar/chamar/shamar…) y objetos directos (yamalo/chamalo/shamalo)
        boolean hasPortunol =
                t.contains(" yama ")  || t.contains(" yamar ") || t.contains(" yamalo ") ||
                        t.contains(" chama ") || t.contains(" chamar ") || t.contains(" chamalo ") ||
                        t.contains(" shama ") || t.contains(" shamar ") || t.contains(" shamalo ");

        // 4) Casos explícitos adicionales que antes no se tomaban:
        //    “ llamas ” (2da persona indicativo sin acento) y “ llamas a ”
        boolean hasSecondPerson = t.contains(" llamas ") || t.contains(" llamas a ");

        // Nota: el string ya viene acolchonado con espacios al inicio/fin, así que los contains con espacios tienen límites de palabra razonables.

        return hasLlamToken || hasRequestPatterns || hasPortunol || hasSecondPerson;
    }

    private static String extractContact(String t) {
        // 1) Con preposición “a” (genérico sobre la raíz “llam” y variantes)
        Pattern[] ps = new Pattern[] {
                // cualquier forma que empiece con “llam…”, ej: llamame/llamar/llames/llame/llamas…
                Pattern.compile("\\bllam\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                // “se llama (a) …” (mantenemos amplio a pedido del usuario)
                Pattern.compile("\\bse\\s+llama(?:\\s+a)?\\s+([a-z0-9\\s-]{1,40})\\b"),
                // portuñol / STT
                Pattern.compile("\\byam\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\bcham\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\bsham\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                // pedidos más largos (acepta “me” opcional)
                Pattern.compile("\\b(?:quiero\\s+que\\s+)?llam\\w*\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\b(?:me\\s+)?(?:podes|podrias|podria|puedes|puede|pueden)\\s+llamar\\s+a\\s+([a-z0-9\\s-]{1,40})\\b"),
                Pattern.compile("\\b(quiero|quisiera|necesito)\\s+llamar\\s+a\\s+([a-z0-9\\s-]{1,40})\\b")
        };
        for (Pattern p : ps) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                // seleccionar último grupo no-nulo (algunos patrones tienen 2 grupos por el verbo previo)
                String grp = null;
                for (int i = m.groupCount(); i >= 1; i--) {
                    String g = m.group(i);
                    if (g != null) { grp = g; break; }
                }
                if (grp != null) {
                    String cand = cleanContactCandidate(grp);
                    cand = stripLeadingAIfConsonant(cand); // “akevin” → “kevin”
                    if (!cand.isEmpty()) return cand;
                }
            }
        }

        // 2) Sin preposición (ej: “shama akevin”, “llamalo kevin”, “llamas kevin”)
        Pattern noPrep = Pattern.compile(
                "\\b(?:llam\\w*|yam\\w*|cham\\w*|sham\\w*)\\s+([a-z0-9][a-z0-9\\s-]{1,40})\\b"
        );
        Matcher m = noPrep.matcher(t);
        if (m.find()) {
            String firstChunk = m.group(1).trim();
            String firstToken = firstChunk.split("\\s+")[0];
            String cand = cleanContactCandidate(firstToken);
            cand = stripLeadingAIfConsonant(cand); // “akevin” → “kevin”
            if (!cand.isEmpty()) return cand;
        }

        // 3) Fallback muy laxo “a …”
        m = Pattern.compile("\\ba\\s+([a-z0-9\\s-]{1,40})\\b").matcher(t);
        if (m.find()) {
            String cand = cleanContactCandidate(m.group(1));
            cand = stripLeadingAIfConsonant(cand);
            if (!cand.isEmpty()) return cand;
        }
        return null;
    }

    /** Quita “a” inicial si la siguiente letra es consonante (indicio de preposición pegada: “a+kevin”). No toca nombres reales que empiezan con vocal (ana, agustin…). */
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
        // recortes de muletillas comunes
        int cut = s.indexOf(" por favor"); if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf(" gracias");      if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf(" ahora");        if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf(" urgente");      if (cut > 0) s = s.substring(0, cut);
        cut = s.indexOf(" ya");           if (cut > 0) s = s.substring(0, cut);

        s = s.replaceAll("[^a-z0-9\\s-]", " ");
        s = s.replaceAll("\\s+", " ").trim();

        // limitar a 4 tokens
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
