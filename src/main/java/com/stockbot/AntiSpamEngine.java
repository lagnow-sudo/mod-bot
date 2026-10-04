package com.stockbot;

import net.dv8tion.jda.api.entities.Message;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * First line of defence against market manipulation:
 *  - message quality filter (bots, system, short, emoji-only, junk, duplicates)
 *  - per-user, per-channel contribution cooldown
 *  - rolling unique-chatter tracking
 */
public final class AntiSpamEngine {

    public enum Verdict { ACCEPTED, BOT, SYSTEM, TOO_SHORT, EMOJI_ONLY, LOW_QUALITY, COOLDOWN, DUPLICATE }

    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:\\w+:\\d+>");
    private static final Pattern UNICODE_EMOJI =
            Pattern.compile("[\\p{So}\\p{Sk}\\u200D\\uFE0F\\u20E3\\x{1F3FB}-\\x{1F3FF}]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final long cooldownMs;
    private final long duplicateWindowMs;
    private final long uniqueWindowMs;
    private final int minLength;

    /** key = channelId:userId -> last accepted contribution. */
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    /** key = channelId|normalizedText -> last seen. */
    private final ConcurrentHashMap<String, Long> recentTexts = new ConcurrentHashMap<>();
    /** channelId -> (userId -> last accepted message). */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> chatters = new ConcurrentHashMap<>();

    public AntiSpamEngine(BotConfig cfg) {
        this.cooldownMs = cfg.lng("antispam.cooldown.seconds", 30) * 1000L;
        this.duplicateWindowMs = cfg.lng("antispam.duplicate.window.minutes", 10) * 60_000L;
        this.uniqueWindowMs = cfg.lng("antispam.unique.window.minutes", 10) * 60_000L;
        this.minLength = cfg.integer("antispam.min.length", 5);
    }

    /** Runs the full pipeline. Only ACCEPTED messages may move a stock price. */
    public Verdict evaluate(Message m) {
        if (m.getAuthor().isBot() || m.isWebhookMessage()) return Verdict.BOT;
        if (m.getType().isSystem()) return Verdict.SYSTEM;

        String raw = m.getContentRaw().strip();
        if (raw.codePointCount(0, raw.length()) < minLength) return Verdict.TOO_SHORT;

        String noEmoji = UNICODE_EMOJI.matcher(CUSTOM_EMOJI.matcher(raw).replaceAll("")).replaceAll("").strip();
        if (noEmoji.isEmpty()) return Verdict.EMOJI_ONLY;

        String norm = NON_ALNUM.matcher(noEmoji.toLowerCase(Locale.ROOT)).replaceAll("");
        if (norm.length() < 3 || norm.chars().distinct().count() < 3) return Verdict.LOW_QUALITY;

        String channelId = m.getChannel().getId();
        String userId = m.getAuthor().getId();
        long now = System.currentTimeMillis();

        if (!tryConsumeCooldown(channelId + ":" + userId, now)) return Verdict.COOLDOWN;

        String textKey = channelId + "|" + (norm.length() > 200 ? norm.substring(0, 200) : norm);
        Long previous = recentTexts.put(textKey, now);
        if (previous != null && now - previous < duplicateWindowMs) return Verdict.DUPLICATE;

        chatters.computeIfAbsent(channelId, k -> new ConcurrentHashMap<>()).put(userId, now);
        return Verdict.ACCEPTED;
    }

    private boolean tryConsumeCooldown(String key, long now) {
        boolean[] granted = {false};
        cooldowns.compute(key, (k, last) -> {
            if (last == null || now - last >= cooldownMs) {
                granted[0] = true;
                return now;
            }
            return last;
        });
        return granted[0];
    }

    /** Distinct users with an accepted message in the rolling window. */
    public int uniqueChatters(String channelId) {
        Map<String, Long> m = chatters.get(channelId);
        if (m == null) return 0;
        long cutoff = System.currentTimeMillis() - uniqueWindowMs;
        m.values().removeIf(t -> t < cutoff);
        return m.size();
    }

    public void clearChannel(String channelId) {
        chatters.remove(channelId);
        cooldowns.keySet().removeIf(k -> k.startsWith(channelId + ":"));
        recentTexts.keySet().removeIf(k -> k.startsWith(channelId + "|"));
    }

    /** Periodic housekeeping so the maps never grow without bound. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        cooldowns.values().removeIf(t -> now - t > cooldownMs * 4);
        recentTexts.values().removeIf(t -> now - t > duplicateWindowMs);
        chatters.forEach((channel, users) -> {
            users.values().removeIf(t -> now - t > uniqueWindowMs);
            if (users.isEmpty()) chatters.remove(channel, users);
        });
    }
}
