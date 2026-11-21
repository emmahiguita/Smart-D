"""
============================================================================
ARCHIVO: sd_hub_agent.py
QUÉ HACE: Conecta el Companion Server a un Hub Electron opcional y reporta
          telemetría periódica del equipo (CPU, RAM, disco y uptime).
CÓMO FUNCIONA: Mantiene un WebSocket con reconexión exponencial hasta 60s y
               envía `agent_telemetry` cada 15 segundos.
POR QUÉ EXISTE: Aplica SRP separando el cliente de Hub del servidor local.
============================================================================
"""

import asyncio
import json
import os
import platform
import time
from sd_config import log


async def hub_agent_loop(hub_url: str, identity: dict):
    """Registra el PC en un Hub Electron opcional y reporta telemetría cada 15s."""
    import websockets
    retry = 1
    while True:
        try:
            async with websockets.connect(hub_url) as ws:
                await ws.send(json.dumps({"type": "register", "client": "agent"}))
                await ws.send(json.dumps({
                    "type": "register_agent", "agentId": identity["agentId"], "name": identity["name"],
                    "hostname": identity.get("hostname", platform.node()),
                    "os": identity.get("os", platform.platform()), "version": "1.0.0",
                    "tags": ["companion", "local"], "group": "Casa",
                }))
                while True:
                    await asyncio.sleep(15)
                    try:
                        import psutil
                        cpu = psutil.cpu_percent(interval=0.5)
                        mem = psutil.virtual_memory()
                        disk = psutil.disk_usage("/" if os.name != "nt" else os.environ["SYSTEMDRIVE"])
                        uptime_s = int(time.time() - psutil.boot_time())
                    except ImportError:
                        cpu, mem, disk, uptime_s = 0, None, None, 0
                    await ws.send(json.dumps({
                        "type": "agent_telemetry", "agentId": identity["agentId"], "cpu": cpu,
                        "ram": {"usedGb": round(mem.used / 1024**3, 1), "totalGb": round(mem.total / 1024**3, 1), "percent": mem.percent} if mem else None,
                        "disk": {"usedGb": round(disk.used / 1024**3, 1), "totalGb": round(disk.total / 1024**3, 1), "percent": disk.percent} if disk else None,
                        "uptime": uptime_s, "version": "1.0.0",
                    }))
        except Exception as e:
            log.warning(f"Agente → Hub error ({e}), reconectando en {retry}s...")
            await asyncio.sleep(retry)
            retry = min(retry * 2, 60)
