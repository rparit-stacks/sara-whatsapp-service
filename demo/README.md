# App Review demo

A throwaway local page for the Meta App Review video. It is not part of the service.

## Steps to record the video

1. In the Meta app, open WhatsApp, API Setup.
   - Step 1: pick the test number as "From" and add your own WhatsApp number as the recipient (you get a code on your phone).
   - Copy the test number's **Phone number ID** and the **temporary access token**.
2. `cp demo/.env.example demo/.env` and paste the two values into `demo/.env`.
3. Run `python3 demo/server.py` and open http://127.0.0.1:8099
4. Start the screen recording. Show the page, type your number, press **Send message**, show the request and the response on the page.
5. In the same recording, show your phone (or WhatsApp Web) receiving the message.

The page shows the request it sends to the Cloud API and Meta's answer, so the reviewer sees the app sending the message and WhatsApp receiving the same one. The token never leaves the server process.
