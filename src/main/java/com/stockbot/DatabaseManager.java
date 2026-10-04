package com.stockbot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded SQLite persistence. A single connection guarded by one lock keeps every
 * multi-statement operation (buy, sell, daily, delist) atomic.
 * Economies are isolated per guild: users and portfolios are keyed by (guild_id, user_id).
 */
public final class DatabaseManager implements AutoCloseable {

    // ---------------------------------------------------------------- records
    public record Stock(String channelId, String guildId, String name, double price,
                        int uniqueChatters, long lastUpdated) {}

    public record Position(String channelId, long shares, double avgBuyPrice, double currentPrice) {}

    public record LeaderboardRow(String userId, double netWorth) {}

    public record EventRow(long id, String guildId, String type, String target,
                           long startedAt, long expiresAt) {}

    public record StockUpdate(String channelId, double price, int uniqueChatters) {}

    public record HaltState(boolean halted, String reason) {}

    public record DailyResult(boolean claimed, double balance, long nextAvailableAt) {}

    public record TradeResult(boolean ok, String error, long shares, double price, double gross,
                              double fee, double net, double cash, long remainingShares,
                              double avgBuyPrice, double realizedPnl) {
        static TradeResult fail(String msg) {
            return new TradeResult(false, msg, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record Held(long shares, double avg) {}

    public static final class DatabaseException extends RuntimeException {
        public DatabaseException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    // ----------------------------------------------------------------- state
    private final Connection conn;
    private final Object lock = new Object();
    private final double startingCash;
    private final Map<String, HaltState> haltCache = new ConcurrentHashMap<>();

    public DatabaseManager(String jdbcUrl, double startingCash) {
        this.startingCash = startingCash;
        try {
            conn = DriverManager.getConnection(jdbcUrl);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS users (
                            guild_id   TEXT    NOT NULL,
                            user_id    TEXT    NOT NULL,
                            cash       REAL    NOT NULL,
                            last_daily INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY (guild_id, user_id))""");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS portfolio (
                            guild_id          TEXT    NOT NULL,
                            user_id           TEXT    NOT NULL,
                            channel_id        TEXT    NOT NULL,
                            shares_owned      INTEGER NOT NULL,
                            average_buy_price REAL    NOT NULL,
                            PRIMARY KEY (guild_id, user_id, channel_id))""");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS stocks (
                            channel_id            TEXT PRIMARY KEY,
                            guild_id              TEXT    NOT NULL,
                            name                  TEXT    NOT NULL,
                            current_price         REAL    NOT NULL,
                            unique_chatters_count INTEGER NOT NULL DEFAULT 0,
                            last_updated          INTEGER NOT NULL)""");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS price_history (
                            channel_id TEXT    NOT NULL,
                            ts         INTEGER NOT NULL,
                            price      REAL    NOT NULL)""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_price_history ON price_history(channel_id, ts)");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS market_state (
                            guild_id   TEXT PRIMARY KEY,
                            is_halted  INTEGER NOT NULL DEFAULT 0,
                            halt_reason TEXT)""");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS market_events (
                            id                INTEGER PRIMARY KEY AUTOINCREMENT,
                            guild_id          TEXT    NOT NULL,
                            active_event_type TEXT    NOT NULL,
                            event_target      TEXT    NOT NULL,
                            started_at        INTEGER NOT NULL,
                            event_expires_at  INTEGER NOT NULL)""");
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to initialise database", e);
        }
    }

    // --------------------------------------------------------------- helpers
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private <T> T sync(SqlWork<T> work) {
        synchronized (lock) {
            try {
                return work.run();
            } catch (SQLException e) {
                throw new DatabaseException("Database error", e);
            }
        }
    }

    private <T> T tx(SqlWork<T> work) {
        synchronized (lock) {
            try {
                conn.setAutoCommit(false);
                try {
                    T result = work.run();
                    conn.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new DatabaseException("Database error", e);
            }
        }
    }

    private PreparedStatement prep(String sql, Object... args) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
        return ps;
    }

    private int exec(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prep(sql, args)) {
            return ps.executeUpdate();
        }
    }

    private static Stock mapStock(ResultSet rs) throws SQLException {
        return new Stock(rs.getString("channel_id"), rs.getString("guild_id"), rs.getString("name"),
                rs.getDouble("current_price"), rs.getInt("unique_chatters_count"), rs.getLong("last_updated"));
    }

    private Stock selectStock(String channelId) throws SQLException {
        try (PreparedStatement ps = prep("SELECT * FROM stocks WHERE channel_id = ?", channelId);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? mapStock(rs) : null;
        }
    }

    private void ensureUser(String guildId, String userId) throws SQLException {
        exec("INSERT OR IGNORE INTO users(guild_id, user_id, cash, last_daily) VALUES(?,?,?,0)",
                guildId, userId, startingCash);
    }

    private double selectCash(String guildId, String userId) throws SQLException {
        try (PreparedStatement ps = prep("SELECT cash FROM users WHERE guild_id=? AND user_id=?", guildId, userId);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getDouble(1) : startingCash;
        }
    }

    private Held selectHeld(String guildId, String userId, String channelId) throws SQLException {
        try (PreparedStatement ps = prep("""
                SELECT shares_owned, average_buy_price FROM portfolio
                WHERE guild_id=? AND user_id=? AND channel_id=?""", guildId, userId, channelId);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new Held(rs.getLong(1), rs.getDouble(2)) : new Held(0, 0);
        }
    }

    // ---------------------------------------------------------------- stocks
    public void ensureStock(String channelId, String guildId, String name, double initialPrice) {
        sync(() -> exec("""
                INSERT INTO stocks(channel_id, guild_id, name, current_price, unique_chatters_count, last_updated)
                VALUES(?,?,?,?,0,?)
                ON CONFLICT(channel_id) DO UPDATE SET name = excluded.name""",
                channelId, guildId, name, initialPrice, System.currentTimeMillis()));
    }

    public Stock getStock(String channelId) {
        return sync(() -> selectStock(channelId));
    }

    public List<Stock> getStocks(String guildId) {
        return sync(() -> {
            List<Stock> out = new ArrayList<>();
            try (PreparedStatement ps = prep("SELECT * FROM stocks WHERE guild_id = ?", guildId);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapStock(rs));
            }
            return out;
        });
    }

    public List<Stock> getAllStocks() {
        return sync(() -> {
            List<Stock> out = new ArrayList<>();
            try (PreparedStatement ps = prep("SELECT * FROM stocks");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapStock(rs));
            }
            return out;
        });
    }

    public boolean setPrice(String channelId, double price) {
        return sync(() -> exec("UPDATE stocks SET current_price=?, last_updated=? WHERE channel_id=?",
                price, System.currentTimeMillis(), channelId) > 0);
    }

    /** Resets price and wipes the price history ("velocity history"). */
    public boolean resetStock(String channelId, double price) {
        return tx(() -> {
            int rows = exec("""
                    UPDATE stocks SET current_price=?, unique_chatters_count=0, last_updated=?
                    WHERE channel_id=?""", price, System.currentTimeMillis(), channelId);
            exec("DELETE FROM price_history WHERE channel_id=?", channelId);
            return rows > 0;
        });
    }

    public void batchUpdateStocks(List<StockUpdate> updates, long now) {
        if (updates.isEmpty()) return;
        tx(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE stocks SET current_price=?, unique_chatters_count=?, last_updated=?
                    WHERE channel_id=?""")) {
                for (StockUpdate u : updates) {
                    ps.setDouble(1, u.price());
                    ps.setInt(2, u.uniqueChatters());
                    ps.setLong(3, now);
                    ps.setString(4, u.channelId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    public void insertSnapshots(List<StockUpdate> updates, long now) {
        if (updates.isEmpty()) return;
        tx(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO price_history(channel_id, ts, price) VALUES(?,?,?)")) {
                for (StockUpdate u : updates) {
                    ps.setString(1, u.channelId());
                    ps.setLong(2, now);
                    ps.setDouble(3, u.price());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            exec("DELETE FROM price_history WHERE ts < ?", now - 26L * 3_600_000L);
            return null;
        });
    }

    /** Oldest recorded price within the last 24h, or null if there is no history yet. */
    public Double price24hAgo(String channelId, long now) {
        return sync(() -> {
            try (PreparedStatement ps = prep("""
                    SELECT price FROM price_history
                    WHERE channel_id=? AND ts >= ? ORDER BY ts ASC LIMIT 1""",
                    channelId, now - 24L * 3_600_000L);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : null;
            }
        });
    }

    /** Shares held by investors per channel (basis for market cap). */
    public Map<String, Long> sharesOutstanding(String guildId) {
        return sync(() -> {
            Map<String, Long> out = new HashMap<>();
            try (PreparedStatement ps = prep("""
                    SELECT channel_id, SUM(shares_owned) FROM portfolio
                    WHERE guild_id=? GROUP BY channel_id""", guildId);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
            return out;
        });
    }

    /** Removes a stock (channel deleted) and cashes holders out at the last price, fee-free. */
    public int delistStock(String channelId) {
        return tx(() -> {
            Stock s = selectStock(channelId);
            if (s == null) return 0;
            int refunded = exec("""
                    UPDATE users SET cash = cash + (
                        SELECT ROUND(p.shares_owned * ?, 2) FROM portfolio p
                        WHERE p.guild_id = users.guild_id AND p.user_id = users.user_id AND p.channel_id = ?)
                    WHERE EXISTS (
                        SELECT 1 FROM portfolio p
                        WHERE p.guild_id = users.guild_id AND p.user_id = users.user_id AND p.channel_id = ?)""",
                    s.price(), channelId, channelId);
            exec("DELETE FROM portfolio WHERE channel_id=?", channelId);
            exec("DELETE FROM price_history WHERE channel_id=?", channelId);
            exec("DELETE FROM stocks WHERE channel_id=?", channelId);
            return refunded;
        });
    }

    // ----------------------------------------------------------------- users
    public double getCash(String guildId, String userId) {
        return sync(() -> {
            ensureUser(guildId, userId);
            return selectCash(guildId, userId);
        });
    }

    /** Adds (or deducts, if negative) cash. Balance never drops below zero. */
    public double adjustCash(String guildId, String userId, double delta) {
        return tx(() -> {
            ensureUser(guildId, userId);
            double updated = Math.max(0, round2(selectCash(guildId, userId) + delta));
            exec("UPDATE users SET cash=? WHERE guild_id=? AND user_id=?", updated, guildId, userId);
            return updated;
        });
    }

    public DailyResult claimDaily(String guildId, String userId, long now, long cooldownMs, double bonus) {
        return tx(() -> {
            ensureUser(guildId, userId);
            long last;
            double cash;
            try (PreparedStatement ps = prep(
                    "SELECT last_daily, cash FROM users WHERE guild_id=? AND user_id=?", guildId, userId);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                last = rs.getLong(1);
                cash = rs.getDouble(2);
            }
            if (last > 0 && now - last < cooldownMs) {
                return new DailyResult(false, cash, last + cooldownMs);
            }
            double updated = round2(cash + bonus);
            exec("UPDATE users SET cash=?, last_daily=? WHERE guild_id=? AND user_id=?",
                    updated, now, guildId, userId);
            return new DailyResult(true, updated, now + cooldownMs);
        });
    }

    public List<Position> getPositions(String guildId, String userId) {
        return sync(() -> {
            List<Position> out = new ArrayList<>();
            try (PreparedStatement ps = prep("""
                    SELECT p.channel_id, p.shares_owned, p.average_buy_price, s.current_price
                    FROM portfolio p JOIN stocks s ON s.channel_id = p.channel_id
                    WHERE p.guild_id=? AND p.user_id=?
                    ORDER BY p.shares_owned * s.current_price DESC""", guildId, userId);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Position(rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4)));
                }
            }
            return out;
        });
    }

    public List<LeaderboardRow> leaderboard(String guildId, int limit) {
        return sync(() -> {
            List<LeaderboardRow> out = new ArrayList<>();
            try (PreparedStatement ps = prep("""
                    SELECT u.user_id,
                           u.cash + COALESCE(SUM(p.shares_owned * s.current_price), 0) AS net_worth
                    FROM users u
                    LEFT JOIN portfolio p ON p.guild_id = u.guild_id AND p.user_id = u.user_id
                    LEFT JOIN stocks s ON s.channel_id = p.channel_id
                    WHERE u.guild_id = ?
                    GROUP BY u.user_id, u.cash
                    ORDER BY net_worth DESC
                    LIMIT ?""", guildId, limit);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new LeaderboardRow(rs.getString(1), round2(rs.getDouble(2))));
            }
            return out;
        });
    }

    // --------------------------------------------------------------- trading
    public TradeResult buy(String guildId, String userId, String channelId, long shares) {
        return tx(() -> {
            HaltState halt = haltState(guildId);
            if (halt.halted()) return TradeResult.fail("🛑 The market is halted: " + halt.reason());

            Stock s = selectStock(channelId);
            if (s == null || !s.guildId().equals(guildId)) return TradeResult.fail("That channel is not a listed stock.");

            ensureUser(guildId, userId);
            double cash = selectCash(guildId, userId);
            double cost = round2(shares * s.price());
            if (cost > cash + 1e-9) {
                return TradeResult.fail(String.format("Insufficient funds: you need $%,.2f but only have $%,.2f.",
                        cost, cash));
            }

            double newCash = round2(cash - cost);
            exec("UPDATE users SET cash=? WHERE guild_id=? AND user_id=?", newCash, guildId, userId);

            Held held = selectHeld(guildId, userId, channelId);
            long newShares = held.shares() + shares;
            double newAvg = round2((held.shares() * held.avg() + shares * s.price()) / newShares);
            exec("""
                    INSERT INTO portfolio(guild_id, user_id, channel_id, shares_owned, average_buy_price)
                    VALUES(?,?,?,?,?)
                    ON CONFLICT(guild_id, user_id, channel_id)
                    DO UPDATE SET shares_owned = excluded.shares_owned,
                                  average_buy_price = excluded.average_buy_price""",
                    guildId, userId, channelId, newShares, newAvg);

            return new TradeResult(true, null, shares, s.price(), cost, 0, cost, newCash, newShares, newAvg, 0);
        });
    }

    /** Gross = shares * price; fee = gross * feeRate; net = gross - fee. */
    public TradeResult sell(String guildId, String userId, String channelId, long shares, double feeRate) {
        return tx(() -> {
            HaltState halt = haltState(guildId);
            if (halt.halted()) return TradeResult.fail("🛑 The market is halted: " + halt.reason());

            Stock s = selectStock(channelId);
            if (s == null || !s.guildId().equals(guildId)) return TradeResult.fail("That channel is not a listed stock.");

            Held held = selectHeld(guildId, userId, channelId);
            if (held.shares() < shares) {
                return TradeResult.fail("You only own " + held.shares() + " share(s) of that stock.");
            }

            double gross = round2(shares * s.price());
            double fee = round2(gross * feeRate);
            double net = round2(gross - fee);
            double realized = round2(net - shares * held.avg());

            ensureUser(guildId, userId);
            double newCash = round2(selectCash(guildId, userId) + net);
            exec("UPDATE users SET cash=? WHERE guild_id=? AND user_id=?", newCash, guildId, userId);

            long remaining = held.shares() - shares;
            if (remaining == 0) {
                exec("DELETE FROM portfolio WHERE guild_id=? AND user_id=? AND channel_id=?",
                        guildId, userId, channelId);
            } else {
                exec("UPDATE portfolio SET shares_owned=? WHERE guild_id=? AND user_id=? AND channel_id=?",
                        remaining, guildId, userId, channelId);
            }
            return new TradeResult(true, null, shares, s.price(), gross, fee, net, newCash, remaining,
                    held.avg(), realized);
        });
    }

    // ----------------------------------------------------------- market state
    public HaltState haltState(String guildId) {
        HaltState cached = haltCache.get(guildId);
        if (cached != null) return cached;
        HaltState loaded = sync(() -> {
            try (PreparedStatement ps = prep(
                    "SELECT is_halted, halt_reason FROM market_state WHERE guild_id=?", guildId);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String reason = rs.getString(2);
                    return new HaltState(rs.getInt(1) == 1, reason == null ? "No reason given" : reason);
                }
                return new HaltState(false, "");
            }
        });
        haltCache.put(guildId, loaded);
        return loaded;
    }

    public boolean isHalted(String guildId) {
        return haltState(guildId).halted();
    }

    public void setHalted(String guildId, boolean halted, String reason) {
        sync(() -> exec("""
                INSERT INTO market_state(guild_id, is_halted, halt_reason) VALUES(?,?,?)
                ON CONFLICT(guild_id) DO UPDATE SET is_halted = excluded.is_halted,
                                                    halt_reason = excluded.halt_reason""",
                guildId, halted ? 1 : 0, reason));
        haltCache.put(guildId, new HaltState(halted, reason == null ? "" : reason));
    }

    // ----------------------------------------------------------------- events
    public long insertEvent(String guildId, String type, String target, long startedAt, long expiresAt) {
        return tx(() -> {
            exec("""
                    INSERT INTO market_events(guild_id, active_event_type, event_target, started_at, event_expires_at)
                    VALUES(?,?,?,?,?)""", guildId, type, target, startedAt, expiresAt);
            try (PreparedStatement ps = prep("SELECT last_insert_rowid()");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    public void deleteEvent(long id) {
        sync(() -> exec("DELETE FROM market_events WHERE id=?", id));
    }

    public int deleteAllEvents(String guildId) {
        return sync(() -> exec("DELETE FROM market_events WHERE guild_id=?", guildId));
    }

    public List<EventRow> loadEvents() {
        return sync(() -> {
            List<EventRow> out = new ArrayList<>();
            try (PreparedStatement ps = prep("SELECT * FROM market_events");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EventRow(rs.getLong("id"), rs.getString("guild_id"),
                            rs.getString("active_event_type"), rs.getString("event_target"),
                            rs.getLong("started_at"), rs.getLong("event_expires_at")));
                }
            }
            return out;
        });
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // shutting down
            }
        }
    }
}
