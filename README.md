# SmartRouter

SmartRouter is a Velocity plugin that smartly routes players based on their last connected server. It is designed for networks with multiple sub-servers like `hub`, `spawn`, and `smp`.

## ✨ Features

- Redirect players trying to connect to a fallback server (e.g., `spawn`) to their **previous server**.
- Track players' last connected server and remember it across reconnects.
- Fully configurable with `config.yml`.
- Requires **LuckPerms** to store and retrieve player metadata.

---

## 📦 Requirements

- **Velocity** Proxy
- **LuckPerms** (must be installed and active on the proxy)

If LuckPerms is not found, the plugin will automatically disable itself.

---

## ⚙️ Configuration

The configuration file is created at `plugins/smartrouter/config.yml`.

```yaml
file-version: 1

intercept-servers:
- spawn

remember-servers:
- spawn
- smp
```

### `intercept-servers`
A list of server names to intercept when a player connects.  
If a player attempts to join any of these servers, SmartRouter checks their last known server and redirects them if needed.

**Example:**  
If a player tries to join `spawn`, but their last known server was `smp`, they’ll be redirected back to `smp` instead of joining `spawn`.

---

### `remember-servers`
A list of server names to remember as a player's last server.  
When a player joins any of these servers, SmartRouter saves the server name to metadata (via LuckPerms).

**Tip:**  
Add any servers here that players might return to after a reconnect.

---

### 💡 Example Use Case

Let’s say your network has:

- A hub server players always connect to first.
- A spawn area where players are teleported initially.
- An SMP world players explore and return to.

With SmartRouter, players are routed to spawn the first time. If they log out in `smp`, the next time they connect, SmartRouter will skip spawn and send them back to `smp`.
