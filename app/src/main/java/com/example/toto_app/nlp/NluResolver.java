// NluResolver.java
package com.example.toto_app.nlp;

import android.util.Log;

import com.example.toto_app.network.NluRouteRequest;
import com.example.toto_app.network.NluRouteResponse;
import com.example.toto_app.network.RetrofitClient;

import java.text.Normalizer;
import java.util.Calendar;
import java.util.Locale;

import retrofit2.Response;

public final class NluResolver {

    private static final String TAG = "NluResolver";

    private NluResolver() {}

    /**
     * Intenta resolver con el backend (/api/nlu/route).
     * Si falla o hay timeout/excepción, aplica fallbacks locales (ahora incluye ALARMAS).
     */
    public static NluRouteResponse resolveWithFallback(String transcript) {
        return resolveWithFallback(transcript, null);
    }

    /**
     * Intenta resolver con el backend (/api/nlu/route), opcionalmente con contexto conversacional.
     * Si falla o hay timeout/excepción, aplica fallbacks locales (ahora incluye ALARMAS).
     */
    public static NluRouteResponse resolveWithFallback(String transcript, java.util.Map<String, Object> context) {
        // 1) Prioridad: backend
        try {
            NluRouteRequest rq = new NluRouteRequest();
            rq.text = transcript; // locale/tz se resuelven por default del backend
            rq.context = context;  // Pass conversational context if available

            Response<NluRouteResponse> r = RetrofitClient.api().nluRoute(rq).execute();
            if (r.isSuccessful() && r.body() != null && r.body().intent != null) {
                return r.body();
            } else {
                Log.w(TAG, "NLU backend not OK → HTTP=" + (r != null ? r.code() : -1));
            }
        } catch (Exception e) {
            Log.e(TAG, "NLU backend call failed", e);
        }

        // 2) FALLBACKS LOCALES (solo si falló el backend)
        //    2.1) Alarmas: gateo + parseo (relativo/absoluto)
        NluRouteResponse alarm = tryAlarmFallback(transcript);
        if (alarm != null) return alarm;

        //    2.n) Otros fallbacks locales, si quisieras, irían acá

        // 3) Sin match → UNKNOWN (InstructionService caerá a /api/ask)
        NluRouteResponse out = new NluRouteResponse();
        out.intent = "UNKNOWN";
        out.confidence = 0.0;
        out.needs_confirmation = false;
        out.slots = new NluRouteResponse.Slots();
        return out;
    }

    // ====== FALLBACK: ALARMAS ======

    private static NluRouteResponse tryAlarmFallback(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        if (!looksLikeAlarmRequest(raw)) return null;

        // Relativo: "en 10", "en 1 hora", "dentro de 20 minutos"…
        QuickTimeParser.Relative rel = QuickTimeParser.parseRelativeEsAr(raw);
        if (rel != null && rel.minutesTotal > 0) {
            Calendar target = Calendar.getInstance();
            target.add(Calendar.MINUTE, rel.minutesTotal);
            int hh = target.get(Calendar.HOUR_OF_DAY);
            int mm = target.get(Calendar.MINUTE);
            return makeSetAlarm(hh, mm);
        }

        // Absoluto: "a las 7", "a las siete y media", "a las 13:45"…
        QuickTimeParser.Absolute abs = QuickTimeParser.parseAbsoluteEsAr(raw);
        if (abs != null) {
            Calendar now = Calendar.getInstance();
            Calendar target = Calendar.getInstance();
            target.set(Calendar.HOUR_OF_DAY, abs.hour24);
            target.set(Calendar.MINUTE, abs.minute);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);
            if (target.before(now)) target.add(Calendar.DATE, 1); // si ya pasó, mañana
            int hh = target.get(Calendar.HOUR_OF_DAY);
            int mm = target.get(Calendar.MINUTE);
            return makeSetAlarm(hh, mm);
        }

        return null;
    }

    private static NluRouteResponse makeSetAlarm(int hh, int mm) {
        NluRouteResponse r = new NluRouteResponse();
        r.intent = "SET_ALARM";
        r.confidence = 0.92;            // confianza alta, pero debajo del backend
        r.needs_confirmation = false;
        r.ack_tts = "Listo, programo la alarma.";
        r.slots = new NluRouteResponse.Slots();
        r.slots.hour = hh;
        r.slots.minute = mm;
        // r.slots.datetime_iso = null; r.slots.contact_query = null; r.slots.message_text = null;
        return r;
    }

    private static boolean looksLikeAlarmRequest(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[¿?¡!.,;:()\\[\\]\"]", " ");
        s = " " + s.replaceAll("\\s+", " ").trim() + " ";
        return s.contains(" alarma ")
                || s.contains(" despert") // despertame, despertador, etc.
                || s.contains(" pone una alarma ")
                || s.contains(" poneme una alarma ")
                || s.contains(" pone alarma ")
                || s.contains(" poneme alarma ")
                || s.contains(" programa una alarma ")
                || s.contains(" programame una alarma ");
    }
}
