# CloudChatMC

[![Source Code](https://img.shields.io/badge/Source-Code-blue?logo=github)](https://github.com/shadowviper-in/CloudChatMC) 
[![Issues](https://img.shields.io/badge/Issues-Tracker-red?logo=github)](https://github.com/shadowviper-in/CloudChatMC/issues) 
[![Modrinth](https://img.shields.io/badge/Modrinth-Download-green?logo=modrinth)](https://modrinth.com/mod/cloudchatmc/)
[![CurseForge](https://img.shields.io/badge/CurseForge-Download-orange?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/cloudchatmc)

CloudChatMC is a client-side Fabric mod that creates a private, persistent chat tunnel between you and your friends — completely independent of whatever Minecraft server you're all playing on.

Whether you're on different servers, different worlds, or one of you is in singleplayer, CloudChatMC keeps you connected through a free Cloudflare Worker backend.

## Features

*   **Global chat** — Send messages to all connected friends at once
*   **Private messaging** — Send direct messages to specific players
*   **Online tracking** — See who's currently connected with `/ccmc online`
*   **Token-based identity** — Each player has a unique token, no login needed
*   **Encrypted messages** — All messages are encoded before sending
*   **Auto-reconnect** — Automatically reconnects if the connection drops
*   **Message queueing** — Messages received during game load are not lost
*   **Config command** — Update your token and server URL in-game without restarting

## Commands

| Command                     |Description                                    |
| --------------------------- |---------------------------------------------- |
| <code>/ccmc msg &lt;message&gt;</code> |Send a global message to all connected players |
| <code>/ccmc pm &lt;player&gt; &lt;message&gt;</code> |Send a private message to a specific player    |
| <code>/ccmc online</code>   |Show all currently connected players           |
| <code>/ccmc status</code>   |Show your current connection status            |
| <code>/ccmc config &lt;token&gt; &lt;url&gt;</code> |Update token and WebSocket URL at runtime      |
| <code>/ccmc help</code>     |Show all available commands                    |

## Setup

CloudChatMC requires a Cloudflare Worker backend to relay messages. Follow the steps below to host your own — it's completely free.

***

### Step 1 — Create a Cloudflare Worker

1.  Go to [Cloudflare Dashboard](https://dash.cloudflare.com/) and sign up for free
2.  Go to **Workers & Pages → Create**
3.  Create a new Worker with any name (e.g. `cloudchatmc`)

***

### Step 2 — Set up Wrangler CLI

```
npm install -g wrangler
wrangler login
mkdir cloudchatmc-worker
cd cloudchatmc-worker
```

***

### Step 3 — Create `wrangler.toml`

```
name = "your-worker-name"
main = "worker.js"
compatibility_date = "2024-01-01"

[[durable_objects.bindings]]
name = "CHAT_ROOM"
class_name = "ChatRoom"

[[migrations]]
tag = "v1"
new_sqlite_classes = ["ChatRoom"]
```

Replace `your-worker-name` with the name you gave your Worker.

***

### Step 4 — Create `worker.js`

```
// Add your tokens and player names here
// Format: "TOKEN": "PlayerName"
// Generate strong random tokens — avoid special characters
// Example: use alphanumeric strings of 32+ characters
const TOKENS = {
  "token_for_player1": "Player1",
  "token_for_player2": "Player2",
  "token_for_player3": "Player3",
  "token_for_admin":   "Admin"
};

export class ChatRoom {
  constructor(state, env) {
    this.clients = new Map();
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("WebSocket only", { status: 400 });
    }

    const url = new URL(request.url);
    const token = url.searchParams.get("token");

    if (!token || !TOKENS[token]) {
      return new Response("Unauthorized", { status: 401 });
    }

    const username = TOKENS[token];

    const existing = this.clients.get(username);
    if (existing) {
      try { existing.close(1000, "Replaced by new connection"); } catch {}
      this.clients.delete(username);
    }

    const [client, server] = new WebSocketPair();
    server.accept();

    this.clients.set(username, server);
    this.broadcast({ type: "join", user: username });

    server.addEventListener("message", (event) => {
      let data;
      try {
        data = JSON.parse(event.data);
      } catch {
        return;
      }

      if (data.type === "broadcast") {
        this.broadcast({ type: "msg", from: username, data: data.data });

      } else if (data.type === "private") {
        const target = this.clients.get(data.to);
        if (target) {
          try {
            target.send(JSON.stringify({
              type: "pm",
              from: username,
              data: data.data
            }));
          } catch {
            this.clients.delete(data.to);
            this.safeSend(server, { type: "error", message: "Player not online" });
          }
        } else {
          this.safeSend(server, { type: "error", message: "Player not online" });
        }

      } else if (data.type === "list") {
        this.safeSend(server, {
          type: "list",
          users: [...this.clients.keys()]
        });
      }
    });

    server.addEventListener("close", () => {
      this.clients.delete(username);
      this.broadcast({ type: "leave", user: username });
    });

    server.addEventListener("error", () => {
      this.clients.delete(username);
    });

    return new Response(null, { status: 101, webSocket: client });
  }

  broadcast(obj) {
    const msg = JSON.stringify(obj);
    const dead = [];
    for (const [name, ws] of this.clients) {
      try {
        ws.send(msg);
      } catch {
        dead.push(name);
      }
    }
    for (const name of dead) this.clients.delete(name);
  }

  safeSend(ws, obj) {
    try { ws.send(JSON.stringify(obj)); } catch {}
  }
}

export default {
  async fetch(request, env) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("WebSocket only", { status: 400 });
    }

    const url = new URL(request.url);
    const token = url.searchParams.get("token");

    if (!token || !TOKENS[token]) {
      return new Response("Unauthorized", { status: 401 });
    }

    const id = env.CHAT_ROOM.idFromName("global");
    const room = env.CHAT_ROOM.get(id);
    return room.fetch(request);
  }
};
```

***

### Step 5 — Deploy

```
wrangler deploy
```

Your Worker URL will be: `wss://your-worker-name.your-subdomain.workers.dev`

***

### Step 6 — Configure the mod

Set your token and Worker URL in: `.minecraft/config/cloudchatmc.json`

```
{
  "token": "YOUR_TOKEN_HERE",
  "url": "wss://your-worker-name.your-subdomain.workers.dev"
}
```

Or use the in-game command:

```
/ccmc config YOUR_TOKEN wss://your-worker-name.your-subdomain.workers.dev
```

***

### Token Tips

*   Each player gets their own unique token — never share tokens between players
*   Use long alphanumeric strings (32+ characters) with no special characters
*   Keep tokens private — anyone with a token can connect as that player
*   You can generate safe tokens at [String Generator](https://www.random.org/strings/)

***

## Requirements

*   Minecraft 1.21.4-1.21.11
*   Fabric Loader 0.16.10+
*   Fabric API

## Why CloudChatMC?

Most cross-server communication mods require a dedicated server or paid hosting. CloudChatMC uses Cloudflare Workers with Durable Objects which has a generous free tier — making it completely free to run for small friend groups with no maintenance required.

## License

GPL-3.0 — free to use, modify, and distribute.
