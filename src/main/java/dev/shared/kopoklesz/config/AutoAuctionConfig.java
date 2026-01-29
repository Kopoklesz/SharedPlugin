package dev.shared.kopoklesz.config;

import java.util.ArrayList;
import java.util.List;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.DarkNumber;
import eu.darkbot.api.config.annotations.Option;

@Configuration("auto_auction")
public class AutoAuctionConfig {

    @Option("general.enabled")
    public boolean enabled = false;

    @Option("kopoklesz.auto_auction.min_bid_step")
    @DarkNumber(min = 10_000, max = 100_000_000_000_000L, step = 10_000)
    public long minBidStep = 10_000;

    @Option("kopoklesz.auto_auction.strategy")
    public boolean strategy = true;

    @Option("kopoklesz.auto_auction.hourly_auctions")
    public HourlyAuctionSettings hourlyAuctions = new HourlyAuctionSettings();

    @Option("kopoklesz.auto_auction.daily_auctions")
    public DailyAuctionSettings dailyAuctions = new DailyAuctionSettings();

    @Option("kopoklesz.auto_auction.log_bids")
    public boolean logBids = true;

    @Option("kopoklesz.auto_auction.log_outbid")
    public boolean logOutbid = true;

    @Option("kopoklesz.auto_auction.log_wins")
    public boolean logWins = true;

    public static class HourlyAuctionSettings {

        @Option("kopoklesz.auto_auction.hourly.enabled")
        public boolean enabled = false;

        @Option("kopoklesz.auto_auction.hourly.targets")
        public List<ProductTarget> targets = new ArrayList<>();
    }

    public static class DailyAuctionSettings {

        @Option("kopoklesz.auto_auction.daily.enabled")
        public boolean enabled = false;

        @Option("kopoklesz.auto_auction.daily.targets")
        public List<ProductTarget> targets = new ArrayList<>();
    }

    public static class ProductTarget {

        @Option("kopoklesz.auto_auction.product.name")
        public String productName = "";

        @Option("kopoklesz.auto_auction.product.max_bid")
        @DarkNumber(min = 10_000, max = 100_000_000_000_000L, step = 10_000)
        public long maxBid = 100_000;

        @Option("kopoklesz.auto_auction.product.successful_bids")
        @DarkNumber(min = 0, max = 1_000, step = 1)
        public int successfulBidsCount = 1;
    }
}