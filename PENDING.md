# Pending

The code is complete for the agreed scope. Everything below is waiting on something outside the code: a Meta account or Meta's documentation, a running MongoDB, a decision, or the connection to Studio Sara. Each item says what it waits on and what to do.

## 1. Needs Meta (credentials, a test number, or Meta's documentation)

| # | Item | Why it is open | What to do |
|---|---|---|---|
| 1.1 | Send-message JSON | The page for sending a template message did not load when the adapter was written. The body follows the usual shape (`messaging_product`, `to`, `type: template`, `template.name`, `language.code`, `components`). Only `biz_opaque_callback_data` and the status webhook format were checked against the docs. | Send `hello_world` from the Meta test number and compare. Fix `MetaPayloadBuilder` if Meta disagrees. |
| 1.2 | Template edit and delete | Written from memory of the API: edit is `POST /{template-id}` with components, delete is `DELETE /{waba-id}/message_templates?name=&hsm_id=`. | Try both against a test template and confirm. |
| 1.3 | Media upload for template headers | Resumable Upload API (`/{app-id}/uploads`, then `POST /{upload-id}` with `file_offset: 0`, answer `h`) written from memory. `OAuth` vs `Bearer` header on the second step is unconfirmed. | Upload a sample image through `POST /v1/templates/media`, then create a template with it. |
| 1.4 | Create-template body | `parameter_format` is passed through exactly as the caller gives it, because its accepted values were not confirmed. Authentication templates use a different (upsert) endpoint, which is not used here. | Create one utility template and one authentication template and see what Meta accepts. |
| 1.5 | Inbound message payload | Parsing follows the usual `messages[]` shape (`from`, `id`, `type`, `text.body`, `contacts[].profile.name`). Not checked against the docs page. | Reply to a test message from a phone and check the stored record. |
| 1.6 | Throughput and per-user marketing limits | Meta's pages on messages per second, pair-rate limits and per-user marketing caps were not found. The sender is limited only by `whatsapp.send.concurrency`. | Read Meta's limits and set the concurrency, or add a rate limiter. |
| 1.7 | Messaging limit | Broadcasts stay under `BROADCAST_DAILY_LIMIT`, which you set by hand. Meta exposes the real limit through the API. | Read it from Meta on a schedule instead of the env value (small change, needs a working account to test). |
| 1.8 | Webhook in practice | Signature check, the verify-token handshake and status updates are covered by unit tests only. | Configure the webhook in the Meta app, send a message, watch `sent`, `delivered`, `read` arrive. App must be in Live mode. |
| 1.9 | Marketing Messages API | Mentioned in Meta's docs as another way to send marketing templates. Not evaluated. | Read it when broadcasts are used for real, and decide whether to switch. |
| 1.10 | Template approval | Meta reviews every template, up to 24 hours. | Submit the templates early. Nothing in the code can speed it up. |

## 2. Needs the service to be started (MongoDB and the first run)

| # | Item | Why it is open | What to do |
|---|---|---|---|
| 2.1 | Application start | Spring's context has never been started, because it needs a MongoDB. A bean wiring or query mistake would show only on the first start. | Put a MongoDB URI in `.env`, run `./mvnw spring-boot:run`, send me the log. |
| 2.2 | Indexes | Created on startup (`auto-index-creation`). Not seen on a real cluster. | Check the collections after the first start. |
| 2.3 | Queue claim | The atomic claim (`findAndModify`) and the sweeper are unit-tested around, not against a real MongoDB. | Queue a few messages and watch them move through the statuses. |
| 2.4 | Aggregations | Usage, broadcast counts and `/v1/admin/stats` use MongoDB aggregation, tested only through mocks. | Call them once with a few messages in the database. |
| 2.5 | Broadcast cursor | Tag audiences page by contact `id` (`id > cursor`). Confirm it pages correctly on real ids. | Import more than one chunk of contacts and run a broadcast. |
| 2.6 | Atlas access | If the cluster is MongoDB Atlas, the VPS address must be on its network allowlist. | Add the VPS IP in Atlas. |

## 3. Needs a decision or a value from you

| # | Item | What is needed |
|---|---|---|
| 3.1 | Rates for spend | Price per delivered message, by category and country, through `PUT /v1/admin/rates`. Without it the cost is 0 and messages show as unpriced. |
| 3.2 | `BROADCAST_DAILY_LIMIT` | Set it to your Meta messaging limit (a new account starts at 250). |
| 3.3 | `META_BILLING_URL` | The link to your WhatsApp billing page. The default is a generic Meta billing address. |
| 3.4 | Opt-out words | `OPT_OUT_KEYWORDS` defaults to STOP, STOPALL, UNSUBSCRIBE, OPTOUT. Add your customers' words, for example Hindi ones. |
| 3.5 | Retention | `messages`, `message_events` and `inbound_messages` are never deleted. Decide how long to keep them. A TTL index would be added once you decide. |
| 3.6 | Phone numbers | A 10-digit number gets the default country code `DEFAULT_COUNTRY_CODE` (91). Change it if customers are elsewhere. |

## 4. Needs the connection to Studio Sara (next step)

| # | Item | What to do |
|---|---|---|
| 4.1 | Client in `sara-backend-ecom` | A thin client that calls `POST /v1/messages` instead of the current WhatsApp path, with a `clientMessageId` built from the business event. |
| 4.2 | Templates and bindings | Create Studio Sara's templates (OTP, order, design request, and so on) and bind its event names. |
| 4.3 | Event receiver | An endpoint in Studio Sara that checks the `X-Signature` header and stores delivery status. |
| 4.4 | Cut-over | A flag in Studio Sara to choose the new service or the current DoubleTick path, so the old path is the way back. |
| 4.5 | DoubleTick clean-up | After cut-over, remove Studio Sara's public DoubleTick entry points. |
| 4.6 | Admin screens | Templates, logs, broadcasts and spend screens in `sara-frontend-ecom`, through the backend. |

## 5. Needs deployment setup

| # | Item | What to do |
|---|---|---|
| 5.1 | Jenkins job | Create the job for this repo. `Jenkinsfile` is ready and uses the same credentials as the other services. |
| 5.2 | Git remote | The repo has no remote yet. Create it on GitHub and push. |
| 5.3 | Doppler | A project with the variables from `.env.example`. pm2 starts the jar with `doppler run`. |
| 5.4 | Public URL | A domain and HTTPS for `/webhooks/meta`, which Meta calls. |
| 5.5 | Server folder | `/root/sara-whatsapp-service/target` on the VPS (Jenkins creates `incoming`, the folder itself must exist). |

## 6. Known and accepted

- If a worker dies after Meta accepts a message and before the service records the message id, the sweeper retries and the customer could get that message twice. It is rare and reduced by short leases.
- The spend report is an estimate from this service's log and your rate card. It is not Meta's invoice.
- A pause on a broadcast stops new messages from being created. Messages already queued still go out. Cancel stops those too.
- Status never moves backwards. A message that is delivered cannot become failed.

## 7. Not built, by choice

- A second provider (DoubleTick). The provider interface is ready.
- Several vendors in one service. One instance serves one Meta account.
- A UI. Everything is an API.
- A chat inbox. Inbound messages are stored and passed to clients, nothing more.
- Per-API-key rate limits and IP allowlists.
- Metrics and alerting beyond `/v1/admin/stats`.
