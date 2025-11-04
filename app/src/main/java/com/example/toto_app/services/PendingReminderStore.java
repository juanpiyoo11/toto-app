package com.example.toto_app.services;

import android.os.SystemClock;
import androidx.annotation.Nullable;
import com.example.toto_app.network.PendingReminderDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PendingReminderStore {

    private static final PendingReminderStore I = new PendingReminderStore();
    public static PendingReminderStore get() { return I; }
    
    private final Object lock = new Object();
    
    private List<PendingReminderDTO> pendingReminders = new ArrayList<>();
    private Set<Long> recentlyAnnouncedIds = new HashSet<>();
    private PendingReminderDTO lastAnnounced;
    private boolean awaitingConfirmation;
    private long lastAnnouncementMs;
    
    private static final long ANNOUNCEMENT_COOLDOWN_MS = 5 * 60 * 1000;
    
    private PendingReminderStore() {}

    public void setPendingReminders(List<PendingReminderDTO> reminders) {
        synchronized (lock) {
            this.pendingReminders = reminders != null ? new ArrayList<>(reminders) : new ArrayList<>();
        }
    }

    @Nullable
    public PendingReminderDTO getNextToAnnounce() {
        synchronized (lock) {
            long now = SystemClock.elapsedRealtime();
            
            for (PendingReminderDTO reminder : pendingReminders) {
                if (reminder.getId() != null && !recentlyAnnouncedIds.contains(reminder.getId())) {
                    return reminder;
                }
            }
            return null;
        }
    }

    public void markAnnounced(PendingReminderDTO reminder) {
        synchronized (lock) {
            if (reminder.getId() != null) {
                recentlyAnnouncedIds.add(reminder.getId());
            }
            this.lastAnnounced = reminder;
            this.lastAnnouncementMs = SystemClock.elapsedRealtime();

            if (reminder.getIsMedication() != null && reminder.getIsMedication()) {
                this.awaitingConfirmation = true;
            }
        }
    }

    @Nullable
    public PendingReminderDTO getLastAnnounced() {
        synchronized (lock) {
            return lastAnnounced;
        }
    }

    public void clearLastAnnounced() {
        synchronized (lock) {
            this.lastAnnounced = null;
            this.awaitingConfirmation = false;
        }
    }

    public boolean isAwaitingMedicationConfirm() {
        synchronized (lock) {
            return awaitingConfirmation;
        }
    }

    public void clearRecentlyAnnounced() {
        synchronized (lock) {
            recentlyAnnouncedIds.clear();
        }
    }
}
