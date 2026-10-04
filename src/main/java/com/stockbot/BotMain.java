package com.stockbot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Virtual Stock Market bot: every text channel is a stock.
 * Requires the privileged MESSAGE CONTENT intent to be enabled in the Discord developer portal.
 */
public final class BotMain {

    private static final Logger log = LoggerFactory.getLogger(BotMain.class);

    public static void main(String[] args) throws Exception {
        BotConfig cfg = BotConfig.load(args.length > 0 ? args[0] : "config.properties");

        String token = cfg.str("discord.token", "");
        if (token.isEmpty()) {
            log.error("No bot token. Set discord.token in config.properties or the DISCORD_TOKEN env var.");
            System.exit(1);
        }

        // ---- core services -------------------------------------------------
        DatabaseManager db = new DatabaseManager(
                cfg.str("database.url", "jdbc:sqlite:stockmarket.db"),
                cfg.dbl("economy.starting.cash", 1000));
        AntiSpamEngine antiSpam = new AntiSpamEngine(cfg);
        StockEngine stocks = new StockEngine(db, antiSpam, cfg);
        MarketEventEngine events = new MarketEventEngine(db, stocks, cfg);
        stocks.setEventEngine(events);
        stocks.loadTracked();
        events.loadFromDatabase();

        List<CommandData> commands = new ArrayList<>();
        commands.addAll(SlashCommandHandler.commands());
        commands.addAll(AdminCommandHandler.commands());

        // ---- JDA -----------------------------------------------------------
        JDA jda = JDABuilder.createLight(token,
                        EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .setActivity(Activity.watching("the market 📈"))
                .addEventListeners(
                        new MessageActivityListener(db, antiSpam, stocks, events),
                        new SlashCommandHandler(db, stocks, events, cfg),
                        new AdminCommandHandler(db, stocks, events),
                        new LifecycleListener(stocks, commands, cfg.csv("excluded.channel.ids")))
                .build();
        jda.awaitReady();

        // ---- background scheduler -----------------------------------------
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "market-scheduler");
            t.setDaemon(true);
            return t;
        });
        long tickSeconds = cfg.lng("stock.tick.seconds", 60);
        long eventMinutes = cfg.lng("events.check.interval.minutes", 10);

        scheduler.scheduleAtFixedRate(safe("stock-tick", stocks::tick), tickSeconds, tickSeconds, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(safe("event-expiry", () -> events.tickExpiry(jda)), 30, 30, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(safe("random-events", () -> events.tickRandom(jda)),
                eventMinutes, eventMinutes, TimeUnit.MINUTES);
        scheduler.scheduleAtFixedRate(safe("antispam-cleanup", antiSpam::cleanup), 5, 5, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down…");
            scheduler.shutdown();
            jda.shutdown();
            try {
                if (!jda.awaitShutdown(10, TimeUnit.SECONDS)) jda.shutdownNow();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            db.close();
        }, "shutdown"));

        log.info("Stock market is open. Logged in as {}", jda.getSelfUser().getName());
    }

    /** A throwing task would silently cancel its schedule, so every task is wrapped. */
    private static Runnable safe(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("Scheduled task '{}' failed", name, t);
            }
        };
    }

    /** Lists channels as stocks, registers slash commands per guild (instant) and delists deleted channels. */
    private static final class LifecycleListener extends ListenerAdapter {

        private final StockEngine stocks;
        private final List<CommandData> commands;
        private final Set<String> excluded;

        LifecycleListener(StockEngine stocks, List<CommandData> commands, Set<String> excluded) {
            this.stocks = stocks;
            this.commands = commands;
            this.excluded = excluded;
        }

        @Override
        public void onGuildReady(GuildReadyEvent e) {
            setup(e.getGuild());
        }

        @Override
        public void onGuildJoin(GuildJoinEvent e) {
            setup(e.getGuild());
        }

        private void setup(Guild guild) {
            guild.updateCommands().addCommands(commands).queue(
                    ok -> log.info("Registered {} commands in guild {}", ok.size(), guild.getName()),
                    err -> log.error("Command registration failed in guild {}", guild.getName(), err));
            for (TextChannel c : guild.getTextChannels()) {
                if (!excluded.contains(c.getId())) stocks.track(guild.getId(), c.getId(), c.getName());
            }
        }

        @Override
        public void onChannelCreate(ChannelCreateEvent e) {
            if (e.getChannelType() != ChannelType.TEXT) return;
            TextChannel c = e.getChannel().asTextChannel();
            if (!excluded.contains(c.getId())) stocks.track(e.getGuild().getId(), c.getId(), c.getName());
        }

        @Override
        public void onChannelDelete(ChannelDeleteEvent e) {
            if (e.getChannelType() != ChannelType.TEXT) return;
            int refunded = stocks.untrack(e.getChannel().getId());
            log.info("Channel {} deleted: stock delisted, {} investor(s) cashed out", e.getChannel().getName(), refunded);
        }
    }
}
