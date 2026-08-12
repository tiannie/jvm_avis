package com.jvmavis.collector.store;

import com.jvmavis.collector.model.ProfileSnapshot;
import com.jvmavis.collector.profile.ProfileMerger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class ProfileStore {
    private static final int MAX_SNAPSHOTS = 120;

    private final Map<String, ConcurrentLinkedDeque<ProfileSnapshot>> byTarget = new ConcurrentHashMap<>();

    public void add(String targetId, ProfileSnapshot snapshot) {
        ConcurrentLinkedDeque<ProfileSnapshot> series =
                byTarget.computeIfAbsent(targetId, id -> new ConcurrentLinkedDeque<>());
        series.addLast(snapshot);
        while (series.size() > MAX_SNAPSHOTS) {
            series.pollFirst();
        }
    }

    public Optional<ProfileSnapshot> latest(String targetId) {
        ConcurrentLinkedDeque<ProfileSnapshot> series = byTarget.get(targetId);
        if (series == null || series.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(series.peekLast());
    }

    /**
     * Merges every dump landing within {@code windowMs} of the newest one. Dumps are incremental,
     * so this is what reconstitutes a full profiling window for display.
     */
    public Optional<ProfileSnapshot> rolling(String targetId, long windowMs) {
        ConcurrentLinkedDeque<ProfileSnapshot> series = byTarget.get(targetId);
        if (series == null || series.isEmpty()) {
            return Optional.empty();
        }
        List<ProfileSnapshot> all = new ArrayList<>(series);
        // Cut against the target's own clock rather than the collector's to avoid skew.
        long cutoff = all.get(all.size() - 1).windowEndMs() - windowMs;
        List<ProfileSnapshot> inWindow = new ArrayList<>();
        for (ProfileSnapshot snapshot : all) {
            if (snapshot.windowEndMs() >= cutoff) {
                inWindow.add(snapshot);
            }
        }
        return Optional.ofNullable(ProfileMerger.merge(inWindow));
    }

    public List<ProfileSnapshot> history(String targetId) {
        ConcurrentLinkedDeque<ProfileSnapshot> series = byTarget.get(targetId);
        if (series == null) {
            return List.of();
        }
        return new ArrayList<>(series);
    }

    public void remove(String targetId) {
        byTarget.remove(targetId);
    }
}
