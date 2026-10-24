# iMail – Sicherheitskonzept und Bedrohungsmodell

Stand: Version 0.1.0. Die Massnahmen in `core/` sind durch Tests belegt; die Android-Teile (`app/`) sind implementiert, aber noch nicht gebaut oder auf einem Gerät geprüft (siehe `README.md`, „Status“).

## Schutzziele
Vertraulichkeit der Zugangsdaten und Mails auf dem Gerät und im Netz, Integrität von Verbindung und Darstellung, keine Datenabflüsse an Dritte.

## Bedrohungen und Gegenmassnahmen

| Bedrohung | Massnahme | Wo |
|---|---|---|
| Mitlesen/Manipulation im Netz, Downgrade | Nur TLS 1.2/1.3. IMAPS/SMTPS oder STARTTLS mit `starttls.required`. Zertifikat- und Hostnamen-Prüfung (`ssl.checkserveridentity`). Kein Klartext-Port wählbar, kein Trust-All. `network_security_config`: `cleartextTrafficPermitted=false`, nur System-CAs. | `MailSession.kt`, `network_security_config.xml` |
| Passwortdiebstahl auf dem Gerät | AES-256-GCM, Schlüssel im Android Keystore (nicht exportierbar). Kein Klartext in Prefs, Logs oder Backup (`allowBackup=false`, Extraktionsregeln leer). | `CredentialStore.kt` |
| Mail-Inhalte auf dem Datenträger | Es wird bewusst **kein** Mail-Cache geschrieben; Inhalte liegen nur im Arbeitsspeicher. Auf Disk liegen nur Firmenlogos (öffentliche Daten, Dateinamen als Hash). | `MailRepository.kt`, `DiskLogoCache.kt` |
| Bösartiges HTML (Skripte, Formulare, Tracking) | jsoup-Allowlist, kein JavaScript, kein File-/Content-Zugriff, WebView navigiert nie selbst, CSP im Dokument, Remote-Bilder standardmässig blockiert (Opt-in pro Mail), SVG nie geladen. | `HtmlSanitizer.kt`, `DetailPane.kt` |
| Phishing/gefährliche Links | Nur `https`, `mailto`, `tel`. Bestätigungsdialog mit Zielhost vor dem Öffnen. | `LinkPolicy.kt` |
| Gefährliche Anhänge | Dateinamen bereinigt (Path Traversal, Steuerzeichen, Länge). Speichern nur über Storage Access Framework. Warnung bei ausführbaren Typen. Grössenlimits (Empfang 25 MB, Senden 15 MB). Kein automatisches Öffnen. | `FileNameSanitizer.kt`, `ImapClient.kt` |
| SSRF/Datenabfluss über Logo-Abruf | Nur https/443, Host darf keine IP/lokaler Name sein, **alle** DNS-Antworten müssen öffentlich sein, Verbindung direkt zur geprüften IP (kein zweites Lookup → kein DNS-Rebinding), Redirects werden neu geprüft (max. 3), 256-KB-Limit, Timeouts, Magic-Byte-Prüfung, defensives Dekodieren (max. 4096 px). Kein Drittanbieter-Dienst; Freemail-Domains lösen keine Anfrage aus. | `UrlGuard.kt`, `SafeHttpsClient.kt`, `LogoFetcher.kt` |
| Header-Injection (SMTP) | CR/LF/NUL werden entfernt, Adressen mit Steuerzeichen abgelehnt, Aufbau nur über die Mail-Bibliothek. | `HeaderSanitizer.kt`, `SmtpClient.kt` |
| Zugriff bei entsperrtem Gerät | Optionale App-Sperre (BiometricPrompt, Gerätecode-Fallback), `FLAG_SECURE`, Sperre bei Wechsel in den Hintergrund. Ohne eingerichtete Gerätesperre bleibt die App gesperrt (fail closed). | `MainActivity.kt` |
| Inhalte in Benachrichtigungen | Bei aktiver App-Sperre nur „Neue Nachricht“; sonst `VISIBILITY_PRIVATE` mit neutraler öffentlicher Version. | `Notifier.kt` |
| Push-Metadaten bei Dritten | Kein FCM, kein Relay-Server: Push per IMAP IDLE direkt zum eigenen Mailserver. | `ImapIdleWatcher.kt`, `MailPushService.kt` |
| Angriffsfläche | Nur `MainActivity` exportiert; Service und Receiver `exported=false`. Minimale Berechtigungen. Release mit R8/Shrinking. Keine Analytics/Telemetrie. Fehlermeldungen ohne Serverbanner. | `AndroidManifest.xml`, `MailViewModel.kt` |

## Bewusste Einschränkungen
- Der Keystore-Schlüssel verlangt keine Nutzerauthentifizierung, weil der Push-Dienst im Hintergrund lesen muss. Schutz der UI: App-Sperre.
- Ein ständiger IDLE-Dienst kostet Akku; Android kann ihn trotz Foreground-Service beenden. Fallback: WorkManager (~15 Min.). Für Play-Store-Veröffentlichung ist die Foreground-Service-Art `specialUse` zu begründen.
- Kein OAuth2: Anbieter, die nur OAuth erlauben (z. B. Gmail ohne App-Passwort), funktionieren mit App-Passwörtern.
- Kein S/MIME/PGP, keine Zertifikats-Pinning-Konfiguration pro Server.
- Abhängigkeiten sind auf feste Versionen gepinnt; vor Releases auf bekannte CVEs prüfen.
