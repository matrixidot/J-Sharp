# Installs the J# command line (jsharp) for the current user on Windows, with its own Java runtime.
#
#   irm https://matrixidot.github.io/J-Sharp/install.ps1 | iex
#
# Installs into %LOCALAPPDATA%\Programs\jsharp and adds its bin folder to your PATH. Running it
# again updates J#. A specific version: $env:JSHARP_VERSION = "0.2.0-beta" before running it.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # Invoke-WebRequest is slow with a progress bar

$repo = 'matrixidot/J-Sharp'
$headers = @{ 'User-Agent' = 'jsharp-installer' }

$tag = $env:JSHARP_VERSION
if (-not $tag) {
    # The newest release, pre-releases included (J# is in beta), from the releases feed: unlike
    # GitHub's API it has no per-address rate limit, which shared networks and CI runners reach.
    # Invoke-RestMethod returns a feed's entries, newest first.
    $entries = Invoke-RestMethod "https://github.com/$repo/releases.atom" -Headers $headers
    $tag = (@($entries)[0].link.href -split '/')[-1]
    if (-not $tag) { throw "J#: no release found at https://github.com/$repo/releases" }
}
$version = $tag.TrimStart('v')
$asset = "jsharp-$version-win32-x64.zip"
$url = "https://github.com/$repo/releases/download/$tag/$asset"

$home_ = Join-Path $env:LOCALAPPDATA 'Programs\jsharp'
$temp = Join-Path ([IO.Path]::GetTempPath()) ("jsharp-install-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    Write-Host "Downloading J# $version..."
    $zip = Join-Path $temp $asset
    Invoke-WebRequest $url -OutFile $zip -Headers $headers
    Expand-Archive $zip -DestinationPath $temp
    if (Test-Path $home_) {
        try {
            Remove-Item $home_ -Recurse -Force
        } catch {
            throw "J#: cannot replace $home_ (is jsharp running? close it and try again): $_"
        }
    }
    New-Item -ItemType Directory -Path (Split-Path $home_) -Force | Out-Null
    Move-Item (Join-Path $temp "jsharp-$version") $home_
} finally {
    Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
}

$bin = Join-Path $home_ 'bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not $userPath) { $userPath = '' }
if (($userPath -split ';') -notcontains $bin) {
    [Environment]::SetEnvironmentVariable('Path', (($userPath.TrimEnd(';') + ";$bin").TrimStart(';')), 'User')
}
if (($env:Path -split ';') -notcontains $bin) { $env:Path = "$env:Path;$bin" }

Write-Host ""
Write-Host "J# $version is installed in $home_"
& (Join-Path $bin 'jsharp.bat') --version
Write-Host ""
Write-Host "Open a new terminal, then try:"
Write-Host "  jsharp new app my-app     (or: jsharp new --list)"
