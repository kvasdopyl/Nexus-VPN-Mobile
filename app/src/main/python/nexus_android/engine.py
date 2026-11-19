"""The PC Engine and Service, running the core through the Android VpnService instead of processes.

Failover, the Discord voice watch, server switching without cutting calls, subscriptions, rule
updates and smart-routing learning are the PC code itself; only starting/stopping the core differs.
"""
from __future__ import annotations

import json
import logging
import re
import time

from nexusvpn import paths, updater
from nexusvpn.engine import Engine, Service

from . import android_config

log = logging.getLogger("android")
APK_RE = re.compile(r"\.apk$", re.I)


class _CoreHandle:
    """Stands in for the core process the PC engine watches."""

    def __init__(self, vpn):
        self.vpn = vpn

    def alive(self) -> bool:
        return bool(self.vpn.is_running())

    @property
    def pid(self):
        return None


class AndroidEngine(Engine):
    def __init__(self, settings, store, on_event, vpn):
        self.vpn = vpn
        super().__init__(settings, store, on_event)

    def _build(self) -> dict:
        return android_config.build(self.s, self.store.nodes)

    def _write(self) -> dict:
        paths.ensure_dirs()
        cfg = self._build()
        paths.SINGBOX_CONFIG.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), "utf-8")
        self._running_cfg = json.dumps(cfg, sort_keys=True)
        self.scope = "all"
        return cfg

    def _wait_api(self) -> None:
        api = self.api()
        deadline = time.time() + 15
        while time.time() < deadline:
            err = self.vpn.last_error()
            if err:
                raise RuntimeError(err)
            if api.alive():
                return
            time.sleep(0.25)
        raise RuntimeError("Ядро не отвечает. Откройте «Журнал», там видна причина.")

    def _start_locked(self) -> None:
        try:
            self._write()
        except ValueError as e:
            raise RuntimeError(str(e)) from e
        err = self.vpn.start(str(paths.SINGBOX_CONFIG))
        if err:
            raise RuntimeError(err)
        self.singbox = _CoreHandle(self.vpn)
        self._wait_api()
        self.learner.start()

    def _kill_children(self) -> None:
        self.vpn.stop()
        self.singbox = self.xray = self.mihomo = None

    def restart(self, reason: str = "") -> None:
        """Reload the running core in place: the VPN key icon and the tunnel stay up."""
        with self._lock:
            if self.state != "running" or not self.vpn.is_running():
                return super().restart(reason)
            log.info("reload: %s", reason)
            try:
                self._write()
                err = self.vpn.reload(str(paths.SINGBOX_CONFIG))
                if err:
                    raise RuntimeError(err)
                self._wait_api()
            except Exception as e:  # noqa: BLE001 - fall back to a full restart
                log.warning("in-place reload failed (%s), full restart", e)
                return super().restart(reason)

    def needs_restart(self) -> bool:
        if self.state != "running" or not self._running_cfg:
            return self.state == "running"
        try:
            return json.dumps(self._build(), sort_keys=True) != self._running_cfg
        except ValueError:
            return True

    @staticmethod
    def _iface_snapshot() -> frozenset:
        return frozenset()   # Android tells the core about network changes itself


class AndroidService(Service):
    """Cores are built into the app and update together with it."""

    def check_cores(self) -> dict[str, dict]:
        return {}

    def update_cores(self, names=None, only_if_idle: bool = False, force: bool = False) -> list[str]:
        return ["Ядро встроено в приложение и обновляется вместе с ним"]

    def check_app_update(self, background: bool = False) -> dict:
        try:
            info = updater.latest(self.s, self.engine.proxy_ports(), APK_RE)
        except updater.UpdateError as e:
            if background:
                log.info("app update check: %s", e)
                return {}
            raise
        info["newer"] = updater.is_newer(info)
        if info["newer"] and background:
            info["notify"] = info["build"] != int(self.s["update_notified_build"])
            if info["notify"]:
                self.s["update_notified_build"] = info["build"]
                self.s.save()
            self.emit("app_update", info)
        return info

    def download_app_update(self, info: dict) -> str:
        d = paths.RUN_DIR / "updates"
        for old in d.glob("*.apk") if d.exists() else []:
            old.unlink(missing_ok=True)
        return str(updater.download(info, self.s, self.engine.proxy_ports()))
