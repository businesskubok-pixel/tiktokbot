# RodeoTikTokLive 1.2.9 — poprawki celowania i generowania terenu

Zmiany względem wersji 1.2.8:
- Bot rozpoznaje Ravagera, Evokera, Wardena, Vindicatora i Vexa jako cele; wykrywa przeciwników z większej odległości.
- Plugin wcześniej wczytuje/generuje chunki wzdłuż trasy (asynchroniczne Paper API), po maksymalnie 2 nowe żądania na sekundę i do 4 równoległych.
- Zachowane gifty, limit 30 mobów, kolejka 150, zabezpieczenie terenu przed wybuchami i tokeny **w prywatnych plikach**.

## Instalacja

1. Skopiuj zmieniony kod do repozytorium i skompiluj JAR przez GitHub Actions.
2. Podmień JAR na serwerze po jego wyłączeniu. Pozostaw dotychczasowy `plugins/RodeoTikTokLive/config.yml`, ale dodaj `lane.chunk-prefetch-blocks: 56` w sekcji `lane`.
3. Podmień jedynie `bot/index.js` i ewentualnie `bot/package.json`. **Nie nadpisuj `bot/config.json` ani `bridge/config.json`.**
4. Uruchom Paper, bota oraz bridge, wpisz `/rodeolive start`, a następnie test `/rodeolive test corgi Test123`.

## Kamera OBS

`/rodeolive camera` to kamera widza w trybie Spectator; z przyczyn gry nie pokaże miecza w pierwszej osobie. Aby go widzieć, potrzebny jest prawdziwy klient Minecraft z renderowanym widokiem sterowanym automatycznie, nie samo Mineflayer. Jeśli kamera widzi niebo, ustaw `view-distance=10` lub `12` w `server.properties`, upewnij się że klient ma render distance 10-12, użyj F3+A, sprawdź dysk i obciążenie TPS. Prefetch łagodzi brak chunków po stronie serwera, lecz nie naprawi wszystkich problemów ze streamingiem chunków do spectatorów.

Nie testowano w działającym serwerze. Plugin wymaga kompilacji przez GitHub Actions. Przed testem zrób kopię świata (plugin trwale zmienia bloki).
