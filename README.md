# iMail

Schlichter nativer Android-Mailclient (Kotlin, Jetpack Compose) im iOS-/iPadOS-Look, IMAP + SMTP, Benachrichtigung bei neuer Mail, Firmenlogo als Avatar. Sicherheitskonzept: [SECURITY.md](SECURITY.md).

## Funktionen
- **Mehrere Konten** (Umschalter in der Postfach-Liste), pro Konto eigener Push-Kanal und eigener Offline-Cache.
- **Anmeldung per Passwort oder OAuth2** (Google, Microsoft; XOAUTH2, Authorization Code + PKCE im Browser, Refresh-Token verschlüsselt gespeichert).
- **Offline-Zugriff:** Ordner, Kopfzeilen, Texte und kleine Anhänge liegen verschlüsselt (AES-256-GCM, Keystore-Schlüssel) auf dem Gerät. Ohne Verbindung zeigt die App den Cache mit Hinweis „Offline“. Die neuesten 30 Mails des Posteingangs werden vorab geladen. Authentifizierungsfehler werden nie durch Cache-Daten überdeckt.
- **Entwürfe:** werden im Entwürfe-Ordner des Servers gespeichert (auf allen Geräten sichtbar), lassen sich dort wieder öffnen, bearbeiten, ersetzen und verschwinden nach dem Senden.
- **Avatare:** Firmenlogo der Absender-Domain, sonst Initialen auf einer pro Absender festen, zufällig wirkenden Farbe.

## OAuth2 einrichten
Anbieter verlangen eine eigene Client-ID (öffentlicher Client, kein Secret). Eintragen in `local.properties` oder als Umgebungsvariablen, **nicht** ins Repo:
```
GOOGLE_CLIENT_ID=1234-abc.apps.googleusercontent.com   # Google Cloud: OAuth-Client "Android", Paketname ch.imail.app
MICROSOFT_CLIENT_ID=00000000-0000-0000-0000-000000000000  # Entra ID: Mobile/Desktop-App, Redirect-URI ch.imail.app:/oauth2redirect
```
Google: Gmail-Scope `https://mail.google.com/` erfordert für Produktivbetrieb eine Verifizierung durch Google. Microsoft: Delegierte Berechtigungen `IMAP.AccessAsUser.All` und `SMTP.Send`; manche Firmen-Tenants blockieren SMTP AUTH. Ohne Client-ID sind die Knöpfe deaktiviert, Passwort-Anmeldung funktioniert weiterhin.

## Aufbau
- `core/` – reine JVM-Bibliothek ohne Android-Abhängigkeit: IMAP/SMTP (Angus Mail), IDLE-Watcher, HTML-/Link-/Dateinamen-Sanitizer, Logo-Abruf mit SSRF-Schutz. Vollständig per Unit- und Integrationstests (GreenMail) geprüft.
- `app/` – Android-App (Compose-UI nach Apple Mail: Postfächer → Nachrichten → Lesen; Tablet 2-/3-spaltig), Keystore-Speicher, Foreground-Service (IDLE), WorkManager-Fallback, App-Sperre.

## Bauen und testen
```
./gradlew :core:test        # läuft ohne Android SDK
./gradlew assembleDebug     # braucht ANDROID_HOME bzw. local.properties (sdk.dir)
./gradlew lintDebug
```
`settings.gradle.kts` bindet `:app` nur ein, wenn ein Android SDK gefunden wird.

## Push ohne FCM
`MailPushService` hält per IMAP IDLE eine TLS-Verbindung zum Posteingang offen (Reconnect mit Backoff, Neustart bei Netzwechsel und nach Neustart des Geräts). `PollWorker` prüft zusätzlich alle ~15 Minuten. Vorteil: kein eigener Server, keine Metadaten bei Google. Nachteil: dauerhaft laufender Dienst (Akku). In den Android-Einstellungen die Akku-Optimierung für iMail abschalten.

## Abhängigkeiten
| Abhängigkeit | Grund |
|---|---|
| `org.eclipse.angus:angus-mail` | IMAP/SMTP/MIME |
| `org.jsoup:jsoup` | HTML-Sanitizing, Icon-Link-Parsing |
| Jetpack Compose, Material3 (Unterbau), Window Size Class | UI, Tablet-Layout |
| `androidx.biometric`, `androidx.fragment` | App-Sperre |
| `androidx.work` | Fallback-Polling |
| Test: JUnit 5, kotlin-test, GreenMail | Tests mit lokalem IMAPS/SMTPS-Server |

## Nicht enthalten (bewusst)
Offline-Aktionen in der Warteschlange (Senden/Löschen/Flags erfordern eine Verbindung), Labels/Regeln, PGP/S-MIME, Push für andere Ordner als den Posteingang.

## Status (ehrlich)
- **Geprüft:** `./gradlew :core:test` – 43 Tests grün: Freemail, Logo-Resolver/SSRF, HTML-Sanitizer, Links, Header-Injection, Dateinamen, **verschlüsselter Cache** (Round-Trip, kein Klartext auf der Platte, falscher Schlüssel/manipulierte Datei = Cache-Miss, Trimmen), **Offline-Modus** (Server gestoppt, Ordner/Liste/Suche/Texte weiter lesbar; falsches Passwort wird trotz Cache gemeldet), **Entwürfe** (speichern, ersetzen, aus Entwürfe-Ordner senden/entfernen), **XOAUTH2-Konfiguration**, ID-Token-Auswertung, sowie Integrationstests gegen lokalen IMAPS/SMTPS-Server (Abruf, Senden mit Anhang, IDLE-Push, Ablehnung selbstsignierter Zertifikate).
- **Nicht geprüft:** `app/` wurde noch nie kompiliert und der **OAuth-Ablauf** nie gegen Google/Microsoft ausgeführt (kein Android SDK, Google-Hosts in der Entwicklungsumgebung gesperrt; XOAUTH2 gegen einen echten Server ebenfalls nicht). Ebenso offen: `lintDebug`, Previews, Verhalten auf einem Gerät (Push im Hintergrund, Biometrie, WebView, Browser-Redirect). Beim ersten Build sind Compile-Korrekturen zu erwarten.
