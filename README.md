# Fantasy Football App

A Spring Boot web application for browsing 2024 NFL season player stats, team rosters, and fantasy football analytics. Future plans include a machine learning algorithm for predicting top fantasy point scorers, draft recommendations, and dynamic player/team ratings.

---

## Prerequisites

Make sure the following are installed on your machine before getting started:

- [Java 17](https://adoptium.net/) (Eclipse Temurin recommended)
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
- [VS Code](https://code.visualstudio.com/) with the [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack)
- [Git](https://git-scm.com/)

---

## Getting Started

### 1. Clone the repository

```bash
git clone <repo-url>
cd FantasyFootball
```

### 2. Create your `.env` file

The database credentials are not committed to git. You must create a `.env` file in the project root (next to `docker-compose.yml`):

```
POSTGRES_USER=your_username
POSTGRES_PASSWORD=your_password
POSTGRES_DB=fantasydb
```

Replace `your_username` and `your_password` with whatever you want your local database credentials to be. These values must match what you put in `application-local.yml` (see step 4).

> `.env` is gitignored and will never be committed.

### 3. Start the database

Make sure Docker Desktop is running, then start the PostgreSQL container:

```bash
docker compose up -d
```

Verify it is running:

```bash
docker ps
```

You should see a container named `fantasyfootball-db-1` with port `5433` mapped.

To stop the container:

```bash
docker compose down
```

### 4. Create your local Spring profile

Create the file `src/main/resources/application-local.yml` in the project (this file is gitignored and will not be committed):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/fantasydb
    username: your_username
    password: your_password
```

Use the same `your_username` and `your_password` values you put in `.env`.

### 5. Create your VS Code launch configuration

Create the file `.vscode/launch.json` in the project root (this file is gitignored):

```json
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "java",
            "name": "FantasyFootballApplication",
            "request": "launch",
            "mainClass": "com.firstember.fantasyfootball.FantasyFootballApplication",
            "projectName": "FantasyFootball",
            "vmArgs": "-Dspring.profiles.active=local"
        }
    ]
}
```

This tells Spring Boot to load `application-local.yml` at runtime with your machine-specific credentials.

### 6. Build the project

```bash
./mvnw compile
```

On Windows:

```powershell
.\mvnw compile
```

### 7. Run the application

Open the Run & Debug panel in VS Code (`Ctrl+Shift+D`) and click the green play button next to **FantasyFootballApplication**.

The app will start and automatically create all database tables and load the 2024 season data on first run.

Once started, open your browser and go to:

```
http://localhost:8080
```

---

## Files you must create locally (not in git)

| File | Purpose |
|------|---------|
| `.env` | Docker database credentials |
| `src/main/resources/application-local.yml` | Spring Boot local DB connection config |
| `.vscode/launch.json` | VS Code run configuration |

All three are gitignored and must be created manually on each machine.

---

## Project Structure

```
FantasyFootball/
├── src/
│   ├── main/
│   │   ├── java/com/firstember/fantasyfootball/
│   │   │   ├── domain/        # JPA entities (Player, Team, PlayerStat)
│   │   │   ├── repo/          # Spring Data repositories
│   │   │   ├── web/           # MVC controllers
│   │   │   └── config/        # Security and data loading config
│   │   └── resources/
│   │       ├── templates/     # Thymeleaf HTML templates
│   │       ├── static/        # CSS and team logo images
│   │       ├── data/          # CSV files loaded on startup
│   │       └── application.yml
├── docker-compose.yml
├── pom.xml
└── .env                       # YOU CREATE THIS (gitignored)
```

---

## Tech Stack

- **Backend:** Spring Boot 3.5, Java 17
- **Frontend:** Thymeleaf, HTML5, CSS3
- **Database:** PostgreSQL 16 (via Docker)
- **ORM:** Spring Data JPA / Hibernate
- **Build:** Maven
- **Container:** Docker Compose

---

## Troubleshooting

**"Connection refused" on startup**
- Make sure Docker Desktop is running and the container is up (`docker ps`)
- Confirm the port in `application-local.yml` matches the container's mapped port (default: `5433`)

**"Password authentication failed"**
- Make sure the `username` and `password` in `application-local.yml` match exactly what is in your `.env` file

**"Database does not exist"**
- The `docker compose up -d` command should create the database automatically using the `POSTGRES_DB` value in `.env`
- If it doesn't, run: `docker compose down` then `docker compose up -d` to recreate the container

**Port 5433 already in use**
- You may have another PostgreSQL container running. Check with `docker ps` and stop any conflicting containers.

**App starts but no data shows**
- The `DataLoader` runs automatically on first startup and populates the database from the CSV files in `src/main/resources/data/`
- Check the console output for any data loading errors
