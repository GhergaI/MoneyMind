package dev.igherga.moneymind.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Creating, editing and archiving savings goals.
 *
 * <p>The property that matters most here: progress is never written. A goal's
 * saved figure is derived from the account balance it mirrors or from the
 * contributions tagged against it, so setting a goal can never move it and
 * moving money always does.
 */
@SpringBootTest(properties = {
        // The seeder's own goals would be counted alongside these fixtures.
        "moneymind.demo-data.enabled=false",
        "moneymind.default-currency=EUR",
})
class GoalServiceTest {

    @DynamicPropertySource
    static void useTemporaryDatabase(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("moneymind-goals-test");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dir.resolve("test.db") + "?journal_mode=WAL&foreign_keys=on");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GoalService goals;

    private long savingsAccount;
    private long currentAccount;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM savings_contribution");
        jdbc.update("DELETE FROM savings_goal");
        jdbc.update("DELETE FROM txn");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM account");

        savingsAccount = account("Savings", "SAVINGS", "EUR", 240000);
        currentAccount = account("Current", "CURRENT", "EUR", 100000);
    }

    @Test
    @DisplayName("a new goal appears on the screen with its target")
    void createsAGoal() {
        goals.create(new GoalCommand("New laptop", 150000, LocalDate.of(2027, 3, 1),
                savingsAccount, FundingMode.ACCOUNT_BALANCE));

        GoalsView view = goals.view();
        assertThat(view.currency()).isEqualTo("EUR");
        assertThat(view.goals()).hasSize(1);

        SavingsGoalDetail goal = view.goals().getFirst();
        assertThat(goal.name()).isEqualTo("New laptop");
        assertThat(goal.target().minorUnits()).isEqualTo(150000);
        assertThat(goal.targetDate()).isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(goal.fundingMode()).isEqualTo(FundingMode.ACCOUNT_BALANCE);
    }

    @Test
    @DisplayName("an account-balance goal reads its progress from the account, not from a stored figure")
    void accountBalanceGoalMirrorsTheAccount() {
        goals.create(new GoalCommand("Emergency fund", 500000, null,
                savingsAccount, FundingMode.ACCOUNT_BALANCE));

        assertThat(goals.view().goals().getFirst().saved().minorUnits()).isEqualTo(240000);

        // Money arriving in the account moves the goal with it. Nothing about
        // the goal row changed; the figure is a projection.
        jdbc.update("INSERT INTO txn (account_id, category_id, kind, amount_minor, currency, booked_on) "
                + "VALUES (?, (SELECT id FROM category WHERE system_key = 'OTHER_INCOME'), "
                + "'INCOME', 60000, 'EUR', '2026-09-06')", savingsAccount);

        assertThat(goals.view().goals().getFirst().saved().minorUnits()).isEqualTo(300000);
    }

    @Test
    @DisplayName("an earmarked goal starts at nothing rather than at the shared balance")
    void earmarkedGoalStartsEmpty() {
        goals.create(new GoalCommand("Holiday", 200000, null,
                savingsAccount, FundingMode.EARMARKED));

        SavingsGoalDetail goal = goals.view().goals().getFirst();
        assertThat(goal.saved().minorUnits()).isZero();
        assertThat(goal.reachedFraction()).isZero();
    }

    @Test
    @DisplayName("a goal with no deadline is an ordinary goal")
    void deadlineIsOptional() {
        goals.create(new GoalCommand("Rainy day", 100000, null,
                savingsAccount, FundingMode.ACCOUNT_BALANCE));

        assertThat(goals.view().goals().getFirst().targetDate()).isNull();
    }

    @Test
    @DisplayName("dated goals come first, soonest deadline leading, undated last")
    void ordersBySoonestDeadline() {
        goals.create(new GoalCommand("Someday", 100000, null, savingsAccount, FundingMode.EARMARKED));
        goals.create(new GoalCommand("Car", 900000, LocalDate.of(2028, 1, 1),
                savingsAccount, FundingMode.EARMARKED));
        goals.create(new GoalCommand("Laptop", 150000, LocalDate.of(2027, 3, 1),
                savingsAccount, FundingMode.EARMARKED));

        assertThat(goals.view().goals())
                .extracting(SavingsGoalDetail::name)
                .containsExactly("Laptop", "Car", "Someday");
    }

    @Test
    @DisplayName("a goal needs a name and a target above zero")
    void rejectsNonsense() {
        assertThatThrownBy(() -> goals.create(new GoalCommand("  ", 150000, null,
                savingsAccount, FundingMode.ACCOUNT_BALANCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a name");

        assertThatThrownBy(() -> goals.create(new GoalCommand("Laptop", 0, null,
                savingsAccount, FundingMode.ACCOUNT_BALANCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than zero");

        assertThatThrownBy(() -> goals.create(new GoalCommand("Laptop", -150000, null,
                savingsAccount, FundingMode.ACCOUNT_BALANCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than zero");
    }

    @Test
    @DisplayName("a goal that mirrors an account balance cannot be created without one")
    void accountBalanceGoalNeedsAnAccount() {
        assertThatThrownBy(() -> goals.create(new GoalCommand("Laptop", 150000, null,
                null, FundingMode.ACCOUNT_BALANCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs an account");
    }

    @Test
    @DisplayName("an unknown account is refused as a sentence, not as a foreign key")
    void refusesAnUnknownAccount() {
        assertThatThrownBy(() -> goals.create(new GoalCommand("Laptop", 150000, null,
                9999L, FundingMode.ACCOUNT_BALANCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No account with id 9999");
    }

    @Test
    @DisplayName("editing a goal changes the intent and leaves the timeline alone")
    void updatesAGoal() {
        goals.create(new GoalCommand("Laptop", 150000, LocalDate.of(2027, 3, 1),
                savingsAccount, FundingMode.ACCOUNT_BALANCE));
        long id = goals.view().goals().getFirst().id();
        LocalDate startedOn = goals.view().goals().getFirst().startedOn();

        goals.update(id, new GoalCommand("Laptop and monitor", 200000, LocalDate.of(2027, 6, 1),
                currentAccount, FundingMode.ACCOUNT_BALANCE));

        SavingsGoalDetail goal = goals.view().goals().getFirst();
        assertThat(goal.id()).isEqualTo(id);
        assertThat(goal.name()).isEqualTo("Laptop and monitor");
        assertThat(goal.target().minorUnits()).isEqualTo(200000);
        assertThat(goal.targetDate()).isEqualTo(LocalDate.of(2027, 6, 1));
        assertThat(goal.accountId()).isEqualTo(currentAccount);
        // The pace rule measures from here, so a rename must not restart it.
        assertThat(goal.startedOn()).isEqualTo(startedOn);
    }

    @Test
    @DisplayName("editing a goal that is not there says so")
    void refusesToUpdateAMissingGoal() {
        assertThatThrownBy(() -> goals.update(4242, new GoalCommand("Ghost", 100000, null,
                savingsAccount, FundingMode.EARMARKED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No savings goal with id 4242");
    }

    @Test
    @DisplayName("archiving takes a goal off the screen without deleting the record")
    void archivesRatherThanDeletes() {
        goals.create(new GoalCommand("Laptop", 150000, null,
                savingsAccount, FundingMode.EARMARKED));
        long id = goals.view().goals().getFirst().id();

        goals.archive(id);

        assertThat(goals.view().goals()).isEmpty();
        // Still on the record, with the date it was put away.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM savings_goal WHERE id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT archived_at FROM savings_goal WHERE id = ?",
                String.class, id)).isNotNull();
    }

    @Test
    @DisplayName("archiving something already gone is reported, not silently accepted")
    void refusesToArchiveTwice() {
        goals.create(new GoalCommand("Laptop", 150000, null,
                savingsAccount, FundingMode.EARMARKED));
        long id = goals.view().goals().getFirst().id();
        goals.archive(id);

        assertThatThrownBy(() -> goals.archive(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No savings goal with id " + id);
    }

    private long account(String name, String type, String currency, long opening) {
        jdbc.update("INSERT INTO account (name, type, currency, opening_balance_minor) "
                + "VALUES (?, ?, ?, ?)", name, type, currency, opening);
        return jdbc.queryForObject("SELECT id FROM account WHERE name = ?", Long.class, name);
    }
}
