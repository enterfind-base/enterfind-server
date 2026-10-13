package enterfind.server.config;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import enterfind.server.exception.InvalidArgumentException;

/**
 * Rate-limits AI-helper resolver calls when appliance-mode is on.
 * Outside appliance-mode every call to {@link #consumeOrThrow()} is a no-op.
 *
 * Configured via:
 *   enterfind.appliance-mode.ai-query-budget-per-day=100
 *
 * The counter is in-memory and resets at UTC midnight. A server restart also
 * resets it — acceptable because restart-spam attacks need a separate
 * deployment-layer defence regardless.
 */
@Component
public class AiQueryBudgetTracker {

    private final ApplianceMode applianceMode;
    private final int budgetPerDay;
    private final AtomicInteger consumedToday = new AtomicInteger(0);
    private volatile LocalDate budgetDate = LocalDate.now(ZoneOffset.UTC);

    public AiQueryBudgetTracker(
            ApplianceMode applianceMode,
            @Value("${enterfind.appliance-mode.ai-query-budget-per-day:100}") int budgetPerDay) {
        this.applianceMode = applianceMode;
        this.budgetPerDay = budgetPerDay;
    }

    /**
     * If appliance-mode is on, increments the daily counter and throws
     * {@link InvalidArgumentException} once the budget is exhausted.
     * If appliance-mode is off, returns immediately without any side-effects.
     */
    public synchronized void consumeOrThrow() {
        if (!applianceMode.isEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(budgetDate)) {
            budgetDate = today;
            consumedToday.set(0);
        }
        if (consumedToday.incrementAndGet() > budgetPerDay) {
            throw new InvalidArgumentException("Daily AI query budget exhausted");
        }
    }
}
