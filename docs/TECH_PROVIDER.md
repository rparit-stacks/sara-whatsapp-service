# Tech Provider and coexistence: what to do

Goal: keep the number `+91 63626 36113` on the WhatsApp Business app and also use it through the Cloud API (coexistence). Meta allows this only through Embedded Signup, which needs the app to be a Tech Provider.

App: `StudioSara WA API` (App ID 2967544970272259), business `Studio Sara` (verified).

## Already done
- Meta app with the WhatsApp use case, connected to the Studio Sara business.
- Business verification passed.
- The app's dashboard shows a "Tech Provider onboarding" section.

## 1. App settings (you)
In the App Dashboard, App settings, Basic:
- App icon (1024 x 1024).
- Privacy policy URL (a public page on studiosara.in).
- App category.
- Contact email.

## 2. Two videos for App Review (you)
Meta accepts screen recordings of the API Setup cURL script or of WhatsApp Manager.

1. A message sent from the app and received in WhatsApp. Show the send request (API Setup cURL, or `POST /v1/messages` of this service), then the message arriving on a phone.
2. A message template being created with the app. Show the template create request (this service's `POST /v1/templates`, or WhatsApp Manager) and the template appearing in the list.

## 3. App Review request (you submit, text below)
Request advanced access for `whatsapp_business_messaging` and `whatsapp_business_management`.

**How the app uses the platform (paste as the use case description):**
> Studio Sara is a custom print and fabric studio in India. Our backend uses the WhatsApp Business Platform to send our own customers messages about their account and orders on studiosara.in: login verification codes, order confirmations and status updates, design request updates, and invoices or quotations as PDF documents. We also send announcements to customers who have opted in, and we honour an opt-out reply (STOP) immediately. We connect our own WhatsApp Business number through coexistence, so our team keeps replying from the WhatsApp Business app while our systems send the automated messages.

**whatsapp_business_messaging:**
> Used to send approved template messages to our customers' WhatsApp numbers and to receive delivery status (sent, delivered, read, failed) and customer replies through webhooks.

**whatsapp_business_management:**
> Used to create, read and manage message templates and to read the phone number and account details of our own WhatsApp Business Account, and to manage our webhook subscription.

**Testing instructions for the reviewer:** a test number and steps to trigger a template message are in the screen recordings. Add any reviewer login you create.

## 4. After approval (me)
Build and wire Embedded Signup for coexistence:
- A small signup page in `sara-frontend-ecom` that runs Embedded Signup with session logging.
- An endpoint in `sara-whatsapp-service` that exchanges the returned code for a token, stores the account and phone number ids, and skips the registration step (the number is already registered).
- Webhook handling for the coexistence events (history and contact sync, echoes of messages sent from the app).
- Check the number with the `is_on_biz_app` and `platform_type` fields to confirm it works on both.

Limits to accept: groups, disappearing messages, broadcast lists and calls do not work on Cloud API for this number; throughput is 20 messages per second; the most recent 6 months of chats sync; linked companion devices get unlinked once. The Business app version must be 2.24.17 or higher.

## Open points
- Meta's page does not give the approval time or any fee.
- It does not say whether a business can be a Tech Provider only for its own number. This is confirmed only when the review goes through.
- Embedded Signup version 2 is being retired in October 2026 (the pages give two different dates). Use the current version when building.
