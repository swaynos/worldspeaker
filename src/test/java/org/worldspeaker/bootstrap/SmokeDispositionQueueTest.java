package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeDispositionQueueTest {
    @Test
    void ordersByOriginalDeadlineAndKeepsStableTies() {
        SmokeDispositionQueue<String> queue = new SmokeDispositionQueue<>();
        assertTrue(queue.offer("later", 20, 0));
        assertTrue(queue.offer("tie-second", 10, 2));
        assertTrue(queue.offer("tie-first", 10, 1));

        assertEquals("tie-first", queue.remove());
        assertEquals("tie-second", queue.remove());
        assertEquals("later", queue.remove());
        assertNull(queue.remove());
    }

    @Test
    void hasFixedCapacityRejectsOverflowAndDuplicateOwnership() {
        SmokeDispositionQueue<Integer> queue = new SmokeDispositionQueue<>();
        for (int value = 0; value < SmokeDispositionQueue.CAPACITY; value++) {
            assertTrue(queue.offer(value, value, value));
        }
        assertFalse(queue.offer(16, 16, 16));
        assertFalse(queue.offer(0, 0, 0));
        assertEquals(SmokeDispositionQueue.CAPACITY, queue.size());
        queue.clear();
        assertEquals(0, queue.size());
    }

    @Test
    void removesOneRetiredRequestWithoutReorderingTheRemainingDispositions() {
        SmokeDispositionQueue<String> queue = new SmokeDispositionQueue<>();
        assertTrue(queue.offer("first", 10, 0));
        assertTrue(queue.offer("second", 20, 1));
        assertTrue(queue.offer("third", 30, 2));

        assertTrue(queue.remove("second"));
        assertFalse(queue.remove("second"));
        assertEquals("first", queue.remove());
        assertEquals("third", queue.remove());
        assertEquals(0, queue.size());
    }

    @Test
    void futureCooldownEntryDoesNotBlockAnOtherPlayersReadyDeadline() {
        SmokeDispositionQueue<String> queue = new SmokeDispositionQueue<>();
        assertTrue(queue.offer("cooldown", 100, 10, 0));
        assertTrue(queue.offer("ready-player", 0, 20, 1));

        assertEquals(20L, queue.peekReadyDeadline(0));
        assertEquals("ready-player", queue.removeReady(0));
        assertEquals(1, queue.size());
        assertEquals(10L, queue.peekReadyDeadline(100));
        assertEquals("cooldown", queue.removeReady(100));
    }
}
