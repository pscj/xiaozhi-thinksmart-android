"""Software updates, as far as the client is concerned: show what the root updater found, ask it to install.

The updater (thinksmart-update, system layer: thinksmart-ubuntu-image/update/) checks after boot and once a
day and never installs by itself. The client runs as ubuntu and installs nothing: it reads the
updater's status file and drops a request file that a systemd path unit hands to the root service,
which installs only the version it checked and verified itself, whatever the request says.
"""
import asyncio
import json
import logging
import os
from pathlib import Path

log = logging.getLogger(__name__)

STATE = Path('/var/lib/thinksmart-update')
STATUS = STATE / 'status.json'
REQUESTS = STATE / 'requests'


def read():
    """The updater's status ({} with enabled=False when the updater isn't installed)."""
    try:
        return {'enabled': True, **json.loads(STATUS.read_text())}
    except FileNotFoundError:
        return {'enabled': REQUESTS.is_dir()}
    except (OSError, ValueError):
        return {'enabled': True}


def request(action, version=''):
    """Ask the updater to 'check' or to 'install' version; False if it isn't installed/allowed."""
    try:
        tmp = REQUESTS / f'.{action}.tmp'
        tmp.write_text(json.dumps({'action': action, 'version': version}))
        os.replace(tmp, REQUESTS / action)
        return True
    except OSError as e:
        log.warning('update request %s: %s', action, e)
        return False


async def watch(publish):
    """Publish 'update' whenever the status file changes (download progress comes this way)."""
    last = None
    while True:
        try:
            mtime = STATUS.stat().st_mtime_ns
        except OSError:
            mtime = None
        if mtime != last:
            last = mtime
            publish('update', read())
        await asyncio.sleep(2)
