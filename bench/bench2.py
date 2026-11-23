import time, io, threading, numpy as np, simplejpeg, os
from picamera2 import Picamera2
from picamera2.encoders import MJPEGEncoder
from picamera2.outputs import FileOutput
from libcamera import controls
p=Picamera2()
cc=p.camera_controls
for k in ("ExposureTime","AnalogueGain","ScalerCrop","LensPosition","AfWindows","AfMetering","AeMeteringMode","FrameDurationLimits","Sharpness","NoiseReductionMode","AeExposureMode","AeConstraintMode","AfRange","AfSpeed","HdrMode"):
    print(k, cc.get(k))
cfg=p.create_video_configuration(main={"size":(4608,2592),"format":"YUV420"}, lores={"size":(768,432),"format":"YUV420"},
     raw={"size":(4608,2592)}, buffer_count=3, controls={"FrameDurationLimits":(71429,71429), "AfMode":controls.AfModeEnum.Continuous})
p.configure(cfg); p.start()
class Out(io.BufferedIOBase):
    def __init__(s): s.n=0; s.sz=0; s.t=[]
    def write(s,b): s.n+=1; s.sz+=len(b); s.t.append(time.time())
o=Out(); e=MJPEGEncoder(bitrate=6_000_000); p.start_encoder(e, FileOutput(o), name="lores")
time.sleep(3); print("mjpeg fps", o.n/3, "avg KB", o.sz/max(1,o.n)/1024)
n0=o.n; t=time.time()
def work():
    r=p.capture_request(); y=r.make_array("main"); raw=r.make_array("raw"); r.release()
    H=2592;W=4608
    simplejpeg.encode_jpeg_yuv_planes(y[:H],np.ascontiguousarray(y[H:H+H//4].reshape(H//2,W//2)),np.ascontiguousarray(y[H+H//4:].reshape(H//2,W//2)),quality=92)
th=[threading.Thread(target=work) for _ in range(3)]
for x in th: x.start(); time.sleep(0.15)
for x in th: x.join()
dt=time.time()-t; gaps=np.diff(o.t[n0:]); print(f"3 captures+jpeg en {dt:.2f}s, visée pendant: {(o.n-n0)/dt:.1f} fps, trou max {1000*gaps.max():.0f}ms")
# zoom
sc=p.camera_properties['ScalerCropMaximum']; print("scmax",sc)
x,y0,w,h=sc; z=2.5; p.set_controls({"ScalerCrop":(int(x+w*(1-1/z)/2),int(y0+h*(1-1/z)/2),int(w/z),int(h/z))}); time.sleep(0.5); print("crop md", p.capture_metadata().get("ScalerCrop"))
# AF window
t=time.time(); p.set_controls({"AfMetering":controls.AfMeteringEnum.Windows,"AfWindows":[(1000,600,400,400)],"AfMode":controls.AfModeEnum.Auto,"AfTrigger":controls.AfTriggerEnum.Start})
while True:
    m=p.capture_metadata(); st=m.get("AfState")
    if st in (controls.AfStateEnum.Focused, controls.AfStateEnum.Failed) or time.time()-t>3: break
print("AF point", st, f"{1000*(time.time()-t):.0f}ms lens", m.get("LensPosition"))
# pose longue 2 s
t=time.time(); p.set_controls({"AeEnable":False,"ExposureTime":2_000_000,"FrameDurationLimits":(100,2_100_000)})
while True:
    m=p.capture_metadata()
    if m["ExposureTime"]>1_900_000: break
print(f"pose 2s obtenue en {time.time()-t:.1f}s exp {m['ExposureTime']}")
p.set_controls({"AeEnable":True,"FrameDurationLimits":(71429,71429)})
t=time.time()
while p.capture_metadata()["ExposureTime"]>100000: pass
print(f"retour normal {time.time()-t:.1f}s")
p.stop_encoder(); p.stop()
