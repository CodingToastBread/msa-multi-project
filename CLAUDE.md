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

There is no central launcher. To bring the system up, start services in this order in separate terminals: **service-discovery → gateway → (user/catalog/order/first/second)-service**. A service started before Eureka will keep retrying registration.

`01_reference/test.http` holds runnable example requests (all hitting the gateway on `:8000`) — the canonical way to exercise the full flow.

## Architecture

Everything talks through two infrastructure services; business services never call each other by host/port directly.

- **service-discovery** (`:8761`) — Eureka **server** (`@EnableEurekaServer`). Every other service registers here. Self-registration is disabled on the server itself.
- **gateway** (`:8000`) — Spring Cloud Gateway on **WebFlux** (reactive). Single public entry point. Routes by path prefix to `lb://SERVICE-NAME` (client-side load balancing via Eureka). Path prefix → service mapping lives entirely in `gateway/src/main/resources/application.yaml` (`/user-service/**`, `/order-service/**`, `/catalog-service/**`, `/first-service/**`, `/second-service/**`).

Business / demo services all register with Eureka and run on **`server.port: 0`** (random port) — instances are addressed only by their registered name, never a fixed port. Eureka `instance-id` is randomized so multiple instances coexist:

- **user-service** — users + login. JPA/H2, Spring Security (see below).
- **catalog-service** — product catalog. JPA/H2 (`ddl-auto: create-drop`, seeded each boot).
- **order-service** — orders keyed by `userId`. JPA/H2.
- **first-service** / **second-service** — minimal demo services (register as `MY-FIRST-SERVICE` / `MY-SECOND-SERVICE`) used to exercise gateway routing and custom filters; not part of the e-commerce domain.

### Cross-cutting conventions

- **Layering** (in each business service): `controller` → `service` (interface + `*Impl`) → `jpa` (Entity + Spring Data `Repository`). Transport objects are split into **`vo`** (request/response value objects, e.g. `RequestUser`/`ResponseUser`) and **`dto`** (internal). Controllers convert between layers with **ModelMapper** using `MatchingStrategies.STRICT`. Follow this split when adding endpoints.
- **Gateway filters** (`gateway/.../filter/`) extend `AbstractGatewayFilterFactory<Config>` and are wired by class name in `application.yaml` (`CustomFilter`, `LoggingFilter`, `GlobalFilter`). `GlobalFilter` is applied to every route via `default-filters`.
- **Persistence**: all business services use **in-memory H2** (`jdbc:h2:mem:testdb`), so data is wiped on restart. H2 console is at `/h2-console` on each service.
- **Service names matter**: the `spring.application.name` value is the Eureka registration name and must match the `lb://` target in the gateway routes (note `first/second-service` register as `MY-FIRST-SERVICE`/`MY-SECOND-SERVICE`).

### Security (user-service only)

- `security/WebSecurity.java` builds the `SecurityFilterChain`: CSRF disabled, `/h2-console/**` open, all other paths gated by a hard-coded **IP allow-list** (`127.0.0.1`, `::1`, and a specific LAN IP) — expect 403s when calling from an unlisted address. HTTP Basic is enabled.
- `security/AuthenticationFilter.java` extends `UsernamePasswordAuthenticationFilter` and reads login credentials from the JSON body as `RequestLogin` (email + password). Passwords are BCrypt-encoded; `UserServiceImpl` implements `UserDetailsService`.
- Note `successfulAuthentication` is currently an empty stub (no token issuance yet) — JWT is the natural next step but is not implemented.

## State to be aware of

- `UserServiceImpl.getUserByUserId` returns an **empty order list placeholder** — the user→order inter-service call (RestTemplate/WebClient/FeignClient) is not wired up yet, even though `ResponseOrder` exists in user-service.
- Commit messages are tagged `[LEARN]` / `[REFACT]`, reflecting the incremental tutorial nature of the repo.
