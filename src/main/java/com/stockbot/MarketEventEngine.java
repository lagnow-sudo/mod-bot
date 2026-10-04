package com.stockbot;

import com.stockbot.DatabaseManager.EventRow;
import com.stockbot.DatabaseManager.Stock;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random booms, flash crashes, rallies and bear markets, plus admin-triggered news shocks.
 * Active events are cached in memory (fast per-message lookups) and mirrored to SQLite so they
 * survive restarts.
 */
public final class MarketEventEngine {

    private static final Logger log = LoggerFactory.getLogger(MarketEventEngine.class);
    public static final String ALL = "ALL";

    public enum EventType {
        BOOM("🚀", "Bull Run", new Color(0xFFD700)),
        CRASH("📉", "Flash Crash", new Color(0x8B0000)),
        RALLY("⚡", "Market Rally", new Color(0x2ECC71)),
        BEAR("🐻", "Bear Market", new Color(0x5B1A18));

        public final String emoji;
        public final String label;
        public final Color color;

        EventType(String emoji, String label, Color color) {
            this.emoji = emoji;
            this.label = label;
            this.color = color;
        }

        /** Accepts the admin-facing names too (STAGNATION is the bear market). */
        public static EventType parse(String s) {
            if (s == null) return null;
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "BOOM", "BULL" -> BOOM;
                case "CRASH" -> CRASH;
                case "RALLY" -> RALLY;
                case "STAGNATION", "BEAR" -> BEAR;
                default -> null;
            };
        }
    }

    public record ActiveEvent(long id, String guildId, EventType type, String target,
                              long startedAt, long expiresAt) {
        boolean appliesTo(String channelId) {
            return ALL.equals(target) || target.equals(channelId);
        }

        boolean isLive(long now) {
            return now < expiresAt;
        }
    }

    private final DatabaseManager db;
    private final StockEngine stocks;
    private final BotConfig cfg;
    private final Map<String, List<ActiveEvent>> byGuild = new ConcurrentHashMap<>();

    private final double randomChance;
    private final int defaultMinutes;
    private final int maxConcurrent;
    private final double boomMin, boomMax, crashMin, crashMax;
    private final double boomActivity, rallyActivity, crashDecay, bearDecay;

    public MarketEventEngine(DatabaseManager db, StockEngine stocks, BotConfig cfg) {
        this.db = db;
        this.stocks = stocks;
        this.cfg = cfg;
        this.randomChance = cfg.dbl("events.random.chance", 0.07);
        this.defaultMinutes = cfg.integer("events.default.duration.minutes", 15);
        this.maxConcurrent = cfg.integer("events.max.concurrent.per.guild", 3);
        this.boomMin = cfg.dbl("events.boom.min.pct", 20);
        this.boomMax = cfg.dbl("events.boom.max.pct", 50);
        this.crashMin = cfg.dbl("events.crash.min.pct", 15);
        this.crashMax = cfg.dbl("events.crash.max.pct", 40);
        this.boomActivity = cfg.dbl("events.boom.activity.multiplier", 2.0);
        this.rallyActivity = cfg.dbl("events.rally.activity.multiplier", 1.5);
        this.crashDecay = cfg.dbl("events.crash.decay.multiplier", 3.0);
        this.bearDecay = cfg.dbl("events.bear.decay.multiplier", 2.5);
    }

    public void loadFromDatabase() {
        for (EventRow r : db.loadEvents()) {
            EventType type = EventType.parse(r.type());
            if (type == null) continue;
            byGuild.computeIfAbsent(r.guildId(), k -> new CopyOnWriteArrayList<>())
                    .add(new ActiveEvent(r.id(), r.guildId(), type, r.target(), r.startedAt(), r.expiresAt()));
        }
    }

    // -------------------------------------------------------------- modifiers
    /** Applied to every accepted message's weight (BOOM x2, RALLY x1.5 by default). */
    public double activityMultiplier(String guildId, String channelId) {
        long now = System.currentTimeMillis();
        double mult = 1.0;
        for (ActiveEvent e : active(guildId)) {
            if (!e.isLive(now) || !e.appliesTo(channelId)) continue;
            if (e.type() == EventType.BOOM) mult *= boomActivity;
            if (e.type() == EventType.RALLY) mult *= rallyActivity;
        }
        return mult;
    }

    /** Multiplies the per-tick decay (CRASH x3, BEAR x2.5 by default). */
    public double decayMultiplier(String guildId, String channelId) {
        long now = System.currentTimeMillis();
        double mult = 1.0;
        for (ActiveEvent e : active(guildId)) {
            if (!e.isLive(now) || !e.appliesTo(channelId)) continue;
            if (e.type() == EventType.CRASH) mult *= crashDecay;
            if (e.type() == EventType.BEAR) mult *= bearDecay;
        }
        return mult;
    }

    public List<ActiveEvent> active(String guildId) {
        return byGuild.getOrDefault(guildId, List.of());
    }

    /** Tags shown next to a stock in /stocks, e.g. "🚀 BOOMING". */
    public String tagFor(String guildId, String channelId) {
        long now = System.currentTimeMillis();
        List<String> tags = new ArrayList<>();
        for (ActiveEvent e : active(guildId)) {
            if (!e.isLive(now) || !e.appliesTo(channelId)) continue;
            tags.add(switch (e.type()) {
                case BOOM -> "🚀 BOOMING";
                case CRASH -> "📉 CRASHING";
                case RALLY -> "⚡ RALLY";
                case BEAR -> "🐻 BEAR";
            });
        }
        return String.join(" ", tags);
    }

    // ------------------------------------------------------------- start/stop
    public ActiveEvent start(Guild guild, EventType type, String target, int minutes, boolean automated) {
        String gid = guild.getId();
        long now = System.currentTimeMillis();
        long expires = now + minutes * 60_000L;
        String priceLine = null;

        if (type == EventType.BOOM || type == EventType.CRASH) {
            List<String> ids = ALL.equals(target)
                    ? db.getStocks(gid).stream().map(Stock::channelId).toList()
                    : List.of(target);
            double sum = 0;
            int n = 0;
            StockEngine.PriceMove last = null;
            for (String id : ids) {
                double pct = type == EventType.BOOM ? between(boomMin, boomMax) : -between(crashMin, crashMax);
                StockEngine.PriceMove move = stocks.applyShock(id, pct / 100.0);
                if (move != null) {
                    sum += move.change();
                    n++;
                    last = move;
                }
            }
            if (n == 1) {
                priceLine = SlashCommandHandler.usd(last.oldPrice()) + " → " + SlashCommandHandler.usd(last.newPrice())
                        + " (" + pct(last.change()) + ")";
            } else if (n > 1) {
                priceLine = "Average " + pct(sum / n) + " across " + n + " stocks";
            }
        }

        long id = db.insertEvent(gid, type.name(), target, now, expires);
        ActiveEvent ev = new ActiveEvent(id, gid, type, target, now, expires);
        byGuild.computeIfAbsent(gid, k -> new CopyOnWriteArrayList<>()).add(ev);

        announce(guild, ALL.equals(target) ? null : target, startEmbed(ev, minutes, priceLine, automated));
        log.info("Event {} started in guild {} target={} for {}m (automated={})", type, gid, target, minutes, automated);
        return ev;
    }

    /** Cancels every active event in the guild and restores baseline volatility. */
    public int stopAll(Guild guild) {
        String gid = guild.getId();
        byGuild.remove(gid);
        int removed = db.deleteAllEvents(gid);
        if (removed > 0) {
            announce(guild, null, new EmbedBuilder()
                    .setColor(Color.GRAY)
                    .setTitle("🛑 Market events cancelled")
                    .setDescription("All active events were stopped by an administrator. "
                            + "Volatility is back to baseline. Prices that already moved stay where they are.")
                    .setTimestamp(Instant.now())
                    .build());
        }
        return removed;
    }

    // ------------------------------------------------------------- scheduling
    public void tickExpiry(JDA jda) {
        long now = System.currentTimeMillis();
        byGuild.forEach((gid, list) -> {
            List<ActiveEvent> expired = list.stream().filter(e -> !e.isLive(now)).toList();
            if (expired.isEmpty()) return;
            list.removeAll(expired);
            Guild guild = jda.getGuildById(gid);
            for (ActiveEvent e : expired) {
                db.deleteEvent(e.id());
                if (guild != null) {
                    announce(guild, ALL.equals(e.target()) ? null : e.target(), endEmbed(e));
                }
            }
        });
    }

    public void tickRandom(JDA jda) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Guild guild : jda.getGuilds()) {
            String gid = guild.getId();
            try {
                if (db.isHalted(gid) || rnd.nextDouble() >= randomChance) continue;
                if (active(gid).size() >= maxConcurrent) continue;
                List<Stock> list = db.getStocks(gid);
                if (list.isEmpty()) continue;

                double roll = rnd.nextDouble();
                EventType type = roll < 0.35 ? EventType.BOOM
                        : roll < 0.60 ? EventType.CRASH
                        : roll < 0.80 ? EventType.RALLY
                        : EventType.BEAR;
                String target = (type == EventType.BOOM || type == EventType.CRASH)
                        ? list.get(rnd.nextInt(list.size())).channelId()
                        : ALL;
                if (isActive(gid, type, target)) continue;

                start(guild, type, target, defaultMinutes, true);
            } catch (Exception ex) {
                log.error("Random event failed for guild {}", gid, ex);
            }
        }
    }

    private boolean isActive(String gid, EventType type, String target) {
        long now = System.currentTimeMillis();
        return active(gid).stream().anyMatch(e -> e.isLive(now) && e.type() == type
                && (ALL.equals(e.target()) || ALL.equals(target) || e.target().equals(target)));
    }

    // ----------------------------------------------------------------- embeds
    private MessageEmbed startEmbed(ActiveEvent ev, int minutes, String priceLine, boolean automated) {
        EventType t = ev.type();
        String scope = ALL.equals(ev.target()) ? "Entire market" : "<#" + ev.target() + ">";

        String headline;
        String flavor;
        String effect;
        switch (t) {
            case BOOM -> {
                headline = "BULL RUN — the hype is real!";
                flavor = "Everyone is talking about it. Money is pouring in and the chart is going vertical.";
                effect = String.format(Locale.US, "Chat activity counts **x%.1f**", boomActivity);
            }
            case CRASH -> {
                headline = "FLASH CRASH — sell-off in progress!";
                flavor = "Panic hits the floor. Bag holders are sweating and the price is in freefall.";
                effect = String.format(Locale.US, "Price decay **x%.1f** while it lasts", crashDecay);
            }
            case RALLY -> {
                headline = "MARKET RALLY — everything is heating up!";
                flavor = "A wave of optimism lifts the whole board. Every conversation moves prices harder.";
                effect = String.format(Locale.US, "Chat activity counts **x%.1f**", rallyActivity);
            }
            default -> {
                headline = "BEAR MARKET — prices are bleeding!";
                flavor = "Confidence evaporates. Prices slide faster than usual until the mood turns.";
                effect = String.format(Locale.US, "Price decay **x%.1f** while it lasts", bearDecay);
            }
        }

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(t.color)
                .setTitle(t.emoji + " " + headline)
                .setDescription(flavor)
                .addField("Scope", scope, true)
                .addField("Duration", minutes + " min", true)
                .addField("Ends", "<t:" + ev.expiresAt() / 1000 + ":R>", true)
                .addField("Effect", effect, false)
                .setFooter(automated ? "Random market event" : "Triggered by administrators")
                .setTimestamp(Instant.now());
        if (priceLine != null) eb.addField("Price impact", priceLine, false);
        return eb.build();
    }

    private MessageEmbed endEmbed(ActiveEvent ev) {
        String scope = ALL.equals(ev.target()) ? "the entire market" : "<#" + ev.target() + ">";
        return new EmbedBuilder()
                .setColor(Color.GRAY)
                .setTitle("✅ " + ev.type().label + " has ended")
                .setDescription("Volatility for " + scope + " returns to baseline.")
                .setTimestamp(Instant.now())
                .build();
    }

    private void announce(Guild guild, String preferredChannelId, MessageEmbed embed) {
        TextChannel ch = resolveAnnounceChannel(guild, preferredChannelId);
        if (ch == null) {
            log.warn("No channel available to announce in guild {}", guild.getId());
            return;
        }
        ch.sendMessageEmbeds(embed).queue(null, t -> log.warn("Announcement failed: {}", t.getMessage()));
    }

    private TextChannel resolveAnnounceChannel(Guild guild, String preferredChannelId) {
        String configured = cfg.str("announce.channel.id", "");
        if (!configured.isEmpty()) {
            TextChannel c = guild.getTextChannelById(configured);
            if (c != null && c.canTalk()) return c;
        }
        if (preferredChannelId != null) {
            TextChannel c = guild.getTextChannelById(preferredChannelId);
            if (c != null && c.canTalk()) return c;
        }
        TextChannel sys = guild.getSystemChannel();
        if (sys != null && sys.canTalk()) return sys;
        return guild.getTextChannels().stream().filter(TextChannel::canTalk).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- helpers
    private static double between(double min, double max) {
        return max <= min ? min : ThreadLocalRandom.current().nextDouble(min, max);
    }

    private static String pct(double fraction) {
        return String.format(Locale.US, "%+.1f%%", fraction * 100);
    }
}
