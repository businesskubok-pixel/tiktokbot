# RodeoTikTokLive 1.3.2 — płynniejsza kamera zza bota

Aktualizacja **1.3.1 → 1.3.2**, wyłącznie mechanizm kamery. Wszystkie dotychczasowe funkcje bota i TikFinity (gifty, moby, 30 aktywnych, kolejka 150), droga o szerokości 7 bloków oraz prefetch chunków pozostały bez zmian.

## Co zmieniono

- Kamera aktualizuje położenie poprzez ograniczoną prędkość (`Player#setVelocity`) na każdym ticku, zamiast teleportować obserwatora co 2 ticki.
- Kamera łagodnie kieruje wzrok na RodeoBota (`Player#setRotation`).
- Teleport awaryjny jest wykonywany tylko po przekroczeniu maksymalnego błędu odległości i z minimalnym odstępem między korektami.
- Rzeczywista pozycja gracza-obserwatora nadal przemieszcza się, więc serwer ma szansę wysyłać chunki względem właściwego gracza.
- `camera.smooth-follow: false` pozwala tymczasowo wrócić do wcześniejszego sposobu kamery w celu diagnostyki.

**Ograniczenie:** sterowanie prędkością w trybie spectator zależy również od zachowania klienta. Ta poprawka nie jest jeszcze przetestowana na działającym serwerze i nie gwarantuje całkowicie płynnego obrazu. Teleport awaryjny może być zauważalny; prawdziwie interpolowana kamera klientowa wymaga moda lub dedykowanego klienta.

## Instalacja

1. Zrób kopię zapasową świata LIVE i `plugins/RodeoTikTokLive/config.yml` (plugin modyfikuje teren).
2. Skopiuj źródła `paper-plugin` do swojego repozytorium przez GitHub Desktop, kliknij **Commit to main** i **Push origin**.
3. W GitHub Actions skompiluj JAR. Nie instaluj, jeśli kompilacja zakończy się błędem.
4. Zatrzymaj Paper, przenieś stary JAR poza `plugins` i wgraj nowy; zostaw tylko jeden JAR RodeoTikTokLive.
5. Zachowaj istniejący `plugins/RodeoTikTokLive/config.yml`. Dodaj **wewnątrz istniejącej sekcji `camera:`** poniższe ustawienia; nie dodawaj drugiego `camera:`.

```yaml
camera:
  distance: 5.5
  height: 2.4
  smooth-follow: true
  smoothing: 0.30
  max-speed-per-tick: 0.65
  max-distance-error: 5.0
  correction-interval-ticks: 20
```

6. Uruchom Paper i sprawdź `version RodeoTikTokLive` (1.3.2).
7. Pozostaw dotychczasowy `bot/index.js`, `bot/config.json`, `bridge/index.js` i `bridge/config.json` bez zmian; uruchom je jak wcześniej.
8. W konsoli serwera wpisz `rodeolive start`, a w grze `/rodeolive camera`. Testuj przez kilka minut; wyjście `/rodeolive uncamera`.

## Regulacja

- Gdy kamera zostaje zbyt daleko: `smoothing: 0.40`, `max-speed-per-tick: 0.80`.
- Gdy kamera nerwowo reaguje na ruch: `smoothing: 0.20`.
- Gdy kamera skacze przy korekcie: `max-distance-error: 7.0` (wyłącznie na testowym świecie, bo opóźnia powrót).
- Jeśli poruszanie się przez velocity w spectator nie działa prawidłowo, `smooth-follow: false` przywraca wcześniejszy sposób obserwacji, ale także wcześniejsze szarpanie.

Po zmianach konfiguracji najlepiej wykonać pełny restart Paper. **Nie zmieniaj** portów Minecraft 25565 i API 18765, tokenów ani mapowania giftów. Nie przesyłaj prywatnych plików `config.json` na GitHub.
