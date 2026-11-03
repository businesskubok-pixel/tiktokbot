# RodeoTikTokLive 1.0 — prototyp

Paper 1.20.6, Java 21+ (Java 25 na serwerze też odpowiednia), Node.js 22.

## Co robi
- Bot Mineflayer idzie wzdłuż wybranej osi, próbuje omijać przeszkody, po śmierci odradza się automatycznie.
- Plugin daje RodeoBot 30 serc, połowę normalnych obrażeń, regenerację i 8 sekund ochrony po odrodzeniu.
- Plugin może stopniowo usuwać ścieżkę terenu za botem (trwale! wyłącznie osobny świat testowy).
- Gift `rose`, `heart` i `galaxy` tworzą moby z nickami. Obsługa komendy testowej.
- Bridge odbiera gift events z TikFinity Desktop i przekazuje je do lokalnego HTTP w pluginie.

## Instalacja
1. Utwórz **osobny** serwer Paper 1.20.6 z osobnym światem `tiktoklive`. Nie uruchamiaj tego na produkcyjnym RODEO SMP. Jeśli używasz domyślnego świata `world`, zmień `world:` w config.yml.
2. W folderze `paper-plugin` wykonaj `gradle build` na JDK 21 lub nowszym (Gradle 8.8+). Gotowy JAR będzie w `build/libs/`. Wgraj JAR do `plugins` i uruchom serwer.
3. Zmień `bridge.token` w pluginowym `plugins/RodeoTikTokLive/config.yml` na długi losowy sekret (minimum 20 znaków). Restart albo `/rodeolive reload`.
4. W folderze `bot` skopiuj `config.example.json` jako `config.json`, skonfiguruj host/port/auth, następnie `npm install` i `npm start`. `auth: offline` działa WYŁĄCZNIE na prywatnym, odizolowanym serwerze do testów, który nie przyjmuje obcych graczy. Dla serwera online użyj własnego konta Microsoft z `auth: microsoft`. Nie omijaj uwierzytelnienia serwerów publicznych.
5. Upewnij się, że bot pojawia się w świecie `tiktoklive`. Użyj `/rodeolive start`, potem `/rodeolive test rose Test123`.
6. W folderze `bridge` skopiuj `config.example.json` do `config.json`, ustaw TEN SAM sekret, `npm install`, `npm start`. TikFinity Desktop uruchom na tym samym komputerze co bridge.
7. Do OBS dodaj okno Minecraft obserwatora. Mineflayer to bot bez graficznego klienta Minecraft.

## Hostowanie w dwóch miejscach
Plugin ogranicza serwer HTTP do `127.0.0.1`, więc bridge musi działać na tym samym serwerze (albo korzystać z autoryzowanego tunelu). Nigdy nie wystawiaj portu 18765 publicznie. Jeśli hosting nie pozwala na Node/loopback, najpierw testuj na lokalnym serwerze, a potem doprojektuj połączenie wychodzące.

## Ostrzeżenia i ograniczenia prototypu
- Usuwanie terenu jest **nieodwracalne**; kopia zapasowa jest obowiązkowa. To prototyp, nie testowany obciążeniowo na świecie produkcyjnym. `terrain.enabled: false` wyłącza usuwanie.
- Podążanie bota jest przybliżone, nie idealnie proste. Nie potrafi pokonać każdej przeszkody. Może się zatrzymać, jeśli zabraknie bezpiecznej trasy.
- Dane giftów i ich pola mogą zależeć od wersji TikFinity. Nazwy prezentów ustawisz w `bridge/config.json`; przed realnym LIVE wykonaj test.
- W tej wersji odrodzenie bota odbywa się z `bot.respawn()` i `spawn` Mineflayer, a plugin ustawia punkt startu przez `PlayerRespawnEvent`.
- Rozpoczęcie nowej rundy nie regeneruje skasowanego terenu. Osobną regenerację świata można dodać w kolejnej wersji.
- Do prawdziwego automatycznego resetu mapy wymagany jest osobny mechanizm kopiowania świata, nieincluded w 1.0.
- `config.yml` bridge i bot nie powinny trafić publicznie z sekretami.
