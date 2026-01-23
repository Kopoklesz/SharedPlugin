1,Start -> 2
2,Input,,Configs read -> 3
3,Process,,,firstStep = false -> 3.5

3.5,Process,,,checkActiveBid() -> 3.6
3.6,Decision,,,hasActiveBid? -> YES -> 3.7
3.6,Decision,,,hasActiveBid? -> NO -> 4

3.7,Process,,,loadActiveBidData() -> 3.8
3.8,Process,,,myLastPrice = activeBidAmount, firstStep = true -> 4

4,Process,,,getAuctionsTime(), checkAuctionReset() -> 5

5,Decision,,,"time > 15min" -> YES -> 6
5,Decision,,,"time > 15min" -> NO -> 8

6,Process,,,getWaitingTime() -> 7
7,Delay,,,Delay "X" min -> 4

8,Decision,,,"time > 3min" -> YES -> 9
8,Decision,,,"time > 3min" -> NO -> 17

9,Process,,,getAuctionProd() && getAuctionPrice() -> 10

10,Decision,,,getEnablePrice() -> YES -> 11
10,Decision,,,getEnablePrice() -> NO -> END

11,Decision,,,"maxBidder != Player" -> YES -> 12
11,Decision,,,"maxBidder != Player" -> NO -> 14

12,Decision,,,"firstStep != true" -> YES -> 13
12,Decision,,,"firstStep != true" -> NO -> 15

13,Decision,,,config.instantMaxBid? -> YES -> 13.1
13,Decision,,,config.instantMaxBid? -> NO -> 13.2

13.1,Process,,,setBid(maxBid - price), firstStep = true, myLastPrice = maxBid -> 14
13.2,Process,,,setBid(step), firstStep = true, myLastPrice = price + step -> 14

14,Delay,,,Delay "3" min -> 4

15,Decision,,,getEnablePrice() -> YES -> 16
15,Decision,,,getEnablePrice() -> NO -> END

16,Process,,,tempPrice = (price - myLastPrice) + step, myLastPrice = price + tempPrice, setBid(tempPrice) -> 14

17,Process,,,getAuctionProd(), getAuctionPrice() -> 18

18,Decision,,,getEnablePrice() -> YES -> 19
18,Decision,,,getEnablePrice() -> NO -> END

19,Delay,,,Delay "2.5" min -> 20

20,Decision,,,getEnablePrice() -> YES -> 21
20,Decision,,,getEnablePrice() -> NO -> END

21,Decision,,,"maxBidder != Player" -> YES -> 22
21,Decision,,,"maxBidder != Player" -> NO -> 29

22,Decision,,,"firstStep != true" -> YES -> 23
22,Decision,,,"firstStep != true" -> NO -> 29

23,Decision,,,(maxBid - price) >= (step + 1) -> YES -> 24
23,Decision,,,(maxBid - price) >= (step + 1) -> NO -> END

24,Decision,,,(maxBid - price) / 2 > step -> YES -> 25
24,Decision,,,(maxBid - price) / 2 > step -> NO -> 31

25,Decision,,,config.instantMaxBid? -> YES -> 25.1
25,Decision,,,config.instantMaxBid? -> NO -> 25.2

25.1,Process,,,tempPrice = maxBid - price, myLastPrice = maxBid -> 26
25.2,Process,,,remaining = maxBid - price, tempPrice = step + RandomINT(0 -> remaining / 2), myLastPrice = price + tempPrice -> 26

26,Process,,,setBid(tempPrice), tempWealth = getWealth() -> 27

27,Delay,,,Delay "5" min -> 28

28,Decision,,,getWealth() < tempWealth -> YES -> "Success (all)" -> END
28,Decision,,,getWealth() >= tempWealth -> NO -> "Fail" -> END

29,Decision,,,(maxBid - price) > (step + step) -> YES -> 30
29,Decision,,,(maxBid - price) > (step + step) -> NO -> 31

30,Process,,,tempPrice = RandomINT(step -> (step + step)), myLastPrice = price + tempPrice -> 27

31,Process,,,tempPrice = step, myLastPrice = price + tempPrice -> 27




//szükséges átnézni + maxDib felrakása csak a z utolsó 3 percben, ha aggresszív az enemy