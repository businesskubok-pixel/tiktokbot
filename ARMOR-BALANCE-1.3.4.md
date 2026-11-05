# RodeoTikTokLive 1.3.4 - oslabienie zbroi bota

Zmiana dotyczy tylko enchantu Protection na netherytowej zbroi bota.
Wczesniej Protection IV na kazdej czesci, teraz domyslnie Protection II.
Miecz, liczba serc, hunger, regeneracja, moby, TikFinity Bridge i kamera bez zmian.

## Instalacja
1. Wgraj caly kod do repozytorium GitHub (nadpisujac stary kod), Commit i Push.
2. Na GitHub Actions pobierz skompilowany JAR RodeoTikTokLive 1.3.4.
3. Zatrzymaj serwer Paper i podmien dotychczasowy JAR pluginu.
4. W istniejacym `plugins/RodeoTikTokLive/config.yml` pod `equipment-enabled: true` mozesz dodac:

```yaml
  armor-protection-level: 2
```

Nie podmieniaj swojego istniejacego config.yml domyslnym plikiem z ZIP-a!
To zachowa m.in. Lion 25 Zombie, token Bridge i ustawienia zdrowia.
Bez dodawania nowej opcji plugin i tak domyslnie wybierze poziom II.
Po restarcie serwera wyjdz i ponownie dolacz RodeoBotem, aby ponownie zalozyl zbroje.

Zakres: 0-4; 0 brak Protection, 1 Protection I, 2 Protection II, 3 Protection III, 4 Protection IV.
