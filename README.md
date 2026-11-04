# RodeoTikTokLive 1.3.1 — kamera zza bota + wczytywanie swiata + droga 7 blokow

Aktualizacja bazuje na projekcie 1.2.9 / 1.3.0. Zachowuje Mineflayer, TikFinity bridge, gifty, limit 30 mobow i kolejke 150.

## Najwazniejsza poprawka kamery
Poprzednia kamera obserwowala niewidzialny ArmorStand, ktory podazal za botem. To moglo prowadzic do problemow z wysylaniem chunkow do klienta obserwatora, gdy jego rzeczywista pozycja nie nadazala za kamera.

Teraz `/rodeolive camera` przestawia konto obserwatora w SPECTATOR i teleportuje jego **rzeczywista pozycje** co 2 ticki, kilka blokow za botem. Widac model postaci, zbroje, trzymany miecz i animacje wynikajace z gry. Kamera patrzy w kierunku bota. Minecraft powinien wysylac chunki wzgledem faktycznej pozycji widza. `/rodeolive uncamera` przywraca poprzednia pozycje i tryb.

## Droga
Szerokosc `lane.width: 7` (3 bloki + srodek + 3). Asynchroniczne wczytywanie chunkow i ograniczone bilety pluginowe (*chunk tickets*) przed botem `lane.chunk-prefetch-blocks: 56`, `lane.look-ahead: 24`. Bilety sa usuwane po przejsciu bota dalej oraz przy wylaczeniu pluginu.

## Instalacja
1. Zrob kopie swiata LIVE i `plugins/RodeoTikTokLive/config.yml`, bo plugin trwale zmienia teren.
2. Przez GitHub Desktop nadpisz caly kod projektu z tej paczki (ale NIE kopiuj prywatnych `config.json`). Commit + Push; GitHub Actions powinien zbudowac JAR.
3. Zatrzymaj Paper, wyjmij stary JAR z folderu `plugins` i wstaw nowy 1.3.1; zostaw tylko jeden JAR RodeoTikTokLive.
4. Zachowaj istniejacy `plugins/RodeoTikTokLive/config.yml`, token i port API 18765. W jego **istniejacej** sekcji `lane` ustaw `width: 7`, `look-ahead: 24`, `chunk-prefetch-blocks: 56`. Dopisz sekcje `camera` jak ponizej, jesli jej nie ma.
5. Zachowaj dotychczasowe `bot/config.json` i `bridge/config.json`: NIE zmieniaj tokenow, portu Minecraft 25565 ani mapowania giftow. Kod bota i bridge pozostaje zgodny.
6. Uruchom Paper, `npm start` w bot i bridge, a na serwerze `/rodeolive start`. Wejdz na konto obserwatora i wpisz `/rodeolive camera`. Wyjscie: `/rodeolive uncamera`.

```yaml
lane:
  width: 7
  look-ahead: 24
  chunk-prefetch-blocks: 56
camera:
  distance: 5.5
  height: 2.4
```

Uwaga: fragment `lane` ma zostac **scalony** z istniejaca sekcja `lane`, nie wklejony jako druga sekcja.

## Test
- `/version RodeoTikTokLive` -> 1.3.1
- `/rodeolive start` oraz `/rodeolive camera`
- Pozostaw kamere na 2-3 minuty: sprawdz, czy nowe chunki przed botem stale sie pojawiaja
- `/rodeolive test corgi Test123`: czy kamera widzi miecz bota i ruch podczas walki
- `/rodeolive uncamera`: powrot na stare miejsce

## Ograniczenia
Ta kamera jest trzecioosobowa: nie wyswietla miecza jako pierwszoosobowej dloni. Teleportacja obserwatora co 2 ticki moze powodowac drganie obrazu; plynne renderowanie zalezy od klienta Minecraft i parametrow serwera. Wczytywanie chunkow ma nadal limity bezpieczenstwa. Kod wymaga kompilacji przez GitHub Actions i testu na prawdziwym serwerze.
