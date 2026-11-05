# RodeoTikTokLive — poprawka HTTP 400 dla fingerheart5 i hat100

Naprawiono walidację nazw eventów w endpoint `/gift`:
`[a-zA-Z_]{1,32}` → `[a-zA-Z0-9_]{1,32}`.

Teraz `fingerheart5` i `hat100` (i inne nazwy z cyframi) są poprawne.
Nie zmieniono logiki mobów, limitów, kolejki ani działania bota.
W domyślnym config.yml uaktualniono opis Finger Heart na 1 prezent = 1 Creeper.

## Instalacja
1. Wgraj zawartość repozytorium na GitHub i uruchom Actions → Build RodeoTikTokLive.
2. Pobierz artifact, rozpakuj i umieść nowy JAR w `plugins`, usuwając poprzednią wersję JAR pluginu.
3. Pozostaw obecne `plugins/RodeoTikTokLive/config.yml` (w nim masz Lion=25).
4. Uruchom ponownie Paper i Bridge.
5. Test: `node test-local.js fingerheart5 TestSpam` → HTTP 200/202, analogicznie `hat100`.

Uwaga: wykonany jest test logiczny regexu, nie pełny test uruchomieniowy na Paper/TikFinity.
