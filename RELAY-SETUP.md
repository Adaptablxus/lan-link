# Run your own relay (optional - the absolute lowest latency)

LAN Link's default path already avoids the old far-away bore.pub server: it uses the open
e4mc relay network and picks the relay closest to you automatically (Asia-Pacific for India,
~70 ms away instead of ~260 ms).

If you want the absolute best latency - or want the connection to depend on nobody but
yourself - run a relay on a small server **close to you** and make it the primary path.
LAN Link works with any [bore](https://github.com/ekzhang/bore)-compatible relay; it uses
your relay as the primary path when `"useQuiclime": false`, and as the automatic fallback
otherwise.

## 1. Get a small server close to you

Anything with a public IP works. Good options for India:

| Provider | Location | Price |
|---|---|---|
| Oracle Cloud | Mumbai / Hyderabad | **Free** ("Always Free" tier) |
| DigitalOcean | Bangalore | ~$4/month |
| Vultr | Mumbai | ~$3.50/month |

Quick note for Oracle: sign up, create an **Always Free** VM (Ubuntu 24.04, any shape),
and note down its public IP.

## 2. Set up the relay (5 minutes)

SSH into the server and run:

```sh
# Download the relay binary
# (use the aarch64 link instead if the server is ARM - Oracle's free tier is ARM)
curl -LO https://github.com/ekzhang/bore/releases/download/v0.6.0/bore-v0.6.0-x86_64-unknown-linux-musl.tar.gz
tar xzf bore-v0.6.0-x86_64-unknown-linux-musl.tar.gz
sudo mv bore /usr/local/bin/

# Allow the relay port through the server firewall
sudo ufw allow 7835/tcp || true

# Run it as a service so it survives reboots
sudo tee /etc/systemd/system/bore.service > /dev/null <<'EOF'
[Unit]
Description=bore relay server
After=network.target

[Service]
ExecStart=/usr/local/bin/bore server
Restart=always

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now bore
sudo systemctl status bore --no-pager
```

**Important:** also open TCP port 7835 in the *provider's* firewall:

- **Oracle:** Networking -> Virtual Cloud Networks -> your VCN -> Security Lists ->
  add an ingress rule: source `0.0.0.0/0`, protocol TCP, destination port `7835`
- **DigitalOcean / Vultr:** VPS products usually have no provider firewall by default,
  but check if one is configured

## 3. Point LAN Link at your relay

Open `config/lanlink.json` (next to your `mods` folder, created on first launch) and set:

```json
{
  "useQuiclime": false,
  "relayHost": "YOUR.SERVER.PUBLIC.IP",
  "relayPort": 7835
}
```

`"useQuiclime": false` makes your own relay the primary path (leave it out or `true` to keep
the e4mc network first and use your relay only as the fallback). Delete the old `"cachedIp"`
/ `"cachedForHost"` lines if present (the mod re-resolves automatically anyway).

Done. The next time you click **Start LAN**, chat will show your own relay's address, and
nearby players should see latency drop from ~260 ms to ~20-60 ms.

## Why not just use my own home IP directly?

Many ISPs (Airtel, Jio, ...) put home connections behind **CGNAT**: the "public" address
your house sees is shared by many customers, and it is impossible for anyone to open a
port on your connection - even with router port forwarding. You can check: if a traceroute
shows a private address (`10.x.x.x` or `100.64.x.x`) immediately after your router, you are
behind CGNAT. A relay near you is the correct fix in that case. Some ISPs sell a
"public/static IP" add-on - with one, direct connections become possible again.
