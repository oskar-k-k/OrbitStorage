# OrbitStorage einrichten

OrbitStorage ist eine API für Dateien mehrerer Anwendungen. Es speichert Dateien in einem vorhandenen S3-Bucket oder lokal und verwaltet Projekte, Zugriffsrechte und Speicherlimits in PostgreSQL.

Der Ablauf ist: **Dienst starten → Storage hinzufügen → Projekt per API registrieren → Dateien übertragen.**

## 1. Voraussetzungen

Installiere Git, Python 3.10 oder neuer sowie Docker mit Compose. Docker muss laufen; unter Windows verwendet Docker Desktop Linux-Container. Das veröffentlichte Image unterstützt Linux AMD64. Java wird auf dem Host nicht benötigt.

Öffne ein Terminal auf dem Rechner, auf dem OrbitStorage laufen soll:

```sh
git clone https://github.com/oskar-k-k/OrbitStorage.git
cd OrbitStorage
```

## 2. Dienst starten

Windows (PowerShell):

```powershell
.\orbit.ps1 init
```

Linux/macOS:

```sh
sh ./orbit init
```

Das Kommando lädt das fertige Image, erzeugt automatisch die internen Zugangsdaten und startet OrbitStorage und PostgreSQL. Es stellt keine Fragen und legt weder Storage noch Projekte an. Beim ersten Start braucht das Herunterladen etwas Zeit.

Die API läuft anschließend unter `http://127.0.0.1:18080`. Prüfen:

```sh
curl http://127.0.0.1:18080/actuator/health
```

Erwartet: `{"status":"UP"}`. Unter Windows kannst du stattdessen `Invoke-RestMethod http://127.0.0.1:18080/actuator/health` verwenden.

Die erzeugte `.env` enthält das interne Datenbankpasswort, den OrbitStorage-Admin-Key und den Verschlüsselungsschlüssel. Diese Werte sind **keine Bucket-Zugangsdaten**. Du musst sie nicht selbst erfinden. Bewahre die Datei sicher auf; sie wird nicht zu Git hinzugefügt. Der Verschlüsselungsschlüssel muss dauerhaft erhalten bleiben.

Erneutes `init` verwendet die vorhandenen Daten. `init --rebuild` ist nur für Entwickler gedacht, die den lokalen Quellcode bauen möchten. Ein anschließendes normales `init` verwendet wieder das veröffentlichte Image.

## 3. Storage hinzufügen

```powershell
.\orbit.ps1 storage add
```

Unter Linux/macOS: `sh ./orbit storage add`.

Für einen vorhandenen Hetzner-Bucket am Standort Nürnberg gibst du beispielsweise ein:

| Abfrage | Eingabe |
|---|---|
| Storage | `s3` |
| Storage connection name | Ein frei gewählter Name, z. B. `Hetzner` |
| S3 endpoint URL | `https://nbg1.your-objectstorage.com` |
| S3 region | `nbg1` |
| Existing bucket name | Der tatsächliche Bucketname, z. B. `orbitstorage` |
| S3 access key | Access Key aus den S3-Zugangsdaten des Anbieters |
| S3 secret key | Zugehöriger Secret Key |

Übernimm Endpoint und Region deines eigenen Bucket-Standorts. Der Endpoint enthält keinen Bucketnamen und keinen Dateipfad. Die beiden Schlüssel werden unsichtbar eingegeben. Den Bucket legst du vorher beim Anbieter an. OrbitStorage fragt keinen Pfad ab und legt hier kein Projekt an.

Für lokale Dateien wähle `local` und gib einen Namen ein. Die Dateien liegen dann im Docker-Volume `objects`.

Registrierte Storages anzeigen:

```powershell
.\orbit.ps1 storage list
.\orbit.ps1 storage list --json
```

Unter Linux/macOS jeweils `sh ./orbit` verwenden. Die Liste zeigt nur die in OrbitStorage eingerichteten Verbindungen. Mit erneutem `storage add` kannst du weitere hinzufügen. Eine erfolgreiche Registrierung bestätigt noch keinen erfolgreichen Dateitransfer; Schritt 5 prüft das.

## 4. Ein Projekt registrieren

Ein Projekt entspricht einer Anwendung, z. B. Lazy. Mehrere Projekte dürfen denselben Storage und Bucket nutzen. Im Bucket entsteht bei Uploads diese Struktur:

```text
orbitstorage/
  <Projekt-ID von Lazy>/
    <Datei-ID>
  <Projekt-ID einer anderen Anwendung>/
    <Datei-ID>
```

Die Ordner verwenden automatisch erzeugte IDs. Namen und Originaldateinamen stehen in der Datenbank.

Die Registrierung übernimmt deine Anwendung einmalig über einen vertrauenswürdigen Backend- oder Einrichtungsprozess. Dieser braucht den Admin-Key. Er gehört niemals in Browsercode. Folgendes vollständige PowerShell-Beispiel führt die Registrierung manuell vor. Führe es im OrbitStorage-Verzeichnis aus:

```powershell
$base = 'http://127.0.0.1:18080'
$adminKey = ((Get-Content .env | Where-Object { $_ -match '^ORBIT_ADMIN_KEY=' }) -split '=', 2)[1]
$adminHeaders = @{ Authorization = "Bearer $adminKey" }
$storages = @(Invoke-RestMethod "$base/api/v1/admin/providers" -Headers $adminHeaders)
$storages | Select-Object id, name, kind, bucket
```

Kopiere die gewünschte Storage-ID aus der Liste in die nächste Zeile:

```powershell
$storageId = Read-Host 'Storage-ID aus der Liste'
$projectBody = @{
    name = 'Meine Anwendung'
    providerId = $storageId
    quotaBytes = 1073741824
    allowedTypes = @('image/png', 'image/jpeg', 'text/plain')
} | ConvertTo-Json
$project = Invoke-RestMethod "$base/api/v1/admin/projects" -Method Post `
    -Headers $adminHeaders -ContentType 'application/json' -Body $projectBody
$keyBody = @{ permissions = @('files:read', 'files:write', 'files:delete', 'usage:read') } | ConvertTo-Json
$issued = Invoke-RestMethod "$base/api/v1/admin/projects/$($project.id)/keys" -Method Post `
    -Headers $adminHeaders -ContentType 'application/json' -Body $keyBody
$projectKey = $issued.key
```

Das Beispiel erlaubt 1 GiB Projektspeicher. Weitere Limits beschreibt die README. `providerId` ist der technische API-Feldname für die Storage-ID.

Der Projekt-Key wird nur einmal zurückgegeben. Speichere `$projectKey` im Secret-Store bzw. in der privaten Environment-Konfiguration deiner Anwendung zusammen mit der OrbitStorage-URL. Für spätere Datei-Aufrufe reicht dieser Key; die Projekt-ID muss dabei nicht mitgesendet werden. Bewahre die Projekt-ID für administrative Änderungen auf. Wiederhole die Projektregistrierung nicht bei jedem Anwendungsstart.

Ohne Storage liefert die Liste `[]`; ein ansonsten gültiger Projekt-Erstellungsaufruf liefert HTTP 409 mit `No storage found…`. Die Registrierung ist nicht anonym.

## 5. Echte Datenübertragung testen

Im selben PowerShell-Fenster nach Schritt 4:

```powershell
$headers = @{ Authorization = "Bearer $projectKey"; 'X-Filename' = 'hello.txt' }
$bytes = [System.Text.Encoding]::UTF8.GetBytes('Hallo OrbitStorage!')
$file = Invoke-RestMethod "$base/api/v1/files" -Method Post `
    -Headers $headers -ContentType 'text/plain' -Body $bytes
$download = Join-Path ([System.IO.Path]::GetTempPath()) ('orbit-' + [guid]::NewGuid() + '.txt')
Invoke-WebRequest "$base/api/v1/files/$($file.id)" -Headers $headers -OutFile $download
if ([System.IO.File]::ReadAllText($download) -ne 'Hallo OrbitStorage!') {
    throw 'Download stimmt nicht mit dem Upload überein.'
}
Invoke-RestMethod "$base/api/v1/usage" -Headers $headers
Invoke-RestMethod "$base/api/v1/files/$($file.id)" -Method Delete -Headers $headers
Remove-Item -LiteralPath $download
```

Damit werden Upload, Download, Inhalt, Speicheranzeige und Löschen geprüft. Das Projekt und sein Key bleiben für deine Anwendung bestehen. POSIX-`curl`-Beispiele stehen in der [README](../README.md#first-project-through-the-api).

Dateien werden als rohe Bytes übertragen, nicht als Multipart-Formular. Sende `Content-Length`, den MIME-Typ und `X-Filename`. Ein Download braucht den Projekt-Key im Authorization-Header; öffentliche Links oder signierte URLs sind aktuell nicht enthalten.

## 6. Auf einem Server betreiben

Führe dieselben Schritte auf deinem Server aus. Standardmäßig ist die API nur dort über `127.0.0.1:18080` erreichbar. Für Anwendungen auf anderen Rechnern richte einen HTTPS-Reverse-Proxy und eine Domain ein. `init` übernimmt weder DNS noch Proxy-Konfiguration.

Beispiel für einen bereits auf dem Host installierten Caddy (Domain per DNS auf den Server richten, Ports 80/443 für Caddy erreichbar machen):

```caddyfile
storage.example.com {
    reverse_proxy 127.0.0.1:18080
}
```

Verwende in deiner Anwendung dann `https://storage.example.com` als Basis-URL. Läuft der Proxy selbst in Docker, muss er über ein gemeinsames Docker-Netzwerk auf `orbit-storage:8080` zugreifen; `127.0.0.1` im Proxy-Container verweist auf den Proxy selbst. PostgreSQL braucht keinen öffentlichen Port. Passe Request-Limits und Timeouts des Proxys an deine Uploadgrößen an.

## 7. Backups und Updates

Für eine eingerichtete automatische Produktionseinrichtung laufen Updates über GitHub Actions: Ein neuer Release-Tag (z. B. `v0.1.1`) startet Tests, Image-Veröffentlichung und anschließend das Server-Deployment. Ein normaler Push auf `main` aktualisiert den Server nicht. Dabei wird vor dem Neustart die Datenbank gesichert; die API ist kurz unterbrochen. Einrichtung, Release-Befehle und Fehlerbehandlung stehen in der [Deployment-Dokumentation](deployment.md). Die folgenden manuellen Schritte gelten für Installationen ohne automatisches Deployment.

Sichere zusammen:

- Die private `.env`, besonders `ORBIT_ENCRYPTION_KEY`, und `.orbit-setup/`.
- PostgreSQL: Projekte, Keys, Dateimetadaten und verschlüsselte Storage-Zugangsdaten.
- Die eigentlichen Dateien: S3-Bucket oder das lokale `objects`-Volume.

Für einen konsistenten Stand stoppe während einer abgestimmten Sicherung die Anwendung (`docker compose stop orbit-storage`), lasse PostgreSQL laufen und verhindere Änderungen an den Objekten von außen. Einen Datenbankdump kannst du ohne binäre Shell-Umleitung erzeugen:

```sh
docker compose exec -T postgres pg_dump -U orbitstorage -d orbitstorage -Fc -f /tmp/orbitstorage.dump
docker compose cp postgres:/tmp/orbitstorage.dump ./orbitstorage.dump
docker compose exec -T postgres rm /tmp/orbitstorage.dump
```

Sichere anschließend die Objekte und privaten Konfigurationsdateien zum gleichen Stand und starte mit `docker compose start orbit-storage` wieder. Verschiebe den Dump in deinen gesicherten Backup-Speicher außerhalb des Repositories. Prüfe Wiederherstellungen regelmäßig in einer separaten Installation. Ein Datenbankdump enthält keine Dateiinhalte.

Vor einem Update Backup und Migrationshinweise lesen, dann:

```sh
git pull --ff-only
```

Danach unter Windows `.\orbit.ps1 init`, unter Linux `sh ./orbit init`. Die Compose-Datei legt die Release-Version fest. Wenn du selbst `ORBIT_IMAGE` in `.env` gesetzt hast, passe diese Version bewusst an. Datenbankmigrationen laufen beim Start; ein Downgrade kann die Wiederherstellung eines passenden Backups erfordern.

`docker compose down` erhält Datenvolumes. **`docker compose down -v` löscht sie.** Lösche oder regeneriere die `.env` bei Updates nicht.

## Häufige Probleme

| Problem | Nächster Schritt |
|---|---|
| Docker ist nicht erreichbar | Docker starten und `init` erneut ausführen |
| Image-Pull meldet denied | Prüfen, ob Release veröffentlicht und GHCR-Paket öffentlich ist; Maintainer stellen die Paketsichtbarkeit einmalig auf Public |
| HTTP 401 | Passenden Admin- oder Projekt-Key kontrollieren |
| HTTP 403 | Berechtigungen des Projekt-Keys prüfen |
| HTTP 409 bei Projekterstellung ohne Storage | Erst `storage add` ausführen |
| HTTP 503 beim Upload | Bucketname, Endpoint, Region und S3-Zugriffsrechte prüfen |
| `.env` fehlt, Volumes existieren | Ursprüngliche `.env` aus Backup wiederherstellen |

Weitere API-Details und Grenzen stehen in der [README](../README.md).
