[CmdletBinding()]
param([string]$Nom='proutlost-s2-r56',[string]$Adresse='0.0.0.0',[int]$Port=25579)
$ErrorActionPreference='Stop'
$pack=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $pack
& docker info --format '{{.OSType}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Demarrez Docker Desktop en mode conteneurs Linux.' }
$containers=@(& docker container ls -a --format '{{.Names}}')
if ($containers -notcontains $Nom) {
  & (Join-Path $PSScriptRoot 'Installer-Monde-R56.ps1')
  if (!(Test-Path 'world/level.dat') -or !(Test-Path 'SEASON_INSTALLATION.json')) { throw 'Extraire le pack serveur complet avec son monde Contra.' }
  $volume="$Nom-data"
  $volumes=@(& docker volume ls --format '{{.Name}}')
  if ($volumes -contains $volume) { throw "Le volume $volume existe deja : conserver ses donnees et retrouver son conteneur avant installation." }
  & docker volume create --label proutlost.candidate=R56 $volume | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Creation du volume impossible.' }
  & docker create -i --name $Nom --label proutlost.candidate=R56 --stop-timeout 120 --memory 6g --workdir /server --mount "type=volume,source=$volume,target=/server" --publish "${Adresse}:${Port}:25579/tcp" --publish "${Adresse}:24454:24454/udp" eclipse-temurin:21-jre sh ./run.sh | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Creation du conteneur impossible. Le volume conserve ses donnees.' }
  foreach ($entry in @('libraries','mods','config','defaultconfigs','world','run.sh','user_jvm_args.txt','eula.txt','server.properties','SEASON_INSTALLATION.json','DIRECTOR_MANIFEST_SHA256.txt')) {
    $item=Join-Path $pack $entry
    if (Test-Path -LiteralPath $item) {
      & docker cp $item "${Nom}:/server/"
      if ($LASTEXITCODE -ne 0) { throw 'Copie interrompue : conserver le volume et terminer la copie avant lancement.' }
    }
  }
}
Write-Host "Serveur $Nom, port $Port. Saisir stop pour sauvegarder et fermer normalement."
& docker start -ai $Nom
exit $LASTEXITCODE
