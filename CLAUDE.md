# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A learning project for **Spring Boot, Spring Cloud, and microservices (MSA)**. It is a **Maven multi-module reactor**, not a set of git submodules — the root `pom.xml` (`packaging=pom`) aggregates every service as a `<module>` and provides the shared parent (Spring Boot 3.5.14, **Java 21**, Spring Cloud 2025.0.2, Lombok + devtools for all modules).

## Build & run

Use the Maven wrapper from the repo root.

```bash
./mvnw clean install              # build all modules
./mvnw -pl user-service install   # build one module (reactor-aware; add -am to also build deps)
./mvnw -pl user-service spring-boot:run   # run one service
./mvnw -pl order-service test                       # all tests in a module
./mvnw -pl order-service test -Dtest=OrderServiceApplicationTests   # single test class
./mvnw -pl order-service test -Dtest=ClassName#methodName           # single test method
```

For local (IDE / `spring-boot:run`) runs, first start the infra containers (`cd docker && docker compose up -d`), then start in this order in separate terminals: **service-discovery → config-service → gateway → (user/catalog/order/first/second)-service**. A service started before Eureka will keep retrying registration; a service started before config-service may boot with missing/empty config (see Config below). RabbitMQ must be **4.2.7**; 4.3.x causes compatibility errors.

### Containers (whole system at once)

Full run guide (profiles, scenarios, mode switching, troubleshooting): `docker/HOW_TO_DOCKER_COMPOSE.md` — keep it in sync when changing `docker/docker-compose.yml`.

```bash
./mvnw clean package -DskipTests                 # Dockerfiles only COPY target/*-0.0.1.jar — rebuild jars first
cd docker
docker compose up -d                                                   # infra only (for local Spring runs)
PROMETHEUS_MODE=container docker compose --profile app up -d --build  # infra + all Spring services
docker compose up -d --build user-service                              # one service (naming it activates its profile)
docker compose --profile app down                                      # without --profile app, stop/down only touch infra
```

**All containers live in the single `docker/docker-compose.yml`** (infra: rabbitmq, kafka, kafka-ui, connect, mariadb, zipkin, zipkin-mysql, prometheus, grafana; Spring services carry `profiles: [app]`). Supporting files sit next to it (`docker/kafka/connect-plugins`, `docker/mariadb/`, `docker/zipkin/initdb.d`, `docker/monitoring/`), bind-mount data goes to gitignored `docker/data/`, and `docker/.env` can pin the mode (`COMPOSE_PROFILES=app`, `PROMETHEUS_MODE=container`, commented out by default). Compose creates the `ecommerce-network` (subnet `172.18.0.0/16`); start order is enforced with `depends_on` + healthchecks. `kafka-practice/` now holds only Kafka study docs. Service yml files keep **local defaults behind env placeholders** (`${RABBITMQ_HOST:127.0.0.1}`, `EUREKA_SERVER_URL`, `CONFIG_SERVER_URI`, `ZIPKIN_ENDPOINT`, `KAFKA_BOOTSTRAP_SERVERS`, `EUREKA_PREFER_IP_ADDRESS`, `NATIVE_REPO_LOCATION`, `ENCRYPT_KEY_STORE_LOCATION`); the compose file sets container names. Keep that pattern when adding a host/URL — never hard-code `localhost`, and inside containers Kafka is `kafka:19092` (INTERNAL listener), not 9092. Prometheus config is split into `docker/monitoring/prometheus/{local,container}/`, chosen by `PROMETHEUS_MODE` (default `local`). The gateway container gets a fixed IP `172.18.0.100` (dynamic IPs come from `ip_range 172.18.1.0/24`) and user-service receives it as `GATEWAY_ALLOWED_IPS`.

`01_reference/test.http` holds runnable example requests (all hitting the gateway on `:8000`) — the canonical way to exercise the full flow.

## Architecture

Diagrams of the whole system (containers, request / config / event / observability flows, IDE vs container mode) are in `ARCHITECTURE.md` — update it when adding a service or a connection.

Everything talks through three infrastructure services; business services never call each other by host/port directly.

- **service-discovery** (`:8761`) — Eureka **server** (`@EnableEurekaServer`). Every other service registers here. Self-registration is disabled on the server itself.
- **config-service** (`:8888`) — Spring Cloud **Config Server** (`@EnableConfigServer`). Central store for all services' settings (notably the shared JWT `token.secret`). Runs in **native profile** (`spring.profiles.active: native`), i.e. it reads plain `.yml` files from a local filesystem folder — **not** git. See Config below.
- **gateway** (`:8000`) — Spring Cloud Gateway on **WebFlux** (reactive). Single public entry point. Routes by path prefix to `lb://SERVICE-NAME` (client-side load balancing via Eureka). Path prefix → service mapping lives entirely in `gateway/src/main/resources/application.yaml` (`/user-service/**`, `/order-service/**`, `/catalog-service/**`, `/first-service/**`, `/second-service/**`). Authenticated routes carry the `AuthorizationHeaderFilter` (JWT check).

Business / demo services all register with Eureka and run on **`server.port: 0`** (random port) — instances are addressed only by their registered name, never a fixed port. Eureka `instance-id` is randomized so multiple instances coexist:

- **user-service** — users + login. JPA/H2, Spring Security (see below).
- **catalog-service** — product catalog. JPA/H2 (`ddl-auto: create-drop`, seeded each boot).
- **order-service** — orders keyed by `userId`. JPA/H2.
- **first-service** / **second-service** — minimal demo services (register as `MY-FIRST-SERVICE` / `MY-SECOND-SERVICE`) used to exercise gateway routing and custom filters; not part of the e-commerce domain.

### Cross-cutting conventions

- **Layering** (in each business service): `controller` → `service` (interface + `*Impl`) → `jpa` (Entity + Spring Data `Repository`). Transport objects are split into **`vo`** (request/response value objects, e.g. `RequestUser`/`ResponseUser`) and **`dto`** (internal). Controllers convert between layers with **ModelMapper** using `MatchingStrategies.STRICT`. Follow this split when adding endpoints.
- **Gateway filters** (`gateway/.../filter/`) extend `AbstractGatewayFilterFactory<Config>` and are wired by class name in `application.yaml` (`CustomFilter`, `LoggingFilter`, `GlobalFilter`, `AuthorizationHeaderFilter`). `GlobalFilter` is applied to every route via `default-filters`; `AuthorizationHeaderFilter` validates the JWT on protected user-service routes.
- **Persistence**: all business services use **in-memory H2** (`jdbc:h2:mem:testdb`), so data is wiped on restart. H2 console is at `/h2-console` on each service.
- **Service names matter**: the `spring.application.name` value is the Eureka registration name and must match the `lb://` target in the gateway routes (note `first/second-service` register as `MY-FIRST-SERVICE`/`MY-SECOND-SERVICE`).

### Security (user-service only)

- `security/WebSecurity.java` builds the `SecurityFilterChain`: CSRF disabled, `/h2-console/**` open, all other paths gated by an **IP allow-list** read from `gateway.allowed-ips` (`${GATEWAY_ALLOWED_IPS:127.0.0.1,::1}`) so only the gateway can call it. Locally this works because user-service registers in Eureka with `eureka.instance.hostname: localhost`, so the gateway connects via loopback (without it, it registers the LAN IP and the source IP changes with the network); in containers the allowed IP is the gateway's fixed IP. A rejected request returns **401, not 403** (HTTP Basic is enabled), which looks like a JWT failure — a 401 on `POST /user-service/users` (no JWT filter) means the IP check.
- `security/AuthenticationFilter.java` extends `UsernamePasswordAuthenticationFilter` and reads login credentials from the JSON body as `RequestLogin` (email + password). Passwords are BCrypt-encoded; `UserServiceImpl` implements `UserDetailsService`.
- **JWT is now implemented.** On login, `successfulAuthentication` mints a JWT (`subject = userId`, HS256, signed with `token.secret`) and returns it in the response `token` header (plus `userId`). The gateway's `AuthorizationHeaderFilter` verifies that JWT on protected routes using the **same** `token.secret`. Both `token.secret` and `token.expiration-time` come from the Config Server, not local config — see Config below.

### Config Server & shared secrets (config-service)

- **config-service** serves settings from local `.yml` files in `01_reference/native-repo/` (native profile). Filenames matter: a service receives `application.yml` (shared) **plus** `{name}.yml` (specific, higher priority), where `{name}` is `spring.cloud.config.name` from that service's `bootstrap.yml`. `search-locations` defaults to a **local absolute path** (`file://${user.home}/study/msa-multi-project/01_reference/native-repo`) — moving the repo breaks it, and native mode silently returns empty config when the folder is missing. In containers it is overridden by `NATIVE_REPO_LOCATION` pointing at the bind-mounted folder.
- **JWT works only if issuer and verifier share the same secret.** `user-service` (issuer) and `gateway` (verifier) must both use the **same** `bootstrap` name so they load the same `{name}.yml` and thus the same `token.secret`. Currently both use `name: ecommerce` → `ecommerce.yml`. Changing one without the other silently breaks all JWT validation (401 "Invalid JWT token"). The seed secrets in `native-repo/*.yml` are deliberately distinct (`..._application` / `..._ecommerce` / `..._user_service`) so you can tell which file a service actually loaded by inspecting the secret.
- **Spring Cloud Bus** (`spring-cloud-starter-bus-amqp` over RabbitMQ) propagates config changes: after editing a `native-repo` file, `POST /actuator/busrefresh` on any bus-connected service re-pulls config across all of them (endpoint exposed via `management.endpoints.web.exposure.include`).

## State to be aware of

- `UserServiceImpl.getUserByUserId` returns an **empty order list placeholder** — the user→order inter-service call (RestTemplate/WebClient/FeignClient) is not wired up yet, even though `ResponseOrder` exists in user-service.
- Commit messages are tagged `[LEARN]` / `[REFACT]`, reflecting the incremental tutorial nature of the repo.
