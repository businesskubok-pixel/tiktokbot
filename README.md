# RodeoTikTokLive 1.2.0 — spokojny bot, moby, kamera, 5 dodatkowych giftów

**Wersja źródłowa, NIE przetestowana na uruchomionym Paper.** Kompilacja przez GitHub Actions. Wymaga Paper 1.20.6, Node.js i lokalnego TikFinity Desktop. Zrób kopię zapasową mapy (wyrównywanie terenu trwale zmienia bloki). Nie przesyłaj publicznie prawdziwych tokenów.

## Najważniejsze naprawy

- `/rodeolive test follow Test123` przywołuje Creepera, `/rodeolive test likes500 Test123` Zombie; wynik komendy mówi, ile **faktycznie** powstało mobów. Mechanizm przygotowuje kawałek podłogi i czyści przestrzeń w punkcie spawnu. Potrzebne jest aktywne `/rodeolive start` i bot online w świecie `world`.
- Bot nie sprintuje (`sprint: false`), a płynne, spokojne chodzenie uzyskuje dzięki `walkDutyCycle: 1` w `bot/config.json` oraz `bot.walk-speed: 0.10` w pluginowym `config.yml`. Wcześniejsze `walkDutyCycle: 0.65` powodowało krótkie szarpnięcia.
- `/rodeolive camera` **wpisane z Minecrafta** ustawia gracza jako Spectatora obserwującego bota; `/rodeolive uncamera` przywraca poprzedni gamemode. W OBS dodaj źródło **Przechwytywanie gry** z oknem Minecraft i przełącz na kamerę bota. **Ważne:** vanilla spectator nie renderuje dłoni ani miecza obserwowanego gracza! To widok z jego oczu, ale nie wierny zapis pierwszoosobowej animacji broni. Aby mieć prawdziwy miecz na ekranie, potrzebny jest osobny klient Minecraft sterowany automatycznie (np. mod do klienta) lub graficzny overlay, a nie sam Mineflayer.
- `/rodeolive gifts` wypisuje aktywne eventy i ich opisy. W pluginie możesz edytować typ i liczbę mobów dla każdego eventu w `mobs.events`.
- Pięć DODATKOWYCH giftów: `Finger Heart` -> 2 Zombie, `Corgi` -> 2 Creepery, `Perfume` -> 3 Szkielety, `Doughnut` -> 4 Pająki, `Lion` -> 8 Zombie. Są to przykłady — nazwy muszą być identyczne z nazwami w zdarzeniach TikFinity. W `bridge/config.json` możesz przypisać własne `label` (co się pojawia) i `coinsLabel` (cena do pokazania widzom). **Te pola są opisami, a nie ustawieniami cen TikToka.** Prawdziwe ceny sprawdzaj w aplikacji TikTok/TikFinity.
- `Rose`, `Heart` i `Galaxy`, a także `follow`, `share` i 500 tapnięć pozostają obsługiwane.

## Instalacja / aktualizacja

1. Podmień foldery `paper-plugin`, `bot`, `bridge` i `.github/workflows` na GitHubie. Nie wysyłaj lokalnych `config.json` z tokenami ani folderów `node_modules`.
2. GitHub Actions skompiluje Paper JAR — pobierz artefakt z `Actions` (tylko po zielonym sukcesie). Podmień starą wersję JAR na serwerze, **nie trzymaj dwóch wersji równocześnie**.
3. W `plugins/RodeoTikTokLive/config.yml` dopisz lub zmień `bot.walk-speed: 0.10` i nowe wpisy `mobs.events`. Jeśli kopiujesz cały nowy config, zachowaj swój `world: world` i osobno wstaw poprawny prywatny `bridge.token` (20+ znaków).
4. W lokalnym `bot/config.json` pozostaw swoje `host: 127.0.0.1`, `port: 25565`, `auth: offline` (TYLKO izolowany serwer testowy), token i inne znane ustawienia. Ustaw `sprint: false`, `walkDutyCycle: 1`. W katalogu `bot` wpisz `npm install`, `npm start`.
5. W `bridge/config.json` zachowaj swój token, `pluginHttp` i `tikfinityWebSocket`, dopisz pięć nowych wpisów do `giftNames` z przykładowej konfiguracji. W katalogu `bridge` wpisz `npm install`, `npm start`. Włącz TikFinity Desktop i LIVE.
6. W konsoli serwera: `rodeolive debug`, `rodeolive start`, `rodeolive test follow Test123`, `rodeolive test likes500 Test123`, `rodeolive test fingerheart Test123`, `rodeolive gifts`. Komendy test mogą spawnować moby jedynie w aktywnym LIVE.
7. W grze na swoim koncie Premium: `/rodeolive camera`. W OBS ustaw przechwytywanie obrazu z Minecrafta. `F1` ukrywa HUD; `/rodeolive uncamera` wraca do poprzedniego trybu.

## Uwaga na bezpieczeństwo i wydajność

- `bridge.bind: 127.0.0.1`, port 18765 i zgodny losowy token w trzech konfiguracjach. Nie udostępniaj portu 18765 w routerze.
- `pluginHttp: http://127.0.0.1:18765/gift`; `pluginStatusUrl: http://127.0.0.1:18765/status`; port Minecraft 25565. Żadnych zamian 25565 na 18765.
- Max 30 gift-mobów, lifetime 90 sekund i limit 10 zdarzeń/s w przykładowym configu. Nacisk na stabilny TPS.
- `PartialReadError` z protodef może dalej występować: poprawki protokołu nie są częścią tej wersji.
- Pole `coinsLabel` to wyłącznie ręczna etykieta — nie zmienia cen prezentów.

## Plansza giftów do OBS (gotowa w ZIP)

W folderze `overlay` znajduje się `gifts.html`. W OBS dodaj źródło **Przeglądarka** → **Plik lokalny** i wskaż ten plik, ustaw rozmiar **1080 × 1920** (LIVE pionowy). Plansza jest przezroczysta, umieszczona w prawym górnym obszarze ekranu. Edytuj w HTML nazwy, opisy i `? 🪙` na **faktyczną cenę monety** odczytaną z TikToka. Plansza jest statyczna (nie mierzy giftów i nie steruje mobami) — mechanikę mobów ustawia się oddzielnie w `mobs.events` oraz `bridge/config.json`. W ofercie TikToka nie ustalasz własnych cen giftów, przypisujesz do nich własne efekty. Zachowaj zgodne nazwy giftów w configu bridge, ponieważ nazwy bywają zależne od języka i dostępności.
