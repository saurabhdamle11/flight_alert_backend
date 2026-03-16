# Flight Whisperer — Backend

Multi-user flight notification service. Users register with a WhatsApp number and location; the system watches for planes overhead and sends WhatsApp notifications with flight details.

---

## Architecture

```
WhatsApp (Twilio Webhook) → User Service → PostgreSQL
                                               ↓
                        Flight Ingestion Service (scheduled)
                               polls OpenSky REST API
                                       ↓
                          Kafka: flight.state.updates
                                       ↓
                         Subscription Matcher (Kafka consumer)
                          checks user bounding boxes + dedup
                                       ↓
                          Kafka: flight.notifications
                                       ↓
                         Notification Service (Kafka consumer)
                               calls Twilio WhatsApp API
                                       ↓
                          Kafka: notification.sent  (audit)
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language / Framework | Java 17, Spring Boot 4.1 |
| Database | PostgreSQL 15 (Flyway migrations, HikariCP) |
| Deduplication | Redis (TTL-based, 10 min window) |
| Messaging | Apache Kafka |
| Notifications | Twilio WhatsApp API |
| Flight Data | OpenSky Network REST API |
| Build | Maven (wrapper included) |
| Packaging | WAR (Tomcat) |

---

## Prerequisites

- Java 17+
- Maven (or use the included `./mvnw` wrapper)
- Docker (for PostgreSQL and Redis)

---

## Quick Start

### 1. Start PostgreSQL and Redis

```bash
docker run -d --name postgres-dev \
  -e POSTGRES_USER=<DB_USERNAME> \
  -e POSTGRES_PASSWORD=<DB_PASSWORD> \
  -e POSTGRES_DB=flight_whisperer \
  -p 5432:5432 postgres:15

docker run -d --name flight-whisperer-redis \
  -p 6379:6379 redis:7
```

### 2. Configure environment

```bash
cp ../.env.example ../.env
# Fill in your values in .env
```

### 3. Run the app

```bash
set -a && source ../.env && set +a
./mvnw spring-boot:run
```

The service starts on **http://localhost:8080**.

---

## Configuration

All config is driven by environment variables. Copy `.env.example` to `.env` to get started.

| Variable | Description |
|---|---|
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `REDIS_HOST` | Redis host |
| `REDIS_PORT` | Redis port |

---

## Database Schema

Managed by Flyway (`src/main/resources/db/migration/`):

| Table | Purpose |
|---|---|
| `users` | UUID PK, WhatsApp number (E.164), display name, active flag |
| `user_locations` | Per-user bounding boxes (`lat/lon min/max`), supports multiple named locations |
| `notifications` | Audit log of every Twilio delivery |
| `regions` | Merged bounding boxes used for OpenSky API polling |

Deduplication is handled in Redis (`seen:flight:{user_id}:{icao24}`, TTL 600 s) — no `seen_flights` table.

---

## Project Structure

```
src/main/java/com/tracker/
├── controller/         # REST endpoints
├── service/            # Business logic
├── repository/         # JPA repositories
├── entity/             # JPA entities
├── dto/                # Request/response objects
└── exception/          # Global error handling
```

---

## Key Business Rules

- **Bounding box merging**: Overlapping user bounding boxes are merged into a minimal set of OpenSky API calls per poll cycle.
- **Deduplication window**: 10 minutes. Same `(user_id, icao24)` pair within 10 min skips the notification.
- **On-ground filter**: Flights with `on_ground == true` are discarded; `null` (unknown) is kept.
- **Default radius**: When a user shares a location via WhatsApp, a ~25 km bounding box is auto-generated.
- **Adaptive polling**: Poll frequency per region is reduced during overnight hours (2–5 AM local time).
- **OpenSky backoff**: Exponential on 429 — 5 s → 15 s → 30 s.
- **Region reload**: Active regions are reloaded from DB every 5 minutes.

---

## Running Tests

```bash
./mvnw test
```

- Unit tests: bounding box merge, message formatter, state vector parser, heading-to-compass
- Integration tests: Testcontainers (PostgreSQL + Kafka) — real DB, no mocks
- Contract tests: Twilio inbound webhook handler
