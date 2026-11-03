# RodeoTikTokLive 1.1.0 — Minecraft Paper 1.20.6 + Mineflayer + TikFinity Desktop

## Co zostało dodane

- `/rodeolive debug`: pokazuje faktyczny nick bota i nazwy swiatow (naprawa diagnostyki `Bot musi byc w swiecie...`).
- `/rodeolive start` moze automatycznie przeniesc bota do skonfigurowanego swiata.
- Marsz bota stale w kierunku z configu (EAST/WEST/NORTH/SOUTH) od momentu `/rodeolive start`.
- Plugin wyrownuje wąski pas terenu przed botem: stawia podloge z trawy, usuwa przeszkody do `clear-height` blokow nad nia; odleglosc `look-ahead`.
- Bot ma netherytowy set Protection IV + Unbreaking III oraz miecz Sharpness V, 30 serc, regeneracje i zmniejszone obrazenia.
- Bot automatycznie atakuje pobliskie wrogie moby w zasiegu ~3,2 blokow, bez gonienia ich (idzie nadal prosto).
- Po smierci auto-respawn i ponowny marsz, kiedy transmisja jest aktywna.
- Zdarzenia TikFinity: follow -> creeper, 500 tapniec -> zombie, rose -> zombie, heart -> 3 zombie, galaxy -> 5 creeperow, share -> skeleton.
- Most /status informuje bota, czy LIVE wystartowal.

**Uwaga: to projekt źródłowy. W tym środowisku nie było dostępnego Gradle ani Paper API do lokalnej kompilacji. Zbuduj JAR przez GitHub Actions i sprawdź wynik kompilacji. Nie twierdź, że JAR jest przetestowany.**

## 1. Instalacja serwera

1. Osobny Paper **Minecraft 1.20.6**, Java **21** (Java 25 na własne ryzyko kompatybilności). Użyj oddzielnego świata/testowego serwera. Edycja bloków jest **nieodwracalna** bez kopii.
2. Podmień **cały** folder `paper-plugin/` w repo (zachowaj structure); ustaw `paper-plugin/src/main/resources/config.yml` na `world: world` lub na nazwę `level-name` w `server.properties`.
3. Workflow w `.github/workflows/build.yml` buduje plugin Gradle 8.14 i publikuje `.jar` jako artifact. Skompiluj i wrzuć JAR do `plugins/`, usuwając stary RodeoTikTokLive 1.0.
4. **Po starcie serwera** edytuj plik `plugins/RodeoTikTokLive/config.yml`, bo stary config 1.0 **nie zostanie automatycznie zastąpiony**. Najłatwiej wykonaj kopię starego i wgraj NOWY config z `paper-plugin/src/main/resources/config.yml`.
5. W config.yml ustaw `bridge.token` na długi losowy sekret (min. 20 znakow). Tego samego uzyj w `bot/config.json` i `bridge/config.json`. NIE commituj sekretu do GitHuba.

## 2. Bot (na Windows, osobne okno CMD)

`cd /d C:\Users\jatom\Desktop\tiktokbot\bot`

Zmien `bot/config.example.json` na `bot/config.json`, dostosuj port: **25566** zgodnie z wcześniejszą konfiguracją lokalnego serwera. Zostaw `auth: offline` tylko jesli to prywatny odizolowany serwer offline. W przypadku publicznego/premium serwera loguj się normalnie. `pluginStatusUrl=http://127.0.0.1:18765/status`, token identyczny jak w pluginie.

`npm install`

`npm start`

Bot zaloguje sie, ale nie pojdzie dopoki nie wykonasz `/rodeolive start`.

## 3. TikFinity Desktop + Bridge (drugie okno CMD)

Uruchom TikFinity Desktop na TYM SAMYM komputerze. Polacz z kontem/transmisja. Event API: `ws://127.0.0.1:21213/`.

`cd /d C:\Users\jatom\Desktop\tiktokbot\bridge`

Skopiuj `bridge/config.example.json` do `bridge/config.json`. Token taki sam jak plugin i bot.

`npm install`

`npm start`

`[TikFinity] Polaczono` oznacza polaczenie z Event API. Za pojedynczy `follow` powstanie creeper. Kazde 500 nowych polubien (`likeCount` — przyrost, nie `totalLikeCount`) powstanie zombie. Zliczanie jest globalne dla LIVE, nie na osobe; restart bridge zeruje licznik. Powtórne follow tego samego usera w ciągu 5 minut jest ignorowane.

## 4. Komendy

- `/rodeolive debug` — diagnoza swiat/nick
- `/rodeolive start` — zapisz punkt startowy, wyrównuj trase, uruchom marsz
- `/rodeolive stop` — zatrzymaj marsz
- `/rodeolive reset` — teleport bota do startu (nie odtwarza usuniętych bloków)
- `/rodeolive test likes500 Kuba`
- `/rodeolive test follow Kuba`
- `/rodeolive test rose Kuba`
- `/rodeolive test galaxy Kuba`
- `/rodeolive stats`
- `/rodeolive reload`

## Bezpieczeństwo i uwagi

- **Tylko osobny testowy swiat**. Zniszczenie blokow jest trwałe, a cofnięcie `/rodeolive reset` NIE odtwarza starych bloków.
- Zapewniony jest płaski **pas drogi**, a nie przebudowa całego świata. Zakres przeszkód do kilku bloków nad drogą; wielkie góry mogą zostać nad trasą.
- Bot walczy tylko z pobliskimi, wybranymi mobami; nie ściga ich, więc nie zatrzymuje długiego marszu. Nie daje gwarancji wygrania każdej walki.
- Nadmierne liczby giftów są ograniczane (`max-live-mobs`, `maxEventsPerSecond`).
- Bridge HTTP nasłuchuje wyłącznie na `127.0.0.1`; nie wystawiaj portów w routerze.
- Dla zewnętrznego hostingu wymagana jest osobna, zabezpieczona komunikacja — ten wariant jest przeznaczony na jeden komputer.
- Jeśli Twój serwer Paper to inna wersja niż Minecraft 1.20.6, musisz dopasować API i Mineflayer.
