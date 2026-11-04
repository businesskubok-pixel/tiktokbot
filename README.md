# RodeoTikTokLive 1.2.8 — Corgi → Ravager; Hat (Czapka) → Evoker

## Nowe ustawienia
- Corgi → 1 Ravager (`corgi`), zamiast 2 Creeperów.
- Prezent „Czapka”, określony przez użytkownika jako 100 monet → 1 Evoker (`hat100`).
- Bridge mapuje na `hat100` nazwy `Hat`, `Cap`, `Czapka`. **Sprawdź dokładną nazwę `giftName` z TikFinity**, jeśli event nie zadziała, i dopisz ją do istniejącego `bridge/config.json`.
- Reszta bez zmian: Galaxy → Warden + 10 Zombie; Rose → 1 Zombie; 5 Finger Heart → 1 Creeper; Follow → Creeper; 500 tapnięć → Zombie.
- Limity: 30 aktywnych, do 150 oczekujących. Ochrona terenu pozostaje.

## Instalacja bez zmiany działającego tokenu, portów i plików bota
Wersja 1.2.7 pluginu już obsługuje konfigurację `mobs.events` i moby `RAVAGER` oraz `EVOKER`. **Nie trzeba ponownie kompilować JAR-a ani aktualizować `bot/index.js` lub `bridge/index.js`**, jeśli działa Ci 1.2.7. Wystarczy:

1. Zrób kopie `plugins/RodeoTikTokLive/config.yml` oraz `bridge/config.json`.
2. W obecnym `config.yml` wewnątrz `mobs.events` zastąp `corgi` i dopisz `hat100`:

```yaml
    corgi: {type: RAVAGER, count: 1, description: "Corgi -> 1 Ravager"}
    hat100: {type: EVOKER, count: 1, description: "Czapka (100 monet) -> 1 Evoker"}
```

3. W obecnym `bridge/config.json` wewnątrz `giftNames` zmień Corgi i dodaj Hat/Cap/Czapka, według `bridge/config.example.json`. Zachowaj `token`, `pluginHttp` i pozostałe wpisy. Nie zastępuj swojego `config.json` całym plikiem przykładowym.
4. Zrestartuj serwer Paper i bridge; bota zostaw z dotychczasową konfiguracją.
5. Test: `/rodeolive start`, `/rodeolive test corgi Test123`, `/rodeolive test hat100 Test123`, `/rodeolive stats`.

**Uwaga:** TikFinity może używać innej nazwy prezentu niż `Hat`, `Cap`, `Czapka`; w takim wypadku w logu bridge pojawi się `Nieznany prezent: ...` — dopisz tę dokładną nazwę do `giftNames`. Ceny monet TikToka nie zostały zweryfikowane: „100 monet” pochodzi z ustalenia użytkownika. Kod wersji 1.2.8 nie był testowany na działającym Paper.
