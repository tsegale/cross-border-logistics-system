# Development setup (Windows)

Everything runs from Windows; WSL2 is only the engine behind Docker Desktop. Maven doesn't need installing:
every service has a Maven wrapper (`mvnw`, `mvnw.cmd`) that downloads the pinned version.

## Tools

Run in PowerShell as Administrator, then reopen the terminal.

```powershell
wsl --install -d Ubuntu-22.04                         # reboot when asked
winget install --id EclipseAdoptium.Temurin.21.JDK -e
winget install --id Docker.DockerDesktop -e           # enable "Use the WSL 2 based engine"
winget install --id Git.Git -e
winget install --id JetBrains.IntelliJIDEA.Community -e
```

Check:

```powershell
java -version            # 21.x, Temurin
docker compose version   # v2+
git --version
```

## Git

The repository normalises line endings to LF (`.gitattributes`), so keep Git from converting them:

```powershell
git config --global core.autocrlf false
```

## IntelliJ IDEA

Open the repository root. IntelliJ reads the root `pom.xml` and imports all four services as Maven projects.
Set the project SDK to Temurin 21 (File, Project Structure).

## Running

```powershell
copy .env.example .env
docker compose up --build --wait
cd banking-service; .\mvnw.cmd verify
```

A service started from the IDE reaches its database on the published port (banking 5441, logistics 5442),
so `docker compose up banking-db logistics-db` is enough when you want to run the services in IntelliJ.
