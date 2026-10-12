"""A fake Jace Social API for testing the native apps in CI (screenshots of signed-in screens).

    python3 native-test/mock_api.py [port]      (default 8787)

Answers the API paths the apps use with fixed data (see FIXTURES), accepts any token, and
remembers messages sent to it while it runs. No live events (the apps cope without them).
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ME = "a" * 32
STEVE, ALEX, ZED, BOT = "b" * 32, "c" * 32, "d" * 32, "00000000000000000000000000000ace"
SID = "22222222-2222-2222-2222-222222222222"
GROUP = "33333333-3333-3333-3333-333333333333"
GENERAL, VOICE, RULES = "44444444-4444-4444-4444-444444444444", "55555555-5555-5555-5555-555555555555", "66666666-6666-6666-6666-666666666666"
AVATAR = "https://mc-heads.net/avatar/{}/64"


def person(uuid, name, **extra):
    return {"uuid": uuid, "name": name, "online": True, "status": "online", "uses_jace": True, "activity": None,
            "custom_status": None, "status_emoji": None, "minecraft_name": name, "jace_name": None,
            "avatar_url": AVATAR.format(name), "bio": None, "pronouns": None, "accent_color": None, "links": [],
            "unread": 0, **extra}


PEOPLE = {
    ME: person(ME, "jace_deb", custom_status="building", status_emoji="🔨"),
    STEVE: person(STEVE, "Steve", activity={"type": "playing", "version": "1.21.1", "loader": "fabric",
                                            "server": "play.example.net"}, unread=2),
    ALEX: person(ALEX, "Alex", status="idle"),
    ZED: person(ZED, "Zed", online=False, status="offline"),
    BOT: person(BOT, "Jace", is_bot=True, avatar_url=None, minecraft_name=None),
}
NOW = time.time()


def at(minutes_ago):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(NOW - minutes_ago * 60))


DMS = {
    STEVE: [
        {"id": 1, "sender": STEVE, "recipient": ME, "body": "hey! want to build a castle on the server?", "created_at": at(30)},
        {"id": 2, "sender": ME, "recipient": STEVE, "body": "yes!! **after dinner**", "created_at": at(28),
         "reactions": [{"emoji": "👍", "count": 1, "me": False}]},
        {"id": 3, "sender": STEVE, "recipient": ME, "body": "cool, I'll be on play.example.net", "created_at": at(5), "reply_to": 2},
    ],
}
CHANNELS = {
    GENERAL: [
        {"id": 10, "sender": None, "kind": "system", "body": "Alex joined the server", "created_at": at(120), "channel_id": GENERAL},
        {"id": 11, "sender": ALEX, "body": "welcome everyone! check out https://jace-social.vercel.app", "created_at": at(60),
         "channel_id": GENERAL, "reactions": [{"emoji": "🎉", "count": 3, "me": True}]},
        {"id": 12, "sender": STEVE, "body": "who's online tonight?", "created_at": at(10), "channel_id": GENERAL},
        {"id": 13, "sender": BOT, "body": "🎲 You rolled **4** (1-6)", "created_at": at(2), "channel_id": GENERAL},
    ],
    GROUP: [{"id": 20, "sender": ALEX, "body": "group project time 🛠️", "created_at": at(15), "channel_id": GROUP}],
}

ROLES = [
    {"id": "r-admin", "server_id": SID, "name": "Admin", "color": "#e0605a", "icon": "⭐", "position": 2, "permissions": 1073741824,
     "hoist": True, "mentionable": False, "is_default": False},
    {"id": "r-everyone", "server_id": SID, "name": "@everyone", "color": None, "icon": None, "position": 0, "permissions": 125455,
     "hoist": False, "mentionable": False, "is_default": True},
]
SERVER = {
    "server": {"id": SID, "name": "Jace Craft", "icon_url": None, "description": "The official Jace server", "owner": ME,
               "invite_code": "abc123", "vanity": "jacecraft", "banner_url": None, "accent_color": "#3ddc84", "rules": "Be kind",
               "welcome": {}, "onboarding": [], "system_channel": GENERAL, "invites_paused": False, "created_at": at(10000)},
    "role": "owner", "perms": 2147483647, "owner": True, "top": 1000000000, "roles": ROLES, "overrides": [], "onboarded": True,
    "channels": [
        {"id": "c-info", "server_id": SID, "name": "Info", "topic": None, "position": 0, "kind": "category"},
        {"id": RULES, "server_id": SID, "name": "rules", "topic": "Read me first", "position": 1, "kind": "announcement", "parent_id": "c-info"},
        {"id": GENERAL, "server_id": SID, "name": "general", "topic": "Say hi!", "position": 2, "kind": "text", "unread": 2},
        {"id": VOICE, "server_id": SID, "name": "Hangout", "topic": None, "position": 3, "kind": "voice"},
    ],
    "members": [dict(PEOPLE[ME], role="owner", roles=["r-admin"]), dict(PEOPLE[STEVE], role="member", roles=[]),
                dict(PEOPLE[ALEX], role="member", roles=[]), dict(PEOPLE[BOT], role="member", roles=[])],
    "voice": [{"channel_id": VOICE, "uuid": ALEX, "muted": False, "deafened": False, "streaming": False}],
}

FIXTURES = {
    "/me": dict(PEOPLE[ME], display_name=None, minecraft_linked=True, jace_linked=True, inbox="demo-inbox",
                realtime={"url": "", "key": ""}, settings={}, onboarded=True),
    "/friends": {"friends": [PEOPLE[STEVE], PEOPLE[ALEX], PEOPLE[ZED]], "incoming": [person("e" * 32, "Notch", online=False)],
                 "outgoing": [person("f" * 32, "Herobrine", online=False)]},
    "/groups": {"groups": [{"id": GROUP, "name": "Build team", "icon_url": None, "owner": ME,
                            "members": [PEOPLE[ME], PEOPLE[STEVE], PEOPLE[ALEX]], "unread": 1, "last_message_at": at(15)}]},
    "/servers": {"servers": [{"id": SID, "name": "Jace Craft", "icon_url": None, "role": "owner", "unread": 2}]},
    f"/servers/{SID}": SERVER,
}
next_id = [100]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("mock: " + fmt % args + "\n")

    def reply(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def route(self, method):
        url = urlparse(self.path)
        path, q = url.path.removeprefix("/api/v1"), parse_qs(url.query)
        n = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(n) or b"{}") if n else {}
        except ValueError:
            body = {}
        if not self.headers.get("Authorization", "").startswith("Bearer "):
            return self.reply(401, {"error": "Sign in first"})
        if method == "GET" and path in FIXTURES:
            return self.reply(200, FIXTURES[path])
        if path == "/messages":                                 # direct messages
            if method == "GET":
                msgs = DMS.get((q.get("with") or [""])[0], [])
                return self.reply(200, {"messages": msgs, "replies": replies_for(msgs)})
            who = body.get("to", "")
            return self.reply(200, {"message": add(DMS.setdefault(who, []), recipient=who, body=body.get("body", ""),
                                                   reply_to=body.get("reply_to"))})
        if path.startswith("/channels/") and path.endswith("/messages"):     # group chats and server channels
            cid = path.split("/")[2]
            if method == "GET":
                msgs = CHANNELS.get(cid, [])
                return self.reply(200, {"messages": msgs, "people": PEOPLE, "replies": replies_for(msgs), "perms": 2147483647})
            return self.reply(200, {"message": add(CHANNELS.setdefault(cid, []), channel_id=cid, body=body.get("body", ""),
                                                   reply_to=body.get("reply_to"))})
        if path.startswith("/users/"):
            uid = path.split("/")[2]
            return self.reply(200, dict(PEOPLE.get(uid, PEOPLE[ZED]), is_friend=True, is_you=uid == ME, mutual_servers=[]))
        if method != "GET":
            return self.reply(200, {"ok": True})               # anything else that changes something: fine
        return self.reply(404, {"error": f"The mock API doesn't know {path}"})

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_PATCH(self):
        self.route("PATCH")

    def do_PUT(self):
        self.route("PUT")

    def do_DELETE(self):
        self.route("DELETE")


def replies_for(msgs):
    ids = {m.get("reply_to") for m in msgs if m.get("reply_to")}
    everything = [m for lst in list(DMS.values()) + list(CHANNELS.values()) for m in lst]
    return {str(m["id"]): {"id": m["id"], "sender": m["sender"], "body": m["body"], "attachments": 0}
            for m in everything if m["id"] in ids}


def add(lst, **fields):
    next_id[0] += 1
    m = {"id": next_id[0], "sender": ME, "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "reactions": [], **fields}
    lst.append(m)
    return m


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print(f"mock Jace Social API on http://0.0.0.0:{port}/api/v1", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
