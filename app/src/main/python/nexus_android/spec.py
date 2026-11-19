"""«Настройки» on the phone: the PC settings page, minus what only exists on Windows."""
from __future__ import annotations

from nexusvpn.gui.help_texts import HELP

# (section, [(key, title, kind)]); kind: bool | (min, max) | [values or (value, title)] | str | "apps"
FIELDS = [
    ("Приложения", [
        ("process_exclusions", "Приложения всегда мимо VPN", "apps"),
        ("process_force_vpn", "Приложения всегда через VPN", "apps"),
    ]),
    ("Подключение", [
        ("tun_stack", "Стек TUN", ["gvisor", "mixed", "system"]),
        ("tun_mtu", "MTU", (1200, 9000)),
        ("strict_route", "Строгая маршрутизация (без утечек)", bool),
        ("endpoint_independent_nat", "Endpoint-independent NAT (Discord, игры, P2P)", bool),
        ("udp_timeout", "Таймаут UDP-сессий", ["1m", "2m", "5m", "10m", "30m"]),
        ("ipv6_mode", "IPv6", ["off", "prefer"]),
        ("bypass_lan", "Локальная сеть напрямую", bool),
        ("mixed_port", "Порт прокси (HTTP+SOCKS)", (1024, 65000)),
        ("clash_api_port", "Порт API ядра", (1024, 65000)),
    ]),
    ("DNS", [
        ("dns_local", "Локальный DNS (для прямых сайтов)", str),
        ("dns_local_alt", "Резервный локальный DNS", str),
        ("dns_remote", "DNS через VPN (DoH/DoT/UDP)", str),
        ("dns_strategy", "Стратегия", ["ipv4_only", "prefer_ipv4", "prefer_ipv6"]),
        ("dns_fakeip", "FakeIP для VPN-доменов", bool),
    ]),
    ("Умная маршрутизация", [
        ("learn_enabled", "Обучение включено", bool),
        ("learn_auto_apply", "Применять изученное автоматически", bool),
        ("learn_apps", "Обучаться по приложениям (игры, клиенты без домена)", bool),
        ("learn_probe_interval", "Зависание соединения, сек", (3, 120)),
        ("learn_min_bytes", "Минимум байт ответа", (100, 1_000_000)),
        ("learn_workers", "Параллельных проверок", (1, 32)),
        ("block_quic_direct", "Блокировать QUIC для прямых сайтов", bool),
    ]),
    ("Надёжность", [
        ("auto_selector", "При сбоях переключаться на «Авто»", bool),
        ("health_check_url", "URL проверки", str),
        ("health_check_interval", "Интервал проверки, сек", (30, 3600)),
        ("auto_switch_failures", "Сбоев до переключения сервера", (1, 20)),
        ("sub_auto_update_hours", "Обновлять подписки, ч", (1, 720)),
        ("rules_auto_update", "Автообновление баз правил", bool),
    ]),
    ("Система", [
        ("autostart", "Подключаться при включении телефона", bool),
        ("app_update_check", "Проверять обновления приложения", bool),
        ("log_level", "Уровень журнала ядра", ["error", "warn", "info", "debug"]),
        ("log_max_mb", "Размер файла журнала, МБ", (1, 200)),
    ]),
]

HELP_OVERRIDES = {
    "process_exclusions": "<b>Приложения всегда мимо VPN</b><br>Трафик этих приложений вообще не попадает в VPN — "
                          "банки, Госуслуги, игры с античитом.",
    "process_force_vpn": "<b>Приложения всегда через VPN</b><br>Всё, что делают эти приложения, идёт через VPN, "
                         "в любом режиме маршрутизации.",
    "autostart": "<b>Подключаться при включении телефона</b><br>Если VPN был включён, после перезагрузки телефона он "
                 "включится сам. Для надёжности можно также включить в настройках Android: «Сеть» → «VPN» → "
                 "Nexus VPN Mobile → «Постоянная VPN».",
}


def describe(kind) -> dict:
    if kind is bool:
        return {"type": "bool"}
    if kind is str:
        return {"type": "text"}
    if kind == "apps":
        return {"type": "apps"}
    if isinstance(kind, tuple):
        return {"type": "int", "min": kind[0], "max": kind[1]}
    return {"type": "choice", "options": [list(i) if isinstance(i, tuple) else [i, i] for i in kind]}


def spec(settings) -> list[dict]:
    out = []
    for section, fields in FIELDS:
        items = []
        for key, title, kind in fields:
            items.append({"key": key, "title": title, "value": settings[key],
                          "help": HELP_OVERRIDES.get(key) or HELP.get(key, ""), **describe(kind)})
        out.append({"title": section, "fields": items})
    return out


def coerce(key: str, value):
    for _section, fields in FIELDS:
        for k, _title, kind in fields:
            if k != key:
                continue
            if kind is bool:
                return bool(value)
            if isinstance(kind, tuple):
                return max(kind[0], min(kind[1], int(value)))
            if kind == "apps":
                return [str(v) for v in value]
            return str(value).strip()
    raise KeyError(key)
