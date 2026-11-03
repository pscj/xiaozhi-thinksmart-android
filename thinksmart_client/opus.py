"""Minimal Opus encoder/decoder over the system libopus (ctypes, no extra package)."""
import ctypes
import ctypes.util

import numpy as np

_lib = ctypes.CDLL(ctypes.util.find_library('opus') or 'libopus.so.0')
_lib.opus_encoder_create.restype = ctypes.c_void_p
_lib.opus_encoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
_lib.opus_encode.restype = ctypes.c_int32
_lib.opus_encode.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_int32]
_lib.opus_encoder_destroy.argtypes = [ctypes.c_void_p]
_lib.opus_decoder_create.restype = ctypes.c_void_p
_lib.opus_decoder_create.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
_lib.opus_decode.restype = ctypes.c_int
_lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int32, ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
_lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]

APPLICATION_VOIP = 2048


class Encoder:
    def __init__(self, rate=16000, channels=1):
        err = ctypes.c_int()
        self._st = _lib.opus_encoder_create(rate, channels, APPLICATION_VOIP, ctypes.byref(err))
        if err.value != 0:
            raise RuntimeError(f'opus_encoder_create: {err.value}')
        self._buf = ctypes.create_string_buffer(4000)

    def encode(self, pcm16):
        """pcm16: int16 array of exactly one frame (e.g. 320 samples = 20 ms at 16 kHz)."""
        pcm16 = np.ascontiguousarray(pcm16, dtype=np.int16)
        n = _lib.opus_encode(self._st, pcm16.ctypes.data, len(pcm16), self._buf, len(self._buf))
        if n < 0:
            raise RuntimeError(f'opus_encode: {n}')
        return self._buf.raw[:n]

    def __del__(self):
        if getattr(self, '_st', None):
            _lib.opus_encoder_destroy(self._st)


class Decoder:
    def __init__(self, rate=24000, channels=1):
        err = ctypes.c_int()
        self._st = _lib.opus_decoder_create(rate, channels, ctypes.byref(err))
        if err.value != 0:
            raise RuntimeError(f'opus_decoder_create: {err.value}')
        self._out = np.zeros(rate * 120 // 1000 * channels, dtype=np.int16)  # 120 ms is Opus's longest frame

    def decode(self, packet):
        """Returns float32 samples in [-1, 1]."""
        n = _lib.opus_decode(self._st, packet, len(packet), self._out.ctypes.data, len(self._out), 0)
        if n < 0:
            raise RuntimeError(f'opus_decode: {n}')
        return self._out[:n].astype(np.float32) / 32768.0

    def __del__(self):
        if getattr(self, '_st', None):
            _lib.opus_decoder_destroy(self._st)
