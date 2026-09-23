# Customers API

REST API for querying customers. Part of the [Mario Sérgio portfolio](https://github.com/mariosergio30/portfolio-frontend), backing the **Customers API** case study page.

## Technical Highlights
- **Explore the AWS AI and Machine Learning Services**
- **Amazon Polly (text to speech)**
- **Amazon Bedrock (natural language processing)**
- **SQL Full Text Search with PostgreSQL**

## Architecture

```
                        +----------------------------+
                        |     portfolio-frontend     |
                        |         (React UI)         |
                        +----------------------------+
                                       |
                                       | HTTP / JSON
                                       v

                        +----------------------------+
                        |     CustomerController     |
                        +----------------------------+
                                       |
                     +-----------------------------------+
                     |                                   |
                     v                                   v
        +------------------------+          +------------------------+
        |    CustomerService     |          |    PronounceService    |
        +------------------------+          +------------------------+
                     |                                   |
                     v                                   |
        +------------------------+                       |
        |   CustomerRepository   |                       |
        +------------------------+                       |
                     |                                   |
                     v                                   v
         +----------------------+             +--------------------+
         |      PostgreSQL      |             |     AWS Polly      |
         |   (CUSTOMER table)   |             |    (Neural TTS)    |
         +----------------------+             +--------------------+
```

The frontend calls the Customers API, which queries PostgreSQL for customer data via `CustomerService`/`CustomerRepository`, and calls AWS Polly on demand via `PronounceService` to synthesize name pronunciations.

*WEB Rest API resources*

![README_UI_0.png](README_UI_0.png)


-

*The Customers case study page (from [portfolio-frontend](https://github.com/mariosergio30/portfolio-frontend)) consuming this API — search, filter, and pronounce customer names.*

![Customers page UI](README_UI_1.png)



## Features

- **Customer search** — list customers by company, with optional case-insensitive partial-text filters on `name` and `country`, and sorting by `id` or `name`.
- **Name pronunciation** — synthesizes "`{name} from {country}`" as MP3 audio via [AWS Polly](https://aws.amazon.com/polly/) Neural TTS, auto-selecting a native voice for the requested language.
- **OpenAPI / Swagger UI** documentation out of the box.

## Tech stack

- Java 25, Spring Boot 4.1
- Spring Web, Spring Data JPA, Spring Validation
- PostgreSQL
- Lombok
- springdoc-openapi (Swagger UI)
- AWS SDK v2 (Polly)

## API endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/companies/{companyId}/customers` | Search customers by company, with optional `name`, `country`, `orderBy` (`id`\|`name`) query params. |
| `GET` | `/api/customers/{customerPk}/pronounce` | Synthesizes the customer's name via AWS Polly. Optional `language` query param (BCP‑47, e.g. `en-US`, `pt-BR`), defaults to `en-US`. Returns `audio/mpeg`. |

Full interactive documentation is available at `/swagger-ui.html` once the app is running.

## Configuration

Configuration lives in [application.yml](src/main/resources/application.yml) and is overridable via environment variables:

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | Active Spring profile. |
| `DB_HOST` | `localhost` | PostgreSQL host. |
| `DB_PORT` | `5432` | PostgreSQL port. |
| `DB_NAME` | `postgres` | PostgreSQL database name. |
| `AWS_REGION` | `us-east-1` | AWS region for Polly. |
| `AWS_POLLY_VOICE_ID` | `Joanna` | Default Polly voice (used as a fallback per language). |

AWS credentials for Polly are resolved in priority order: static `aws.polly.access-key` / `aws.polly.secret-key` if set in configuration, otherwise the default AWS credentials chain (env vars, `~/.aws/credentials`, `aws login`/SSO session, or an IAM role).

The server listens on port `8082` by default.

## Running locally

Prerequisites: JDK 25, Maven, and a PostgreSQL instance with a `CUSTOMER` table matching [Customer.java](src/main/java/com/mycompany/customersapi/domain/Customer.java).

```bash
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8082`, with Swagger UI at `http://localhost:8082/swagger-ui.html`.

To use the `/pronounce` endpoint, make sure valid AWS credentials with Polly access are available (see Configuration above);

## Project structure

```
src/main/java/com/mycompany/customersapi/
├── config/       # CORS, Jackson, OpenAPI, global exception handling
├── controller/    # REST endpoints
├── domain/        # JPA entities
├── dto/           # Request/response payloads
├── repository/    # Spring Data JPA repositories
└── service/       # Business logic, Polly TTS synthesis
```
