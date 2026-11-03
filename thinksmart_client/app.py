"""The voice client: wake word → listen → hub replies → follow-up window → idle.

States shown to the page: idle, connecting, listening, thinking, speaking.
- Wake (voice or button): chime, connect if needed, stream the microphone ("realtime": the
  hub cuts sentences with its VAD).
- Once the hub has recognised what was said, the microphone stops uploading until the reply has
  been played: talking during a reply (to someone else, "嗯", a cough) never interrupts it. Only
  the wake word (still listened for meanwhile) or the button does: the reply stops, the hub is told
  how much of it was heard (it keeps only that in the context), and what is said next is a new turn.
  The same while the hub is still recognising what was said (the wake word is listened for again
  once its VAD says the user stopped) or thinking: the question is dropped, whatever of that turn
  still arrives (its text, its reply) is ignored, and the mic opens for a new one at once.
- After a reply the microphone stays open for follow_up_seconds, then stops uploading.
  The connection itself stays until the hub closes it (no speech for ~2 minutes, an exit
  word, the end button), so waking again within that time keeps the conversation.
- The hub keeps every model's conversation on disk (data/chat.db) and gives the next connection
  its recent part, so the page's threads survive a closed connection and a restart: they are loaded
  from the hub (/kiosk/chat/history) when not connected. Clearing them (settings, or saying
  "清除聊天记录") clears the hub's copy too.
- The screen stays on while not idle and blanks after the user's screen-off (device.Screen); the volume
  keys and the mic-mute switch are handled here too (device.Keys).
- Reports (Grok Bot finishing later) are polled from the hub: when idle the client chimes and
  connects so the hub reads them; in the sleep hours (sleep.py) they wait on the screen and the
  screen is dimmed.
"""
import asyncio
import json
import logging
import os
import socket
import time
import urllib.parse
from pathlib import Path

import aiohttp
import numpy as np

from . import config, mcp, sleep, wake_verify
from .audio import OUT_RATE, Audio, load_wav
from .device import DIM_LEVEL, Keys, Screen, normalize_screen_off
from .hub import Hub
from .kws import Kws
from .opus import Decoder, Encoder

log = logging.getLogger(__name__)

ROOT = Path(__file__).resolve().parent.parent
# after echo cancellation, above this the user is probably still talking (keeps the mic open).
# Was 0.012 (-38 dBFS): speech from across the room (~-42 dBFS in the wake word evaluation) fell
# under it, the mic closed after 10 s while the user was mid-sentence and the hub never got the
# end of it. Room noise here is ~-57 dBFS.
SPEECH_RMS = 0.005
MAX_LISTEN = 60          # seconds: one wake never keeps the mic open longer (a TV could otherwise)
RECOGNITION_WAIT = 15    # seconds to wait for the hub's text after the user stops (long sentences)
NORMAL_STOP_TAIL = 0.6   # a normal "tts stop" leaves less than this queued; more means aborted
PUNCT = set('，。！？；：、,.!?;:…')
REPORT_POLL = 10         # seconds between asking the hub for pending reports (local HTTP)
HISTORY_LIMIT = 50       # messages per model shown from the hub's saved conversations
MODEL_POLL = 30          # seconds between asking the hub for its models (changes made in its admin page)
MODEL_RETRY = 3          # until the hub answers (it takes over a minute to start after a boot)

# Hub vendor id -> (logo file, company). Unknown vendors are guessed from the model name.
VENDORS = {
    'claude': ('claude-color', 'Anthropic'), 'claude-code': ('claude-color', 'Anthropic'),
    'claude_cli': ('claude-color', 'Anthropic'), 'openai': ('openai', 'OpenAI'),
    'gemini': ('gemini-color', 'Google'), 'deepseek': ('deepseek-color', 'DeepSeek'),
    'qwen': ('qwen-color', '阿里通义'), 'doubao': ('doubao-color', '字节豆包'),
    'glm': ('zhipu-color', '智谱'), 'kimi': ('kimi-color', '月之暗面'),
    'grok_bot': ('grok', 'Grok Bot'), 'grok': ('grok', 'xAI'),
}
BY_MODEL = [
    (('claude',), 'claude'), (('deepseek',), 'deepseek'), (('gemini',), 'gemini'),
    (('qwen', 'qwq'), 'qwen'), (('doubao',), 'doubao'), (('glm',), 'glm'),
    (('kimi', 'moonshot'), 'kimi'), (('grok',), 'grok'), (('gpt', 'o1-', 'o3', 'o4-'), 'openai'),
]


def describe(option):
    vendor = option['vendor']
    if vendor not in VENDORS:
        low = (option.get('model') or '').lower()
        vendor = next((v for keys, v in BY_MODEL if any(k in low for k in keys)), vendor)
    logo, company = VENDORS.get(vendor, ('', option.get('label', '')))
    return {'kind': option['kind'], 'vendor': option['vendor'], 'label': option['label'],
            'entry': option['entry'], 'model': option.get('model', ''), 'logo': logo, 'company': company}


def marimba(notes, gap=0.1, peak=0.2):
    """Soft marimba notes as 48 kHz stereo: every prompt sound (wake, goodbye, didn't catch that,
    no hub, a new report) is from this one family. notes: Hz, one every `gap` seconds."""
    dur = 0.45
    t = np.arange(int(OUT_RATE * dur)) / OUT_RATE
    out = np.zeros(int(OUT_RATE * (gap * (len(notes) - 1) + dur)))
    for i, f in enumerate(notes):
        # a struck bar: the fundamental rings, the 4th and ~10th partials only for the strike
        x = (np.sin(2 * np.pi * f * t) * np.exp(-9 * t)
             + 0.35 * np.sin(2 * np.pi * 4 * f * t) * np.exp(-28 * t)
             + 0.12 * np.sin(2 * np.pi * 9.9 * f * t) * np.exp(-45 * t)) * np.minimum(1, t / 0.0015)
        start = int(OUT_RATE * gap * i)
        out[start:start + len(x)] += x
    out[-int(OUT_RATE * 0.015):] *= np.linspace(1, 0, int(OUT_RATE * 0.015))
    x = (out * peak / np.max(np.abs(out))).astype(np.float32)
    return np.repeat(x[:, None], 2, axis=1)


def overlay(base, extra, at):
    """`extra` mixed into `base` starting `at` seconds in (both 48 kHz stereo)."""
    start = int(OUT_RATE * at)
    out = np.zeros((max(len(base), start + len(extra)), 2), np.float32)
    out[:len(base)] += base
    out[start:start + len(extra)] += extra
    return np.clip(out, -1, 1)


ZAI_AT = 0.15   # seconds into the wake chime: just after its second note (0.09 s)
C5, E5, G5, A5, B5, C6, D6, E6 = 523.25, 659.25, 783.99, 880.0, 987.77, 1046.5, 1174.66, 1318.5


def has_default_route():
    """An IPv4 default route (/proc/net/route: destination 00000000), as a hint whether the device is online."""
    try:
        with open('/proc/net/route') as f:
            return any(line.split()[1] == '00000000' for line in f.readlines()[1:] if len(line.split()) > 1)
    except OSError:
        return True   # can't tell: don't claim it is offline


def local_ip():
    """The LAN address the device reaches the network from (the one to type into a computer's browser), or None offline.
    connect() on a UDP socket only picks the route and source address; nothing is sent."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('1.1.1.1', 53))   # a unicast address: a broadcast one is refused without SO_BROADCAST
            ip = s.getsockname()[0]
    except OSError:
        return None
    return None if ip.startswith('127.') or ip == '0.0.0.0' else ip

class Events:
    """Fan-out to the page's Server-Sent Events streams."""

    def __init__(self):
        self.queues = set()

    def publish(self, event, data):
        for q in list(self.queues):
            if q.qsize() < 500:
                q.put_nowait((event, data))


class Client:
    def __init__(self, cfg):
        self.cfg = cfg
        self.events = Events()
        self.loop = None
        self.state = 'idle'
        self.uploading = False
        self.deadline = None          # monotonic: stop listening if nothing is said by then
        self.listen_since = 0.0       # monotonic: when this wake started listening
        self.listen_mode = None       # 'realtime' (the hub's VAD ends it) or 'manual' (push-to-talk)
        self.ptt_held = False         # the mic button is held down (push-to-talk)
        self.awaiting_text = False    # the user spoke, the mic is closed, the hub is still recognising
        self.recognising = False      # the hub heard the user stop (its VAD) and is recognising, mic still open
        self.hub_pending = False      # the hub said "vad end" and hasn't answered it yet (text or "empty")
        self.vad_ended = False        # the hub is recognising, kept while the user talks on: the wake word is listened for
        self.aborted_at = None        # monotonic: the last abort, until listening starts again (logged)
        self.abort_pending = None     # monotonic: abort sent, the hub hasn't confirmed it yet (its "tts stop")
        self.question = None          # the user's message of this turn, until its reply starts
        self.heard = False            # anything recognised since this wake
        self.mute_audio = False       # after an abort, drop the old reply's frames
        self.listen_after_reply = False   # the mic was closed for this reply: reopen it when played
        self.reply_samples = 0        # reply audio received since "tts start" (to tell what was heard)
        self.reply_packets = 0
        self.muted_packets = 0
        self.recognised = 0           # count of "stt" messages
        self.threads = {}             # model entry -> messages (the hub's saved ones, then this connection's)
        self.history_loaded = False
        self.entry = None             # the model this connection talks to
        self.models = {'current': None, 'options': []}
        self.bot_msg = None
        self.decoder = None
        self._seq = 0
        self._send_q = None
        self.encoder = Encoder(16000, 1)
        self.kws = Kws(str(ROOT / 'models' / 'kws-zh'), cfg['wake_words'],
                       cfg['kws_score'], cfg['kws_threshold'], cfg['kws_paths'], cfg['kws_gain_db'])
        self.verifier = wake_verify.make(cfg['wake_verify'], cfg['wake_words'], cfg['hub_http'])
        self.backlog = None           # mic blocks since a wake word hit, uploaded once listening starts
        self.audio = Audio(self._on_block)
        self.hub = Hub(cfg['hub_ws'], cfg['device_id'], cfg['client_id'], cfg['token'],
                       self._on_json, self._on_audio, self._on_closed)
        sounds = ROOT / 'web' / 'sounds'
        # Wake: two rising marimba notes; goodbye is the same two falling
        self.sounds = {'wake': marimba([G5, D6], 0.09), 'bye': marimba([D6, G5], 0.12, 0.16),
                       'miss': marimba([E6, C6], 0.12, 0.16), 'error': marimba([A5, E5, C5], 0.14),
                       'report': marimba([G5, B5, D6], 0.08)}
        if (sounds / 'zai.wav').exists():
            # the one-character "在" comes in as the second note rings, over its tail: played after
            # the whole chime it came ~0.4 s later
            self.sounds['wake'] = overlay(self.sounds['wake'], load_wav(sounds / 'zai.wav'), ZAI_AT)
        self.speech_end = 0.0         # monotonic: last time the user was heard talking
        self.tools_listed = asyncio.Event()   # the hub asked for our device tools on this connection
        self.connecting = asyncio.Lock()      # one connection: a second wake while connecting waits for it
        self.screen = Screen()
        try:
            self.sleep = sleep.normalize(cfg.get('sleep'))
        except ValueError:
            log.warning('invalid sleep setting %r, using the default', cfg.get('sleep'))
            self.sleep = sleep.normalize(None)
        self.sleeping = False
        self.bright_in_sleep = False  # the user set a brightness in these sleep hours: don't dim
        level = cfg.get('brightness')
        if isinstance(level, bool) or not isinstance(level, (int, float)):
            log.warning('invalid brightness %r, using 100', level)
            level = 100
        self.screen.level = max(0, min(100, int(level)))
        try:
            self.screen_off = normalize_screen_off(cfg.get('screen_off'))
        except ValueError:
            log.warning('invalid screen_off %r, using the default', cfg.get('screen_off'))
            self.screen_off = normalize_screen_off(None)
        self.screen.idle = self._screen_off_seconds()   # written by start()'s release()
        self.reports = 0              # pending on the hub
        self.hub_seen = 0.0           # monotonic: last answer from the hub's HTTP side (health check)
        self.keys = Keys(on_volume=lambda v: self.events.publish('volume', {'volume': v}),
                         on_mic=self._on_mic)

    # ---- startup ----

    async def start(self):
        self.loop = asyncio.get_running_loop()
        self._send_q = asyncio.Queue(maxsize=200)
        self.audio.start()
        self.keys.start()
        self.screen.release()   # a hold left by a previous run must not keep the screen on
        self._apply_sleep()
        asyncio.create_task(self._sender())
        asyncio.create_task(self._ticker())
        asyncio.create_task(self._model_poller())
        asyncio.create_task(self._report_poller())

    # ---- microphone (worker thread) ----

    def _on_block(self, block, wake_block):
        # While streaming to the hub the wake word isn't needed (speech already reaches it), and
        # the CPU it would take is better left to the hub's speech recognition. wake_block: what the
        # wake word hears (during a reply, echo-cancelled without suppressing the voice; audio.py)
        # (after the hub's VAD said the user stopped, the wake word interrupts the recognition)
        hit = None if self.uploading and not self.vad_ended else self.kws.feed(wake_block)
        backlog = self.backlog
        if hit:
            log.info('wake word: %s', hit)
            if self.vad_ended:
                self.uploading = False   # keep what follows for the new question, not the old one
            # keep what follows: checking the hit, the chime and connecting take a moment, and
            # "小麦小麦，今天..." is often said in one go
            self.backlog = []
            audio = self.kws.recent()
            self.loop.call_soon_threadsafe(lambda: asyncio.create_task(self.voice_wake(hit, audio)))
        elif backlog is not None and not self.uploading:
            backlog.append(block)
            del backlog[:-200]   # 4 s, what the send queue takes at once
        elif self.uploading:
            self.backlog = None
            for b in (backlog or []) + [block]:
                packet = self.encoder.encode(np.clip(b * 32768, -32768, 32767).astype(np.int16))
                self.loop.call_soon_threadsafe(self._queue_packet, packet)

    def _queue_packet(self, packet):
        if not self._send_q.full():
            self._send_q.put_nowait(packet)

    async def _sender(self):
        while True:
            packet = await self._send_q.get()
            if self.uploading:
                await self.hub.send_audio(packet)

    # ---- state ----

    def set_state(self, state):
        self.audio.aec_hold = self.hub.connected or self.state == 'connecting'
        if state != self.state:
            self.state = state
            self.recognising = False
            log.info('state: %s', state)
            # only on a real change: set_state(self.state) just refreshes the page
            if state == 'idle':
                self.screen.release()
            else:
                self.screen.hold()
        self.events.publish('state', self.status())

    def status(self):
        return {'state': self.state, 'connected': self.hub.connected, 'uploading': self.uploading,
                'recognising': self.recognising or self.awaiting_text,
                'mic': self.keys.mic_on, 'sleep': {**self.sleep, 'active': self.sleeping},
                'brightness': self.brightness(), 'screen_off': self.screen_off,
                'reports': self.reports}

    def mic_off_toast(self):
        if self.keys.mic_on:
            return False
        self.play('miss')
        self.events.publish('toast', {'kind': 'info', 'text': '麦克风已关，拨开机身侧面的开关就能说话'})
        return True

    def _on_mic(self, on):
        log.info('mic switch: %s', 'on' if on else 'off')
        self.events.publish('toast', {'kind': 'info', 'text': '麦克风已打开' if on else '麦克风已关'})
        if not on and self.uploading:
            asyncio.create_task(self.stop_uploading())
        else:
            self.set_state(self.state)

    def health(self):
        """For the updater's self-test after installing a version (thinksmart-update)."""
        return {'audio': bool(self.audio._streams) and all(s.active for s in self.audio._streams),
                'hub': self.hub_seen > 0 and time.monotonic() - self.hub_seen < 30}

    def snapshot(self):
        return {'status': self.status(), 'models': self.models,
                'thread': {'entry': self.view_entry(), 'messages': self.threads.get(self.view_entry(), [])}}

    def view_entry(self):
        cur = self.models.get('current')
        return self.entry or (cur['entry'] if cur else None)

    def play(self, name):
        sound = self.sounds.get(name)
        if sound is not None:
            self.audio.player.push(sound)

    # ---- actions (from the wake word and the page) ----

    async def ensure_connected(self):
        async with self.connecting:
            return await self._connect()

    async def _connect(self):
        if self.hub.connected:
            return True
        self.set_state('connecting')
        self.tools_listed.clear()
        try:
            await self.hub.connect()
        except Exception as e:
            log.warning('cannot reach the hub: %s', e)
            self.play('error')
            self.events.publish('toast', {'kind': 'error', 'text': '连不上小智网关，请检查网关是否在运行'})
            self.set_state('idle')
            return False
        self.decoder = None
        # a new connection has no old reply to drop: the hub's own announcements (reports) send no
        # "tts start", so a mute left over from the last abort/end would silence them
        self.mute_audio = False
        return True

    async def voice_wake(self, label, audio):
        """The wake word model fired. With a second look (wake_verify.py), which takes 1-2 s here,
        the chime still comes at once, but connecting and listening wait for its answer: what is
        said meanwhile is kept (backlog) and sent on, and a false wake ends quietly instead of
        with the hub answering the TV or a "didn't catch that"."""
        if not self.verifier:
            await self.wake('voice')
        elif self.in_turn():
            # don't cut a reply off for what may not be the wake word
            if (await self._check_wake(label, audio))['ok']:
                await self.wake('voice')
        elif not self.mic_off_toast():
            self.screen.poke()
            self.chime('voice')
            self.set_state('connecting')
            if (await self._check_wake(label, audio))['ok']:
                await self.wake('voice', chimed=True)
            else:
                log.info('not the wake word after all: back to idle')
                self.set_state('idle')
        if not self.uploading:
            self.backlog = None

    async def _check_wake(self, label, audio):
        return await asyncio.get_running_loop().run_in_executor(None, self.verifier.check, audio, label)

    def chime(self, source):
        self.play('wake')
        self.events.publish('woke', {'source': source})

    def in_turn(self):
        """The hub is recognising, thinking or speaking: the wake word interrupts it."""
        return (self.state in ('thinking', 'speaking') or self.vad_ended) and self.hub.connected

    async def wake(self, source='button', chimed=False):
        self.screen.poke()
        if self.mic_off_toast():
            return
        if self.in_turn():
            await self.abort('wake_word_detected')
        if not chimed:
            self.chime(source)
        if not await self.ensure_connected():
            return
        await self.start_listening('realtime')
        self.heard = False
        self.deadline = time.monotonic() + self.cfg['follow_up_seconds']

    async def start_listening(self, mode):
        await self.hub.send({'type': 'listen', 'state': 'start', 'mode': mode})
        self.uploading = True
        self.listen_mode = mode
        self.vad_ended = False
        self.listen_since = time.monotonic()
        self.awaiting_text = False
        self.hub_pending = False
        if self.aborted_at is not None:
            log.info('listening again %.0f ms after the abort', (self.listen_since - self.aborted_at) * 1000)
            self.aborted_at = None
        self.set_state('listening')

    async def stop_uploading(self, missed=False):
        self.uploading = False
        self.deadline = None
        if missed:
            self.play('miss')
            self.events.publish('toast', {'kind': 'info', 'text': '没听清，可以再说一次「小麦小麦」'})
        self.set_state('idle')

    async def abort(self, reason=None):
        now = time.monotonic()
        phase = 'recognising' if self.vad_ended or self.awaiting_text else self.state
        # how much of this reply was actually heard: the hub keeps only that in the context
        played = max(self.reply_samples / OUT_RATE - self.audio.player.buffered(), 0)
        msg = {'type': 'abort', 'played_ms': int(played * 1000)}
        if reason:
            msg['reason'] = reason
        self.audio.player.trim(0)
        self.mute_audio = True
        self.listen_after_reply = False
        if phase == 'recognising':
            # what was just said is dropped (the hub drops it too): stop sending the rest of it
            self.uploading = False
        self.vad_ended = self.recognising = self.awaiting_text = self.hub_pending = False
        self.aborted_at = self.abort_pending = now
        log.info('abort while %s (%s)', phase, reason or 'button')
        await self.hub.send(msg)
        if self.bot_msg:
            self.bot_msg['interrupted'] = True
            self.publish_message(self.bot_msg)
            self.bot_msg = None
        elif self.question:
            # interrupted before any answer: the hub leaves the question out of the context
            self.question['interrupted'] = True
            self.publish_message(self.question)
        self.question = None
        self.set_state('listening' if self.uploading else 'idle')

    async def end(self):
        """End the conversation: stop everything and close the channel, which clears the hub's context."""
        if self.in_turn():
            await self.abort()
        self.uploading = False
        self.listen_after_reply = False
        self.deadline = None
        await self.hub.close()
        self.set_state('idle')

    async def say(self, text):
        """Ask in text instead of voice (the hub answers it like recognised speech)."""
        text = (text or '').strip()
        if not text:
            return
        if self.in_turn():
            await self.abort()
        if not await self.ensure_connected():
            return
        try:
            # a question sent right after connecting reaches the model before our tool list does,
            # and then "关掉睡眠模式" can't call anything; speech takes longer than this anyway
            await asyncio.wait_for(self.tools_listed.wait(), 2)
        except asyncio.TimeoutError:
            pass
        if not self.hub.connected:
            return   # ended (or dropped) while we waited: nothing will answer, stay idle
        await self.hub.send({'type': 'listen', 'state': 'detect', 'text': text})
        self.set_state('thinking')

    async def ptt_start(self):
        if self.mic_off_toast():
            return
        self.ptt_held = True
        if self.in_turn():
            await self.abort()
        if not await self.ensure_connected():
            return
        if not self.ptt_held:
            # let go while we were connecting: nothing would ever end a manual listen, so take it
            # as a tap (listen with the hub's VAD)
            await self.wake('button')
            return
        await self.start_listening('manual')
        self.heard = False
        self.deadline = None

    async def ptt_stop(self):
        self.ptt_held = False
        if self.uploading:
            await self.hub.send({'type': 'listen', 'state': 'stop'})
            self.uploading = False
            self.awaiting_text = True   # the hub's "nothing recognised" ends it at once
            self.set_state('thinking')
            self.deadline = time.monotonic() + 8   # back to idle if nothing comes back

    # ---- from the hub ----

    def _stale(self, msg):
        """Sent by the hub before it handled our abort (it confirms with "tts stop"): the old turn's
        question or the start of its reply, arriving late. 3 s in case the confirmation got lost."""
        if self.abort_pending is None:
            return False
        if time.monotonic() - self.abort_pending > 3:
            self.abort_pending = None
            return False
        kind, state = msg.get('type'), msg.get('state')
        if kind == 'tts' and state == 'stop' and msg.get('interrupted'):
            log.info('the hub confirmed the abort after %.0f ms', (time.monotonic() - self.abort_pending) * 1000)
            self.abort_pending = None
            return True   # our own abort: what comes next was already decided
        if kind == 'stt' or (kind == 'tts' and state in ('start', 'sentence_start')):
            log.info('dropped from the interrupted turn: %s %s', kind, state or msg.get('text'))
            return True
        return False

    async def _on_json(self, msg):
        kind = msg.get('type')
        if self._stale(msg):
            return
        if kind == 'stt':
            text = (msg.get('text') or '').strip()
            if text.startswith('% '):
                # not speech: the hub's note that the model calls a tool (for the screens of ESP32 boards)
                log.info('the model calls %s', text[2:])
            elif text:
                if self.speech_end > self.listen_since:
                    log.info('recognised %.1f s after the user stopped talking: %s',
                             time.monotonic() - self.speech_end, text)
                else:
                    # said before this listening began (right after the wake word, in its backlog):
                    # the last time we heard talking belongs to an earlier turn
                    log.info('recognised: %s', text)
                self.heard = True
                self.awaiting_text = False
                self.recognising = self.vad_ended = False
                self.hub_pending = False
                self.deadline = None
                self.recognised += 1
                self.reply_samples = 0    # an abort before this turn's audio has heard nothing of it
                if self.uploading:
                    # nothing said during the reply counts; the wake word is listened for instead
                    self.uploading = False
                    self.listen_after_reply = True
                # on the page right away: asking the hub for its models first took 0.7-2 s
                m = self.question = self.add_message('user', text)
                self.set_state('thinking')
                asyncio.create_task(self._refresh_after_question(m))
        elif kind == 'vad':
            # VAD events, a feature asked for in our hello. end: the hub decided the user finished and
            # is recognising; empty: it recognised nothing (a cough, too short, only echo)
            state = msg.get('state')
            if state == 'end':
                self.hub_pending = True
                if self.uploading and not self.recognising:
                    self.recognising = self.vad_ended = True
                    self.set_state(self.state)
            elif state == 'empty':
                self.hub_pending = self.vad_ended = False
                log.info('the hub recognised nothing')
                if self.awaiting_text:
                    # the mic is closed and this was all we were waiting for
                    self.awaiting_text = False
                    await self.stop_uploading(missed=True)
                elif self.recognising:
                    # the mic is still open: back to listening
                    self.recognising = False
                    self.set_state(self.state)
        elif kind == 'tts':
            state = msg.get('state')
            if state != 'sentence_start':
                log.info('reply %s (%.1f s queued)', state, self.audio.player.buffered())
            if state == 'stop':
                player = self.audio.player
                log.info('reply audio: %d packets (%d dropped muted), %.2f s; buffer ran dry %d times (last sample %s); '
                         'speaker callback flags %s', self.reply_packets, self.muted_packets, self.reply_samples / OUT_RATE,
                         len(player.ran_out), ' '.join(f'{v:.3f}' for v in player.ran_out[-8:]),
                         player.late[-8:])
                levels = self.audio.take_echo_levels()
                if levels:
                    log.info('echo while it played: mic %s dBFS, cancelled %s, for the wake word %s', *levels)
            if state == 'start':
                # The hub says "start" as soon as it has the question; the model is still
                # thinking until the first sentence arrives
                self.mute_audio = False
                self.decoder = None
                self.bot_msg = None
                self.reply_samples = self.reply_packets = self.muted_packets = 0
                self.audio.player.ran_out.clear()
                self.audio.player.late.clear()
                self.deadline = None
                self.set_state('thinking')
            elif state == 'sentence_start':
                text = (msg.get('text') or '').strip()
                if self.mute_audio and self.abort_pending is None:
                    # the hub confirmed our abort, so this is something new it says on its own: a
                    # report (e.g. the interrupted Grok Bot turn finished) sends no "tts start" to unmute
                    self.mute_audio = False
                if self.uploading and self.listen_mode == 'realtime' and not self.mute_audio:
                    # the hub speaks on its own while we listen (a pending report: no stt, no
                    # "tts start" before it). Like any reply, only the wake word interrupts it,
                    # and the follow-up window opens after it
                    self.uploading = False
                    self.listen_after_reply = True
                    self.recognising = self.vad_ended = self.hub_pending = False
                    self.deadline = None
                if not self.mute_audio:
                    self.set_state('speaking')
                if text and not self.mute_audio:
                    if self.bot_msg is None:
                        self.bot_msg = self.add_message('bot', text)
                        self.question = None
                    else:
                        # the hub sends sentences with their trailing punctuation stripped
                        prev = self.bot_msg['text']
                        self.bot_msg['text'] = prev + ('' if prev[-1:] in PUNCT else '，') + text
                        self.publish_message(self.bot_msg)
            elif state == 'stop':
                # the hub cut the reply off (a newer question); an older hub doesn't say so, but then
                # more than the usual tail is still queued
                if msg.get('interrupted', self.audio.player.buffered() > NORMAL_STOP_TAIL):
                    self.audio.player.trim(0)
                    if self.bot_msg:
                        self.bot_msg['interrupted'] = True
                        self.publish_message(self.bot_msg)
                elif self.question is not None and self.bot_msg is None and not self.mute_audio:
                    # over without a word: the model and the hub's spoken apology both failed to reach
                    # their servers (no network yet after a boot, an outage); say so on the page
                    log.warning('the turn ended without a reply')
                    self.events.publish('toast', {'kind': 'error', 'text': '网络还没连上，稍后再试' if not has_default_route()
                                                  else '没收到回答，可能是网络或模型服务连不上，稍后再试'})
                self.bot_msg = self.question = None
                if self.listen_after_reply:
                    asyncio.create_task(self._listen_after_reply())
                elif self.uploading:
                    self.heard = True
                    self.deadline = time.monotonic() + self.cfg['follow_up_seconds']
                    self.set_state('listening')
                else:
                    self.set_state('idle')
        elif kind == 'mcp':
            payload = msg.get('payload') or {}
            reply = await mcp.handle(payload,
                                     on_volume=lambda v: self.events.publish('volume', {'volume': v}),
                                     client=self)
            if reply:
                await self.hub.send({'type': 'mcp', 'payload': reply})
            if payload.get('method') == 'tools/list':
                self.tools_listed.set()
        elif kind == 'chat_history' and msg.get('state') == 'cleared':
            # said "清除聊天记录" and confirmed: the hub has forgotten everything
            self.threads = {}
            self.bot_msg = self.question = None
            self.events.publish('thread', {'entry': self.view_entry(), 'messages': [], 'reset': True})
        elif kind == 'server':
            log.info('server message: %s', msg)

    async def _refresh_after_question(self, m):
        """The hub may have switched models for this question (called one by name, or changed in its
        admin page): move the question, and anything said after it, to that model's thread."""
        old = m['entry']
        await self.refresh_models()
        thread = self.threads.get(old, [])
        if self.entry == old or m not in thread:
            return
        moved = thread[thread.index(m):]
        del thread[thread.index(m):]
        for x in moved:
            x['entry'] = self.entry
        self.threads.setdefault(self.entry, []).extend(moved)
        self.events.publish('thread', {'entry': self.entry, 'messages': self.threads[self.entry]})

    async def _listen_after_reply(self):
        """Reopen the mic for a follow-up once the last of the reply has been played (uploading
        earlier would send the hub its tail)."""
        give_up = time.monotonic() + 3
        while self.audio.player.buffered() > 0.02 and time.monotonic() < give_up:
            await asyncio.sleep(0.05)
        if not self.listen_after_reply or not self.hub.connected:
            return   # woken, aborted or ended meanwhile: that already decided what comes next
        self.listen_after_reply = False
        if not self.keys.mic_on:
            # switched off during the reply: nothing would be heard, don't show "listening"
            self.set_state('idle')
            return
        recognised = self.recognised
        await self.start_listening('realtime')
        if self.recognised != recognised:
            # the hub cut this reply off for a newer question that arrived while we reopened
            self.uploading, self.listen_after_reply = False, True
            self.set_state('thinking')
            return
        self.heard = True
        self.deadline = time.monotonic() + self.cfg['follow_up_seconds']

    def _on_audio(self, packet):
        if self.mute_audio:
            self.muted_packets += 1
            return
        if self.decoder is None:
            # Opus decodes to any rate whatever it was encoded at: straight to the output rate
            self.decoder = Decoder(OUT_RATE, 1)
        try:
            mono = self.decoder.decode(packet)
            self.reply_samples += len(mono)
            self.reply_packets += 1
            self.audio.player.push(np.repeat(mono[:, None], 2, axis=1))
        except Exception:
            log.exception('cannot decode reply audio')

    async def _on_closed(self):
        # the conversation is over (the hub's idle timeout, an exit word, the end button)
        self.play('bye')
        self.uploading = False
        self.vad_ended = False
        self.abort_pending = self.question = None
        self.listen_after_reply = False
        self.deadline = None
        self.awaiting_text = False
        self.mute_audio = False
        self.bot_msg = None
        self.entry = None
        self.set_state('idle')
        self.events.publish('thread', {'entry': self.view_entry(), 'messages': self.threads.get(self.view_entry(), [])})
        # the hub's copy has interrupted replies cut to what was heard: show that one
        asyncio.create_task(self.load_history())

    # ---- conversation threads (one per model, like the hub keeps them) ----

    def add_message(self, role, text):
        entry = self.view_entry()
        if entry != self.entry:
            self.entry = entry
            self.events.publish('thread', {'entry': entry, 'messages': self.threads.get(entry, [])})
        self._seq += 1
        m = {'id': self._seq, 'role': role, 'text': text, 'time': time.time(), 'entry': entry}
        self.threads.setdefault(entry, []).append(m)
        del self.threads[entry][:-200]
        self.publish_message(m)
        return m

    def publish_message(self, m):
        self.events.publish('message', m)

    async def load_history(self):
        """Show the recent part of the conversations the hub saved. Not while connected: this
        connection's messages are being added then (it reloads once the connection closes)."""
        if self.hub.connected:
            return
        try:
            data = await self._hub_http('/kiosk/chat/history?' + urllib.parse.urlencode(
                {'device': self.cfg['device_id'], 'limit': HISTORY_LIMIT}))
        except Exception:
            return   # the hub is down or too old to keep conversations
        if not data.get('ok') or self.hub.connected:
            return
        self.threads = {entry: [{'id': f"h{m['id']}", 'role': m['role'], 'text': m['text'], 'time': m['time'],
                                 'entry': entry, **({'interrupted': True} if m.get('interrupted') else {})}
                                for m in items]
                        for entry, items in (data.get('models') or {}).items()}
        self.history_loaded = True
        self.events.publish('thread', {'entry': self.view_entry(), 'messages': self.threads.get(self.view_entry(), []),
                                       'reset': True})

    async def clear_history(self, memory_only=False):
        """Clear every model's conversation on the hub (and the page). memory_only: just the summary
        the hub made of the older part, the messages stay."""
        try:
            result = await self._hub_http('/kiosk/chat/clear', {'device': self.cfg['device_id'],
                                                                'memory_only': bool(memory_only)})
        except Exception as e:
            log.warning('cannot clear the chat history: %s', e)
            return {'ok': False, 'error': '连不上小智网关'}
        if result.get('ok') and not memory_only:
            self.threads = {}
            self.bot_msg = self.question = None
            self.events.publish('thread', {'entry': self.view_entry(), 'messages': [], 'reset': True})
        return result

    # ---- sleep hours ----

    def _apply_sleep(self):
        self.sleeping = sleep.active(self.sleep)
        if not self.sleeping:
            self.bright_in_sleep = False
        self.screen.dim(self.sleeping and not self.bright_in_sleep)

    async def set_sleep(self, **changes):
        """Change the sleep setting (page or voice); raises ValueError for a bad time."""
        self.sleep = sleep.normalize(self.sleep, changes)
        config.save(sleep=self.sleep)
        log.info('sleep setting: %s', self.sleep)
        self._apply_sleep()
        self.set_state(self.state)
        await self.poll_reports()     # tell the hub now, not at the next poll
        return {**self.sleep, 'active': self.sleeping}

    # ---- screen brightness (the blanker sets the backlight: device.Screen, idle-blanker.py) ----

    def brightness(self):
        """level: the screen's 0-100 now (the dim level while the sleep hours dim it, unless the
        user's is darker); dimmed: held dim for the sleep hours."""
        level, dimmed = self.screen.level, self.screen.dimmed
        return {'level': min(level, DIM_LEVEL) if dimmed else level, 'dimmed': dimmed}

    async def set_brightness(self, level=None, change=None):
        """Page or voice: level (0-100) or change (relative, from the level now). A level set in
        the sleep hours is kept until they end."""
        if level is None:
            if change is None:
                raise ValueError('level or change is needed')
            level = self.brightness()['level'] + int(change)
        self.screen.brightness(max(0, min(100, int(level))))
        self.bright_in_sleep = self.sleeping
        config.save(brightness=self.screen.level)
        self._apply_sleep()
        log.info('brightness: %s', self.screen.level)
        self.set_state(self.state)
        return self.brightness()

    # ---- auto screen-off (the blanker blanks: device.Screen, idle-blanker.py) ----

    def _screen_off_seconds(self):
        return self.screen_off['minutes'] * 60 if self.screen_off['enabled'] else 0

    async def set_screen_off(self, **changes):
        """Page: enabled and/or minutes (one of device.SCREEN_OFF_MINUTES); raises ValueError."""
        self.screen_off = normalize_screen_off(self.screen_off, changes)
        config.save(screen_off=self.screen_off)
        self.screen.blank_after(self._screen_off_seconds())
        log.info('screen-off: %s', self.screen_off)
        self.set_state(self.state)
        return self.screen_off

    # ---- reports (the hub's local-only /kiosk/reports endpoint) ----

    async def poll_reports(self, play=False):
        try:
            data = await self._hub_http('/kiosk/reports', {'device': self.cfg['device_id'],
                                                           'quiet': self.sleeping, 'play': play})
        except Exception:
            return None
        self.hub_seen = time.monotonic()
        count = int(data.get('count') or 0)
        new = count > self.reports
        if count != self.reports:
            self.reports = count
            self.set_state(self.state)
        if new:
            self._on_new_report()
        return data

    def _on_new_report(self):
        if self.sleeping:
            # just light the screen with the notice; it blanks again after the user's screen-off
            self.screen.poke()
        elif self.state == 'idle' and not self.hub.connected:
            # connected and idle, the hub reads it by itself
            self.play('report')
            asyncio.create_task(self.play_reports())

    async def play_reports(self):
        """Have the hub read the pending reports now (also in the sleep hours: the user asked)."""
        self.screen.poke()
        if not await self.ensure_connected():
            return
        if self.state == 'connecting':
            self.set_state('idle')
        # a fresh connection gets them from the hub itself after its greeting delay (~6 s)
        await self.poll_reports(play=True)

    async def _report_poller(self):
        while True:
            if sleep.active(self.sleep) != self.sleeping:
                self._apply_sleep()
                log.info('sleep hours %s', 'begin' if self.sleeping else 'end')
                self.set_state(self.state)
            await self.poll_reports()
            await asyncio.sleep(REPORT_POLL)

    # ---- models (the hub's local-only /kiosk/llm endpoints) ----

    async def _hub_http(self, path, body=None):
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15)) as s:
            if body is None:
                async with s.get(self.cfg['hub_http'] + path) as r:
                    return await r.json(content_type=None)
            async with s.post(self.cfg['hub_http'] + path, json=body) as r:
                return await r.json(content_type=None)

    async def refresh_models(self):
        """Ask the hub for its models; False when it didn't answer (down, or still starting)."""
        try:
            data = await self._hub_http('/kiosk/llm?' + urllib.parse.urlencode({'device': self.cfg['device_id']}))
        except Exception:
            return False
        self._apply_models(data)
        return True

    def _apply_models(self, data):
        options = [describe(o) for o in data.get('options', [])]
        models = {'current': next((o for o in options if o['entry'] == data.get('selected')), None),
                  'options': options}
        if models != self.models:
            self.models = models
            self.events.publish('model', models)
            if self.hub.connected and self.entry and models['current'] and models['current']['entry'] != self.entry:
                self.entry = models['current']['entry']
                self.events.publish('thread', {'entry': self.entry, 'messages': self.threads.get(self.entry, [])})
            elif not self.entry:
                # also while connected with nothing said yet (e.g. just after an interrupt reconnected)
                self.events.publish('thread', {'entry': self.view_entry(), 'messages': self.threads.get(self.view_entry(), [])})

    async def set_model(self, kind, vendor, entry=None):
        # entry names the model on the hub; a Grok Bot is only identified by it (its vendor is shared)
        if self.in_turn():
            # the answer in flight belongs to the old model: stop it rather than file it under the new one
            await self.abort()
        try:
            result = await self._hub_http('/kiosk/llm/use', {'kind': kind, 'vendor': vendor, 'entry': entry,
                                                             'device': self.cfg['device_id']})
        except Exception as e:
            result = {'ok': False, 'error': str(e)}
        if result.get('ok') and 'options' in result:
            self._apply_models(result)   # the hub answers with the new list (an older hub doesn't)
        else:
            await self.refresh_models()
        return {k: v for k, v in result.items() if k not in ('options', 'selected', 'default', 'kind', 'vendor')}

    async def _model_poller(self):
        # often while the hub is down (after a boot the page would show no models for over a minute),
        # then only to notice changes made in the hub's admin page
        while True:
            up = await self.refresh_models()
            if up and not self.history_loaded:
                await self.load_history()   # at start, or once the hub is up
            await asyncio.sleep(MODEL_POLL if up else MODEL_RETRY)

    # ---- timers ----

    async def _ticker(self):
        last_level = 0.0
        while True:
            await asyncio.sleep(0.1)
            now = time.monotonic()
            stalled = self.audio.stalled()
            if stalled:
                # PipeWire restarted (or died): the streams won't come back by themselves. systemd
                # restarts the service, which waits for PipeWire's socket and opens them again.
                log.error('audio is dead (%s): exiting to be restarted', stalled)
                os._exit(1)
            if self.uploading and self.audio.mic_level > SPEECH_RMS:
                self.speech_end = now
                if self.recognising:   # talking again: the hub listens on
                    self.recognising = False
                    self.set_state(self.state)
                if self.deadline:   # still talking
                    self.deadline = min(max(self.deadline, now + 2.5), self.listen_since + MAX_LISTEN)
            if self.uploading and self.listen_mode == 'manual' and now > self.listen_since + MAX_LISTEN:
                # push-to-talk held (or a release lost) for too long: the hub buffers all of it
                log.info('push-to-talk open for %d s: stopping it', MAX_LISTEN)
                await self.ptt_stop()
            if self.deadline and now > self.deadline:
                if self.state == 'listening' and self.uploading:
                    # a hub that sends VAD events has answered everything it heard unless one is pending
                    waiting = self.hub_pending or not self.hub.features.get('vad_end')
                    if not self.heard and self.speech_end > self.listen_since and waiting:
                        # they did talk; a long sentence takes the hub a while to recognise, so
                        # close the mic but wait for its text before saying "didn't catch that"
                        self.uploading = False
                        self.awaiting_text = True
                        self.deadline = now + RECOGNITION_WAIT
                        self.set_state('thinking')
                    else:
                        await self.stop_uploading(missed=not self.heard)
                elif self.state == 'thinking' and not self.uploading:
                    missed, self.awaiting_text = self.awaiting_text, False
                    await self.stop_uploading(missed=missed)
            if self.state in ('listening', 'speaking') and now - last_level >= 0.1:
                last_level = now
                self.events.publish('level', {'mic': round(self.audio.mic_level, 4),
                                              'out': round(self.audio.player.level, 4)})


def dumps(data):
    return json.dumps(data, ensure_ascii=False)
