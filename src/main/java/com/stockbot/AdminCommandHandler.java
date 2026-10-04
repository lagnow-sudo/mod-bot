package com.stockbot;

import com.stockbot.DatabaseManager.Stock;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Administrator-only commands. Protection is layered: Discord-side default permissions
 * (hidden from non-admins) AND a runtime Permission.ADMINISTRATOR check on every call.
 */
public final class AdminCommandHandler extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(AdminCommandHandler.class);
    private static final Set<String> NAMES = Set.of("market-halt", "market-resume", "stock-reset",
            "stock-set-price", "admin-give-cash", "event-trigger", "event-stop");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d+)>");

    private final DatabaseManager db;
    private final StockEngine stocks;
    private final MarketEventEngine events;

    public AdminCommandHandler(DatabaseManager db, StockEngine stocks, MarketEventEngine events) {
        this.db = db;
        this.stocks = stocks;
        this.events = events;
    }

    // ------------------------------------------------------------ registration
    private static SlashCommandData admin(String name, String description) {
        return Commands.slash(name, description)
                .setGuildOnly(true)
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR));
    }

    public static List<CommandData> commands() {
        return List.of(
                admin("market-halt", "Freeze buying, selling and price updates server-wide")
                        .addOption(OptionType.STRING, "reason", "Why the market is being halted", true),
                admin("market-resume", "Re-enable normal market operations"),
                admin("stock-reset", "Reset a channel stock and clear its history")
                        .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Channel stock to reset", true)
                                        .setChannelTypes(ChannelType.TEXT),
                                new OptionData(OptionType.NUMBER, "initial_price", "Price to reset to (default $10.00)", false)
                                        .setMinValue(0.01).setMaxValue(1_000_000)),
                admin("stock-set-price", "Manually override a channel stock price")
                        .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Channel stock", true)
                                        .setChannelTypes(ChannelType.TEXT),
                                new OptionData(OptionType.NUMBER, "price", "New price", true)
                                        .setMinValue(0.01).setMaxValue(1_000_000)),
                admin("admin-give-cash", "Grant (or deduct, with a negative amount) virtual cash")
                        .addOptions(new OptionData(OptionType.USER, "user", "Target user", true),
                                new OptionData(OptionType.NUMBER, "amount", "Amount; negative deducts", true)
                                        .setMinValue(-1_000_000_000).setMaxValue(1_000_000_000)),
                admin("event-trigger", "Manually start a market event")
                        .addOptions(new OptionData(OptionType.STRING, "type", "Event type", true)
                                        .addChoice("🚀 BOOM (bull run)", "BOOM")
                                        .addChoice("📉 CRASH (flash crash)", "CRASH")
                                        .addChoice("⚡ RALLY (market-wide activity boost)", "RALLY")
                                        .addChoice("🐻 STAGNATION (bear market)", "STAGNATION"),
                                new OptionData(OptionType.STRING, "target", "A channel, or ALL", true)
                                        .setAutoComplete(true),
                                new OptionData(OptionType.INTEGER, "duration_minutes", "How long it lasts", true)
                                        .setMinValue(1).setMaxValue(1440)),
                admin("event-stop", "Cancel all active events and restore baseline volatility"));
    }

    // ---------------------------------------------------------------- dispatch
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if (!NAMES.contains(e.getName()) || e.getGuild() == null) return;

        Member member = e.getMember();
        if (member == null || !member.hasPermission(Permission.ADMINISTRATOR)) {
            e.reply("⛔ This command requires the **Administrator** permission.").setEphemeral(true).queue();
            return;
        }

        boolean publicReply = e.getName().equals("market-halt") || e.getName().equals("market-resume");
        e.deferReply(!publicReply).queue();
        try {
            switch (e.getName()) {
                case "market-halt" -> halt(e);
                case "market-resume" -> resume(e);
                case "stock-reset" -> reset(e);
                case "stock-set-price" -> setPrice(e);
                case "admin-give-cash" -> giveCash(e);
                case "event-trigger" -> trigger(e);
                case "event-stop" -> stop(e);
                default -> { }
            }
        } catch (Exception ex) {
            log.error("Admin command /{} failed", e.getName(), ex);
            e.getHook().sendMessageEmbeds(error("Something went wrong. Check the bot logs.")).queue();
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent e) {
        if (!e.getName().equals("event-trigger") || !e.getFocusedOption().getName().equals("target")
                || e.getGuild() == null) {
            return;
        }
        Member member = e.getMember();
        if (member == null || !member.hasPermission(Permission.ADMINISTRATOR)) {
            e.replyChoices(List.of()).queue();
            return;
        }
        String typed = e.getFocusedOption().getValue().toLowerCase(Locale.ROOT).replace("#", "");
        List<Command.Choice> choices = new ArrayList<>();
        if (typed.isEmpty() || "all".startsWith(typed)) choices.add(new Command.Choice("ALL (entire market)", "ALL"));
        for (Stock s : db.getStocks(e.getGuild().getId())) {
            String name = SlashCommandHandler.channelName(e.getGuild(), s);
            if (choices.size() >= 25) break;
            if (typed.isEmpty() || name.toLowerCase(Locale.ROOT).contains(typed)) {
                choices.add(new Command.Choice("#" + name, s.channelId()));
            }
        }
        e.replyChoices(choices).queue();
    }

    // ---------------------------------------------------------------- handlers
    private void halt(SlashCommandInteractionEvent e) {
        String reason = Objects.requireNonNull(e.getOption("reason")).getAsString();
        db.setHalted(e.getGuild().getId(), true, reason);
        e.getHook().sendMessageEmbeds(new EmbedBuilder()
                .setColor(new Color(0x8B0000))
                .setTitle("🛑 MARKET HALTED")
                .setDescription("Trading and price updates are frozen until an administrator resumes the market.")
                .addField("Reason", reason, false)
                .setFooter("Halted by " + e.getUser().getEffectiveName())
                .setTimestamp(Instant.now())
                .build()).queue();
    }

    private void resume(SlashCommandInteractionEvent e) {
        db.setHalted(e.getGuild().getId(), false, "");
        e.getHook().sendMessageEmbeds(new EmbedBuilder()
                .setColor(new Color(0x2ECC71))
                .setTitle("🔔 MARKET RESUMED")
                .setDescription("The bell has rung. Buying, selling and price updates are live again.")
                .setFooter("Resumed by " + e.getUser().getEffectiveName())
                .setTimestamp(Instant.now())
                .build()).queue();
    }

    private void reset(SlashCommandInteractionEvent e) {
        String channelId = Objects.requireNonNull(e.getOption("channel")).getAsChannel().getId();
        if (!ownsStock(e.getGuild(), channelId)) {
            e.getHook().sendMessageEmbeds(error("That channel is not a listed stock.")).queue();
            return;
        }
        OptionMapping priceOpt = e.getOption("initial_price");
        double price = priceOpt != null ? priceOpt.getAsDouble() : stocks.initialPrice();
        stocks.resetStock(channelId, price);
        e.getHook().sendMessageEmbeds(ok("♻️ Stock reset",
                "<#" + channelId + "> was reset to **" + SlashCommandHandler.usd(price)
                        + "** and its price history and velocity were cleared.")).queue();
    }

    private void setPrice(SlashCommandInteractionEvent e) {
        String channelId = Objects.requireNonNull(e.getOption("channel")).getAsChannel().getId();
        if (!ownsStock(e.getGuild(), channelId)) {
            e.getHook().sendMessageEmbeds(error("That channel is not a listed stock.")).queue();
            return;
        }
        double price = Objects.requireNonNull(e.getOption("price")).getAsDouble();
        StockEngine.PriceMove move = stocks.setPrice(channelId, price);
        e.getHook().sendMessageEmbeds(ok("✏️ Price overridden",
                "<#" + channelId + ">: " + SlashCommandHandler.usd(move.oldPrice()) + " → **"
                        + SlashCommandHandler.usd(move.newPrice()) + "**")).queue();
    }

    private void giveCash(SlashCommandInteractionEvent e) {
        User target = Objects.requireNonNull(e.getOption("user")).getAsUser();
        if (target.isBot()) {
            e.getHook().sendMessageEmbeds(error("Bots can't hold a portfolio.")).queue();
            return;
        }
        double amount = Objects.requireNonNull(e.getOption("amount")).getAsDouble();
        double balance = db.adjustCash(e.getGuild().getId(), target.getId(), amount);
        e.getHook().sendMessageEmbeds(ok(amount >= 0 ? "💸 Cash granted" : "🧾 Cash deducted",
                SlashCommandHandler.signedUsd(amount) + " for <@" + target.getId() + ">.\n"
                        + "New balance: **" + SlashCommandHandler.usd(balance) + "** (balances never go below $0).")).queue();
    }

    private void trigger(SlashCommandInteractionEvent e) {
        Guild g = e.getGuild();
        MarketEventEngine.EventType type = MarketEventEngine.EventType.parse(
                Objects.requireNonNull(e.getOption("type")).getAsString());
        int minutes = Objects.requireNonNull(e.getOption("duration_minutes")).getAsInt();
        String target = resolveTarget(g, Objects.requireNonNull(e.getOption("target")).getAsString());

        if (type == null) {
            e.getHook().sendMessageEmbeds(error("Unknown event type.")).queue();
            return;
        }
        if (target == null) {
            e.getHook().sendMessageEmbeds(error("I couldn't find that stock. Use ALL or pick a channel from the list.")).queue();
            return;
        }
        if (db.isHalted(g.getId())) {
            e.getHook().sendMessageEmbeds(error("The market is halted. Run `/market-resume` first.")).queue();
            return;
        }
        events.start(g, type, target, minutes, false);
        e.getHook().sendMessageEmbeds(ok(type.emoji + " Event started",
                type.label + " is live for " + minutes + " minute(s) — announcement sent.")).queue();
    }

    private void stop(SlashCommandInteractionEvent e) {
        int removed = events.stopAll(e.getGuild());
        e.getHook().sendMessageEmbeds(ok("🛑 Events stopped",
                removed == 0 ? "There were no active events."
                        : "Cancelled " + removed + " event(s). Volatility is back to baseline.")).queue();
    }

    // ----------------------------------------------------------------- helpers
    /** Returns "ALL", a tracked channel id of this guild, or null if it can't be resolved. */
    private String resolveTarget(Guild g, String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty() || t.equalsIgnoreCase(MarketEventEngine.ALL)) return MarketEventEngine.ALL;

        Matcher m = CHANNEL_MENTION.matcher(t);
        if (m.matches()) t = m.group(1);

        if (t.chars().allMatch(Character::isDigit)) {
            return ownsStock(g, t) ? t : null;
        }
        String wanted = t.replace("#", "").toLowerCase(Locale.ROOT);
        for (Stock s : db.getStocks(g.getId())) {
            if (SlashCommandHandler.channelName(g, s).toLowerCase(Locale.ROOT).equals(wanted)) return s.channelId();
        }
        return null;
    }

    private boolean ownsStock(Guild g, String channelId) {
        Stock s = db.getStock(channelId);
        return s != null && s.guildId().equals(g.getId());
    }

    private static MessageEmbed ok(String title, String description) {
        return new EmbedBuilder().setColor(new Color(0x2ECC71)).setTitle(title).setDescription(description).build();
    }

    private static MessageEmbed error(String message) {
        return new EmbedBuilder().setColor(new Color(0xE74C3C)).setTitle("❌ Can't do that").setDescription(message).build();
    }
}
