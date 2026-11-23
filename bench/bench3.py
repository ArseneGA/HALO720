import time, io, numpy as np
from picamera2 import Picamera2
from picamera2.encoders import MJPEGEncoder, Quality
from picamera2.outputs import FileOutput
from libcamera import controls
p=Picamera2()
cfg=p.create_video_configuration(main={"size":(4608,2592),"format":"YUV420"}, lores={"size":(768,432),"format":"YUV420"},
     raw={"size":(4608,2592)}, buffer_count=3, controls={"FrameDurationLimits":(71429,71429)})
p.configure(cfg); p.start()
ts=[]
def cb(r):
    ts.append(time.time())
p.pre_callback=cb
time.sleep(3); d=np.diff(ts[-30:]); print("req fps sans encodeur", 1/d.mean())
class Out(io.BufferedIOBase):
    def __init__(s): s.t=[]
    def write(s,b): s.t.append(time.time())
for args in ({},):
    o=Out(); e=MJPEGEncoder(); p.start_encoder(e, FileOutput(o), name="lores", quality=Quality.MEDIUM)
    ts.clear(); time.sleep(3); print("avec encodeur: req fps", len(ts)/3, "enc fps", len(o.t)/3)
    p.stop_encoder()
m=p.capture_metadata(); print("FrameDuration", m["FrameDuration"], "Exp", m["ExposureTime"], "gain", m["AnalogueGain"], "lux", m.get("Lux"))
import os; print("load", os.getloadavg())
p.stop()
