package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@link MetalGpuQueryPool} index-bounds contract.
 *
 * <p>Before the fix, the getters performed no bounds validation: an out-of-range
 * query surfaced an opaque {@link ArrayIndexOutOfBoundsException} (with a
 * {@code null} message) thrown from the backing array / {@link System#arraycopy}.
 * After the fix the pool rejects the out-of-range query with a single, clearly
 * worded {@link IndexOutOfBoundsException}, keeping the behaviour for valid
 * inputs unchanged.
 */
final class MetalGpuQueryPoolTest {

    private MetalGpuQueryPool newPool(final int size) {
        return new MetalGpuQueryPool(size);
    }

    @Test
    void validValueRoundTripIsPreserved() {
        MetalGpuQueryPool pool = newPool(4);
        pool.setValue(0, 42L);
        pool.setValue(3, 7L);

        assertEquals(OptionalLong.of(42L), pool.getValue(0));
        assertEquals(OptionalLong.of(7L), pool.getValue(3));
        assertEquals(OptionalLong.empty(), pool.getValue(1));
        assertEquals(4, pool.size());
    }

    @Test
    void getValuesReturnsRequestedWindow() {
        MetalGpuQueryPool pool = newPool(4);
        pool.setValue(1, 11L);
        pool.setValue(2, 22L);

        OptionalLong[] values = pool.getValues(1, 2);
        assertEquals(2, values.length);
        assertArrayEquals(
                new OptionalLong[]{OptionalLong.of(11L), OptionalLong.of(22L)},
                values);
    }

    @Test
    void getValuesFullRangeIsAllowed() {
        MetalGpuQueryPool pool = newPool(2);
        assertEquals(2, pool.getValues(0, 2).length);
    }

    @Test
    void outOfRangeSingleReadThrowsClearMessage() {
        MetalGpuQueryPool pool = newPool(2);
        IndexOutOfBoundsException e = assertThrows(
                IndexOutOfBoundsException.class, () -> pool.getValue(2));
        assertTrue(e.getMessage() != null && e.getMessage().contains("out of range"),
                "expected a descriptive message but was: " + e.getMessage());
    }

    @Test
    void outOfRangeWindowReadThrowsClearMessage() {
        MetalGpuQueryPool pool = newPool(2);
        // index + count overshoots size() — previously an opaque AIOOBE from arraycopy.
        IndexOutOfBoundsException e = assertThrows(
                IndexOutOfBoundsException.class, () -> pool.getValues(1, 2));
        assertTrue(e.getMessage() != null && e.getMessage().contains("out of range"),
                "expected a descriptive message but was: " + e.getMessage());
    }

    @Test
    void negativeIndexReadThrowsClearMessage() {
        MetalGpuQueryPool pool = newPool(2);
        IndexOutOfBoundsException e = assertThrows(
                IndexOutOfBoundsException.class, () -> pool.getValue(-1));
        assertTrue(e.getMessage() != null && e.getMessage().contains("out of range"),
                "expected a descriptive message but was: " + e.getMessage());
    }

    @Test
    void outOfRangeWriteThrowsClearMessage() {
        MetalGpuQueryPool pool = newPool(2);
        IndexOutOfBoundsException e = assertThrows(
                IndexOutOfBoundsException.class, () -> pool.setValue(2, 5L));
        assertTrue(e.getMessage() != null && e.getMessage().contains("out of range"),
                "expected a descriptive message but was: " + e.getMessage());
    }

    @Test
    void getValuesAfterCloseYieldsEmptySlots() {
        MetalGpuQueryPool pool = newPool(2);
        pool.setValue(0, 1L);
        pool.close();
        assertFalse(pool.getValues(0, 2)[0].isPresent());
    }
}