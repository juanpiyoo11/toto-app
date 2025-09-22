package com.example.toto_app.util;

public final class TtsSanitizer {
    private TtsSanitizer(){}

    public static String sanitizeForTTS(String s) {
        if (s == null) return "";
        String out = s;

        // Bloques de código / md básico
        out = out.replaceAll("(?s)```.*?```", " ");
        out = out.replace("`", "");
        out = out.replaceAll("!\\[(.*?)\\]\\((.*?)\\)", "$1");
        out = out.replaceAll("\\[(.*?)\\]\\((.*?)\\)", "$1");
        out = out.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        out = out.replaceAll("(?m)^\\s*>\\s?", "");

        // Énfasis markdown
        out = out.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "$1");
        out = out.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        out = out.replaceAll("\\*(.+?)\\*", "$1");

        // Listas / numeración
        out = out.replaceAll("(?m)^\\s*([-*+]|•)\\s+", "— ");
        out = out.replaceAll("(?m)^\\s*(\\d+)[\\.)]\\s+", "$1: ");
        out = out.replace(" - ", ", ");
        out = out.replaceAll("\\((.*?)\\)", ", $1, ");
        out = out.replace(":", ", ");

        // Saltos de línea → pausa
        out = out.replaceAll("\\r?\\n\\s*\\r?\\n", " … ");
        out = out.replaceAll("\\r?\\n", " … ");
        out = out.replace("*", ""); // asteriscos sueltos

        // Espacios
        out = out.replaceAll("\\s{2,}", " ").trim();

        out = ensureSpanishOpeners(out);
        if (!out.matches(".*[\\.!?…]$")) out = out + ".";
        return out;
    }

    private static String ensureSpanishOpeners(String text) {
        String[] parts = text.split("(?<=[\\.\\!\\?…])\\s+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.endsWith("?") && !p.startsWith("¿")) parts[i] = "¿" + p;
            else if (p.endsWith("!") && !p.startsWith("¡")) parts[i] = "¡" + p;
            else parts[i] = p;
        }
        return String.join(" ", parts);
    }
}
