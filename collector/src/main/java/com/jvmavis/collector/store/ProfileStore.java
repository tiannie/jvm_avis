package com.jvmavis.collector.store;

import com.jvmavis.collector.model.ProfileSnapshot;

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
