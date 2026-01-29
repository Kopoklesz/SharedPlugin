package dev.shared.kopoklesz.config;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.DarkNumber;
import eu.darkbot.api.config.annotations.Option;

@Configuration("auto_auction")
public class AutoAuctionConfig {

    @Option("general.enabled")
    public boolean enabled = false;

    @Option("kopoklesz.auto_auction.min_bid_step")
    @DarkNumber(min = 10_000, max = 100_000_000_000_000, step = 10_000)
    public int minBidStep = 10_000;

    /**
     * Konzol log amikor licitál
     */
    @Option("kopoklesz.auto_auction.log_bids")
    public boolean logBids = true;

    /**
     * Konzol log amikor túllicitálnak
     */
    @Option("kopoklesz.auto_auction.log_outbid")
    public boolean logOutbid = true;

    /**
     * Konzol log amikor megnyersz egy árverést
     */
    @Option("kopoklesz.auto_auction.log_wins")
    public boolean logWins = true;
}