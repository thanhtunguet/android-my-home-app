# MyHome - A simple home automation system

## Features
- Handle Dynamic DNS updates using Cloudflare API
- Manage PC and server power states
- Alert on system events via Telegram

## Configuration

Use either environment variables or `local.properties` (Gradle reads env first, then falls back to `local.properties`). Do not quote values.

```dotenv
# Cloudflare (choose ONE auth method)
# 1) Preferred: Scoped API Token (must include Zone DNS:Edit for the zone)
CLOUDFLARE_API_TOKEN=your_scoped_api_token
# 2) Fallback: Global API Key (email + key)
CLOUDFLARE_API_EMAIL=you@example.com
CLOUDFLARE_API_KEY=your_global_api_key

# Zone ID and Record ID for DNS management
CLOUDFLARE_ZONE_ID=your_cloudflare_zone_id
CLOUDFLARE_RECORD_ID=your_cloudflare_record_id
# DNS record name (FQDN) to update
CLOUDFLARE_RECORD_NAME=example.yourdomain.com

TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_CHAT_ID=your_telegram_chat_id

PC_IP_ADDRESS=your_pc_ip_address
PC_MAC_ADDRESS=your_pc_mac_address
PC_SHUTDOWN_COMMAND=shutdown-my-pc
```

Notes:
- Values are loaded from environment variables; if empty, Gradle falls back to `local.properties` with the same keys.
- Ensure `CLOUDFLARE_RECORD_ID` belongs to `CLOUDFLARE_ZONE_ID` and `CLOUDFLARE_RECORD_NAME` matches that record.
- The app logs which Cloudflare auth method it uses at startup (token or global key).

## Roadmap

- Create a background service, targeting Android 5.1 and later, to run the app in the background.
- The background service should be started automatically when the device boots.
- The background service holding a handler to periodically check the public IP address of the device by calling to icanhazip.com.
- The background service should use nslookup to check current DNS settings (`CLOUDFLARE_RECORD_NAME`)
- If the public IP address is different from the one set in Cloudflare, update the DNS record using the Cloudflare API.
- The background service should also start a simple HTTP Server for PC's power management.
- The HTTP will expose 3 APIs: /turn-on, /turn-off, and /is-online
- API turn-on: will send a Wake-on-LAN packet to the PC using its MAC address.
- API turn-off: will send a shutdown command to the PC via both TCP socket and UDP socket, directly to the PC's IP address.
- API is-online: will check if the PC is online by telnet-ing to the PC's IP address on port 22 (SSH) or 3389 (RDP) and return true if one of them is open.
- The background service should also send a Telegram message to the user when the PC is turned on or off, or when the public IP address changes.
