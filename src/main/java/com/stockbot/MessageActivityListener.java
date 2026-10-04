package com.stockbot;

import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/** Turns chat messages into price pressure: anti-spam first, then event multipliers. */
public final class MessageActivityListener extends ListenerAdapter {

    private final DatabaseManager db;
    private final AntiSpamEngine antiSpam;
    private final StockEngine stocks;
    private final MarketEventEngine events;

    public MessageActivityListener(DatabaseManager db, AntiSpamEngine antiSpam,
                                   StockEngine stocks, MarketEventEngine events) {
        this.db = db;
        this.antiSpam = antiSpam;
        this.stocks = stocks;
        this.events = events;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent e) {
        if (!e.isFromGuild() || e.getChannelType() != ChannelType.TEXT) return;

        String channelId = e.getChannel().getId();
        if (!stocks.isTracked(channelId)) return;

        String guildId = e.getGuild().getId();
        if (db.isHalted(guildId)) return; // price updates are frozen during a halt

        if (antiSpam.evaluate(e.getMessage()) != AntiSpamEngine.Verdict.ACCEPTED) return;

        double weight = events.activityMultiplier(guildId, channelId);
        stocks.recordActivity(channelId, e.getAuthor().getId(), weight);
    }
}
