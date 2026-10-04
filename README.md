# Customers API

REST API for querying customers. Part of the [Mario Sérgio portfolio](https://github.com/mariosergio30/portfolio-frontend), backing the **Customers API** case study page.

## Technical Highlights
- **Explore the AWS AI and Machine Learning Services**
- **Amazon Polly (text to speech)**
- **Amazon Bedrock (natural language processing)**
- **SQL Full Text Search with PostgreSQL**

## Architecture


The frontend calls the Customers API, which queries PostgreSQL for customer data via `CustomerService`/`CustomerRepository`, and calls AWS Polly on demand via `PronounceService` to synthesize name pronunciations.
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

<img src="docs/customers-api-architecture.gif" alt="Customers API AWS architecture" width="600">

---

### REST API

<img src="README_UI_0.png" alt="README_UI_0.png" width="600">

### Customers FRONT UI 

*The Customers case study page (from [portfolio-frontend](https://github.com/mariosergio30/portfolio-frontend)) consuming this API — search, filter, and pronounce customer names.*

<img src="README_UI_1.png" alt="Customers page UI" width="600">



## Features

- **Customer search** — list customers by company, with optional case-insensitive partial-text filters on `name` and `country`, and sorting by `id` or `name`.
- **Name pronunciation** — synthesizes "`{name} from {country}`" as MP3 audio via [AWS Polly](https://aws.amazon.com/polly/) Neural TTS, auto-selecting a native voice for the requested language.
- **Company AI agent** — ask about a company's customers in plain language, or ask it to write emails to them (see below).
- **OpenAPI / Swagger UI** documentation out of the box.

## Natural-language customer assistant (agent)

Ask in plain language and act on the result, e.g. *"write a thank-you email for the 20 oldest customers, in the main language of each customer's country"*. `POST /api/companies/{companyId}/agentic-ask` runs a tool-use agent on Amazon Bedrock (`aws.bedrock.assistant-model-id`, a model that supports tool use). The model decides which tools to call and how often:

1. **Understand and search** — the `run_query` tool. The model writes one read-only `SELECT` at a time (filters, ordering, `LIMIT`); the service validates it, scopes it to the company, and returns the rows. It reads the answer, retries rejected queries, and never touches data outside the company.
2. **Act** — the `draft_emails` tool. The model writes one email per customer (subject and body) in the main language of the customer's country. The language comes from a fixed country → language lookup ([`CountryLanguage`](src/main/java/com/mycompany/customersapi/domain/CountryLanguage.java)), unknown countries fall back to English. The recipient address always comes from the customer record, never from the model.
3. **Review and approve** — the agent only drafts. The drafts are stored as an email batch and returned in `emailBatch`; nothing is sent. A person reviews them (`GET .../email-batches/{batchId}`) and approves them with `POST .../email-batches/{batchId}/approve`.
4. **Deliver** — approving sends the emails through [Amazon SES](https://aws.amazon.com/ses/). A batch is capped at `assistant.email.max-recipients` recipients (default 25), expires after `assistant.email.batch-ttl-hours` (default 24) and cannot be sent twice. An email SES refuses is marked `FAILED` and approving again retries only those.

Because only the approve endpoint sends, text injected through stored customer data can at most add drafts for customers of the same company, within the cap. Sending needs a verified sender: set `AWS_SES_FROM_ADDRESS`, otherwise approval answers `503`. While the SES account is in the sandbox, recipients must be verified as well.

Both services (Java and Node) call Bedrock and SES through the ECS task role (`bedrock:InvokeModel`, `ses:SendEmail`). The request flow is shown below.

<img src="docs/customers-api-nl-assistant.gif" alt="NL assistant sequence diagram" width="600">

*Prototype of the planned assistant screen (mockup, not implemented yet):*

<img src="docs/customers-api-nl-assistant-ui.png" alt="NL assistant UI prototype" width="600">

Not done yet: a `birthDate` per customer (so "the oldest customers" currently means the highest `age`, and birthday emails cannot target someone's actual birthday). Kendra was considered and dropped: it ranks text relevance and cannot sort or filter structured rows, and it is costly to run.

## Tech stack

- Java 25, Spring Boot 4.1
- Spring Web, Spring Data JPA, Spring Validation
- PostgreSQL
- Lombok
- springdoc-openapi (Swagger UI)
- AWS SDK v2 (Polly, Bedrock Runtime, SES v2)

## API endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/companies/{companyId}/customers` | Search customers by company, with optional `name`, `country`, `orderBy` (`id`\|`name`) query params. |
| `GET` | `/api/customers/{customerPk}/pronounce` | Synthesizes the customer's name via AWS Polly. Optional `language` query param (BCP‑47, e.g. `en-US`, `pt-BR`), defaults to `en-US`. Returns `audio/mpeg`. |
| `POST` | `/api/customers/{customerPk}/birthday-greetings` | Writes a birthday greeting with Amazon Bedrock. The model receives the customer's name and country and chooses the language (main language of the country) and the grammatical gender; the service sets a formality band from the age and does not send the exact age, email or phone. Returns `{ message, tone }`. |
| `POST` | `/api/companies/{companyId}/ask` | Question about a company's customers in plain language. One Bedrock call: the model sees only the table structure and writes a SQL query plus a short message; the API validates the query and runs it read-only for the company. No customer data goes to the model. Returns `{ message, sql, rowCount, rows }`. |
| `POST` | `/api/companies/{companyId}/agentic-ask` | Same question, answered by a tool-use agent (needs a tool-capable model, `aws.bedrock.assistant-model-id`, e.g. Claude). The model runs read-only SQL as often as it needs, reads the full rows each query returns (**customer data, including names, emails and phones, is sent to the model**), fixes rejected queries and writes the answer. When the prompt asks for emails it also drafts them in the language of each customer's country (stored, not sent). Returns `{ answer, sql, rowCount, rows, steps, emailBatch }`, where `steps` lists every tool call and `emailBatch` holds the drafts (null if none). |
| `GET` | `/api/companies/{companyId}/email-batches/{batchId}` | Reviews a batch of drafted emails and the status of each. Sends nothing. |
| `POST` | `/api/companies/{companyId}/email-batches/{batchId}/approve` | Approves the batch and sends its emails through Amazon SES (the only call that sends). `409` if already sent, `410` if expired, `503` if no sender address is configured. |
| `POST` | `/api/bedrock/ask` | Sends a prompt (and optional `systemPrompt`) to the Bedrock model. Returns `{ answer }`. |

Full interactive documentation is available at `/customers/swagger-ui.html` (the app runs under the `/customers` context path) once the app is running.

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
| `AWS_SES_FROM_ADDRESS` | *(empty)* | Verified SES sender for approved emails. Empty = emails can be drafted but not sent. |
| `AWS_SES_REGION` | `AWS_REGION` | AWS region for SES. |
| `ASSISTANT_EMAIL_MAX_RECIPIENTS` | `25` | Recipients per email batch. |
| `ASSISTANT_EMAIL_BATCH_TTL_HOURS` | `24` | Hours a drafted batch can still be approved. |

AWS credentials for Polly are resolved in priority order: static `aws.polly.access-key` / `aws.polly.secret-key` if set in configuration, otherwise the default AWS credentials chain (env vars, `~/.aws/credentials`, `aws login`/SSO session, or an IAM role).

The server listens on port `8082` by default.

## Running locally

Prerequisites: JDK 25, Maven, and a PostgreSQL instance with a `CUSTOMER` table matching [Customer.java](src/main/java/com/mycompany/customersapi/domain/Customer.java).

```bash
./mvn spring-boot:run
```

The API will be available at `http://localhost:8082/customers`, with Swagger UI at `http://localhost:8082/customers/swagger-ui.html`.

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
