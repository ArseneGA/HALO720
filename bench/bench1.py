import time, os, numpy as np, simplejpeg, threading
from picamera2 import Picamera2
from libcamera import controls
t0=time.time()
p=Picamera2()
print("modes:", [(m['size'], round(m['fps'],1), str(m['format'])) for m in p.sensor_modes])
cfg=p.create_video_configuration(main={"size":(4608,2592),"format":"YUV420"}, lores={"size":(768,432),"format":"YUV420"},
     raw={"size":(4608,2592)}, buffer_count=3, controls={"FrameRate":14, "AfMode":controls.AfModeEnum.Continuous})
p.configure(cfg)
print("cfg raw", p.camera_config['raw'])
p.start(); print("start", round(time.time()-t0,2))
time.sleep(2)
md=p.capture_metadata(); print("fd", md.get("FrameDuration"), "exp", md.get("ExposureTime"))
# latence capture
for i in range(3):
    t=time.time(); r=p.capture_request(); t1=time.time()
    y=r.make_array("main"); t2=time.time()
    raw=r.make_array("raw"); t3=time.time()
    r.release()
    print(f"capture_request {1000*(t1-t):.0f}ms main copy {1000*(t2-t1):.0f}ms raw copy {1000*(t3-t2):.0f}ms shapes {y.shape} {raw.shape} {raw.dtype}")
H=2592; W=4608
Y=y[:H]; U=y[H:H+H//4].reshape(H//2,W//2); V=y[H+H//4:].reshape(H//2,W//2)
print(Y.shape,U.shape,V.shape, y.shape)
for q in (90,):
    t=time.time(); j=simplejpeg.encode_jpeg_yuv_planes(np.ascontiguousarray(Y),np.ascontiguousarray(U),np.ascontiguousarray(V),quality=q); print(f"simplejpeg q{q} {1000*(time.time()-t):.0f}ms {len(j)/1e6:.1f}MB")
# parallèle 2
def enc(): simplejpeg.encode_jpeg_yuv_planes(np.ascontiguousarray(Y),np.ascontiguousarray(U),np.ascontiguousarray(V),quality=90)
t=time.time(); th=[threading.Thread(target=enc) for _ in range(3)]; [x.start() for x in th]; [x.join() for x in th]; print(f"3 encodes parallèles {1000*(time.time()-t):.0f}ms")
# look chroma
t=time.time(); U2=(np.clip((V.astype(np.int16)-128)*0.79+128,0,255)).astype(np.uint8); print(f"look chroma {1000*(time.time()-t):.0f}ms")
t=time.time(); open("/home/pi/HALO/bench.jpg","wb").write(j); f=os.open("/home/pi/HALO/bench.jpg",os.O_RDONLY); os.fsync(f); os.close(f); print(f"write+fsync jpg {1000*(time.time()-t):.0f}ms")
t=time.time(); open("/home/pi/HALO/bench.raw","wb").write(raw.tobytes()); os.sync(); print(f"write raw {raw.nbytes/1e6:.1f}MB {1000*(time.time()-t):.0f}ms")
# DNG
r=p.capture_request(); t=time.time(); r.save_dng("/home/pi/HALO/bench.dng"); print(f"save_dng {1000*(time.time()-t):.0f}ms {os.path.getsize('/home/pi/HALO/bench.dng')/1e6:.1f}MB"); r.release()
# lores fps
n=0; t=time.time()
while time.time()-t<3: p.capture_metadata(); n+=1
print("fps", n/3)
import resource; print("maxrss MB", resource.getrusage(resource.RUSAGE_SELF).ru_maxrss/1024)
p.stop()
for f in ("bench.jpg","bench.raw","bench.dng"): os.remove("/home/pi/HALO/"+f)
