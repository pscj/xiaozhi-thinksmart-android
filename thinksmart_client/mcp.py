"""Device tools the hub's models may call (MCP over the xiaozhi channel): speaker volume, sleep hours,
screen brightness."""
import asyncio
import json
import logging
import re

log = logging.getLogger(__name__)

TOOLS = [
    {
        'name': 'self.audio_speaker.set_volume',
        'description': '设置设备扬声器的音量。用户说「声音大一点」「音量调到 50」这类话时调用；'
                       '相对调整时先用 get_volume 读当前音量。',
        'inputSchema': {'type': 'object',
                        'properties': {'volume': {'type': 'integer', 'minimum': 0, 'maximum': 100}},
                        'required': ['volume']},
    },
    {
        'name': 'self.audio_speaker.get_volume',
        'description': '读取设备扬声器当前的音量（0-100）。',
        'inputSchema': {'type': 'object', 'properties': {}},
    },
    {
        'name': 'self.sleep_mode.set',
        'description': '设置设备的睡眠时段：时段内后台任务做完的汇报不主动念，只在屏幕上提示，'
                       '等用户点一下或唤醒再念；屏幕调暗；唤醒和对话照常，音量不变。'
                       '用户说「晚上十点到早上七点别念了」时 enabled=true, start="22:00", end="07:00"；'
                       '说「关掉睡眠模式」时 enabled=false。时间用 24 小时制 HH:MM，可以跨午夜；只改开关时不用传时间。',
        'inputSchema': {'type': 'object',
                        'properties': {'enabled': {'type': 'boolean'},
                                       'start': {'type': 'string', 'description': '开始时间 HH:MM'},
                                       'end': {'type': 'string', 'description': '结束时间 HH:MM'}},
                        'required': ['enabled']},
    },
    {
        'name': 'self.sleep_mode.get',
        'description': '读取睡眠时段的设置，以及现在是否在睡眠时段内。',
        'inputSchema': {'type': 'object', 'properties': {}},
    },
    {
        'name': 'self.screen.set_brightness',
        'description': '调节设备屏幕亮度，两个参数只传一个。亮度 0-100：0 是最暗（仍看得清），100 最亮。'
                       '「屏幕亮一点」change=20，「暗一点」change=-20（「再亮一点」同样传 change，不用先读）；'
                       '「调到最亮」brightness=100，「调到最暗」brightness=0，「亮度调到 60」brightness=60。'
                       '设备没有自动亮度。调过之后一直保持；睡眠时段屏幕会自动调暗，时段内调的亮度用到这次时段结束。',
        'inputSchema': {'type': 'object',
                        'properties': {'brightness': {'type': 'integer', 'minimum': 0, 'maximum': 100},
                                       'change': {'type': 'integer', 'minimum': -100, 'maximum': 100,
                                                  'description': '在当前亮度上加减'}}},
    },
    {
        'name': 'self.screen.get_brightness',
        'description': '读取屏幕亮度：level 当前亮度 0-100，dimmed 是否因睡眠时段调暗。',
        'inputSchema': {'type': 'object', 'properties': {}},
    },
]


async def _wpctl(*args):
    p = await asyncio.create_subprocess_exec('wpctl', *args, stdout=asyncio.subprocess.PIPE,
                                             stderr=asyncio.subprocess.DEVNULL)
    out, _ = await p.communicate()
    return out.decode()


async def get_volume():
    m = re.search(r'([\d.]+)', await _wpctl('get-volume', '@DEFAULT_AUDIO_SINK@'))
    return round(float(m.group(1)) * 100) if m else None


async def set_volume(volume):
    await _wpctl('set-volume', '@DEFAULT_AUDIO_SINK@', f'{max(0, min(100, int(volume))) / 100:.2f}')


async def handle(payload, on_volume=None, client=None):
    """One JSON-RPC request from the hub; returns the reply payload (None for notifications)."""
    method, req_id, params = payload.get('method'), payload.get('id'), payload.get('params') or {}
    if req_id is None:
        return None

    def result(value):
        return {'jsonrpc': '2.0', 'id': req_id, 'result': value}

    def error(message):
        return {'jsonrpc': '2.0', 'id': req_id, 'error': {'code': -32601, 'message': message}}

    if method == 'initialize':
        return result({'protocolVersion': '2024-11-05', 'capabilities': {'tools': {}},
                       'serverInfo': {'name': 'thinksmart-client', 'version': '0.1.0'}})
    if method == 'tools/list':
        return result({'tools': TOOLS})
    if method == 'tools/call':
        name, args = params.get('name'), params.get('arguments') or {}
        try:
            if name == 'self.audio_speaker.set_volume':
                await set_volume(args['volume'])
                if on_volume:
                    on_volume(int(args['volume']))
                text = json.dumps({'success': True, 'volume': int(args['volume'])})
            elif name == 'self.audio_speaker.get_volume':
                text = json.dumps({'volume': await get_volume()})
            elif name == 'self.sleep_mode.set' and client:
                text = json.dumps({'success': True, 'sleep': await client.set_sleep(
                    enabled=args.get('enabled'), start=args.get('start'), end=args.get('end'))},
                    ensure_ascii=False)
            elif name == 'self.sleep_mode.get' and client:
                text = json.dumps({**client.sleep, 'active': client.sleeping}, ensure_ascii=False)
            elif name == 'self.screen.set_brightness' and client:
                text = json.dumps({'success': True, **await client.set_brightness(
                    level=args.get('brightness'), change=args.get('change'))})
            elif name == 'self.screen.get_brightness' and client:
                text = json.dumps(client.brightness())
            else:
                return error(f'Unknown tool: {name}')
        except Exception as e:
            log.exception('tool %s failed', name)
            return result({'content': [{'type': 'text', 'text': str(e)}], 'isError': True})
        return result({'content': [{'type': 'text', 'text': text}], 'isError': False})
    return error(f'Method not implemented: {method}')
