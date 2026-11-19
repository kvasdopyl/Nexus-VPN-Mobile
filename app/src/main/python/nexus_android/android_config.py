"""The PC config builder, adapted to a phone.

Everything — servers, groups, DNS, presets, smart routing, Discord voice group — comes from
nexusvpn.config_builder unchanged. On Android there are no .exe files and no program folders, so rules
that match Windows programs are turned into rules that match Android apps (package names), and
the TUN always carries the whole phone: the system VpnService has no «only these addresses» mode
that would let apps bypass the program the way the PC «Мимо программы» mode does.
"""
from __future__ import annotations

import copy
import json
import re

from nexusvpn import config_builder, paths
from nexusvpn.models import Node
from nexusvpn.presets import DISCORD_PROCESS_REGEX, TELEGRAM_PROCESSES

APP_PACKAGE = "pro.nexusvpn.mobile"

TELEGRAM_PACKAGES = [
    "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
    "org.thunderdog.challegram", "org.telegram.plus", "com.radolyn.ayugram", "tw.nekomimi.nekogram",
    "xyz.nextalone.nagram", "com.exteragram.messenger", "app.nicegram", "it.owlgram.android",
]
DISCORD_PACKAGES = ["com.discord"]

# Windows program names that the PC presets use -> the same apps on Android
PROGRAM_TO_PACKAGES: dict[str, list[str]] = {
    **{p.lower(): TELEGRAM_PACKAGES for p in TELEGRAM_PROCESSES},
    "discord.exe": DISCORD_PACKAGES, "discordptb.exe": DISCORD_PACKAGES, "discordcanary.exe": DISCORD_PACKAGES,
}

# «Своя маршрутизация» service buttons: the PC list, with Android apps instead of .exe files.
# name -> (sites, apps); kept apart because a package name looks exactly like a domain
SERVICES: dict[str, tuple[list[str], list[str]]] = {
    "Discord": (["discord.com", "discord.gg", "discord.media", "discordapp.com", "discordapp.net", "discord.co",
                 "discordcdn.com"], DISCORD_PACKAGES),
    "YouTube": (["youtube.com", "googlevideo.com", "ytimg.com", "youtu.be", "ggpht.com", "youtube-nocookie.com",
                 "youtubei.googleapis.com"],
                ["com.google.android.youtube", "com.google.android.apps.youtube.music", "app.revanced.android.youtube",
                 "app.rvx.android.youtube"]),
    "Telegram": (["telegram.org", "t.me", "telegram.me", "telesco.pe"], TELEGRAM_PACKAGES),
    "ChatGPT": (["openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "sora.com"],
                ["com.openai.chatgpt"]),
    "Claude": (["anthropic.com", "claude.ai", "claude.com", "claudeusercontent.com"], ["com.anthropic.claude"]),
    "Gemini": (["gemini.google.com", "aistudio.google.com", "generativelanguage.googleapis.com"],
               ["com.google.android.apps.bard"]),
    "Instagram / Facebook": (["instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net", "fb.com",
                              "messenger.com"], ["com.instagram.android", "com.facebook.katana", "com.facebook.orca"]),
    "X / Twitter": (["x.com", "twitter.com", "twimg.com", "t.co"], ["com.twitter.android"]),
    "WhatsApp": (["whatsapp.com", "whatsapp.net", "wa.me"], ["com.whatsapp", "com.whatsapp.w4b"]),
}


def service_entries(name: str) -> list[tuple[str, str]]:
    from nexusvpn.rules_text import parse_text
    sites, apps = SERVICES[name]
    return parse_text("\n".join(sites))[0] + [("process", a) for a in apps]


# «Игры» on the phone: key, name, packages, note
GAMES: list[tuple[str, str, list[str], str]] = [
    ("m_pubg", "PUBG Mobile", ["com.tencent.ig", "com.pubg.krmobile", "com.vng.pubgmobile", "com.pubg.imobile",
                               "com.rekoo.pubgm"], ""),
    ("m_codm", "Call of Duty: Mobile", ["com.activision.callofduty.shooter", "com.garena.game.codm"], ""),
    ("m_wzm", "Call of Duty: Warzone Mobile", ["com.activision.callofduty.warzone"], ""),
    ("m_standoff", "Standoff 2", ["com.axlebolt.standoff2"], ""),
    ("m_freefire", "Free Fire / Free Fire MAX", ["com.dts.freefireth", "com.dts.freefiremax"], ""),
    ("m_mlbb", "Mobile Legends: Bang Bang", ["com.mobile.legends"], ""),
    ("m_wildrift", "League of Legends: Wild Rift", ["com.riotgames.league.wildrift"], ""),
    ("m_supercell", "Supercell (Brawl Stars, Clash Royale, Clash of Clans)",
     ["com.supercell.brawlstars", "com.supercell.clashroyale", "com.supercell.clashofclans"], ""),
    ("m_hoyo", "Genshin, Honkai: Star Rail, ZZZ (HoYoverse)",
     ["com.miHoYo.GenshinImpact", "com.HoYoverse.hkrpgoversea", "com.HoYoverse.Nap"], ""),
    ("m_roblox", "Roblox", ["com.roblox.client"], ""),
    ("m_minecraft", "Minecraft", ["com.mojang.minecraftpe"], ""),
    ("m_fortnite", "Fortnite", ["com.epicgames.fortnite"], ""),
    ("m_wotb", "World of Tanks Blitz", ["net.wargaming.wot.blitz"], ""),
    ("m_pokemongo", "Pokémon GO", ["com.nianticlabs.pokemongo"], ""),
    ("m_amongus", "Among Us", ["com.innersloth.spacemafia"], ""),
]

_PACKAGE_RE = re.compile(r"^[A-Za-z][\w]*(\.[A-Za-z_][\w]*)+$")


def is_package(value: str) -> bool:
    return bool(_PACKAGE_RE.match(value)) and not value.lower().endswith(".exe")


def packages_for(values: list[str]) -> list[str]:
    """Program names from rules (PC names or Android packages) -> Android packages."""
    out: list[str] = []
    for v in values:
        mapped = PROGRAM_TO_PACKAGES.get(v.lower())
        if mapped:
            out += mapped
        elif is_package(v):
            out.append(v)
    seen: set[str] = set()
    return [p for p in out if not (p in seen or seen.add(p))]


def supported(n: Node) -> tuple[bool, str]:
    """Can the phone carry this server? xhttp needs Xray, AmneziaWG needs mihomo, OpenVPN/IKEv2 need
    Windows adapters — none of them run inside the Android app yet."""
    if n.needs_xray:
        return False, "XHTTP пока работает только на ПК"
    if n.type == "amneziawg":
        return False, "AmneziaWG пока работает только на ПК"
    if n.is_system_tunnel:
        return False, f"{n.type.upper()} пока работает только на ПК"
    return True, ""


def game_packages(s) -> tuple[list[str], list[str]]:
    by_mode: dict[str, list[str]] = {"direct": [], "proxy": []}
    chosen = s["games"] or {}
    for key, _name, pkgs, _note in GAMES:
        mode = chosen.get(key, "off")
        if mode in by_mode:
            by_mode[mode] += pkgs
    for g in s["custom_games"] or []:
        if g.get("mode") in by_mode and is_package(g.get("match", "")):
            by_mode[g["mode"]].append(g["match"])
    return by_mode["direct"], by_mode["proxy"]


class _PhoneSettings:
    """The user's settings with the values the phone has to use (whole phone in the TUN, no games
    by folder — those are rebuilt from package names below)."""

    FORCED = {"capture_mode": "tun", "direct_bypass": "core", "games": {}, "custom_games": [],
              "auto_detect_interface": True}

    def __init__(self, s):
        self._s = s

    def __getitem__(self, key):
        return copy.deepcopy(self.FORCED[key]) if key in self.FORCED else self._s[key]

    def get(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            return default


def android_rule_path(path) -> str:
    return str(paths.RULES_DIR / "android" / path.name)


RULE_FILES = ("USER_PROXY_RS", "USER_DIRECT_RS", "USER_PROXY_IP_RS", "USER_DIRECT_IP_RS", "LEARNED_PROXY_RS",
              "LEARNED_PROXY_IP_RS", "LEARNED_DIRECT_RS")


def convert_rule_files() -> bool:
    """User and learned lists store programs as process_name; on Android they must be package_name.
    Writes converted copies next to the originals. Returns True if any copy changed (sing-box reloads
    changed local rule sets by itself)."""
    config_builder.ensure_rule_files()
    out_dir = paths.RULES_DIR / "android"
    out_dir.mkdir(parents=True, exist_ok=True)
    changed = False
    for attr in RULE_FILES:
        src = getattr(paths, attr)
        try:
            data = json.loads(src.read_text("utf-8"))
        except (OSError, ValueError):
            data = {"version": 3, "rules": []}
        rules = []
        for r in data.get("rules", []):
            r = dict(r)
            procs = r.pop("process_name", None)
            if procs:
                pkgs = packages_for(procs)
                if pkgs:
                    rules.append({"package_name": pkgs})
            r.pop("process_path", None)
            r.pop("process_path_regex", None)
            if r:
                rules.append(r)
        body = json.dumps({"version": data.get("version", 3),
                           "rules": rules or [{"domain": ["placeholder.invalid"]}]}, ensure_ascii=False)
        dst = out_dir / src.name
        try:
            if dst.read_text("utf-8") == body:
                continue
        except OSError:
            pass
        tmp = dst.with_suffix(".tmp")
        tmp.write_text(body, "utf-8")
        tmp.replace(dst)
        changed = True
    return changed


def _convert_rules(rules: list[dict], s) -> list[dict]:
    game_direct, game_proxy = game_packages(s)
    out: list[dict] = []
    for r in rules:
        if "process_name" in r:
            if r["process_name"] == config_builder.CORE_PROCESSES:
                continue   # the core runs inside the app; nothing to exclude
            pkgs = packages_for(r["process_name"])
            if not pkgs:
                continue
            r = {k: v for k, v in r.items() if k != "process_name"}
            r = {"package_name": pkgs, **r}
        if "process_path_regex" in r:
            if r["process_path_regex"] != [DISCORD_PROCESS_REGEX]:
                continue
            r = {"package_name": DISCORD_PACKAGES, **{k: v for k, v in r.items() if k != "process_path_regex"}}
        out.append(r)
        if r.get("rule_set") == ["user-direct", "user-direct-ip"]:
            # same place as the PC game rules: after the user's direct list, before presets and learning
            if game_direct:
                out.append({"package_name": game_direct, "outbound": "direct"})
            if game_proxy:
                out.append({"package_name": game_proxy, "outbound": "proxy"})
    return out


def build(s, nodes: list[Node]) -> dict:
    """sing-box config for the phone."""
    usable = [n for n in nodes if supported(n)[0]]
    if not [n for n in usable if n.enabled]:
        raise ValueError("Нет включённых серверов, которые работают на телефоне (XHTTP, AmneziaWG, OpenVPN и IKEv2 "
                         "пока поддерживаются только на ПК)")
    convert_rule_files()
    cfg = config_builder.build_singbox(_PhoneSettings(s), usable)
    for inbound in cfg["inbounds"]:
        if inbound["type"] != "tun":
            continue
        inbound.pop("interface_name", None)
        inbound.pop("route_address", None)
        exclude = [APP_PACKAGE] + packages_for(s["process_exclusions"] or [])
        inbound["exclude_package"] = sorted(set(exclude))
    route = cfg["route"]
    route["rules"] = _convert_rules(route["rules"], s)
    for rs in route["rule_set"]:
        if rs["type"] == "local" and rs.get("format") == "source":
            for attr in RULE_FILES:
                if rs["path"] == str(getattr(paths, attr)):
                    rs["path"] = android_rule_path(getattr(paths, attr))
    for server in cfg["dns"]["servers"]:
        if server.get("type") == "dhcp":
            # no DHCP client on Android; the phone's own resolver is used instead
            tag = server["tag"]
            server.clear()
            server.update({"tag": tag, "type": "local"})
    cfg["log"]["output"] = str(paths.LOGS_DIR / "sing-box.log")
    return cfg
