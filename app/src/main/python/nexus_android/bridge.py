"""Everything the Android screens ask for. Every function takes plain values and returns JSON text."""
from __future__ import annotations

import json
import logging
import queue
import threading
import time
from pathlib import Path

from nexusvpn import build_info, cores, paths, updater
from nexusvpn.engine import setup_logging
from nexusvpn.links import to_text as node_link_text
from nexusvpn.parsers import parse_subscription
from nexusvpn.presets import PRESET_TITLES
from nexusvpn.route_stats import RouteStats
from nexusvpn.rules_text import parse_text, to_text
from nexusvpn.settings import DEFAULTS, Settings
from nexusvpn.subscription import NodeStore, Subscription, subs_from_settings, subs_to_settings

from . import android_config, spec
from .android_config import GAMES, SERVICES, service_entries, supported
from .engine import AndroidEngine, AndroidService

log = logging.getLogger("bridge")
STATE_TEXT = {"stopped": "Отключено", "starting": "Подключение…", "running": "Подключено",
              "stopping": "Отключение…", "error": "Ошибка"}
MODE_TEXT = {"smart": "Умная маршрутизация", "global": "Всё через VPN", "custom": "Своя маршрутизация"}


class _Vpn:
    """Calls into the Kotlin VPN service (pro.nexusvpn.VpnBridge)."""

    def __init__(self):
        from java import jclass  # Chaquopy
        self._k = jclass("pro.nexusvpn.VpnBridge")

    def start(self, path: str) -> str:
        return str(self._k.start(path) or "")

    def reload(self, path: str) -> str:
        return str(self._k.reload(path) or "")

    def stop(self) -> None:
        self._k.stop()

    def is_running(self) -> bool:
        return bool(self._k.isRunning())

    def last_error(self) -> str:
        return str(self._k.lastError() or "")


class App:
    def __init__(self, vpn=None):
        paths.ensure_dirs()
        self.settings = Settings()
        self.ring = setup_logging(int(self.settings["log_max_mb"]))
        self.store = NodeStore()
        self.events: queue.Queue = queue.Queue(maxsize=200)
        self.vpn = vpn or _Vpn()
        self.engine = AndroidEngine(self.settings, self.store, self._on_event, self.vpn)
        self.service = AndroidService(self.settings, self.store, self.engine, self._on_event)
        self.routes = RouteStats()
        self.delay = -1
        self.update_info: dict = {}
        self._traffic = (0.0, 0, 0)
        self._lock = threading.Lock()
        android_config.convert_rule_files()

    # ------------------------------------------------------------------ events
    def _note(self, text: str, kind: str = "info") -> None:
        try:
            self.events.put_nowait({"text": text, "kind": kind})
        except queue.Full:
            pass

    def _on_event(self, name: str, data: dict) -> None:
        if name == "state":
            if data.get("state") == "error" and data.get("error"):
                self._note(data["error"], "error")
            if data.get("state") in ("running", "stopped"):
                self.settings["was_connected"] = data["state"] == "running"
                self.settings.save()
            if data.get("state") == "stopped":
                self.routes.reset()
        elif name == "health":
            self.delay = int(data.get("delay", -1))
        elif name == "rules_changed":
            android_config.convert_rule_files()
        elif name == "failover":
            self._note(f"Сервер не отвечал — переключено на {data['node']} ({data['delay']} мс)")
        elif name == "voice_moved":
            self._note(f"Сервер голоса не отвечал — звонок Discord перенесён на {data['node']}")
        elif name == "all_down":
            self._note("Все серверы недоступны. Проверьте подписку или интернет.", "error")
        elif name == "nodes_changed":
            for m in data.get("messages") or []:
                self._note(m)
        elif name == "app_update":
            self.update_info = data
            if data.get("notify"):
                self._note(f"Доступно обновление {data.get('name', '')}", "update")

    # ------------------------------------------------------------------ home
    def status(self) -> dict:
        e = self.engine
        out = {"state": e.state, "state_text": STATE_TEXT.get(e.state, e.state), "error": e.error,
               "mode": self.settings["routing_mode"], "mode_text": MODE_TEXT.get(self.settings["routing_mode"], ""),
               "active_node": self.settings["active_node"], "delay": self.delay if e.state == "running" else -1,
               "uptime": int(time.time() - e.started_at) if e.state == "running" and e.started_at else 0,
               "up": 0, "down": 0, "vpn_bytes": 0, "direct_bytes": 0, "vpn_conns": 0, "direct_conns": 0,
               "vpn_now": [], "update": self.update_info if self.update_info.get("newer") else None,
               "nodes": [{"tag": n.tag, "title": n.display} for n in self.store.nodes
                         if n.enabled and supported(n)[0]]}
        try:
            out.update(self._learn_counts())
        except Exception:  # noqa: BLE001
            pass
        if e.state == "running":
            try:
                data = e.api().connections()
                conns = data.get("connections") or []
                snap = self.routes.update(conns)
                now, up, down = time.time(), int(data.get("uploadTotal", 0)), int(data.get("downloadTotal", 0))
                t0, u0, d0 = self._traffic
                if t0 and now > t0:
                    out["up"], out["down"] = max(0, int((up - u0) / (now - t0))), max(0, int((down - d0) / (now - t0)))
                self._traffic = (now, up, down)
                out.update({k: snap[k] for k in ("vpn_bytes", "direct_bytes", "vpn_conns", "direct_conns",
                                                 "vpn_now")})
            except (OSError, ValueError):
                pass
        events = []
        while True:
            try:
                events.append(self.events.get_nowait())
            except queue.Empty:
                break
        out["events"] = events
        return out

    def _learn_counts(self) -> dict:
        rows = self.engine.learner.db.all()
        return {"learned_proxy": sum(1 for r in rows if r.get("verdict") == "proxy" and r.get("source") != "user"),
                "learned_direct": sum(1 for r in rows if r.get("verdict") == "direct" and r.get("source") != "user")}

    def toggle(self) -> dict:
        if self.engine.state in ("running", "starting"):
            self.engine.stop()
        else:
            self.engine.start()
        return {"state": self.engine.state, "error": self.engine.error}

    def start(self) -> dict:
        self.engine.start()
        return {"state": self.engine.state, "error": self.engine.error}

    def stop(self) -> dict:
        self.engine.stop()
        return {"state": self.engine.state}

    def revoked(self) -> dict:
        """Android took the VPN away (another VPN app, or the user in system settings)."""
        if self.engine.state != "stopped":
            self.engine.stop()
            self._note("Android отключил VPN (возможно, включили другое VPN-приложение)", "error")
        return {}

    def set_mode(self, mode: str) -> dict:
        self.settings["routing_mode"] = mode
        self.settings.save()
        self.engine.reload()
        empty = mode == "custom" and not self.engine.learner.db.user_entries("proxy")
        return {"custom_empty": empty}

    def select_node(self, tag: str) -> dict:
        self.engine.select_node(tag)
        return {}

    # ------------------------------------------------------------------ servers
    def servers(self) -> dict:
        subs = [{"name": s.name, "url": s.url, "enabled": s.enabled, "updated_at": s.updated_at,
                 "error": s.last_error, "count": sum(1 for n in self.store.nodes if n.group == s.name),
                 "upload": s.upload, "download": s.download, "total": s.total, "expire": s.expire}
                for s in subs_from_settings(self.settings)]
        nodes = []
        for n in self.store.nodes:
            ok, why = supported(n)
            nodes.append({"tag": n.tag, "title": n.display, "group": n.group, "enabled": n.enabled, "type": n.type,
                          "supported": ok, "reason": why, "voice": n.voice_grade,
                          "active": n.tag == self.settings["active_node"]})
        return {"subs": subs, "nodes": nodes, "auto": not self.settings["active_node"]}

    def add_text(self, text: str) -> dict:
        """Clipboard / typed text: a subscription link or one or many server links."""
        text = text.strip()
        lines = [x.strip() for x in text.splitlines() if x.strip()]
        if len(lines) == 1 and lines[0].lower().startswith(("http://", "https://")):
            return self.add_sub("", lines[0])
        nodes, errors = parse_subscription(text)
        if nodes:
            self.store.add_nodes(nodes)
            self.engine.reload()
        return {"added": len(nodes), "errors": errors[:10]}

    def add_sub(self, name: str, url: str) -> dict:
        subs = subs_from_settings(self.settings)
        name = name.strip() or f"Подписка {len(subs) + 1}"
        if any(s.name == name for s in subs):
            name = f"{name} ({len(subs) + 1})"
        subs.append(Subscription(name=name, url=url.strip()))
        subs_to_settings(self.settings, subs)
        self.settings.save()
        msgs = self.service.update_subscriptions(names=[name])
        return {"name": name, "messages": msgs}

    def edit_sub(self, old: str, name: str, url: str, enabled: bool) -> dict:
        subs = subs_from_settings(self.settings)
        for s in subs:
            if s.name == old:
                if name.strip() and name.strip() != old:
                    for n in self.store.nodes:
                        if n.group == old:
                            n.group = name.strip()
                    self.store.save()
                    s.name = name.strip()
                s.url, s.enabled = url.strip(), bool(enabled)
        subs_to_settings(self.settings, subs)
        self.settings.save()
        return {}

    def delete_sub(self, name: str) -> dict:
        subs_to_settings(self.settings, [s for s in subs_from_settings(self.settings) if s.name != name])
        self.settings.save()
        self.store.remove_group(name)
        self.engine.reload()
        return {}

    def update_subs(self, names_json: str = "[]") -> dict:
        names = json.loads(names_json or "[]") or None
        return {"messages": self.service.update_subscriptions(names=names)}

    def toggle_node(self, tag: str, on: bool) -> dict:
        n = self.store.by_tag(tag)
        if n:
            n.enabled = bool(on)
            self.store.save()
            self.engine.reload_later()
        return {}

    def remove_nodes(self, tags_json: str) -> dict:
        self.store.remove(set(json.loads(tags_json)))
        self.engine.reload()
        return {}

    def node_link(self, tag: str) -> dict:
        n = self.store.by_tag(tag)
        return {"link": node_link_text(n) if n else ""}

    def test_delays(self) -> dict:
        if self.engine.state != "running":
            return {"error": "Сначала подключите VPN"}
        try:
            res = self.engine.api().group_delay("auto", self.settings["health_check_url"], 5000)
        except OSError as e:
            return {"error": str(e)}
        return {"delays": {k: v for k, v in res.items() if isinstance(v, int)}}

    # ------------------------------------------------------------------ custom routing
    def _entries(self, verdict: str) -> list[tuple[str, str]]:
        return [tuple(e) for e in self.engine.learner.db.user_entries(verdict)]

    def routing(self) -> dict:
        proxy = set(self._entries("proxy"))
        return {"proxy": [{"kind": k, "value": v} for k, v in self._entries("proxy")],
                "direct": [{"kind": k, "value": v} for k, v in self._entries("direct")],
                "services": [{"name": name, "on": set(service_entries(name)) <= proxy} for name in SERVICES],
                "use_presets": bool(self.settings["custom_use_presets"])}

    def _change(self, verdict: str, add: list | None = None, remove: list | None = None) -> None:
        add, remove = [tuple(e) for e in add or []], {tuple(e) for e in remove or []}
        other = "direct" if verdict == "proxy" else "proxy"
        mine = [e for e in self._entries(verdict) if e not in remove]
        mine += [e for e in add if e not in mine]
        theirs = [e for e in self._entries(other) if e not in set(add)]
        self.engine.learner.db.replace_user(verdict, mine)
        self.engine.learner.db.replace_user(other, theirs)
        self.engine.learner.apply()
        android_config.convert_rule_files()

    def routing_change(self, verdict: str, add_json: str = "[]", remove_json: str = "[]") -> dict:
        add = [(e["kind"], e["value"]) for e in json.loads(add_json or "[]")]
        remove = [(e["kind"], e["value"]) for e in json.loads(remove_json or "[]")]
        self._change(verdict, add, remove)
        self.engine.reload_later()   # a new app list may change the core config; sites apply without it
        return self.routing()

    def routing_add_text(self, verdict: str, text: str) -> dict:
        entries, errors = parse_text(text)
        self._change(verdict, add=entries)
        return {"added": len(entries), "errors": errors[:10], **self.routing()}

    def routing_service(self, name: str, on: bool) -> dict:
        if on:
            self._change("proxy", add=service_entries(name))
        else:
            self._change("proxy", remove=service_entries(name))
        self.engine.reload_later()
        return self.routing()

    def routing_use_presets(self, on: bool) -> dict:
        self.settings["custom_use_presets"] = bool(on)
        self.settings.save()
        self.engine.reload()
        return self.routing()

    def routing_export(self, verdict: str) -> dict:
        return {"text": to_text(self._entries(verdict))}

    # ------------------------------------------------------------------ games
    def games(self) -> dict:
        chosen = self.settings["games"] or {}
        rows = [{"key": key, "name": name, "packages": pkgs, "note": note, "mode": chosen.get(key, "off"),
                 "custom": False} for key, name, pkgs, note in GAMES]
        for i, g in enumerate(self.settings["custom_games"] or []):
            rows.append({"key": f"custom:{i}", "name": g.get("name", ""), "packages": [g.get("match", "")],
                         "note": "своя игра", "mode": g.get("mode", "direct"), "custom": True})
        return {"games": rows}

    def set_game(self, key: str, mode: str) -> dict:
        s = self.settings
        if key.startswith("custom:"):
            custom = [dict(g) for g in s["custom_games"] or []]
            custom[int(key.split(":", 1)[1])]["mode"] = mode
            s["custom_games"] = custom
        else:
            games = dict(s["games"] or {})
            if mode == "off":
                games.pop(key, None)
            else:
                games[key] = mode
            s["games"] = games
        s.save()
        self.engine.reload_later()
        return self.games()

    def add_game(self, name: str, package: str) -> dict:
        s = self.settings
        s["custom_games"] = list(s["custom_games"] or []) + [{"name": name, "match": package, "mode": "direct"}]
        s.save()
        self.engine.reload_later()
        return self.games()

    def remove_game(self, key: str) -> dict:
        i = int(key.split(":", 1)[1])
        s = self.settings
        s["custom_games"] = [g for j, g in enumerate(s["custom_games"] or []) if j != i]
        s.save()
        self.engine.reload_later()
        return self.games()

    # ------------------------------------------------------------------ discord
    def discord(self) -> dict:
        listed = set(self._entries("proxy"))
        on = bool(self.settings["preset_discord"]) or set(service_entries("Discord")) <= listed
        nodes = [{"tag": n.tag, "title": n.display, "voice": n.voice_grade} for n in self.store.nodes
                 if n.enabled and supported(n)[0] and n.supports_udp]
        conns = []
        for c in self.engine.discord_connections():
            md = c.get("metadata") or {}
            conns.append({"host": md.get("host") or md.get("destinationIP", ""), "network": md.get("network", ""),
                          "chains": c.get("chains") or []})
        current = ""
        if self.engine.state == "running":
            try:
                current = self.engine.api().now("discord")
                if current == "auto-udp":
                    current = "Авто: " + self.engine.api().now("auto-udp")
            except OSError:
                pass
        return {"enabled": on, "nodes": nodes, "chosen": self.settings["discord_node"], "current": current,
                "connections": conns, "isolate": bool(self.settings["discord_isolate"])}

    def discord_enable(self, on: bool) -> dict:
        self.settings["preset_discord"] = bool(on)
        self.settings.save()
        if on:
            self._change("proxy", add=service_entries("Discord"))
        else:
            self._change("proxy", remove=service_entries("Discord"))
        self.engine.reload_later()
        return self.discord()

    def discord_node(self, tag: str, now: bool) -> dict:
        self.engine.select_voice_node(tag, bool(now))
        return self.discord()

    # ------------------------------------------------------------------ smart routing
    def smart(self) -> dict:
        rows = []
        for r in self.engine.learner.db.all():
            rows.append({"kind": r.get("kind"), "value": r.get("value"), "verdict": r.get("verdict"),
                         "source": r.get("source"), "process": _clean_process(r.get("process") or ""),
                         "reason": r.get("note") or "", "updated": r.get("updated") or 0})
        rows.sort(key=lambda r: -(r["updated"] or 0))
        presets = [{"key": k, "title": t, "on": bool(self.settings[k])} for k, t in PRESET_TITLES.items()]
        return {"rules": rows[:2000], "presets": presets}

    def smart_set(self, kind: str, value: str, verdict: str) -> dict:
        self.engine.learner.set_rule(kind, value, verdict)
        self.engine.learner.apply()
        return self.smart()

    def smart_forget(self, kind: str, value: str) -> dict:
        self.engine.learner.remove_rule(kind, value)
        self.engine.learner.apply()
        return self.smart()

    def smart_clear_auto(self) -> dict:
        self.engine.learner.db.clear_auto()
        self.engine.learner.apply()
        return self.smart()

    def smart_test(self, host: str) -> dict:
        if self.engine.state != "running":
            return {"error": "Сначала подключите VPN"}
        self.engine.learner.test_now(host.strip())
        return {"ok": True}

    def set_presets(self, presets_json: str) -> dict:
        for k, on in json.loads(presets_json).items():
            if k in PRESET_TITLES:
                self.settings[k] = bool(on)
        self.settings.save()
        threading.Thread(target=self._rules_then_reload, daemon=True).start()
        return self.smart()

    def _rules_then_reload(self) -> None:
        try:
            self.service.update_rules()
        except Exception:  # noqa: BLE001
            log.exception("rules")
        self.engine.reload()

    # ------------------------------------------------------------------ connections
    def connections(self) -> dict:
        if self.engine.state != "running":
            return {"connections": []}
        try:
            conns = self.engine.api().connections().get("connections") or []
        except (OSError, ValueError):
            return {"connections": []}
        out = []
        for c in conns:
            md = c.get("metadata") or {}
            if "probe-" in str(md.get("type", "")):
                continue
            out.append({"id": c.get("id"), "host": md.get("host") or md.get("destinationIP", ""),
                        "port": md.get("destinationPort", ""), "network": md.get("network", ""),
                        "app": _clean_process(md.get("processPath", "")), "chains": c.get("chains") or [],
                        "rule": c.get("rule", ""), "up": int(c.get("upload", 0)), "down": int(c.get("download", 0)),
                        "start": c.get("start", "")})
        out.sort(key=lambda c: -(c["up"] + c["down"]))
        return {"connections": out[:500]}

    def close_connection(self, cid: str) -> dict:
        try:
            self.engine.api().close_connection(cid)
        except OSError:
            pass
        return self.connections()

    # ------------------------------------------------------------------ settings
    def settings_spec(self) -> dict:
        return {"sections": spec.spec(self.settings)}

    def set_setting(self, key: str, value_json: str) -> dict:
        self.settings[key] = spec.coerce(key, json.loads(value_json))
        self.settings.save()
        self.engine.reload_later()
        return {}

    def reset_settings(self) -> dict:
        keep = {k: self.settings[k] for k in ("subscriptions", "clash_secret", "active_node", "was_connected")}
        self.settings.reset()
        for k, v in keep.items():
            self.settings[k] = v
        self.settings.save()
        self.engine.reload()
        return self.settings_spec()

    def get_setting(self, key: str) -> dict:
        return {"value": self.settings[key] if key in DEFAULTS else None}

    # ------------------------------------------------------------------ logs, updates, about
    def logs(self) -> dict:
        lines = list(self.ring)[-800:]
        core = paths.LOGS_DIR / "sing-box.log"
        try:
            with open(core, "rb") as fh:
                fh.seek(0, 2)
                fh.seek(max(0, fh.tell() - 120_000))
                tail = fh.read().decode("utf-8", "replace").splitlines()[-600:]
        except OSError:
            tail = []
        return {"app": lines, "core": tail}

    def updates(self) -> dict:
        age = cores.rules_age_days()
        return {"version": paths.APP_VERSION, "build": build_info.BUILD, "repo": build_info.REPO,
                "version_text": updater.current_version_text(),
                "rules_age_days": None if age == float("inf") else round(age, 1),
                "update": self.update_info or None}

    def check_update(self) -> dict:
        try:
            self.update_info = self.service.check_app_update()
        except updater.UpdateError as e:
            return {"error": str(e)}
        return self.update_info

    def download_update(self) -> dict:
        if not self.update_info.get("newer"):
            return {"error": "Обновлений нет"}
        try:
            return {"path": self.service.download_app_update(self.update_info)}
        except (updater.UpdateError, OSError) as e:
            return {"error": str(e)}

    def update_rules(self) -> dict:
        res = self.service.update_rules()
        bad = {k: v for k, v in res.items() if v != "ok"}
        return {"ok": len(res) - len(bad), "failed": bad}

    def help(self) -> dict:
        from nexusvpn.gui.help_texts import HELP
        return {**HELP, **spec.HELP_OVERRIDES}

    def about(self) -> dict:
        return {"name": paths.APP_NAME, "version": updater.current_version_text(), "repo": build_info.REPO,
                "data_dir": str(paths.DATA_DIR)}


def _clean_process(p: str) -> str:
    """Android reports «org.telegram.messenger (u0_a123)»; the package is enough."""
    p = p.split(" (", 1)[0]
    return Path(p.replace("\\", "/")).name if "/" in p or "\\" in p else p


_app: App | None = None
_init_lock = threading.Lock()


def init() -> str:
    global _app
    with _init_lock:
        if _app is None:
            _app = App()
    return json.dumps({"version": paths.APP_VERSION, "build": build_info.BUILD,
                       "autostart": bool(_app.settings["autostart"]),
                       "was_connected": bool(_app.settings["was_connected"])})


def call(name: str, *args) -> str:
    """One entry point for Kotlin: call("toggle"), call("select_node", tag)…"""
    if _app is None:
        init()
    try:
        res = getattr(_app, name)(*args)
    except Exception as e:  # noqa: BLE001 - every error is shown to the user, never crashes the app
        log.exception("call %s", name)
        return json.dumps({"error": str(e) or e.__class__.__name__}, ensure_ascii=False)
    return json.dumps(res, ensure_ascii=False, default=str)
