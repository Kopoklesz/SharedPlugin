package dev.shared.kopoklesz.config;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.DarkNumber;
import eu.darkbot.api.config.annotations.Option;

/**
 * Konfiguráció az Auto Auction plugin számára
 *
 * Használat:
 * - enabled: Be/ki kapcsolás
 * - maxBid: Maximum licit összeg (credit)
 * - targetItems: Vessző-elválasztott lista a keresett tárgyakról
 * - updateIntervalSec: Milyen gyakran frissítse az árveréseket (másodperc)
 * - auctionTypes: Milyen típusú árveréseket figyeljen (hour/day/week)
 */
@Configuration("auto_auction")
public class AutoAuctionConfig {

    // ============================================
    // Alapvető beállítások
    // ============================================

    /**
     * Plugin engedélyezése / tiltása
     */
    @Option("general.enabled")
    public boolean enabled = false;

    /**
     * Maximum licit összeg creditekben
     * Ha egy tétel ennél többe kerülne, nem licitál rá
     */
    @Option("kopoklesz.auto_auction.max_bid")
    @DarkNumber(min = 10_000, max = 100_000_000, step = 10_000)
    public int maxBid = 100_000;

    /**
     * Keresett tárgyak listája (vessző-elválasztva)
     * Példa: "LF-4,Havoc,Hercules,Spearhead"
     *
     * A plugin csak ezekre a tárgyakra fog licitálni
     */
    @Option("kopoklesz.auto_auction.target_items")
    public String targetItems = "LF-4,Havoc";

    /**
     * Frissítési intervallum másodpercekben
     * Milyen gyakran ellenőrizze az árveréseket
     *
     * Ajánlott: 30-60 másodperc
     * - Túl gyakori frissítés → szerver terhelés
     * - Túl ritka frissítés → lemaradás a tételekről
     */
    @Option("kopoklesz.auto_auction.update_interval")
    @DarkNumber(min = 10, max = 300, step = 5)
    public int updateIntervalSec = 30;

    // ============================================
    // Haladó beállítások
    // ============================================

    /**
     * Árverés típusok (HOUR / DAY / WEEK)
     * Beállíthatod hogy melyik típusú árverésekre licitáljon
     */
    @Option("kopoklesz.auto_auction.auction_types")
    public AuctionTypeSettings auctionTypes = new AuctionTypeSettings();

    /**
     * Licitálási stratégia beállítások
     */
    @Option("kopoklesz.auto_auction.bid_strategy")
    public BidStrategySettings bidStrategy = new BidStrategySettings();

    // ============================================
    // Belső konfigurációs osztályok
    // ============================================

    /**
     * Árverés típus beállítások
     */
    public static class AuctionTypeSettings {
        /**
         * 1 órás árverések (leggyorsabb, legversenyképesebb)
         */
        @Option("kopoklesz.auto_auction.types.hour")
        public boolean hour = true;

        /**
         * 24 órás árverések (közepes tempó)
         */
        @Option("kopoklesz.auto_auction.types.day")
        public boolean day = true;

        /**
         * 7 napos árverések (leglassabb)
         */
        @Option("kopoklesz.auto_auction.types.week")
        public boolean week = false;
    }

    /**
     * Licitálási stratégia beállítások
     */
    public static class BidStrategySettings {
        /**
         * Minimum licitlépés (credit)
         * A játék minimuma: 10,000
         * Magasabb érték → gyorsabban emeled a licitet
         */
        @Option("kopoklesz.auto_auction.strategy.min_bid_step")
        @DarkNumber(min = 10_000, max = 100_000, step = 10_000)
        public int minBidStep = 10_000;

        /**
         * Azonnal licitálj vissza ha túllicitálnak?
         * - true: Azonnal újra licitál (agresszív)
         * - false: Kivárja a következő frissítést (konzervatív)
         */
        @Option("kopoklesz.auto_auction.strategy.instant_rebid")
        public boolean instantRebid = false;

        /**
         * Maximum újra-licitálások száma ugyanarra a tételre
         * 0 = korlátlan
         */
        @Option("kopoklesz.auto_auction.strategy.max_rebids")
        @DarkNumber(min = 0, max = 20, step = 1)
        public int maxRebids = 5;

        /**
         * Ne licitálj ha az ár már meghaladta az eredeti X%-át
         * Példa: 150 = ne licitálj ha már 1.5x az eredeti ár
         * 0 = nincs limit (csak a maxBid számít)
         */
        @Option("kopoklesz.auto_auction.strategy.max_price_increase_percent")
        @DarkNumber(min = 0, max = 500, step = 10)
        public int maxPriceIncreasePercent = 200;
    }

    // ============================================
    // Értesítési beállítások
    // ============================================

    /**
     * Értesítési beállítások
     */
    @Option("kopoklesz.auto_auction.notifications")
    public NotificationSettings notifications = new NotificationSettings();

    public static class NotificationSettings {
        /**
         * Konzol log amikor licitál
         */
        @Option("kopoklesz.auto_auction.notifications.log_bids")
        public boolean logBids = true;

        /**
         * Konzol log amikor túllicitálnak
         */
        @Option("kopoklesz.auto_auction.notifications.log_outbid")
        public boolean logOutbid = true;

        /**
         * Konzol log amikor megnyersz egy árverést
         */
        @Option("kopoklesz.auto_auction.notifications.log_wins")
        public boolean logWins = true;
    }

    // ============================================
    // Biztonsági beállítások
    // ============================================

    /**
     * Biztonsági és limit beállítások
     */
    @Option("kopoklesz.auto_auction.safety")
    public SafetySettings safety = new SafetySettings();

    public static class SafetySettings {
        /**
         * Maximum költés / óra (credit)
         * Ha elérted ezt a limitet, 1 órára leáll a licitálás
         * 0 = nincs limit
         */
        @Option("kopoklesz.auto_auction.safety.max_spend_per_hour")
        @DarkNumber(min = 0, max = 1_000_000_000, step = 100_000)
        public int maxSpendPerHour = 0;

        /**
         * Tartalék credit (ne menjen ez alá)
         * Mindig hagyj ennyi creditet a számlán
         */
        @Option("kopoklesz.auto_auction.safety.reserve_credits")
        @DarkNumber(min = 0, max = 100_000_000, step = 10_000)
        public int reserveCredits = 100_000;

        /**
         * Maximum párhuzamos licitek
         * Egyszerre ennyi tételre licitálhat
         * 0 = korlátlan
         */
        @Option("kopoklesz.auto_auction.safety.max_concurrent_bids")
        @DarkNumber(min = 0, max = 20, step = 1)
        public int maxConcurrentBids = 3;
    }
}