package com.example.toto_app.falls;

import android.util.Log;

import com.example.toto_app.network.WhatsAppSendRequest;
import com.example.toto_app.network.WhatsAppSendResponse;
import com.example.toto_app.network.RetrofitClient;

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
                || norm.contains(" estoy mal ")
                || norm.contains(" me duele ")
                || norm.contains(" me lastime ") || norm.contains(" me lastimé ")
                || norm.contains(" no me puedo mover ")
                || norm.contains(" no puedo levantarme ") || norm.contains(" no puedo pararme ")
                || norm.contains(" ayuda ") || norm.contains(" ayudame ") || norm.contains(" ayúdame ")
                || norm.contains(" auxilio ") || norm.contains(" emergencia ") || norm.contains(" ambulancia ")
                || norm.contains(" doctor ") || norm.contains(" medico ") || norm.contains(" médico ");
    }

    public static boolean mentionsFall(String norm) {
        return norm.contains(" me cai ") || norm.contains(" me caí ")
                || norm.contains(" me caigo ") || norm.contains(" me estoy cayendo ")
                || norm.contains(" caida ") || norm.contains(" caída ")
                || norm.contains(" me tropece ") || norm.contains(" me tropecé ")
                || norm.contains(" me pegue ") || norm.contains(" me pegué ")
                || norm.contains(" me desmaye ") || norm.contains(" me desmayé ");
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
            return false;
        }
    }
}
