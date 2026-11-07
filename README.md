# sara-whatsapp-service

Template-driven WhatsApp service on Meta's Cloud API. API only, no UI. Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Status

Code is complete for the agreed scope: API keys and scopes, template message sending through a MongoDB queue with workers, retries and priority lanes, message log and status history, Meta status webhook, incoming messages with automatic opt-out, signed outbound events, templates (list, register, sync, create, edit, delete, media upload), event bindings, contacts and consent, broadcasts (create, edit, schedule, start, pause, resume, cancel), usage and estimated spend, provider registry (Meta), admin endpoints.

Everything that waits on Meta, a running MongoDB, a decision or the Studio Sara connection is listed in [PENDING.md](PENDING.md).

## API at a glance

All under `/v1`, header `X-Api-Key` (scopes: `send`, `read`, `templates`, `contacts`, `broadcast`). `/v1/admin/**` uses `X-Admin-Key`.

| Area | Endpoints |
|---|---|
| Messages | `POST /messages`, `GET /messages`, `GET /messages/{id}`, `GET /messages/{id}/events`, `POST /messages/{id}/retry` |
| Templates | `GET /templates`, `GET /templates/{key}`, `POST /templates` (create in Meta), `PUT /templates/{key}` (register by hand), `PUT /templates/{key}/components` (edit in Meta), `DELETE /templates/{key}`, `POST /templates/sync`, `POST /templates/media` (upload a header sample) |
| Event bindings | `GET /event-bindings`, `PUT /event-bindings/{event}` |
| Contacts | `PUT /contacts/{phone}/consent`, `GET /contacts`, `GET /contacts/{phone}`, `DELETE /contacts/{phone}`, `POST /contacts/import` |
| Broadcasts | `POST /broadcasts`, `GET /broadcasts`, `GET /broadcasts/{id}`, `PUT /broadcasts/{id}`, `POST /broadcasts/{id}/start`, `/pause`, `/resume`, `/cancel`, `GET /broadcasts/{id}/recipients` |
| Inbound | `GET /inbound` (messages customers sent; a reply of STOP opts the sender out) |
| Usage | `GET /usage?month=2026-10`, `GET /usage/billing-link` |
| Admin | `POST/GET /admin/clients`, `GET/PUT /admin/provider`, `GET /admin/provider/health`, `GET/PUT /admin/rates`, `GET /admin/stats` |
| Webhook | `GET/POST /webhooks/meta` |

Consent rules: a MARKETING template is sent only to a contact who opted in. A contact who opted out gets nothing except AUTHENTICATION messages. An import never re-opts-in someone who opted out.

Broadcasts: creating one sends nothing. Start it (or schedule it) and a worker turns the audience into messages in chunks, staying under `BROADCAST_DAILY_LIMIT` unique recipients per rolling 24 hours, so set that to your Meta messaging limit.

Spend: set the price per delivered message with `PUT /v1/admin/rates`, for example `{"currency":"INR","rates":{"MARKETING:91":0.78,"UTILITY:*":0.12}}`. The report is an estimate from this service's log, not Meta's invoice.

## Configuration

All values come from environment variables. Locally they are read from `.env` (gitignored, copy `.env.example`). In production they come from Doppler.

| Variable | Meaning |
|---|---|
| `MONGODB_URI`, `MONGODB_DATABASE` | MongoDB cluster |
| `CONFIG_ENCRYPTION_KEY` | base64 AES key, encrypts callback secrets stored in the database |
| `ADMIN_API_KEY` | header `X-Admin-Key` for `/v1/admin/**` |
| `META_WABA_ID`, `META_PHONE_NUMBER_ID` | from the Meta app, WhatsApp, API Setup |
| `META_ACCESS_TOKEN` | permanent system user token |
| `META_APP_SECRET` | verifies webhook signatures |
| `META_APP_ID` | needed later for media-header templates |
| `META_WEBHOOK_VERIFY_TOKEN` | any string; the same value goes into the Meta webhook settings |
| `META_GRAPH_VERSION` | Graph API version, default `v24.0` |
| `BROADCAST_DAILY_LIMIT` | unique recipients a broadcast may start per rolling 24 hours (default 250) |
| `META_BILLING_URL` | opened by `/v1/usage/billing-link` |

## First steps

```bash
# 1. Create an API client. The apiKey and callbackSecret are shown once.
curl -X POST localhost:8090/v1/admin/clients -H "X-Admin-Key: $ADMIN_API_KEY" -H "Content-Type: application/json" \
  -d '{"name":"studio-sara","callbackUrl":"https://example.com/whatsapp-events"}'

# 2. Pull the templates from Meta (or register one by hand with PUT /v1/templates/{key}).
curl -X POST localhost:8090/v1/templates/sync -H "X-Api-Key: $KEY"

# 3. Send a message with any template.
curl -X POST localhost:8090/v1/messages -H "X-Api-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"clientMessageId":"order-42-shipped","templateKey":"order_update","to":"9876543210",
       "parameters":{"variables":{"1":"A-42"}}}'

# 4. Follow it.
curl localhost:8090/v1/messages/{id} -H "X-Api-Key: $KEY"
curl localhost:8090/v1/messages/{id}/events -H "X-Api-Key: $KEY"
```

Meta webhook: callback URL `https://<host>/webhooks/meta`, the verify token from `.env`, subscribe to `messages` and `message_template_status_update`.

Outbound events are posted to the client's `callbackUrl` with `X-Signature: sha256=<HMAC-SHA256 of "<X-Timestamp>.<raw body>" with the callback secret>`.

## Build and test

```bash
./mvnw test
```
