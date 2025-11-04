package com.example.toto_app.services;

import android.os.SystemClock;
import androidx.annotation.Nullable;
import com.example.toto_app.network.PendingReminderDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Store for pending reminders similar to IncomingMessageStore for WhatsApp.
 * Manages the state of pending reminders that need to be announced to the user.
 */
public final class PendingReminderStore {

    private static final PendingReminderStore I = new PendingReminderStore();
    public static PendingReminderStore get() { return I; }
    
    private final Object lock = new Object();
    
    private List<PendingReminderDTO> pendingReminders = new ArrayList<>();
    private Set<Long> recentlyAnnouncedIds = new HashSet<>();
    private PendingReminderDTO lastAnnounced;
    private boolean awaitingConfirmation;
    private long lastAnnouncementMs;
    
    private static final long ANNOUNCEMENT_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes
    
    private PendingReminderStore() {}

    /**
     * Set the list of pending reminders from backend.
     */
    public void setPendingReminders(List<PendingReminderDTO> reminders) {
        synchronized (lock) {
            this.pendingReminders = reminders != null ? new ArrayList<>(reminders) : new ArrayList<>();
        }
    }

    /**
     * Get the next reminder to announce (one that hasn't been announced recently).
     */
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

    /**
     * Mark a reminder as announced.
     */
    public void markAnnounced(PendingReminderDTO reminder) {
        synchronized (lock) {
            if (reminder.getId() != null) {
                recentlyAnnouncedIds.add(reminder.getId());
            }
            this.lastAnnounced = reminder;
            this.lastAnnouncementMs = SystemClock.elapsedRealtime();
            
            // If it's a medication, mark as awaiting confirmation
            if (reminder.getIsMedication() != null && reminder.getIsMedication()) {
                this.awaitingConfirmation = true;
            }
        }
    }

    /**
     * Get the last announced reminder (for medication confirmation).
     */
    @Nullable
    public PendingReminderDTO getLastAnnounced() {
        synchronized (lock) {
            return lastAnnounced;
        }
    }

    /**
     * Clear the last announced reminder (after confirmation).
     */
    public void clearLastAnnounced() {
        synchronized (lock) {
            this.lastAnnounced = null;
            this.awaitingConfirmation = false;
        }
    }

    /**
     * Check if we're awaiting medication confirmation.
     */
    public boolean isAwaitingMedicationConfirm() {
        synchronized (lock) {
            return awaitingConfirmation;
        }
    }

    /**
     * Clear all recently announced IDs (called periodically to allow re-announcement).
     */
    public void clearRecentlyAnnounced() {
        synchronized (lock) {
            recentlyAnnouncedIds.clear();
        }
    }
}
