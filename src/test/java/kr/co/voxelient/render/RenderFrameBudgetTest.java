package kr.co.voxelient.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFrameBudgetTest {

    @Test
    void tryUse_ShouldStopAtItemLimit() {
        AtomicLong now = new AtomicLong(0L);
        RenderFrameBudget budget = RenderFrameBudget.usingClock(2, 10, now::get);

        assertTrue(budget.tryUse());
        assertTrue(budget.tryUse());
        assertFalse(budget.tryUse());
        assertEquals(2, budget.usedItems());
    }

    @Test
    void tryUse_ShouldStopAtTimeBudgetAfterFirstItem() {
        AtomicLong now = new AtomicLong(0L);
        RenderFrameBudget budget = RenderFrameBudget.usingClock(4, 3, now::get);

        assertTrue(budget.tryUse());
        now.set(4_000_000L);

        assertFalse(budget.tryUse());
        assertEquals(1, budget.usedItems());
    }

    @Test
    void tryUse_ShouldAlwaysAllowFirstItemWhenItemBudgetAllowsIt() {
        AtomicLong now = new AtomicLong(10_000_000L);
        RenderFrameBudget budget = RenderFrameBudget.usingClock(1, 0, now::get);

        assertTrue(budget.tryUse());
        assertFalse(budget.tryUse());
    }

    @Test
    void tryUse_ShouldRejectNonPositiveItemBudgets() {
        AtomicLong now = new AtomicLong(0L);
        RenderFrameBudget budget = RenderFrameBudget.usingClock(0, 10, now::get);

        assertFalse(budget.tryUse());
    }
}
