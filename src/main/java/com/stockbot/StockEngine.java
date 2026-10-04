package com.stockbot;

import com.stockbot.DatabaseManager.Stock;
import com.stockbot.DatabaseManager.StockUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pricing model.
 *
 * Each tick, per stock:
 *   score  = SUM over users of ln(1 + weighted accepted messages)   (diminishing returns per user)
 *   gain   = growthCoefficient * ln(1 + score)                       (logarithmic scaling)
 * Gains require enough UNIQUE chatters, are frozen by the circuit breaker, and can never push the
 * price above windowStartPrice * (1 + maxGrowthPerWindow) (organic cap). Quiet stocks decay, and
 * CRASH / BEAR events multiply the decay rate. Instant event shocks bypass the organic cap.
 */
public final class StockEngine {

    private static final Logger log = LoggerFactory.getLogger(StockEngine.class);

    public record PriceMove(double oldPrice, double newPrice) {
        public double change() {
            return oldPrice == 0 ? 0 : (newPrice - oldPrice) / oldPrice;
        }
    }

    private static final class Bucket {
        final ConcurrentHashMap<String, Double> weights = new ConcurrentHashMap<>();
        final AtomicInteger messages = new AtomicInteger();
    }

    private static final class WindowState {
        long windowStart;
        double windowStartPrice;
        double emaVolume = 0;
        long breakerUntil = 0;

        WindowState(double price, long now) {
            this.windowStart = now;
            this.windowStartPrice = price;
        }
    }

    private final DatabaseManager db;
    private final AntiSpamEngine antiSpam;
    private volatile MarketEventEngine events;

    private final Set<String> tracked = ConcurrentHashMap.newKeySet();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();

    private final double initialPrice;
    private final double minPrice;
    private final double baseDecay;
    private final double growthCoefficient;
    private final double maxGrowthPerWindow;
    private final long windowMs;
    private final long snapshotMs;
    private final int minUniqueChatters;
    private final int breakerMinMessages;
    private final double breakerSpikeRatio;
    private final int breakerMinUnique;
    private final long breakerFreezeMs;
    private long lastSnapshot = 0;

    public StockEngine(DatabaseManager db, AntiSpamEngine antiSpam, BotConfig cfg) {
        this.db = db;
        this.antiSpam = antiSpam;
        this.initialPrice = cfg.dbl("stock.initial.price", 10.00);
        this.minPrice = cfg.dbl("stock.min.price", 0.10);
        this.baseDecay = cfg.dbl("stock.decay.per.tick", 0.0003);
        this.growthCoefficient = cfg.dbl("stock.growth.coefficient", 0.004);
        this.maxGrowthPerWindow = cfg.dbl("stock.max.growth.per.window", 0.10);
        this.windowMs = cfg.lng("stock.window.minutes", 10) * 60_000L;
        this.snapshotMs = cfg.lng("stock.snapshot.minutes", 10) * 60_000L;
        this.minUniqueChatters = cfg.integer("antispam.min.unique.chatters", 2);
        this.breakerMinMessages = cfg.integer("breaker.min.messages", 8);
        this.breakerSpikeRatio = cfg.dbl("breaker.spike.ratio", 4.0);
        this.breakerMinUnique = cfg.integer("breaker.min.unique.chatters", 4);
        this.breakerFreezeMs = cfg.lng("breaker.freeze.minutes", 10) * 60_000L;
    }

    public void setEventEngine(MarketEventEngine events) {
        this.events = events;
    }

    public double initialPrice() {
        return initialPrice;
    }

    public double minPrice() {
        return minPrice;
    }

    // -------------------------------------------------------------- registry
    public void loadTracked() {
        db.getAllStocks().forEach(s -> tracked.add(s.channelId()));
    }

    public void track(String guildId, String channelId, String name) {
        db.ensureStock(channelId, guildId, name, initialPrice);
        tracked.add(channelId);
    }

    /** Delists a stock; holders are cashed out at the last price. Returns investors refunded. */
    public synchronized int untrack(String channelId) {
        tracked.remove(channelId);
        buckets.remove(channelId);
        windows.remove(channelId);
        antiSpam.clearChannel(channelId);
        return db.delistStock(channelId);
    }

    public boolean isTracked(String channelId) {
        return tracked.contains(channelId);
    }

    public boolean isBreakerActive(String channelId) {
        WindowState ws = windows.get(channelId);
        return ws != null && System.currentTimeMillis() < ws.breakerUntil;
    }

    // -------------------------------------------------------------- activity
    /** Called for every ACCEPTED message. Weight already includes any active event multiplier. */
    public void recordActivity(String channelId, String userId, double weight) {
        Bucket b = buckets.computeIfAbsent(channelId, k -> new Bucket());
        b.weights.merge(userId, weight, Double::sum);
        b.messages.incrementAndGet();
    }

    // ------------------------------------------------------------------ tick
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        List<Stock> stocks = db.getAllStocks();
        List<StockUpdate> updates = new ArrayList<>(stocks.size());

        for (Stock s : stocks) {
            if (db.isHalted(s.guildId())) {
                buckets.remove(s.channelId()); // no backlog builds up while frozen
                continue;
            }
            updates.add(step(s, now));
        }
        db.batchUpdateStocks(updates, now);

        if (now - lastSnapshot >= snapshotMs) {
            db.insertSnapshots(updates, now);
            lastSnapshot = now;
        }
    }

    private StockUpdate step(Stock s, long now) {
        String id = s.channelId();
        WindowState ws = windows.computeIfAbsent(id, k -> new WindowState(s.price(), now));
        if (now - ws.windowStart >= windowMs) {
            ws.windowStart = now;
            ws.windowStartPrice = s.price();
        }

        Bucket b = buckets.remove(id);
        int messages = b == null ? 0 : b.messages.get();
        double score = 0;
        if (b != null) {
            for (double w : b.weights.values()) score += Math.log1p(w);
        }
        int unique = antiSpam.uniqueChatters(id);

        // Circuit breaker: abnormal volume with too few distinct people behind it.
        boolean spike = messages >= breakerMinMessages
                && messages > breakerSpikeRatio * Math.max(ws.emaVolume, 1.0);
        if (spike && unique < breakerMinUnique && now >= ws.breakerUntil) {
            ws.breakerUntil = now + breakerFreezeMs;
            log.warn("Circuit breaker tripped for channel {} ({} msgs, {} unique chatters)", id, messages, unique);
        }
        ws.emaVolume = 0.8 * ws.emaVolume + 0.2 * messages;
        boolean frozen = now < ws.breakerUntil;

        double price = s.price();
        double gain = 0;
        if (!frozen && score > 0 && unique >= minUniqueChatters) {
            gain = Math.min(growthCoefficient * Math.log1p(score), maxGrowthPerWindow);
        }
        if (gain > 0) {
            double cap = ws.windowStartPrice * (1 + maxGrowthPerWindow);
            // never pull an event-inflated price DOWN to the cap; just stop organic gains
            price = Math.max(price, Math.min(price * (1 + gain), cap));
        }

        double decayMult = events == null ? 1.0 : events.decayMultiplier(s.guildId(), id);
        if (gain <= 0 || decayMult > 1.0) {
            price *= 1 - Math.min(0.5, baseDecay * decayMult);
        }

        price = Math.max(minPrice, DatabaseManager.round2(price));
        return new StockUpdate(id, price, unique);
    }

    // ------------------------------------------------- admin / event mutators
    /** Instant price change (event shock). fraction = +0.30 for +30%. */
    public synchronized PriceMove applyShock(String channelId, double fraction) {
        Stock s = db.getStock(channelId);
        if (s == null) return null;
        double newPrice = Math.max(minPrice, DatabaseManager.round2(s.price() * (1 + fraction)));
        db.setPrice(channelId, newPrice);
        rebaseWindow(channelId, s.price(), newPrice);
        return new PriceMove(s.price(), newPrice);
    }

    public synchronized PriceMove setPrice(String channelId, double price) {
        Stock s = db.getStock(channelId);
        if (s == null) return null;
        double newPrice = Math.max(minPrice, DatabaseManager.round2(price));
        db.setPrice(channelId, newPrice);
        rebaseWindow(channelId, s.price(), newPrice);
        return new PriceMove(s.price(), newPrice);
    }

    /** Resets price and wipes velocity/history/breaker state for the stock. */
    public synchronized boolean resetStock(String channelId, double price) {
        double p = Math.max(minPrice, DatabaseManager.round2(price));
        boolean ok = db.resetStock(channelId, p);
        if (ok) {
            buckets.remove(channelId);
            windows.remove(channelId);
            antiSpam.clearChannel(channelId);
        }
        return ok;
    }

    private void rebaseWindow(String channelId, double oldPrice, double newPrice) {
        WindowState ws = windows.get(channelId);
        if (ws != null && oldPrice > 0) ws.windowStartPrice *= newPrice / oldPrice;
    }
}
