package com.example.toto_app.falls;

import android.util.Log;

import com.example.toto_app.network.WhatsAppSendRequest;
import com.example.toto_app.network.WhatsAppSendResponse;
import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.services.PendingEmergencyStore;

import java.text.Normalizer;
import java.util.Locale;

public final class FallLogic {
    private FallLogic(){}

    public enum FallReply { OK, HELP, UNKNOWN }

    public static String normEs(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        s = s.replaceAll("([.,])", " $1 ");
        s = s.replaceAll("[¿?¡!;:()\\[\\]\"]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return " " + s + " ";
    }

    public static boolean saysHelp(String norm) {
        return norm.contains(" no estoy bien ")
                || norm.contains(" no esta bien ")
                || norm.contains(" me caí ")
                || norm.contains(" me he caído ")
                || norm.contains(" estoy mal ")
                || norm.contains(" me duele ")
                || norm.contains(" duele ")
                || norm.contains(" dolor ")
                || norm.contains(" dolió ")
                || norm.contains(" me lastime ") || norm.contains(" me lastimé ")
                || norm.contains(" no me puedo mover ")
                || norm.contains(" no puedo levantarme ") || norm.contains(" no puedo pararme ")
                || norm.contains(" ayuda ") || norm.contains(" ayudame ") || norm.contains(" ayúdame ")
                || norm.contains(" auxilio ") || norm.contains(" emergencia ") || norm.contains(" ambulancia ")
                || norm.contains(" doctor ") || norm.contains(" medico ") || norm.contains(" médico ");
    }

    public static boolean mentionsFall(String norm) {
        // expanded forms: handle variations like "cae", "cayo", "resbalé" and also fuzzy matches
        if (norm.contains(" me cai ") || norm.contains(" me ca\u00ed ")
                || norm.contains(" me caigo ") || norm.contains(" me estoy cayendo ")
                || norm.contains(" caida ") || norm.contains(" ca\u00edda ")
                || norm.contains(" me tropece ") || norm.contains(" me tropec\u00e9 ")
                || norm.contains(" me pegue ") || norm.contains(" me pegu\u00e9 ")
                || norm.contains(" me desmaye ") || norm.contains(" me desmay\u00e9 ")) return true;

        // verbs and stems
        if (norm.contains(" cae ") || norm.contains(" cayo ") || norm.contains(" caigo ")) return true;
        if (norm.contains(" resbale ") || norm.contains(" resbal\u00e9 ") || norm.contains(" resbal\u00f3 ") || norm.contains(" resbalo ")) return true;

        // fuzzy token-level check for common misspellings near "cai"/"cae"
        String s = norm.trim();
        String[] toks = s.split("\\s+");
        for (String t : toks) {
            String tt = t.replaceAll("[^a-zA-Z0-9]", "");
            if (approxEqual(tt, "cai", 1) || approxEqual(tt, "cayo", 1) || approxEqual(tt, "cae", 1)) return true;
        }
        return false;
    }

    private static boolean approxEqual(String a, String b, int maxDist) {
        if (a == null || b == null) return false;
        int d = levenshtein(a, b);
        return d <= maxDist;
    }

    // simple Levenshtein
    private static int levenshtein(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++) costs[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]), a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }

    private static boolean saysOk(String norm) {
        if (norm.contains(" no me puedo mover ")
                || norm.contains(" no puedo levantarme ") || norm.contains(" no puedo pararme ")
                || norm.contains(" estoy mal ")
                || norm.contains(" me duele ")
                || norm.contains(" me lastime ") || norm.contains(" me lastimé ")) {
            return false;
        }
        if (norm.matches(".*\\bno\\s+estoy\\s+bien\\b.*")) return false;
        if (norm.matches(".*\\bno\\s+esta\\s+bien\\b.*")) return false;
        if (norm.contains(" estoy bien ")
                || norm.contains(" esta bien ")
                || norm.contains(" esta todo bien ")
                || norm.contains(" todo bien ")
                || norm.contains(" todo ok ")
                || norm.contains(" estoy ok ")
                || norm.contains(" tranquilo ") || norm.contains(" tranquila ")
                || norm.contains(" ya estoy bien ")
                || norm.contains(" no fue nada ")
                || norm.contains(" no te preocupes ")
                || norm.contains(" no hay problema ")
                || norm.contains(" no estoy mal ")
                || norm.contains(" no me paso nada ") || norm.contains(" no me pasó nada ")
                || norm.matches(".*\\b(si|sí)\\b.*")) {
            return true;
        }
        return false;
    }

    private static boolean hasStandaloneNo(String norm) {
        return norm.matches(".*\\bno\\b.*")
                && !norm.contains(" no fue nada ")
                && !norm.contains(" no te preocupes ")
                && !norm.contains(" no hay problema ")
                && !norm.contains(" no gracias ")
                && !saysOk(norm);
    }

    public static FallReply assessFallReply(String norm) {
        if (saysHelp(norm)) return FallReply.HELP;
        if (saysOk(norm))   return FallReply.OK;
        if (mentionsFall(norm)) return FallReply.HELP;
        if (hasStandaloneNo(norm)) return FallReply.HELP;
        return FallReply.UNKNOWN;
    }

    // ===== Emergencia (idéntico comportamiento) =====
    public static String buildEmergencyText(String userName) {
        String u = (userName == null || userName.isBlank()) ? "la persona" : userName;
        return "⚠️ Alerta: " + u + " puede haberse caído o pidió ayuda. "
                + "Este aviso fue enviado automáticamente por Toto para que puedan comunicarse";
    }

    public static boolean sendEmergencyMessageTo(String numberE164, String userName) {
        try {
            String to = numberE164.replaceAll("[^0-9+]", "");
            String msg = buildEmergencyText(userName);
            WhatsAppSendRequest wreq = new WhatsAppSendRequest(to, msg, Boolean.FALSE);
            retrofit2.Response<WhatsAppSendResponse> wresp = RetrofitClient.api().waSend(wreq).execute();
            WhatsAppSendResponse wbody = wresp.body();
            return wresp.isSuccessful()
                    && wbody != null
                    && ("ok".equalsIgnoreCase(wbody.status)
                    || "ok_template".equalsIgnoreCase(wbody.status)
                    || (wbody.id != null && !wbody.id.trim().isEmpty()));
        } catch (Exception ex) {
            Log.e("FallLogic", "Error enviando WhatsApp a emergencia", ex);
            // Si falló, encolar para reintentar cuando el backend vuelva
            try {
                PendingEmergencyStore.get().add(numberE164, userName);
            } catch (Exception e2) { Log.e("FallLogic", "No se pudo encolar emergencia", e2); }
            try { com.example.toto_app.services.BackendHealthManager.get().markFailure(); } catch (Exception ignore) {}
            return false;
        }
    }

    /**
     * Result codes: 0=sent, 1=queued (backend down), 2=failed
     */
    public static int sendEmergencyMessageToResult(String numberE164, String userName) {
        try {
            String to = numberE164.replaceAll("[^0-9+]", "");
            String msg = buildEmergencyText(userName);
            WhatsAppSendRequest wreq = new WhatsAppSendRequest(to, msg, Boolean.FALSE);
            retrofit2.Response<WhatsAppSendResponse> wresp = RetrofitClient.api().waSend(wreq).execute();
            WhatsAppSendResponse wbody = wresp.body();
            boolean ok = wresp.isSuccessful()
                    && wbody != null
                    && ("ok".equalsIgnoreCase(wbody.status)
                    || "ok_template".equalsIgnoreCase(wbody.status)
                    || (wbody.id != null && !wbody.id.trim().isEmpty()));
            if (ok) return 0;
            // si la respuesta no fue ok consideramos que hay algún problema de backend (queue)
            PendingEmergencyStore.get().add(numberE164, userName);
            try { com.example.toto_app.services.BackendHealthManager.get().markFailure(); } catch (Exception ignore) {}
            return 1;
        } catch (Exception ex) {
            Log.e("FallLogic", "Error enviando WhatsApp a emergencia", ex);
            try {
                PendingEmergencyStore.get().add(numberE164, userName);
            } catch (Exception e2) { Log.e("FallLogic", "No se pudo encolar emergencia", e2); }
            try { com.example.toto_app.services.BackendHealthManager.get().markFailure(); } catch (Exception ignore) {}
            return 1;
        }
    }

    /**
     * Send emergency message to multiple contacts.
     * Returns the worst result: 0=all sent, 1=some queued, 2=all failed
     */
    public static int sendEmergencyMessageToMultiple(java.util.List<String> phoneNumbers, String userName) {
        if (phoneNumbers == null || phoneNumbers.isEmpty()) return 2;
        
        int worstResult = 0;
        int sentCount = 0;
        
        for (String phone : phoneNumbers) {
            if (phone == null || phone.trim().isEmpty()) continue;
            
            int result = sendEmergencyMessageToResult(phone, userName);
            if (result > worstResult) worstResult = result;
            if (result == 0) sentCount++;
        }
        
        Log.d("FallLogic", "Emergency messages sent to " + sentCount + "/" + phoneNumbers.size() + " contacts");
        return worstResult;
    }
}
