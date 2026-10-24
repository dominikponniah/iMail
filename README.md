# iMail

Schlichter nativer Android-Mailclient (Kotlin, Jetpack Compose) im iOS-/iPadOS-Look, IMAP + SMTP, Benachrichtigung bei neuer Mail, Firmenlogo als Avatar. Sicherheitskonzept: [SECURITY.md](SECURITY.md).

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
Mehrere Konten, OAuth2, persistenter Mail-Cache, Entwürfe synchronisieren, Labels/Regeln, PGP/S-MIME.

## Status (ehrlich)
- **Geprüft:** `./gradlew :core:test` – 32 Tests grün: Freemail-Erkennung, Logo-Resolver inkl. SSRF-Blockierung und Grössenlimit, HTML-Sanitizer, Link-Policy, Header-Injection, Dateinamen, sowie Integrationstests gegen einen lokalen IMAPS/SMTPS-Server (Abruf, Senden mit Anhang, IDLE-Benachrichtigung, Ablehnung von selbstsignierten Zertifikaten und falschem Passwort).
- **Nicht geprüft:** `app/` wurde noch nicht kompiliert (in der Entwicklungsumgebung war kein Android SDK verfügbar), ebenso `lintDebug`, Compose-Previews/Screenshot-Tests und das Verhalten auf einem echten Gerät (Push im Hintergrund, Biometrie, WebView). Plugin-/Bibliotheksversionen in `settings.gradle.kts` und `app/build.gradle.kts` sind gesetzt, aber nicht gegen Google Maven aufgelöst. Beim ersten Build sind kleinere Compile-Korrekturen zu erwarten.
