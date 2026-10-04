# Discord Virtual Stock Market

Every text channel is a stock. Chat moves the price up, silence lets it decay, and members trade shares to climb the leaderboard.

## Commands

| Command | What it does |
|---|---|
| `/stocks` | All channel stocks: price, 24h trend, market cap, event tags |
| `/buy <channel> <shares>` | Buy shares |
| `/sell <channel> <shares>` | Sell shares (2% fee) |
| `/portfolio` | Cash, holdings, net worth, profit/loss |
| `/leaderboard` | Top 10 by net worth |
| `/daily` | Claim the daily cash bonus |

Admin only: `/market-halt`, `/market-resume`, `/stock-reset`, `/stock-set-price`, `/admin-give-cash`, `/event-trigger`, `/event-stop`.

## Run locally

1. Enable the **Message Content** intent in the Discord developer portal.
2. Set `DISCORD_TOKEN` (or `discord.token` in `config.properties`).
3. `mvn package`
4. `java -jar target/discord-stock-market.jar`

Requires Java 17+. Invite the bot with the `bot` and `applications.commands` scopes.

## Deploy on Railway

1. Push this repo to GitHub, then create a Railway project from it. The `Dockerfile` is detected automatically.
2. Add a **Volume** to the service and mount it at `/data`.
3. Set variables:
   - `DISCORD_TOKEN=<your bot token>`
   - `DATABASE_URL=jdbc:sqlite:/data/stockmarket.db`
4. Keep the service at **1 replica** (SQLite allows a single writer).

## Configuration

Everything tunable lives in `config.properties`. Any key can be overridden by an env var: uppercase it and swap dots for underscores (`economy.sell.fee` becomes `ECONOMY_SELL_FEE`).
