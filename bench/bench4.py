import time, sys
from picamera2 import Picamera2
p=Picamera2()
def run(bc, raw=True, mainfmt="YUV420"):
    kw=dict(main={"size":(4608,2592),"format":mainfmt}, lores={"size":(768,432),"format":"YUV420"}, buffer_count=bc, controls={"FrameDurationLimits":(71429,71429)})
    if raw: kw["raw"]={"size":(4608,2592)}
    p.configure(p.create_video_configuration(**kw)); ts=[]
    p.pre_callback=lambda r: ts.append(time.time())
    p.start(); time.sleep(3); n=len(ts); p.stop()
    print(f"bc={bc} raw={raw} -> {n/3:.1f} fps", flush=True)
for bc in (2,3,4,6): run(bc)
run(4, raw=False)
