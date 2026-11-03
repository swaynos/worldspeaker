package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTickBudgetTest {
    @Test
    void capsOperationStartsAndDeadlineChecksIndependently() {
        SmokeTickBudget budget = new SmokeTickBudget(100);
        for (int index = 0; index < SmokeTickBudget.MAX_OPERATIONS; index++) budget.operationStarted();
        assertEquals(8, budget.operationsStarted());
        assertFalse(budget.mayStartOperation(101));
        assertThrows(IllegalStateException.class, budget::operationStarted);

        for (int index = 0; index < SmokeTickBudget.MAX_DEADLINE_RECORDS; index++) {
            assertTrue(budget.mayCheckDeadline());
            budget.deadlineChecked();
        }
        assertEquals(17, budget.deadlineRecordsChecked());
        assertFalse(budget.mayCheckDeadline());
        assertThrows(IllegalStateException.class, budget::deadlineChecked);
    }

    @Test
    void stopsBeginningOperationsAfterOneMillisecondButDoesNotPreemptStartedWork() {
        SmokeTickBudget budget = new SmokeTickBudget(1_000);
        assertTrue(budget.mayStartOperation(1_000_999));
        budget.operationStarted();
        assertFalse(budget.mayStartOperation(1_001_000));
        assertEquals(1, budget.operationsStarted());
    }

    @Test
    void reservesSixReplyAndTwoUrgentSlotsWhenBothClassesHaveBacklog() {
        SmokeTickBudget budget = new SmokeTickBudget(0);
        for (int index = 0; index < 6; index++) {
            assertTrue(budget.mayStartOperation(index, SmokeTickBudget.WorkClass.REPLY, 3));
            budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
        }
        assertFalse(budget.mayStartOperation(6, SmokeTickBudget.WorkClass.REPLY, 3));
        for (int index = 0; index < 2; index++) {
            assertTrue(budget.mayStartOperation(6 + index, SmokeTickBudget.WorkClass.RESERVED, 17));
            budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
        }
        assertFalse(budget.mayStartOperation(8, SmokeTickBudget.WorkClass.RESERVED, 17));
        assertEquals(8, budget.operationsStarted());
    }
}
