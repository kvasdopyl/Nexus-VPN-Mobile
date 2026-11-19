"""The program's Python side, tested on a PC with the real sing-box core.

Run from the repository root:  python -m pytest -q tests
The sing-box binary comes from the SINGBOX environment variable or PATH.
psutil must NOT be installed (like on the phone) — tests check the code starts without it.
"""
import json
import os
import shutil
import socket
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "app/src/main/python"))

LINKS = [
    "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443?security=reality&sni=www.microsoft.com&fp=chrome"
    "&pbk=jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0&sid=6ba85179e30d4fc2&type=tcp&flow=xtls-rprx-vision#VLESS-R",
    "hysteria2://pass@203.0.113.11:443?sni=example.com#Hy2",
    "vless://11111111-2222-3333-4444-555555555555@203.0.113.12:443?security=tls&type=xhttp&path=/x#XHTTP",
]


def _singbox() -> str:
    for c in (os.environ.get("SINGBOX"), shutil.which("sing-box")):
        if c and Path(c).exists():
            return c
    pytest.skip("sing-box binary not found")


@pytest.fixture()
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("NEXUSVPN_DATA_DIR", str(tmp_path / "data"))
    for m in [m for m in sys.modules if m.startswith(("nexusvpn", "nexus_android"))]:
        del sys.modules[m]
    from nexusvpn import paths
    paths.ensure_dirs()
    return tmp_path


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class FakeVpn:
    """What the Kotlin VpnBridge does, with a sing-box process instead of the Android service.
    The TUN inbound is dropped (no VpnService on a PC); everything else is the phone config as is."""

    def __init__(self):
        self.proc = None
        self.started = []
        self.reloads = 0

    def _run(self, path):
        cfg = json.loads(Path(path).read_text("utf-8"))
        cfg["inbounds"] = [i for i in cfg["inbounds"] if i["type"] != "tun"]
        cfg["log"].pop("output", None)
        run = Path(path).with_name("fake-run.json")
        run.write_text(json.dumps(cfg), "utf-8")
        self.proc = subprocess.Popen([_singbox(), "run", "-c", str(run)], stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL)

    def start(self, path):
        self.started.append(json.loads(Path(path).read_text("utf-8")))
        self._run(path)
        return ""

    def reload(self, path):
        self.reloads += 1
        self.stop()
        self._run(path)
        return ""

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            self.proc.wait(5)
        self.proc = None

    def is_running(self):
        return bool(self.proc and self.proc.poll() is None)

    def last_error(self):
        return "" if self.proc is None or self.proc.poll() is None else "ядро завершилось"


def _settings(env):
    from nexusvpn.settings import Settings
    s = Settings()
    s.update(clash_api_port=_free_port(), mixed_port=_free_port(), learn_enabled=False, rules_auto_update=False,
             app_update_check=False, sub_auto_update_hours=720)
    s.save()
    return s


def _store():
    from nexusvpn.parsers import parse_subscription
    from nexusvpn.subscription import NodeStore
    st = NodeStore()
    st.add_nodes(parse_subscription("\n".join(LINKS))[0])
    return st


def test_phone_config_passes_singbox_check(env):
    from nexus_android import android_config
    s = _settings(env)
    st = _store()
    s.update(process_exclusions=["ru.sberbankmobile"], games={"m_pubg": "direct", "m_codm": "proxy"},
             custom_games=[{"name": "Моя", "match": "com.my.game", "mode": "proxy"}])
    cfg = android_config.build(s, st.nodes)
    tun = next(i for i in cfg["inbounds"] if i["type"] == "tun")
    assert "route_address" not in tun and "interface_name" not in tun
    assert tun["exclude_package"] == sorted(["pro.nexusvpn.mobile", "ru.sberbankmobile"])
    rules = cfg["route"]["rules"]
    assert not any("process_name" in r or "process_path_regex" in r for r in rules)
    tg = next(r for r in rules if "org.telegram.messenger" in r.get("package_name", []))
    assert tg["outbound"] == "proxy"
    assert {"package_name": ["com.discord"], "outbound": "discord"} in rules
    i_user_direct = rules.index({"rule_set": ["user-direct", "user-direct-ip"], "outbound": "direct"})
    assert rules[i_user_direct + 1] == {"package_name": android_config.GAMES[0][2], "outbound": "direct"}
    assert rules[i_user_direct + 2]["package_name"][-1] == "com.my.game"
    tags = [o["tag"] for o in cfg["outbounds"]]
    assert "XHTTP" not in tags and "VLESS-R" in tags and "Hy2" in tags   # xhttp needs Xray: not on the phone
    for rs in cfg["route"]["rule_set"]:
        if rs["type"] == "local" and rs.get("format") == "source":
            assert "/android/" in rs["path"].replace("\\", "/") and Path(rs["path"]).exists()
    path = env / "check.json"
    path.write_text(json.dumps(cfg), "utf-8")
    r = subprocess.run([_singbox(), "check", "-c", str(path)], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


def test_user_lists_get_package_names(env):
    from nexus_android import android_config
    from nexusvpn import paths
    from nexusvpn.learner import LearnDB
    db = LearnDB()
    db.replace_user("proxy", [("domain", "kinopoisk.ru"), ("process", "Telegram.exe"),
                              ("process", "com.example.app"), ("process", "steam.exe")])
    db.export()
    android_config.convert_rule_files()
    data = json.loads((paths.RULES_DIR / "android" / paths.USER_PROXY_RS.name).read_text("utf-8"))
    pk = [p for r in data["rules"] for p in r.get("package_name", [])]
    assert "org.telegram.messenger" in pk and "com.example.app" in pk and "steam.exe" not in pk
    assert not any("process_name" in r for r in data["rules"])
    assert any("kinopoisk.ru" in r.get("domain_suffix", []) for r in data["rules"])


def test_services_keep_apps_apart_from_sites(env):
    from nexus_android.android_config import service_entries
    e = service_entries("Telegram")
    assert ("process", "org.telegram.messenger") in e and ("domain", "telegram.org") in e
    assert ("domain", "org.telegram.messenger") not in e


def test_engine_with_real_core(env):
    from nexus_android.bridge import App
    _settings(env)
    _store().save()
    vpn = FakeVpn()
    app = App(vpn)
    try:
        r = json.loads(json.dumps(app.toggle()))
        assert r["state"] == "running", r
        st = app.status()
        assert st["state_text"] == "Подключено" and [n["title"] for n in st["nodes"]][0].startswith("VLESS-R")
        assert len(vpn.started) == 1
        app.select_node("Hy2")
        assert app.engine.api().now("proxy") == "Hy2"
        assert vpn.reloads == 0                      # switching a server never restarts the core
        app.set_mode("global")
        assert vpn.reloads == 1 and app.engine.state == "running" and vpn.is_running()
        assert app.engine.api().alive()
        r = app.routing_service("YouTube", True)
        assert next(x for x in r["services"] if x["name"] == "YouTube")["on"]
        assert any(e["value"] == "com.google.android.youtube" for e in r["proxy"])
        assert app.settings_spec()["sections"][0]["fields"][0]["type"] == "apps"
        app.set_setting("tun_mtu", "1500")
        assert app.settings["tun_mtu"] == 1500
        assert app.servers()["nodes"][2]["supported"] is False
        app.toggle()
        assert app.engine.state == "stopped" and not vpn.is_running()
    finally:
        app.engine.shutdown()
        app.service.stop()
        vpn.stop()


def test_bridge_call_never_raises(env):
    from nexus_android import bridge
    bridge._app = bridge.App(FakeVpn())
    try:
        out = json.loads(bridge.call("remove_game", "custom:7"))
        assert out == {"games": [g for g in out.get("games", [])]} or "error" not in out or out["error"]
        assert "error" in json.loads(bridge.call("no_such_function"))
    finally:
        bridge._app.engine.shutdown()
        bridge._app.service.stop()
        bridge._app = None


def test_starts_without_psutil(env, monkeypatch):
    """The phone has no psutil: the whole program must import and start without it."""
    monkeypatch.setitem(sys.modules, "psutil", None)
    for m in [m for m in sys.modules if m.startswith(("nexusvpn", "nexus_android"))]:
        del sys.modules[m]
    from nexus_android import bridge
    bridge._app = bridge.App(FakeVpn())
    try:
        info = json.loads(bridge.init())
        assert info["version"] == "1.0.0"
        st = json.loads(bridge.call("status"))
        assert st["state"] == "stopped" and not st["error"]
        for name in ("servers", "routing", "games", "discord", "smart", "connections", "settings_spec", "logs",
                     "updates", "about", "help"):
            out = json.loads(bridge.call(name))
            assert not out.get("error"), (name, out)
    finally:
        bridge._app.engine.shutdown()
        bridge._app.service.stop()
        bridge._app = None
