package dev.igherga.moneymind.savings;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code savings_goal} as native SQL.
 *
 * <p>Only the stored intent lives here — name, target, deadline, funding. What
 * has actually been saved is a projection over the ledger and belongs to
 * {@code ReportingQueries}, which already computes it for the dashboard; asking
 * two different queries the same question is how the Goals screen and the
 * dashboard would end up disagreeing.
 */
@Repository
public class GoalRepository {

    private final JdbcTemplate jdbc;

    GoalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every live goal in one currency, ordered the way the dashboard orders
     * them: soonest deadline first, undated goals last.
     */
    public List<GoalRow> live(String currency) {
        return jdbc.query("""
                SELECT id, name, target_minor, currency, target_date, account_id, funding_mode,
                       date(created_at) AS started_on
                  FROM savings_goal
                 WHERE archived_at IS NULL
                   AND currency = ?
                 ORDER BY target_date IS NULL, target_date, name
                """,
                (rs, row) -> new GoalRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getLong("target_minor"),
                        rs.getString("currency"),
                        optionalDate(rs.getString("target_date")),
                        optionalId(rs),
                        FundingMode.valueOf(rs.getString("funding_mode")),
                        LocalDate.parse(rs.getString("started_on"))),
                currency);
    }

    public long insert(String name, long targetMinor, String currency, LocalDate targetDate,
            Long accountId, FundingMode fundingMode) {
        jdbc.update("""
                INSERT INTO savings_goal (name, target_minor, currency, target_date, account_id,
                                          funding_mode)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                name, targetMinor, currency, targetDate == null ? null : targetDate.toString(),
                accountId, fundingMode.name());

        return jdbc.queryForObject("SELECT MAX(id) FROM savings_goal", Long.class);
    }

    /**
     * Replaces a live goal's editable fields. {@code created_at} is left alone
     * deliberately: it is the start of the timeline the pace rule measures
     * against, and moving it would rewrite history every time a name was fixed.
     */
    public int update(long id, String name, long targetMinor, String currency, LocalDate targetDate,
            Long accountId, FundingMode fundingMode) {
        return jdbc.update("""
                UPDATE savings_goal
                   SET name         = ?,
                       target_minor = ?,
                       currency     = ?,
                       target_date  = ?,
                       account_id   = ?,
                       funding_mode = ?
                 WHERE id = ?
                   AND archived_at IS NULL
                """,
                name, targetMinor, currency, targetDate == null ? null : targetDate.toString(),
                accountId, fundingMode.name(), id);
    }

    /**
     * Archives a goal rather than deleting it.
     *
     * <p>A reached goal is a thing that happened, and its contributions point at
     * real transactions — deleting the row would either fail on those foreign
     * keys or, worse, take the history with it. Every read filters
     * {@code archived_at IS NULL}, so archiving removes it from the screens
     * without removing it from the record.
     */
    public int archive(long id, LocalDate on) {
        return jdbc.update(
                "UPDATE savings_goal SET archived_at = ? WHERE id = ? AND archived_at IS NULL",
                on.toString(), id);
    }

    /** The funding account, if it exists and is still live. */
    public Optional<FundingAccount> account(long accountId) {
        try {
            return Optional.of(jdbc.queryForObject("""
                    SELECT id, currency
                      FROM account
                     WHERE id = ?
                       AND archived_at IS NULL
                    """,
                    (rs, row) -> new FundingAccount(rs.getLong("id"), rs.getString("currency")),
                    accountId));
        } catch (EmptyResultDataAccessException absent) {
            return Optional.empty();
        }
    }

    private static LocalDate optionalDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    /**
     * {@code account_id} read as a nullable long. sqlite-jdbc hands back an
     * {@code Integer} for any INTEGER that fits in one, so casting
     * {@code getObject} compiles and then throws on real data.
     */
    private static Long optionalId(java.sql.ResultSet rs) throws java.sql.SQLException {
        long value = rs.getLong("account_id");
        return rs.wasNull() ? null : value;
    }

    /** The stored side of a goal, with no derived progress in it. */
    public record GoalRow(
            long id,
            String name,
            long targetMinor,
            String currency,
            LocalDate targetDate,
            Long accountId,
            FundingMode fundingMode,
            LocalDate startedOn) {
    }

    public record FundingAccount(long id, String currency) {
    }
}
