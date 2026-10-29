# Minimap (Paper 1.21+)

Minimapa jako przedmiot (mapa w lewej ręce): teren z góry (północ u góry), twoja pozycja,
kierunek patrzenia, koordynaty. Pod ziemią / w Netherze przełącza się w tryb jaskini.

## Budowanie
Wymagania: JDK 21 + Maven.

    mvn package

Gotowy plik: `target/Minimap.jar` -> wrzuć do `plugins/` serwera Paper i zrestartuj.

Bez instalowania czegokolwiek: wrzuć folder na GitHuba - workflow `.github/workflows/build.yml`
zbuduje jar (zakładka Actions -> artefakt "Minimap").

## Komendy
- `/minimap` (aliasy `/m`, `/mm`) - włącz/wyłącz minimapę
- `/minimap zoom [1|2|4|8]` - zoom (bez argumentu przełącza po kolei)
- `/minimap reload` - przeładuj config (`minimap.admin`)

Klawisz M nie jest możliwy w pluginie serwerowym - klawisze obsługuje tylko mod kliencki.
