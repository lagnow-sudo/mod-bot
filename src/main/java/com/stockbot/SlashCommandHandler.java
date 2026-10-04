package com.stockbot;

import com.stockbot.DatabaseManager.DailyResult;
import com.stockbot.DatabaseManager.HaltState;
import com.stockbot.DatabaseManager.LeaderboardRow;
import com.stockbot.DatabaseManager.Position;
import com.stockbot.DatabaseManager.Stock;
import com.stockbot.DatabaseManager.TradeResult;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** /stocks /buy /sell /portfolio /leaderboard /daily — every command defers its reply first. */
public final class SlashCommandHandler extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandHandler.class);
    private static final Color BLURPLE = new Color(0x5865F2);
    private static final Color GREEN = new Color(0x2ECC71);
    private static final Color RED = new Color(0xE74C3C);
    private static final Color GOLD = new Color(0xFFD700);

    private final DatabaseManager db;
    private final StockEngine stocks;
    private final MarketEventEngine events;
    private final double sellFee;
    private final double dailyBonus;
    private final long dailyCooldownMs;

    public SlashCommandHandler(DatabaseManager db, StockEngine stocks, MarketEventEngine events, BotConfig cfg) {
        this.db = db;
        this.stocks = stocks;
        this.events = events;
        this.sellFee = cfg.dbl("economy.sell.fee", 0.02);
        this.dailyBonus = cfg.dbl("economy.daily.bonus", 200);
        this.dailyCooldownMs = cfg.lng("economy.daily.cooldown.hours", 24) * 3_600_000L;
    }

    // ------------------------------------------------------------ registration
    public static List<CommandData> commands() {
        OptionData channel = new OptionData(OptionType.CHANNEL, "channel", "The channel stock", true)
                .setChannelTypes(ChannelType.TEXT);
        return List.of(
                Commands.slash("stocks", "View every channel stock, its price, trend and market cap")
                        .setGuildOnly(true),
                Commands.slash("buy", "Buy shares of a channel stock")
                        .addOptions(channel, new OptionData(OptionType.INTEGER, "shares", "How many shares", true)
                                .setMinValue(1).setMaxValue(1_000_000))
                        .setGuildOnly(true),
                Commands.slash("sell", "Sell shares of a channel stock (2% fee applies)")
                        .addOptions(new OptionData(OptionType.CHANNEL, "channel", "The channel stock", true)
                                        .setChannelTypes(ChannelType.TEXT),
                                new OptionData(OptionType.INTEGER, "shares", "How many shares", true)
                                        .setMinValue(1).setMaxValue(1_000_000))
                        .setGuildOnly(true),
                Commands.slash("portfolio", "Your cash, holdings, net worth and profit/loss")
                        .setGuildOnly(true),
                Commands.slash("leaderboard", "Top 10 richest traders by net worth")
                        .setGuildOnly(true),
                Commands.slash("daily", "Claim your daily cash bonus")
                        .setGuildOnly(true));
    }

    // ---------------------------------------------------------------- dispatch
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        switch (e.getName()) {
            case "stocks", "buy", "sell", "portfolio", "leaderboard", "daily" -> { }
            default -> { return; }
        }

        e.deferReply().queue();
        try {
            switch (e.getName()) {
                case "stocks" -> cmdStocks(e);
                case "buy" -> cmdBuy(e);
                case "sell" -> cmdSell(e);
                case "portfolio" -> cmdPortfolio(e);
                case "leaderboard" -> cmdLeaderboard(e);
                case "daily" -> cmdDaily(e);
                default -> { }
            }
        } catch (Exception ex) {
            log.error("Command /{} failed", e.getName(), ex);
            e.getHook().sendMessageEmbeds(error("Something went wrong. Please try again in a moment.")).queue();
        }
    }

    // ---------------------------------------------------------------- /stocks
    private void cmdStocks(SlashCommandInteractionEvent e) {
        Guild g = e.getGuild();
        String gid = g.getId();
        long now = System.currentTimeMillis();

        List<Stock> list = new ArrayList<>(db.getStocks(gid));
        list.sort(Comparator.comparingDouble(Stock::price).reversed());
        Map<String, Long> outstanding = db.sharesOutstanding(gid);
        HaltState halt = db.haltState(gid);

        StringBuilder sb = new StringBuilder("```\n");
        sb.append(String.format(Locale.US, "%-16s %9s %8s %9s  %s\n", "CHANNEL", "PRICE", "24H", "MKT CAP", "STATUS"));
        int shown = 0;
        for (Stock s : list) {
            if (shown++ >= 30) break;
            Double old = db.price24hAgo(s.channelId(), now);
            String trend = "—";
            if (old != null && old > 0) {
                double ch = (s.price() - old) / old * 100;
                trend = (ch >= 0 ? "▲" : "▼") + String.format(Locale.US, "%.1f%%", Math.abs(ch));
            }
            String tag = events.tagFor(gid, s.channelId());
            if (stocks.isBreakerActive(s.channelId())) tag = (tag + " 🔒").trim();
            double cap = s.price() * outstanding.getOrDefault(s.channelId(), 0L);

            sb.append(String.format(Locale.US, "%-16s %9s %8s %9s  %s\n",
                    truncate("#" + channelName(g, s), 16), usd(s.price()), trend, compactUsd(cap), tag));
        }
        if (list.isEmpty()) sb.append("No stocks listed yet.\n");
        sb.append("```");
        if (list.size() > 30) sb.append("\n…and ").append(list.size() - 30).append(" more.");

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(halt.halted() ? new Color(0x8B0000) : BLURPLE)
                .setTitle(halt.halted() ? "🛑 MARKET HALTED" : "📊 Server Stock Market")
                .setDescription(sb.toString())
                .setFooter("Market cap = price × shares held by traders • " + feeLabel() + " fee on sales")
                .setTimestamp(Instant.now());
        if (halt.halted()) eb.addField("Reason", halt.reason(), false);

        List<MarketEventEngine.ActiveEvent> active = events.active(gid);
        if (!active.isEmpty()) {
            StringBuilder ev = new StringBuilder();
            for (MarketEventEngine.ActiveEvent a : active) {
                String scope = MarketEventEngine.ALL.equals(a.target()) ? "entire market" : "<#" + a.target() + ">";
                ev.append(a.type().emoji).append(' ').append(a.type().label).append(" — ").append(scope)
                        .append(" (ends <t:").append(a.expiresAt() / 1000).append(":R>)\n");
            }
            eb.addField("Active events", ev.toString(), false);
        }
        e.getHook().sendMessageEmbeds(eb.build()).queue();
    }

    // ------------------------------------------------------------- /buy /sell
    private void cmdBuy(SlashCommandInteractionEvent e) {
        String channelId = Objects.requireNonNull(e.getOption("channel")).getAsChannel().getId();
        long shares = Objects.requireNonNull(e.getOption("shares")).getAsLong();
        Guild g = e.getGuild();

        TradeResult r = db.buy(g.getId(), e.getUser().getId(), channelId, shares);
        if (!r.ok()) {
            e.getHook().sendMessageEmbeds(error(r.error())).queue();
            return;
        }
        MessageEmbed embed = new EmbedBuilder()
                .setColor(GREEN)
                .setTitle("🟢 Bought " + r.shares() + " share(s) of <#" + channelId + ">")
                .addField("Price per share", usd(r.price()), true)
                .addField("Total cost", usd(r.gross()), true)
                .addField("Cash remaining", usd(r.cash()), true)
                .addField("Shares owned", String.valueOf(r.remainingShares()), true)
                .addField("Average buy price", usd(r.avgBuyPrice()), true)
                .setTimestamp(Instant.now())
                .build();
        e.getHook().sendMessageEmbeds(embed).queue();
    }

    private void cmdSell(SlashCommandInteractionEvent e) {
        String channelId = Objects.requireNonNull(e.getOption("channel")).getAsChannel().getId();
        long shares = Objects.requireNonNull(e.getOption("shares")).getAsLong();
        Guild g = e.getGuild();

        TradeResult r = db.sell(g.getId(), e.getUser().getId(), channelId, shares, sellFee);
        if (!r.ok()) {
            e.getHook().sendMessageEmbeds(error(r.error())).queue();
            return;
        }
        boolean profit = r.realizedPnl() >= 0;
        MessageEmbed embed = new EmbedBuilder()
                .setColor(profit ? GREEN : RED)
                .setTitle("🔴 Sold " + r.shares() + " share(s) of <#" + channelId + ">")
                .addField("Sale price", usd(r.price()) + " / share", true)
                .addField("Gross Sale", usd(r.gross()), true)
                .addField(feeLabel() + " Fee", "-" + usd(r.fee()), true)
                .addField("Net Cash Received", "**" + usd(r.net()) + "**", true)
                .addField("Remaining Shares", String.valueOf(r.remainingShares()), true)
                .addField("Realized P/L", signedUsd(r.realizedPnl()), true)
                .addField("Cash balance", usd(r.cash()), false)
                .setFooter("The trading fee discourages rapid micro-flipping")
                .setTimestamp(Instant.now())
                .build();
        e.getHook().sendMessageEmbeds(embed).queue();
    }

    // -------------------------------------------------------------- /portfolio
    private void cmdPortfolio(SlashCommandInteractionEvent e) {
        Guild g = e.getGuild();
        String gid = g.getId();
        String uid = e.getUser().getId();

        double cash = db.getCash(gid, uid);
        List<Position> positions = db.getPositions(gid, uid);

        double holdings = 0;
        double basis = 0;
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Position p : positions) {
            double value = p.shares() * p.currentPrice();
            double cost = p.shares() * p.avgBuyPrice();
            holdings += value;
            basis += cost;
            if (shown++ < 20) {
                double pl = value - cost;
                double plPct = cost == 0 ? 0 : pl / cost * 100;
                sb.append(String.format(Locale.US, "<#%s> — `%d` @ %s → %s  (**%+.1f%%**, %s)\n",
                        p.channelId(), p.shares(), usd(p.avgBuyPrice()), usd(p.currentPrice()),
                        plPct, signedUsd(pl)));
            }
        }
        if (positions.size() > 20) sb.append("…and ").append(positions.size() - 20).append(" more.\n");
        if (positions.isEmpty()) sb.append("You don't own any shares yet — try `/stocks` then `/buy`.");

        double unrealized = holdings - basis;
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(unrealized >= 0 ? GREEN : RED)
                .setTitle("💼 " + e.getUser().getEffectiveName() + "'s portfolio")
                .setDescription(sb.toString())
                .addField("Cash", usd(cash), true)
                .addField("Holdings value", usd(holdings), true)
                .addField("Net worth", "**" + usd(cash + holdings) + "**", true)
                .setTimestamp(Instant.now());
        if (!positions.isEmpty()) {
            double pct = basis == 0 ? 0 : unrealized / basis * 100;
            eb.addField("Unrealized P/L", String.format(Locale.US, "%s (%+.1f%%)", signedUsd(unrealized), pct), false);
        }
        e.getHook().sendMessageEmbeds(eb.build()).queue();
    }

    // ------------------------------------------------------------ /leaderboard
    private void cmdLeaderboard(SlashCommandInteractionEvent e) {
        List<LeaderboardRow> rows = db.leaderboard(e.getGuild().getId(), 10);
        StringBuilder sb = new StringBuilder();
        String[] medals = {"🥇", "🥈", "🥉"};
        int rank = 0;
        for (LeaderboardRow r : rows) {
            String prefix = rank < 3 ? medals[rank] : "`#" + (rank + 1) + "`";
            sb.append(prefix).append(" <@").append(r.userId()).append("> — **").append(usd(r.netWorth())).append("**\n");
            rank++;
        }
        if (rows.isEmpty()) sb.append("No traders yet. Be the first with `/daily` or `/buy`!");

        e.getHook().sendMessageEmbeds(new EmbedBuilder()
                .setColor(GOLD)
                .setTitle("🏆 Richest traders")
                .setDescription(sb.toString())
                .setFooter("Net worth = cash + shares at current prices")
                .setTimestamp(Instant.now())
                .build()).queue();
    }

    // ------------------------------------------------------------------ /daily
    private void cmdDaily(SlashCommandInteractionEvent e) {
        DailyResult r = db.claimDaily(e.getGuild().getId(), e.getUser().getId(),
                System.currentTimeMillis(), dailyCooldownMs, dailyBonus);
        if (!r.claimed()) {
            e.getHook().sendMessageEmbeds(error("You already claimed your bonus. Come back <t:"
                    + r.nextAvailableAt() / 1000 + ":R>.")).queue();
            return;
        }
        e.getHook().sendMessageEmbeds(new EmbedBuilder()
                .setColor(GREEN)
                .setTitle("🎁 Daily bonus claimed")
                .setDescription("+" + usd(dailyBonus) + " added to your balance.\nNew balance: **" + usd(r.balance()) + "**")
                .setFooter("Next claim available in 24h")
                .setTimestamp(Instant.now())
                .build()).queue();
    }

    // ----------------------------------------------------------------- helpers
    private String feeLabel() {
        double pct = Math.round(sellFee * 10_000) / 100.0;
        return BigDecimal.valueOf(pct).stripTrailingZeros().toPlainString() + "%";
    }

    private static MessageEmbed error(String message) {
        return new EmbedBuilder().setColor(RED).setTitle("❌ Can't do that").setDescription(message).build();
    }

    static String channelName(Guild g, Stock s) {
        TextChannel c = g.getTextChannelById(s.channelId());
        return c != null ? c.getName() : s.name();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String usd(double v) {
        return (v < 0 ? "-" : "") + String.format(Locale.US, "$%,.2f", Math.abs(v));
    }

    static String signedUsd(double v) {
        return (v < 0 ? "-" : "+") + String.format(Locale.US, "$%,.2f", Math.abs(v));
    }

    static String compactUsd(double v) {
        if (v >= 1e9) return String.format(Locale.US, "$%.1fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.US, "$%.1fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.US, "$%.1fK", v / 1e3);
        return String.format(Locale.US, "$%.0f", v);
    }
}
