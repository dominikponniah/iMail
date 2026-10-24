# iMail

Schlichter nativer Android-Mailclient (Kotlin, Jetpack Compose) im iOS-/iPadOS-Look, IMAP + SMTP, Benachrichtigung bei neuer Mail, Firmenlogo als Avatar. Sicherheitskonzept: [SECURITY.md](SECURITY.md).

## Funktionen
- **Mehrere Konten** (Umschalter in der Postfach-Liste), pro Konto eigener Push-Kanal und eigener Offline-Cache.
- **Anmeldung per Passwort oder OAuth2** (Google, Microsoft; XOAUTH2, Authorization Code + PKCE im Browser, Refresh-Token verschlüsselt gespeichert).
- **Offline-Zugriff:** Ordner, Kopfzeilen, Texte und kleine Anhänge liegen verschlüsselt (AES-256-GCM, Keystore-Schlüssel) auf dem Gerät. Ohne Verbindung zeigt die App den Cache mit Hinweis „Offline“. Die neuesten 30 Mails des Posteingangs werden vorab geladen. Authentifizierungsfehler werden nie durch Cache-Daten überdeckt.
- **Entwürfe:** werden im Entwürfe-Ordner des Servers gespeichert (auf allen Geräten sichtbar), lassen sich dort wieder öffnen, bearbeiten, ersetzen und verschwinden nach dem Senden.
- **Vormerken ohne Verbindung:** Senden, Löschen und Gelesen/Ungelesen werden bei fehlender Verbindung verschlüsselt vorgemerkt (Reihenfolge bleibt erhalten), lokal sofort angezeigt und automatisch gesendet, sobald das Netz zurück ist (App-Start, Netzwechsel, alle ~15 Min.). Ungültige Mails werden sofort abgelehnt, Anmelde-/Zertifikatsfehler nie stillschweigend vorgemerkt; vom Server endgültig abgelehnte Aktionen werden verworfen.
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
./gradlew assembleDebug     # braucht ANDROID_HOME bzw. local.properties (sdk.dir), Android SDK 35 + Build-Tools 35.0.0
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
Entwürfe speichern ohne Verbindung (nur Senden/Löschen/Flags werden vorgemerkt), Labels/Regeln, PGP/S-MIME, Push für andere Ordner als den Posteingang.

## Status (ehrlich)
- **Geprüft:** `./gradlew :core:test` – 48 Tests grün (u. a. vorgemerkte Aktionen: Löschen, Gelesen-Flag und Senden offline → nach Reconnect in Reihenfolge angewendet): Freemail, Logo-Resolver/SSRF, HTML-Sanitizer, Links, Header-Injection, Dateinamen, **verschlüsselter Cache** (Round-Trip, kein Klartext auf der Platte, falscher Schlüssel/manipulierte Datei = Cache-Miss, Trimmen), **Offline-Modus** (Server gestoppt, Ordner/Liste/Suche/Texte weiter lesbar; falsches Passwort wird trotz Cache gemeldet), **Entwürfe** (speichern, ersetzen, aus Entwürfe-Ordner senden/entfernen), **XOAUTH2-Konfiguration**, ID-Token-Auswertung, sowie Integrationstests gegen lokalen IMAPS/SMTPS-Server (Abruf, Senden mit Anhang, IDLE-Push, Ablehnung selbstsignierter Zertifikate).
- **App gebaut (Android SDK 35, AGP 8.7.3):** `./gradlew :app:assembleDebug`, `:app:assembleRelease` (mit R8-Verkleinerung) und `:app:lintDebug` laufen fehlerfrei (Lint: 0 Fehler, 11 Warnungen, v. a. neuere Bibliotheksversionen). Im gemergten Release-Manifest: `allowBackup=false`, `usesCleartextTraffic=false`, Netzwerk-Sicherheitskonfiguration aktiv; exportiert sind nur `MainActivity`, der OAuth-Redirect-Empfänger und per Berechtigung geschützte Bibliotheks-Komponenten (WorkManager, ProfileInstaller).
- **Nicht geprüft:** Start und Verhalten auf einem Gerät oder Emulator (Push im Hintergrund, Biometrie, WebView, Browser-Redirect), der **OAuth-Ablauf** gegen Google/Microsoft sowie XOAUTH2 gegen einen echten Server (auf Android fehlt `javax.security.sasl`; wir nutzen den eingebauten XOAUTH2-Pfad von Angus Mail, der ohne SASL auskommen sollte – bitte mit einem echten Konto testen), Compose-Previews/Screenshot-Tests, der Release-Build mit Signatur (liegt unsigniert vor) und die Unit-Tests des App-Moduls (es gibt keine).
