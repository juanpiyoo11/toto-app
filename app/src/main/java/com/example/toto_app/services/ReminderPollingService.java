package com.example.toto_app.services;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.toto_app.network.RetrofitClient;
import com.example.toto_app.network.PendingReminderDTO;
import com.example.toto_app.util.UserDataManager;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Background service that polls the backend for pending reminders.
 * Similar to how WhatsApp messages are handled: announces when Toto is free.
 */
public class ReminderPollingService extends Service {

    private static final String TAG = "ReminderPolling";
    private static final long POLL_INTERVAL_MS = 120000; // 2 minutes

    private Handler handler;
    private Runnable pollRunnable;
    private boolean isRunning = false;
    private UserDataManager userDataManager;

    @Override
    public void onCreate() {
        super.onCreate();
        userDataManager = new UserDataManager(this);
        handler = new Handler(Looper.getMainLooper());
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    pollForReminders();
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                }
            }
        };
        Log.d(TAG, "ReminderPollingService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            handler.post(pollRunnable);
            Log.i(TAG, "Reminder polling started");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (handler != null) {
            handler.removeCallbacks(pollRunnable);
        }
        Log.i(TAG, "Reminder polling stopped");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollForReminders() {
        Long elderlyId = userDataManager.getUserId();
        if (elderlyId == null) {
            Log.w(TAG, "No elderly ID configured, skipping poll");
            return;
        }

        Log.d(TAG, "Polling for reminders for elderlyId=" + elderlyId);
        
        try {
            RetrofitClient.api().getPendingReminders(elderlyId)
                    .enqueue(new Callback<List<PendingReminderDTO>>() {
                        @Override
                        public void onResponse(Call<List<PendingReminderDTO>> call, Response<List<PendingReminderDTO>> response) {
                            if (response.isSuccessful() && response.body() != null) {
                                handlePendingReminders(response.body());
                            } else {
                                Log.w(TAG, "Failed to fetch pending reminders: " + response.code());
                            }
                        }

                        @Override
                        public void onFailure(Call<List<PendingReminderDTO>> call, Throwable t) {
                            Log.e(TAG, "Error fetching pending reminders", t);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Exception during polling", e);
        }
    }

    private void handlePendingReminders(List<PendingReminderDTO> reminders) {
        if (reminders == null || reminders.isEmpty()) {
            return;
        }

        Log.i(TAG, "Received " + reminders.size() + " pending reminders");
        PendingReminderStore.get().setPendingReminders(reminders);

        // Get next reminder to announce (hasn't been announced recently)
        PendingReminderDTO nextReminder = PendingReminderStore.get().getNextToAnnounce();
        if (nextReminder == null) {
            return;
        }

        // Check if we're already waiting for medication confirmation
        if (PendingReminderStore.get().isAwaitingMedicationConfirm()) {
            Log.d(TAG, "Already awaiting medication confirmation, skipping new announcement");
            return;
        }

        // Announce the reminder via WakeWordService (it will enqueue if busy)
        announceReminder(nextReminder);
    }

    private void announceReminder(PendingReminderDTO reminder) {
        Log.i(TAG, "Announcing reminder: " + reminder.getTitle());

        Intent i = new Intent(this, WakeWordService.class);
        i.setAction(WakeWordService.ACTION_SAY);
        i.putExtra("text", reminder.getTtsMessage());
        i.putExtra(WakeWordService.EXTRA_ENQUEUE_IF_BUSY, true);
        i.putExtra(WakeWordService.EXTRA_AFTER_SAY_START_SERVICE, true);
        i.putExtra("reminder_id", reminder.getId());
        i.putExtra("is_medication", reminder.getIsMedication() != null && reminder.getIsMedication());
        
        // Mark as announced in store
        PendingReminderStore.get().markAnnounced(reminder);
        
        // Start instruction service to handle response
        androidx.core.content.ContextCompat.startForegroundService(this, i);

        // Notify backend that reminder was announced
        notifyBackendReminderAnnounced(reminder.getId(), reminder.getElderlyId());
    }

    private void notifyBackendReminderAnnounced(Long reminderId, Long elderlyId) {
        try {
            RetrofitClient.api()
                    .markReminderAnnounced(reminderId, elderlyId)
                    .enqueue(new Callback<Void>() {
                        @Override
                        public void onResponse(Call<Void> call, Response<Void> response) {
                            if (response.isSuccessful()) {
                                Log.d(TAG, "Notified backend: reminder " + reminderId + " announced");
                            }
                        }

                        @Override
                        public void onFailure(Call<Void> call, Throwable t) {
                            Log.w(TAG, "Failed to notify backend about announcement", t);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error notifying backend", e);
        }
    }
}
