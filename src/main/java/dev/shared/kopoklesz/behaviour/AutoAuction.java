package dev.shared.kopoklesz.behaviour;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.shared.kopoklesz.config.AutoAuctionConfig;
import dev.shared.kopoklesz.config.AutoAuctionConfig.ProductTarget;
import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.managers.BackpageAPI;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.StatsAPI;

/**
 * Automatikus árverési rendszer plugin
 * 
 * ========================================
 * MULTI-TERMÉK MŰKÖDÉS
 * ========================================
 * 
 * Ez a plugin PÁRHUZAMOSAN kezeli a több terméket!
 * 
 * Működési elv:
 * - Minden terméknek SAJÁT BidState-je van (bidStates Map)
 * - Minden termékre KÜLÖN-KÜLÖN fut le a licitálási logika
 * - A processAuctions() NEM áll meg az első találatnál
 * - MINDEN érdekes termékre alkalmazza a stratégiát
 * 
 * Példa:
 * -------
 * Config:
 * - Órás aukciók: LF-4 (max 500k), Havoc (max 1M)
 * - Napi aukciók: Hercules (max 2M)
 * 
 * Aktív aukciók:
 * 1. LF-4 (órás, 10 perc van még)
 * 2. Havoc (órás, 2 perc van még)
 * 3. Hercules (napi, 5 perc van még)
 * 
 * Működés:
 * 1. LF-4-re normál licitálás (> 3min)
 * 2. Havoc-ra agresszív licitálás (< 3min)
 * 3. Hercules-re normál licitálás (> 3min)
 * 
 * Minden termék FÜGGETLEN:
 * - LF-4: firstStep=true, myLastPrice=100k
 * - Havoc: firstStep=false, myLastPrice=0
 * - Hercules: firstStep=true, myLastPrice=500k
 * 
 * ========================================
 * 
 * Működés:
 * 1. Rendszeresen lekéri az aktív árveréseket a szerverről
 * 2. Parseolja az árverési adatokat
 * 3. MINDEN érdekes tételre ellenőrzi (config alapján)
 * 4. MINDEN tételre automatikusan licitál stratégiai logika alapján
 * 
 * Stratégia (AutoAuctionDocu alapján):
 * - Ellenőrzi ha van aktív licit (termék-specifikusan)
 * - Időzítés alapú logika (15min, 3min küszöbök)
 * - instantMaxBid opció támogatása
 * - Intelligens licit összeg kalkuláció
 */
@Feature(name = "Auto Auction", description = "Automatically monitors and bids on auctions")
public class AutoAuction implements Behavior, Configurable<AutoAuctionConfig> {

    // ============================================
    // API-k és Managerek
    // ============================================
    private final BackpageAPI backpage;
    private final StatsAPI stats;
    private final BotAPI bot;
    private final Random random = new Random();

    // ============================================
    // Konfiguráció
    // ============================================
    private AutoAuctionConfig config;

    // ============================================
    // Állapot követés
    // ============================================
    private long lastUpdate = 0;
    private final Map<String, AuctionItem> trackedAuctions = new LinkedHashMap<>();
    private final Map<String, BidState> bidStates = new LinkedHashMap<>();

    // Állapot változók a stratégiához
    private boolean firstStep = false;
    private long myLastPrice = 0;
    private long tempWealth = 0;

    // ============================================
    // Regex pattern-ek HTML parseoláshoz
    // ============================================

    /**
     * Pattern az árverési táblázat sorainak megtalálásához
     */
    private static final Pattern AUCTION_TABLE_ROW = Pattern.compile(
            "(itemKey=\"item_[\\S\\s]+?)</tr>",
            Pattern.DOTALL);

    /**
     * Pattern egy árverési tétel részleteinek kinyeréséhez
     * Csoportok:
     * 1. Item ID (pl. "12345" az "item_hour_12345"-ből)
     * 2. Tétel neve (pl. "LF-4")
     * 3. Tétel típusa (pl. "Laser")
     * 4. Legmagasabb licitáló info (showUser="..." tag)
     * 5. Jelenlegi licit összege
     * 6. Saját licit összege
     * 7. Azonnali vásárlás ár
     * 8. LootID (szerver-oldali azonosító)
     */
    private static final Pattern AUCTION_ITEM_DETAILS = Pattern.compile(
            "itemKey=\"item_[a-zA-Z]+_(.+?)\".*?" +
                    "auction_item_name_col\">\\s+(.+?)\\s+<.*?" +
                    "auction_item_type\">\\s+(.+?)\\s+<.*?" +
                    "auction_item_highest\" (.+?)>.*?" +
                    "auction_item_current\">\\s+([\\d,.-]+)\\s+<.*?" +
                    "auction_item_you\">\\s+([\\d,.-]+)\\s+<.*?" +
                    "item_[a-zA-Z]+_\\d+_buyPrice\" value=\"([\\d,.-]+)\".*?" +
                    "item_[a-zA-Z]+_\\d+_lootId\" value=\"(.+?)\".*?",
            Pattern.DOTALL);

    /**
     * Pattern a licitálási válasz feldolgozásához
     */
    private static final Pattern AUCTION_RESPONSE = Pattern.compile(
            "infoText = '(.*?)';.*?icon = '(.*)';",
            Pattern.DOTALL);

    /**
     * Pattern az árverés idő parseoléséhez
     */
    private static final Pattern AUCTION_TIME = Pattern.compile(
            "auction_item_time\"[^>]*>\\s*([^<]+)\\s*<",
            Pattern.DOTALL);

    // ============================================
    // Konstruktor - API-k inicializálása
    // ============================================
    public AutoAuction(PluginAPI api) {
        this.backpage = api.requireAPI(BackpageAPI.class);
        this.stats = api.requireAPI(StatsAPI.class);
        this.bot = api.requireAPI(BotAPI.class);
    }

    // ============================================
    // Configurable interfész implementáció
    // ============================================
    @Override
    public void setConfig(ConfigSetting<AutoAuctionConfig> setting) {
        this.config = setting.getValue();
    }

    // ============================================
    // Behavior interfész - Fő tick loop
    // ============================================
    @Override
    public void onTickBehavior() {
        // Ellenőrzések
        if (!isReadyForAuction()) {
            return;
        }

        // Időzítés - 30 másodpercenként frissít
        long now = System.currentTimeMillis();
        long interval = 30_000; // 30 sec

        if (now - lastUpdate < interval) {
            return;
        }

        try {
            // Árverések frissítése
            updateAuctions();

            // Licitálási logika
            processAuctions();

            lastUpdate = now;

        } catch (Exception e) {
            System.err.println("[AutoAuction] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ============================================
    // Ellenőrző metódusok
    // ============================================

    /**
     * Ellenőrzi hogy a plugin futhat-e
     */
    private boolean isReadyForAuction() {
        // Config betöltve?
        if (config == null || !config.enabled) {
            return false;
        }

        // Van legalább 1 enabled aukció típus?
        boolean hasEnabledType = (config.hourlyAuctions != null && config.hourlyAuctions.enabled) ||
                (config.dailyAuctions != null && config.dailyAuctions.enabled);

        if (!hasEnabledType) {
            return false;
        }

        // Van legalább 1 target item?
        boolean hasTargets = false;
        if (config.hourlyAuctions != null && config.hourlyAuctions.enabled) {
            hasTargets = !config.hourlyAuctions.targets.isEmpty();
        }
        if (!hasTargets && config.dailyAuctions != null && config.dailyAuctions.enabled) {
            hasTargets = !config.dailyAuctions.targets.isEmpty();
        }

        return hasTargets;
    }

    /**
     * Ellenőrzi hogy egy tétel érdekel-e minket az új config alapján
     */
    private boolean isItemInteresting(AuctionItem item) {
        // Megfelelő config kiválasztása a típus alapján
        switch (item.auctionType) {
            case HOUR:
                if (!config.hourlyAuctions.enabled) {
                    return false;
                }
                return findMatchingTarget(item, config.hourlyAuctions.targets) != null;

            case DAY:
                if (!config.dailyAuctions.enabled) {
                    return false;
                }
                return findMatchingTarget(item, config.dailyAuctions.targets) != null;

            case WEEK:
                // Heti aukciók jelenleg nem támogatottak
                return false;

            default:
                return false;
        }
    }

    /**
     * Megkeresi a matching ProductTarget-et az item-hez
     */
    private ProductTarget findMatchingTarget(AuctionItem item, List<ProductTarget> targets) {
        for (ProductTarget target : targets) {
            if (target.productName.trim().equalsIgnoreCase(item.name.trim())) {
                return target;
            }
        }
        return null;
    }

    /**
     * Ellenőrzi hogy licitálhatunk-e egy tételre
     */
    private boolean canBidOnItem(AuctionItem item, long bidAmount) {
        // Megkeressük a megfelelő target-et
        ProductTarget target = null;

        switch (item.auctionType) {
            case HOUR:
                target = findMatchingTarget(item, config.hourlyAuctions.targets);
                break;
            case DAY:
                target = findMatchingTarget(item, config.dailyAuctions.targets);
                break;
            default:
                return false;
        }

        if (target == null) {
            return false;
        }

        // Van elég credit?
        if (stats.getCredits() < bidAmount) {
            if (config.logBids) {
                System.out.println("[AutoAuction] ❌ Not enough credits for " + item.name);
            }
            return false;
        }

        // Belefér a termék-specifikus max bid limitbe?
        if (bidAmount > target.maxBid) {
            if (config.logBids) {
                System.out.println("[AutoAuction] ❌ Bid amount " + bidAmount +
                        " exceeds max bid " + target.maxBid + " for " + item.name);
            }
            return false;
        }

        // Már elérte a sikeres licitek számát?
        if (target.successfulBidsCount > 0) {
            // TODO: Számolni kell hogy hány sikeres licit volt már
            // Ezt a bid success után kell frissíteni
        }

        return true;
    }

    // ============================================
    // Árverés frissítés - GET kérés + HTML parse
    // ============================================

    /**
     * Lekéri az aktuális árveréseket a szerverről és frissíti a listát
     */
    private void updateAuctions() {
        try {
            // HTTP GET kérés
            String auctionPageHtml = backpage.getHttp("indexInternal.es?action=internalAuction")
                    .getContent();

            // HTML parseolás
            parseAuctionPage(auctionPageHtml);

        } catch (Exception e) {
            System.err.println("[AutoAuction] Failed to update auctions: " + e.getMessage());
        }
    }

    /**
     * Parseolja az árverési HTML oldalt és frissíti a trackedAuctions listát
     */
    private void parseAuctionPage(String html) {
        // Régi tételek megjelölése törlésre
        trackedAuctions.values().forEach(item -> item.markedForRemoval = true);

        // Táblázat sorok megkeresése
        Matcher tableMatcher = AUCTION_TABLE_ROW.matcher(html);

        while (tableMatcher.find()) {
            String rowHtml = tableMatcher.group();
            parseAuctionItem(rowHtml);
        }

        // Lejárt árverések törlése
        trackedAuctions.values().removeIf(item -> item.markedForRemoval);

        if (config.logBids) {
            System.out.println("[AutoAuction] Tracked auctions: " + trackedAuctions.size());
        }
    }

    /**
     * Parseol egy árverési tételt és hozzáadja/frissíti a listában
     */
    private void parseAuctionItem(String rowHtml) {
        Matcher matcher = AUCTION_ITEM_DETAILS.matcher(rowHtml);

        if (!matcher.find()) {
            return;
        }

        try {
            // Adatok kinyerése
            String itemId = matcher.group(1);
            String name = matcher.group(2).trim();
            String type = matcher.group(3).trim();
            String highestBidderInfo = matcher.group(4);
            long currentBid = parseNumber(matcher.group(5));
            long ownBid = parseNumber(matcher.group(6));
            long instantBuy = parseNumber(matcher.group(7));
            String lootId = matcher.group(8);

            // Árverés típus meghatározása
            AuctionType auctionType = determineAuctionType(rowHtml);
            String fullItemId = "item_" + auctionType.getId() + "_" + itemId;

            // Idő parseolása
            long remainingTime = parseAuctionTime(rowHtml);

            // AuctionItem létrehozása vagy frissítése
            AuctionItem item = trackedAuctions.computeIfAbsent(fullItemId, k -> new AuctionItem());
            item.id = fullItemId;
            item.lootId = lootId;
            item.name = name;
            item.type = type;
            item.currentBid = currentBid;
            item.ownBid = ownBid;
            item.instantBuy = instantBuy;
            item.auctionType = auctionType;
            item.remainingTimeSeconds = remainingTime;
            item.isPlayerHighestBidder = highestBidderInfo.contains("showUser");
            item.markedForRemoval = false;

        } catch (Exception e) {
            System.err.println("[AutoAuction] Failed to parse auction item: " + e.getMessage());
        }
    }

    /**
     * Meghatározza az árverés típusát a HTML alapján
     */
    private AuctionType determineAuctionType(String html) {
        if (html.contains("item_hour_"))
            return AuctionType.HOUR;
        if (html.contains("item_day_"))
            return AuctionType.DAY;
        if (html.contains("item_week_"))
            return AuctionType.WEEK;
        return AuctionType.HOUR; // default
    }

    /**
     * Parseolja az árverés hátralévő idejét másodpercekben
     */
    private long parseAuctionTime(String html) {
        Matcher matcher = AUCTION_TIME.matcher(html);
        if (!matcher.find()) {
            return 0;
        }

        String timeStr = matcher.group(1).trim();
        // Formátum: "00:15:30" vagy "15:30" vagy "30"
        String[] parts = timeStr.split(":");

        try {
            if (parts.length == 3) {
                // HH:MM:SS
                return Long.parseLong(parts[0]) * 3600 +
                        Long.parseLong(parts[1]) * 60 +
                        Long.parseLong(parts[2]);
            } else if (parts.length == 2) {
                // MM:SS
                return Long.parseLong(parts[0]) * 60 +
                        Long.parseLong(parts[1]);
            } else if (parts.length == 1) {
                // SS
                return Long.parseLong(parts[0]);
            }
        } catch (NumberFormatException e) {
            // Ignore
        }

        return 0;
    }

    /**
     * Számot parseol a HTML-ből
     */
    private long parseNumber(String numberStr) {
        if (numberStr == null || numberStr.isEmpty() || numberStr.equals("-")) {
            return 0;
        }
        return Long.parseLong(numberStr.replace(",", "").replace(".", "").replace("-", ""));
    }

    // ============================================
    // Licitálási logika (AutoAuctionDocu alapján)
    // ============================================

    /**
     * Végigmegy a tracked árveréseken és licitál ha kell
     * 
     * FONTOS: Ez a logika MINDEN TERMÉKRE KÜLÖN-KÜLÖN fut le!
     * Nem áll meg az első találatnál, hanem végigmegy az összes árverésen,
     * és mindegyikre függetlenül alkalmazza a stratégiát.
     * 
     * Stratégia (AutoAuctionDocu alapján):
     * 1. checkActiveBid() - Ellenőrzi van-e aktív licit
     * 2. getAuctionsTime() - Lekéri az árverés idejét
     * 3. Időzítés alapú döntés:
     * - Ha > 15min: Várakozás
     * - Ha > 3min: Normál licitálás
     * - Ha < 3min: Agresszív licitálás (utolsó percek)
     */
    private void processAuctions() {
        int processedCount = 0;
        int bidsPlaced = 0;

        // MINDEN termékre végigmegyünk, nem állunk meg az elsőnél!
        for (AuctionItem item : trackedAuctions.values()) {
            // Érdekel?
            if (!isItemInteresting(item)) {
                continue;
            }

            // Lekérjük a target-et
            ProductTarget target = null;
            switch (item.auctionType) {
                case HOUR:
                    target = findMatchingTarget(item, config.hourlyAuctions.targets);
                    break;
                case DAY:
                    target = findMatchingTarget(item, config.dailyAuctions.targets);
                    break;
            }

            if (target == null) {
                continue;
            }

            // Lekérjük vagy létrehozzuk a bid state-et erre a konkrét termékre
            BidState state = bidStates.computeIfAbsent(item.id, k -> new BidState());

            // AutoAuctionDocu logika implementálása erre a termékre
            boolean bidPlaced = processAuctionWithStrategy(item, target, state);

            processedCount++;
            if (bidPlaced) {
                bidsPlaced++;
            }

            // FONTOS: NEM break-elünk! Folytatjuk a következő termékkel!
        }

        if (config.logBids && processedCount > 0) {
            System.out.println("[AutoAuction] 📊 Processed " + processedCount +
                    " auctions, placed " + bidsPlaced + " bids");
        }
    }

    /**
     * Stratégiai licitálási logika (AutoAuctionDocu alapján)
     * 
     * Ez a metódus EGY KONKRÉT TERMÉKRE dolgozza fel a licitálási logikát.
     * Minden terméknek megvan a saját BidState-je, így függetlenül kezelhetők.
     * 
     * @return true ha licit történt, false ha nem
     */
    private boolean processAuctionWithStrategy(AuctionItem item, ProductTarget target, BidState state) {
        long currentTime = item.remainingTimeSeconds;
        long step = config.minBidStep;
        long maxBid = target.maxBid;
        long price = item.currentBid;

        // 3.5-3.8: checkActiveBid - Ez termék-specifikus!
        if (!state.firstStep && item.isPlayerHighestBidder && item.ownBid > 0) {
            state.myLastPrice = item.ownBid;
            state.firstStep = true;
            if (config.logBids) {
                System.out.println("[AutoAuction] 📌 Active bid detected for " + item.name +
                        ": " + item.ownBid);
            }
        }

        // 4-7: Időzítés ellenőrzés és reset
        if (currentTime > 900) { // > 15min
            if (config.logBids) {
                System.out.println("[AutoAuction] ⏰ [" + item.name + "] Waiting, auction has " +
                        currentTime / 60 + " minutes left");
            }
            return false;
        }

        // 8-16: Ha > 3min (180 sec)
        if (currentTime > 180) {
            return processNormalBidding(item, target, state, step, maxBid, price);
        }

        // 17-31: Ha < 3min (utolsó percek - agresszív)
        return processAggressiveBidding(item, target, state, step, maxBid, price);
    }

    /**
     * Normál licitálás (> 3min hátralévő idő)
     * 
     * FÁZIS 2: TESZT LICIT (15 min → 3 min)
     * 
     * Cél: Felismerni van-e vetélytársunk (PASSZÍV vs AKTÍV eset)
     * 
     * ELSŐ LICIT (firstStep=false):
     * - Strategy=TRUE: Csak +1 step (TESZT)
     * → Ha senki nem reagál → PASSZÍV eset ✅ (olcsón nyerünk)
     * → Ha valaki reagál → AKTÍV eset ⚠️ (háború indul)
     * 
     * - Strategy=FALSE: Azonnal maxBid
     * → Biztos, de drága mód
     * 
     * RE-BID (firstStep=true, outbid):
     * - AKTÍV eset felismerve
     * - Folyamatos emelés: (currentPrice - myLastPrice) + step
     * - "Háború" a vetélytárssal
     * 
     * Ez a metódus EGY KONKRÉT TERMÉKRE alkalmazza a normál licitálási logikát.
     * Termék-specifikus state és max bid használatával.
     * 
     * @return true ha licit történt, false ha nem
     */
    private boolean processNormalBidding(AuctionItem item, ProductTarget target, BidState state,
            long step, long maxBid, long price) {
        // 10: getEnablePrice ellenőrzés
        if (price >= maxBid) {
            if (config.logBids) {
                System.out.println("[AutoAuction] ⚠️ [" + item.name + "] Price exceeds max bid");
            }
            return false;
        }

        // 11: Ellenőrizzük hogy a játékos a legmagasabb licitáló-e
        if (!item.isPlayerHighestBidder) {
            // 12: firstStep ellenőrzés
            if (!state.firstStep) {
                // 13: Első licit
                long bidAmount;
                if (config.strategy) { // instantMaxBid flag
                    // 13.1: Azonnal max bid
                    bidAmount = maxBid;
                    state.myLastPrice = maxBid;
                } else {
                    // 13.2: Csak step-pel növel
                    bidAmount = price + step;
                    state.myLastPrice = price + step;
                }
                state.firstStep = true;

                if (placeBid(item, bidAmount)) {
                    if (config.logBids) {
                        System.out.println("[AutoAuction] ✅ [" + item.name + "] First bid placed: " + bidAmount);
                    }
                    return true;
                }
            } else {
                // 15-16: Túllicitáltak minket
                if (price < maxBid) {
                    long tempPrice = (price - state.myLastPrice) + step;
                    long newBid = price + tempPrice;

                    if (newBid <= maxBid && placeBid(item, newBid)) {
                        state.myLastPrice = newBid;
                        if (config.logOutbid) {
                            System.out.println("[AutoAuction] 🔄 [" + item.name + "] Re-bid after outbid: " + newBid);
                        }
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Agresszív licitálás (< 3min hátralévő idő)
     * 
     * FÁZIS 3: AGRESSZÍV LICITÁLÁS (< 3 perc)
     * 
     * Két különböző eset attól függően, hogy PASSZÍV vagy AKTÍV:
     * 
     * ─── PASSZÍV ESET ───
     * (Senki nem figyeli, mi vagyunk highest)
     * 
     * 3 min - 30 sec:
     * - Várakozás (még mindig highest vagyunk)
     * - Nincs teendő
     * 
     * < 30 sec:
     * - KIS random összeg: step + random(0...step)
     * - Miért: NEHEZÍTÉS, ha valaki last minute jönne
     * - Cél: Minimális költséggel nyerni
     * 
     * ─── AKTÍV ESET ───
     * (Valaki figyeli, folyamatos licitháború)
     * 
     * ELSŐ LICIT (firstStep=false):
     * - NAGY random összeg
     * - Ha (maxBid-price)/2 > step:
     * → Random(step...remaining/2)
     * → Cél: NAGY UGRÁS, meglepni a vetélytársat
     * - Különben: step + random(0...step)
     * 
     * RE-BID (firstStep=true, outbid):
     * - Közepes/nagy random összeg
     * - Folyamatos nyomás a vetélytársra
     * - Próbáljuk elvenni a terméket
     * 
     * TODO: Implementálni a 30 sec küszöböt a passzív esetben!
     * Jelenleg az összes < 3min esetre azonnal licitál.
     * Javítás: Ellenőrizni hogy < 30 sec-e, ha passzív eset.
     * 
     * Ez a metódus EGY KONKRÉT TERMÉKRE alkalmazza az agresszív licitálási logikát.
     * Utolsó percekben intelligens, random összegekkel licitál.
     * 
     * @return true ha licit történt, false ha nem
     */
    private boolean processAggressiveBidding(AuctionItem item, ProductTarget target, BidState state,
            long step, long maxBid, long price) {
        // 18: getEnablePrice ellenőrzés
        if (price >= maxBid) {
            return false;
        }

        // TODO: PASSZÍV ESET 30 SEC ELLENŐRZÉS
        // Ha passzív eset (isPlayerHighestBidder=true) ÉS time > 30 sec
        // → return false (várunk még)
        //
        // if (item.isPlayerHighestBidder && item.remainingTimeSeconds > 30) {
        // return false; // Passzív eset, még várunk
        // }

        // 21: Ellenőrizzük hogy a játékos a legmagasabb licitáló-e
        if (!item.isPlayerHighestBidder) {
            // 22: firstStep ellenőrzés
            if (!state.firstStep) {
                // 23-31: Utolsó percek agresszív stratégia
                long remaining = maxBid - price;

                if (remaining >= (step + 1)) {
                    long tempPrice;

                    if (remaining / 2 > step) {
                        // 24-25: Nagy különbség
                        if (config.strategy) { // instantMaxBid
                            // 25.1: Azonnal max bid
                            tempPrice = maxBid - price;
                            state.myLastPrice = maxBid;
                        } else {
                            // 25.2: Random összeg step és remaining/2 között
                            long randomAmount = random.nextLong(remaining / 2);
                            tempPrice = step + randomAmount;
                            state.myLastPrice = price + tempPrice;
                        }
                    } else if (remaining > (step + step)) {
                        // 29-30: Közepes különbség
                        tempPrice = step + random.nextLong(step);
                        state.myLastPrice = price + tempPrice;
                    } else {
                        // 31: Kis különbség
                        tempPrice = step;
                        state.myLastPrice = price + step;
                    }

                    // 26-28: Licit és sikeres ellenőrzés
                    state.tempWealth = stats.getCredits();
                    if (placeBid(item, tempPrice)) {
                        state.firstStep = true;

                        if (config.logBids) {
                            System.out.println("[AutoAuction] 🎯 [" + item.name + "] Aggressive bid: " + tempPrice);
                        }
                        return true;
                    }
                }
            } else {
                // 29-31: Már van active bid-ünk erre a termékre
                long remaining = maxBid - price;
                if (remaining > (step + step)) {
                    long tempPrice = step + random.nextLong(step);
                    long newBid = price + tempPrice;

                    if (placeBid(item, newBid)) {
                        state.myLastPrice = newBid;
                        if (config.logBids) {
                            System.out.println("[AutoAuction] 🔄 [" + item.name + "] Aggressive re-bid: " + newBid);
                        }
                        return true;
                    }
                } else if (remaining >= step) {
                    long newBid = price + step;
                    if (placeBid(item, newBid)) {
                        state.myLastPrice = newBid;
                        if (config.logBids) {
                            System.out.println("[AutoAuction] 🔄 [" + item.name + "] Final bid: " + newBid);
                        }
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ============================================
    // Licitálás végrehajtás
    // ============================================

    /**
     * Licitál egy tételre
     */
    private boolean placeBid(AuctionItem item, long amount) {
        if (!canBidOnItem(item, amount)) {
            return false;
        }

        try {
            // 1. Reload token lekérése
            String token = fetchReloadToken();

            if (token == null || token.isEmpty()) {
                System.err.println("[AutoAuction] Failed to fetch reload token");
                return false;
            }

            // 2. POST kérés a licittel
            String response = backpage.postHttp("indexInternal.es")
                    .setParam("action", "internalAuction")
                    .setParam("reloadToken", token)
                    .setParam("auctionType", item.auctionType.getId())
                    .setParam("subAction", "bid")
                    .setParam("lootId", item.lootId)
                    .setParam("itemId", item.id)
                    .setParam("credits", String.valueOf(amount))
                    .setParam("auction_buy_button", "BID")
                    .getContent();

            // 3. Válasz feldolgozása
            boolean success = handleBidResponse(response);

            if (success) {
                // Sikeres licit esetén frissítjük a target számláló
                onSuccessfulBid(item);
            }

            return success;

        } catch (Exception e) {
            System.err.println("[AutoAuction] Bid error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Lekéri a reload tokent (CSRF védelem)
     */
    private String fetchReloadToken() {
        try {
            return backpage.getHttp("indexInternal.es")
                    .setParam("action", "internalAuction")
                    .consumeInputStream(backpage::getReloadToken);
        } catch (Exception e) {
            System.err.println("[AutoAuction] Failed to fetch token: " + e.getMessage());
            return null;
        }
    }

    /**
     * Feldolgozza a szerver válaszát a licit után
     */
    private boolean handleBidResponse(String response) {
        Matcher matcher = AUCTION_RESPONSE.matcher(response);

        if (!matcher.find()) {
            return false;
        }

        String message = matcher.group(1);
        String icon = matcher.group(2);

        boolean success = !icon.contains("error");

        if (success) {
            if (config.logBids) {
                System.out.println("[AutoAuction] ✅ Server response: " + message);
            }
        } else {
            System.err.println("[AutoAuction] ❌ Server error: " + message);
        }

        return success;
    }

    /**
     * Sikeres licit után hívjuk meg
     */
    private void onSuccessfulBid(AuctionItem item) {
        // Megkeressük a target-et
        ProductTarget target = null;

        switch (item.auctionType) {
            case HOUR:
                target = findMatchingTarget(item, config.hourlyAuctions.targets);
                break;
            case DAY:
                target = findMatchingTarget(item, config.dailyAuctions.targets);
                break;
        }

        if (target != null && config.logWins) {
            // TODO: Csak akkor számoljuk sikeresnek, ha tényleg megnyertük
            // Ezt delay után ellenőrizni kell a wealth alapján
            System.out.println("[AutoAuction] 🎉 Potential win: " + item.name +
                    " - Waiting for confirmation...");
        }
    }

    // ============================================
    // Belső adatstruktúrák
    // ============================================

    /**
     * Egy árverési tételt reprezentáló osztály
     */
    public static class AuctionItem {
        public String id;
        public String lootId;
        public String name;
        public String type;
        public long currentBid;
        public long ownBid;
        public long instantBuy;
        public AuctionType auctionType;
        public long remainingTimeSeconds;
        public boolean isPlayerHighestBidder;
        public boolean markedForRemoval;

        @Override
        public String toString() {
            return String.format("AuctionItem{name='%s', currentBid=%d, type=%s, time=%ds}",
                    name, currentBid, auctionType, remainingTimeSeconds);
        }
    }

    /**
     * Árverés típusok
     */
    public enum AuctionType {
        HOUR("hour"),
        DAY("day"),
        WEEK("week");

        private final String id;

        AuctionType(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /**
     * Licitálási állapot követése termékenkénт
     * 
     * FONTOS: Minden AuctionItem-nek SAJÁT BidState-je van!
     * Az állapotok NEM keverednek - teljesen függetlenek.
     * 
     * Példa:
     * bidStates.get("item_hour_12345") → LF-4 állapota
     * bidStates.get("item_hour_67890") → Havoc állapota
     * bidStates.get("item_day_11111") → Hercules állapota
     * 
     * Így minden termék saját "emlékezettel" rendelkezik:
     * - Melyik termékre tettünk már első licitet (firstStep)
     * - Mennyi volt az utolsó licitünk termékenként (myLastPrice)
     * - Mi volt a wealth amikor licitáltunk termékenként (tempWealth)
     * 
     * firstStep FLAG SZEREPE:
     * - false: "Még NEM licitáltunk erre a termékre"
     * → Akkor is false, ha a bot közben indult (pl. 11 perc van vissza)
     * → Első licit: TESZT step VAGY maxBid (strategy alapján)
     * 
     * - true: "Már licitáltunk erre a termékre"
     * → Re-bid esetén: folyamatos emelés VAGY random (helyzet alapján)
     * → Tudja a bot hogy már reagált erre a termékre
     * 
     * Életciklus:
     * 1. Start: firstStep=false
     * 2. Első licit: firstStep=true, myLastPrice beállítás
     * 3. Bot újraindítás + HTML parse:
     * - Ha isPlayerHighestBidder=true → firstStep=true, myLastPrice=ownBid
     * - Ha isPlayerHighestBidder=false → firstStep=false
     * 4. Árverés vége: BidState törlése
     */
    private static class BidState {
        public boolean firstStep = false; // Volt-e már első licit erre a termékre
        public long myLastPrice = 0; // Utolsó licit összege erre a termékre
        public long tempWealth = 0; // Ideiglenes wealth erre a termékre
    }
}